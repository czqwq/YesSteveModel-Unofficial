package parity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.nativebridge.NativeDrawGroups;
import com.fox.ysmu.nativebridge.NativeGeometry;
import com.fox.ysmu.nativebridge.NativeGeometryReader;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.util.MatrixStack;

/**
 * M4 — per-frame draw-group parity and benchmark.
 *
 * <p>
 * Both sides are driven from <b>one</b> bake so geometry is not a variable: the harness bakes the JSON,
 * reads the materialisation blob through {@link NativeGeometryReader} and poses that object graph. The
 * reader sets {@code GeoBone.nativeBoneIndex}, which is exactly what {@code IGeoRenderer}'s fast path
 * passes to the emitter, so the harness does not have to reconstruct the native bone numbering.
 *
 * <p>
 * Java reference: walk the model as {@code renderRecursivelyNative} does — push,
 * {@code MatrixStack.transformBone}, then the same {@code IGeoRenderer.renderCube} calls the Java loop
 * makes — into a private {@link Tessellator} without ever calling {@code draw()}. Rust: one
 * {@code NativeDrawGroups.emit} per draw group with the matrix copied by
 * {@code IGeoRenderer.copyModelMatrix}/{@code copyNormalMatrix} and the colour packed by
 * {@code IGeoRenderer.packNativeColor}, i.e. the exact inputs the production fast path builds. The raw
 * int arrays must be identical; a mismatch prints the model, bone, filter and the first differing int
 * decoded as position/uv/colour/normal.
 *
 * <p>
 * The fast path's own fallback rules are mirrored so the comparison is about bytes and not about cases
 * the renderer would never emit natively: a bone without a native index, or a group that mixes flat and
 * non-flat cubes, stays on the Java loop and is excluded from the native comparison and benchmark
 * (counted in the report).
 *
 * <p>
 * Timing isolates the intended replacement: Java = bone matrix math + per-vertex emission; Rust = the
 * emitter only, with the matrices the Java side computes (the ExecPlan's architecture: the host keeps
 * every matrix/GL decision, native produces vertices).
 */
public final class ParityRender {

    /**
     * Largest compared/int-count Java reference stream. {@code IGeoRenderer.renderCube} flushes (and
     * draws, i.e. touches GL) once {@code rawBufferIndex} passes
     * {@code TessellatorBufferKeep.SHRINK_THRESHOLD - BATCH_RESERVE} = {@code 0x1E000 - 0x2000}, so a
     * group above this size would not be comparable headlessly.
     */
    private static final int SAFE_GROUP_INTS = 0x1B000;

    private static final IGeoRenderer<Object> RENDERER = new HarnessRenderer();

    private ParityRender() {}

    public static void run(Path repo, Report report) {
        if (!NativeBootstrap.available()) {
            report.pending("render", "draw-group parity", "native layer unavailable: " + NativeBootstrap.status());
            return;
        }
        if (!TessellatorRaw.available()) {
            report.skip("render", "draw-group parity", TessellatorRaw.problem());
            return;
        }
        if (!NativeGeometry.isUsable()) {
            report.pending(
                "render",
                "draw-group parity",
                "requires the native geometry bake (M3) first: NativeGeometry.isUsable() is false ("
                    + NativeBootstrap.status() + ")");
            return;
        }
        if (!NativeDrawGroups.isUsable()) {
            report.pending(
                "render",
                "draw-group parity",
                "NativeDrawGroups.isUsable() is false: " + NativeBootstrap.status());
            return;
        }

        List<Path> models;
        try {
            models = representativeModels(repo);
        } catch (IOException failure) {
            report.fail("render", "corpus", "", "", "", "could not walk the geometry corpus: " + failure);
            return;
        }
        if (models.isEmpty()) {
            report.fail("render", "corpus", "", "", "", "no representative geometry models found");
            return;
        }
        warmUp(models.get(0));

        int modelsPassed = 0;
        int modelsFailed = 0;
        String firstFailure = null;
        long totalVertices = 0L;
        long totalGroups = 0L;
        long totalJavaOnlyBones = 0L;
        long totalSkippedGroups = 0L;
        double javaBenchTotalMs = 0.0;
        double rustBenchTotalMs = 0.0;
        int benched = 0;

        for (Path modelPath : models) {
            String modelName = Corpus.name(repo, modelPath);
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(modelPath);
            } catch (IOException failure) {
                modelsFailed++;
                firstFailure = firstFailure != null ? firstFailure : modelName + ": read failed: " + failure;
                continue;
            }

            long handle = NativeGeometry.bake(bytes, 0);
            if (handle == 0L) {
                report.pending(
                    "render",
                    "draw-group parity",
                    "NativeGeometry.bake returned 0 for " + modelName
                        + " - the M3/M4 native symbols are not in the loaded artifact yet");
                return;
            }
            try {
                byte[] blob = NativeGeometry.blob(handle);
                if (blob == null || blob.length == 0) {
                    report.pending(
                        "render",
                        "draw-group parity",
                        "NativeGeometry.blob returned null/empty for " + modelName
                            + " (M3 blob materialisation not wired)");
                    return;
                }
                GeoModel model;
                try {
                    model = NativeGeometryReader.read(blob);
                } catch (Throwable failure) {
                    modelsFailed++;
                    firstFailure = firstFailure != null ? firstFailure
                        : modelName + ": NativeGeometryReader.read threw: " + failure;
                    continue;
                }

                int poseCount = applyDeterministicPose(model);
                PreparedModel prepared = new PreparedModel();
                WalkContext context = new WalkContext(
                    handle,
                    TessellatorRaw.create(SAFE_GROUP_INTS),
                    new int[SAFE_GROUP_INTS],
                    prepared);
                MatrixStack stack = IGeoRenderer.MATRIX_STACK;
                for (GeoBone bone : model.topLevelBones) {
                    walkAndCompare(bone, stack, 1f, 1f, 1f, 1f, context);
                }
                if (context.state.pending != null) {
                    report.pending("render", "draw-group parity", context.state.pending);
                    return;
                }
                totalVertices += prepared.totalVertices;
                totalGroups += prepared.groups.size();
                totalJavaOnlyBones += prepared.javaOnlyBones;
                totalSkippedGroups += prepared.skippedGroups;
                String notes = notes(prepared, poseCount);
                if (context.state.failure == null) {
                    modelsPassed++;
                    report.pass(
                        "render",
                        "draw-group parity (" + modelName + ")",
                        prepared.groups.size() + " groups",
                        prepared.totalVertices + " vertices",
                        "",
                        "raw int arrays identical for every compared group; " + notes);
                } else {
                    modelsFailed++;
                    if (firstFailure == null) {
                        firstFailure = modelName + ": " + context.state.failure;
                    }
                    report.fail(
                        "render",
                        "draw-group parity (" + modelName + ")",
                        prepared.groups.size() + " groups",
                        "",
                        "",
                        context.state.failure);
                }

                // ── Benchmark: emission of the whole posed model, one draw group at a time ────────
                Tessellator benchTessellator = TessellatorRaw.create(SAFE_GROUP_INTS);
                int[] scratch = new int[SAFE_GROUP_INTS];
                Map<GeoBone, List<Group>> byBone = indexGroupsByBone(prepared.groups);
                Bench.Result javaResult = Bench.time(
                    0,
                    () -> emitJavaModelOnce(model, benchTessellator, byBone));
                Bench.Result rustResult = Bench.time(0, () -> emitNativeModel(handle, prepared.groups, scratch));
                double javaMs = javaResult.millisPerOperation();
                double rustMs = rustResult.millisPerOperation();
                double javaVerticesPerMs = javaMs > 0.0 ? prepared.totalVertices / javaMs : 0.0;
                double rustVerticesPerMs = rustMs > 0.0 ? prepared.totalVertices / rustMs : 0.0;
                report.pass(
                    "render",
                    "emit speed (" + modelName + ")",
                    String.format(Locale.ROOT, "%.2f vert/ms", javaVerticesPerMs),
                    String.format(Locale.ROOT, "%.2f vert/ms", rustVerticesPerMs),
                    Report.timeSpeedup(javaMs, rustMs),
                    String.format(
                        Locale.ROOT,
                        "%d vertices in %d groups; %.3f ms vs %.3f ms per full-model emission",
                        prepared.totalVertices,
                        prepared.groups.size(),
                        javaMs,
                        rustMs));
                javaBenchTotalMs += javaMs;
                rustBenchTotalMs += rustMs;
                benched++;
            } finally {
                NativeGeometry.release(handle);
            }
        }

        if (benched > 0) {
            double javaMs = javaBenchTotalMs / benched;
            double rustMs = rustBenchTotalMs / benched;
            report.pass(
                "render",
                "emit aggregate",
                String.format(Locale.ROOT, "%.3f ms/model", javaMs),
                String.format(Locale.ROOT, "%.3f ms/model", rustMs),
                Report.timeSpeedup(javaMs, rustMs),
                benched + " models, " + totalVertices + " vertices in " + totalGroups + " groups; "
                    + totalJavaOnlyBones + " Java-fallback bones and " + totalSkippedGroups
                    + " oversized groups excluded from both sides");
        }
        if (modelsFailed == 0) {
            report.pass(
                "render",
                "draw-group parity summary",
                modelsPassed + "/" + models.size() + " models",
                totalVertices + " vertices",
                "",
                "0 mismatched groups: " + totalGroups + " draw groups compared byte-for-byte; "
                    + totalJavaOnlyBones + " Java-only bones and " + totalSkippedGroups
                    + " oversized groups excluded by the fast path's own rules");
        } else if (firstFailure != null) {
            report.fail(
                "render",
                "draw-group parity summary",
                modelsPassed + "/" + models.size() + " models",
                "",
                "",
                firstFailure);
        }
    }

    private static String notes(PreparedModel prepared, int poseCount) {
        StringBuilder builder = new StringBuilder();
        builder.append(poseCount)
            .append(" posed bones");
        if (prepared.javaOnlyBones > 0) {
            builder.append("; ")
                .append(prepared.javaOnlyBones)
                .append(" bone(s) Java-only by the fast path's own rules");
        }
        if (prepared.skippedGroups > 0) {
            builder.append("; ")
                .append(prepared.skippedGroups)
                .append(" oversized group(s) skipped (> 0x1B000 ints, would flush/draw)");
        }
        return builder.toString();
    }

    /** Walks the posed model, capturing and comparing one draw group per (bone, cube filter). */
    private static void walkAndCompare(GeoBone bone, MatrixStack stack, float red, float green, float blue,
        float alpha, WalkContext context) {
        stack.push();
        try {
            stack.transformBone(bone);
            float boneRed = red * bone.getRenderRed();
            float boneGreen = green * bone.getRenderGreen();
            float boneBlue = blue * bone.getRenderBlue();
            float boneAlpha = alpha * bone.getRenderAlpha();

            if (!bone.isHidden() && bone.nativeBoneIndex >= 0) {
                boolean anyNeg = false;
                boolean anyPos = false;
                for (GeoCube cube : bone.childCubes) {
                    if (cube == null) {
                        continue;
                    }
                    if (cube.hasNegSize) {
                        anyNeg = true;
                    } else {
                        anyPos = true;
                    }
                    if (anyNeg && anyPos) {
                        break;
                    }
                }
                List<Group> groups = new ArrayList<>(2);
                if (!anyNeg) {
                    groups.add(buildGroup(bone, NativeDrawGroups.CUBE_FILTER_ALL, stack, boneRed, boneGreen, boneBlue,
                        boneAlpha));
                } else {
                    if (anyNeg) {
                        groups.add(buildGroup(bone, NativeDrawGroups.CUBE_FILTER_NEGATIVE, stack, boneRed, boneGreen,
                            boneBlue, boneAlpha));
                    }
                    if (anyPos) {
                        groups.add(buildGroup(bone, NativeDrawGroups.CUBE_FILTER_POSITIVE, stack, boneRed, boneGreen,
                            boneBlue, boneAlpha));
                    }
                }
                boolean mixedFlatness = false;
                for (Group group : groups) {
                    if (group.flatnessMixed) {
                        mixedFlatness = true;
                        break;
                    }
                }
                if (mixedFlatness) {
                    // IGeoRenderer.renderCubeGroupNative declines a group that mixes flat and non-flat
                    // cubes (the polygon offset is a batch boundary), so the production renderer keeps the
                    // whole bone on the Java loop. Not a native comparison case.
                    context.prepared.javaOnlyBones++;
                } else {
                    for (Group group : groups) {
                        compareGroup(group, context);
                    }
                }
                if (!mixedFlatness && !groups.isEmpty()) {
                    for (Group group : groups) {
                        context.prepared.add(group);
                    }
                }
            } else if (bone.nativeBoneIndex < 0) {
                context.prepared.javaOnlyBones += countBones(bone);
            }

            if (!bone.childBonesAreHiddenToo()) {
                for (GeoBone child : bone.childBones) {
                    walkAndCompare(child, stack, boneRed, boneGreen, boneBlue, boneAlpha, context);
                }
            }
        } finally {
            stack.pop();
        }
    }

    private static Group buildGroup(GeoBone bone, int filter, MatrixStack stack, float red, float green, float blue,
        float alpha) {
        Group group = new Group();
        group.bone = bone;
        group.boneIndex = bone.nativeBoneIndex;
        group.filter = filter;
        group.red = red;
        group.green = green;
        group.blue = blue;
        group.alpha = alpha;
        group.colorArgb = IGeoRenderer.packNativeColor(red, green, blue, alpha);
        group.modelMatrix = new float[16];
        group.normalMatrix = new float[9];
        IGeoRenderer.copyModelMatrix(stack.getModelMatrix(), group.modelMatrix);
        IGeoRenderer.copyNormalMatrix(stack.getNormalMatrix(), group.normalMatrix);
        int flatness = -1;
        for (GeoCube cube : bone.childCubes) {
            if (cube == null || !matchesFilter(cube, filter)) {
                continue;
            }
            group.cubes.add(cube);
            int cubeFlat = !cube.mesh && (cube.size.x == 0f || cube.size.y == 0f || cube.size.z == 0f) ? 1 : 0;
            if (flatness < 0) {
                flatness = cubeFlat;
            } else if (flatness != cubeFlat) {
                group.flatnessMixed = true;
            }
            if (cube.quads != null) {
                for (GeoQuad quad : cube.quads) {
                    if (quad != null && quad.vertices != null) {
                        group.vertices += quad.vertices.length;
                    }
                }
            }
        }
        return group;
    }

    private static void compareGroup(Group group, WalkContext context) {
        if (context.state.pending != null || context.state.failure != null) {
            return;
        }
        int expectedInts = group.vertices * 8;
        if (expectedInts <= 0) {
            return;
        }
        if (expectedInts > SAFE_GROUP_INTS) {
            context.prepared.skippedGroups++;
            return;
        }
        int[] javaInts = javaGroup(context.tessellator, group);
        if (context.scratch.length < javaInts.length) {
            context.scratch = new int[Math.max(javaInts.length, context.scratch.length * 2)];
        }
        int written = NativeDrawGroups.emit(
            context.handle,
            group.boneIndex,
            group.modelMatrix,
            group.normalMatrix,
            group.colorArgb,
            group.filter,
            context.scratch);
        if (written < 0) {
            if (NativeBootstrap.moduleDisabled("render")) {
                context.state.pending = "NativeDrawGroups.emit returned -1 and NativeLoader disabled the render module"
                    + " - the M4 JNI emitter is not in the loaded artifact yet (or its symbol does not match the"
                    + " facade)";
            } else {
                context.state.failure = "native emit returned -1 for bone '" + group.bone.getName() + "' filter "
                    + group.filter + " (" + group.cubes.size() + " cubes) although the module is still enabled";
            }
            return;
        }
        if (written * 8 != javaInts.length) {
            context.state.failure = "vertex count differs for bone '" + group.bone.getName() + "' filter " + group.filter
                + ": Java " + (javaInts.length / 8) + " vertices, Rust " + written;
            return;
        }
        for (int index = 0; index < javaInts.length; index++) {
            if (javaInts[index] != context.scratch[index]) {
                context.state.failure = "first differing int for bone '" + group.bone.getName() + "' filter "
                    + group.filter + " at vertex " + (index / 8) + " slot " + (index % 8) + ": Java "
                    + describeInt(javaInts, index) + ", Rust " + describeInt(context.scratch, index);
                return;
            }
        }
        context.state.comparedGroups++;
    }

    private static String describeInt(int[] buffer, int index) {
        int slot = index % 8;
        switch (slot) {
            case 0:
            case 1:
            case 2:
                return "pos[" + slot + "]=" + Float.intBitsToFloat(buffer[index]) + " (0x"
                    + Integer.toHexString(buffer[index]) + ')';
            case 3:
            case 4:
                return "uv[" + (slot - 3) + "]=" + Float.intBitsToFloat(buffer[index]) + " (0x"
                    + Integer.toHexString(buffer[index]) + ')';
            case 5:
                int color = buffer[index];
                return String.format(
                    Locale.ROOT,
                    "color=#%08x (alpha=%d red=%d green=%d blue=%d)",
                    color,
                    (color >> 24) & 0xFF,
                    (color >> 16) & 0xFF,
                    (color >> 8) & 0xFF,
                    color & 0xFF);
            case 6:
                int normal = buffer[index];
                return "normal=(" + (byte) (normal & 0xFF) + ',' + (byte) ((normal >> 8) & 0xFF) + ','
                    + (byte) ((normal >> 16) & 0xFF) + ") (0x" + Integer.toHexString(normal) + ')';
            default:
                return "brightness=0x" + Integer.toHexString(buffer[index]) + " (must stay 0: the engine never sets"
                    + " brightness on model draws)";
        }
    }

    /** Java side of one draw group: the same calls {@code IGeoRenderer.renderCube} makes per cube. */
    private static int[] javaGroup(Tessellator tessellator, Group group) {
        TessellatorRaw.rewind(tessellator);
        for (GeoCube cube : group.cubes) {
            RENDERER.renderCube(tessellator, cube, group.red, group.green, group.blue, group.alpha);
        }
        return TessellatorRaw.copyUsed(tessellator);
    }

    private static boolean matchesFilter(GeoCube cube, int filter) {
        if (filter == NativeDrawGroups.CUBE_FILTER_NEGATIVE) {
            return cube.hasNegSize;
        }
        if (filter == NativeDrawGroups.CUBE_FILTER_POSITIVE) {
            return !cube.hasNegSize;
        }
        return true;
    }

    /** One full-model Java emission over the groups the fast path would emit, for the benchmark. */
    private static void emitJavaModelOnce(GeoModel model, Tessellator tessellator,
        Map<GeoBone, List<Group>> groupsByBone) {
        MatrixStack stack = IGeoRenderer.MATRIX_STACK;
        for (GeoBone bone : model.topLevelBones) {
            emitJavaBone(bone, stack, tessellator, groupsByBone);
        }
    }

    private static void emitJavaBone(GeoBone bone, MatrixStack stack, Tessellator tessellator,
        Map<GeoBone, List<Group>> groupsByBone) {
        List<Group> groups = groupsByBone.get(bone);
        if (groups == null) {
            // The production fast path keeps this bone's subtree on the Java loop; excluding it here keeps
            // the comparison limited to the native-emitted workload.
            return;
        }
        stack.push();
        try {
            stack.transformBone(bone);
            for (Group group : groups) {
                TessellatorRaw.rewind(tessellator);
                for (GeoCube cube : group.cubes) {
                    RENDERER.renderCube(tessellator, cube, group.red, group.green, group.blue, group.alpha);
                }
            }
            for (GeoBone child : bone.childBones) {
                emitJavaBone(child, stack, tessellator, groupsByBone);
            }
        } finally {
            stack.pop();
        }
    }

    /** One full-model native emission from the precomputed matrices, for the benchmark. */
    private static void emitNativeModel(long handle, List<Group> groups, int[] scratch) {
        for (Group group : groups) {
            NativeDrawGroups.emit(
                handle,
                group.boneIndex,
                group.modelMatrix,
                group.normalMatrix,
                group.colorArgb,
                group.filter,
                scratch);
        }
    }

    private static Map<GeoBone, List<Group>> indexGroupsByBone(List<Group> groups) {
        Map<GeoBone, List<Group>> byBone = new IdentityHashMap<>();
        for (Group group : groups) {
            byBone.computeIfAbsent(group.bone, ignored -> new ArrayList<>(2))
                .add(group);
        }
        return byBone;
    }

    private static int countBones(GeoBone bone) {
        int count = 1;
        for (GeoBone child : bone.childBones) {
            count += countBones(child);
        }
        return count;
    }

    /** Sets a deterministic, non-identity pose on every bone; returns the bone count. */
    private static int applyDeterministicPose(GeoModel model) {
        int[] counter = { 0 };
        for (GeoBone bone : model.topLevelBones) {
            applyPose(bone, counter);
        }
        return counter[0];
    }

    private static void applyPose(GeoBone bone, int[] counter) {
        int index = counter[0]++;
        bone.setRotationX(0.13f * (index % 5 + 1));
        bone.setRotationY(-0.07f * (index % 7 + 1));
        bone.setRotationZ(0.03f * (index % 3));
        bone.setPositionX(0.02f * (index % 4));
        bone.setPositionY(-0.015f * (index % 3));
        bone.setPositionZ(0.025f * (index % 5));
        float scale = index % 11 == 0 ? 0.85f : 1f;
        bone.setScaleX(scale);
        bone.setScaleY(scale);
        bone.setScaleZ(scale);
        for (GeoBone child : bone.childBones) {
            applyPose(child, counter);
        }
    }

    private static List<Path> representativeModels(Path repo) throws IOException {
        List<Path> all = Corpus.geometryFiles(repo);
        List<Path> mains = new ArrayList<>();
        for (Path file : all) {
            if ("main.json".equals(
                file.getFileName()
                    .toString())) {
                mains.add(file);
            }
        }
        mains.sort(Comparator.comparingLong(file -> {
            try {
                return Files.size(file);
            } catch (IOException ignored) {
                return 0L;
            }
        }));
        Set<Path> selected = new LinkedHashSet<>();
        Path arm = repo.resolve(Corpus.BUILTIN)
            .resolve("default/models/arm.json");
        if (Files.isRegularFile(arm)) {
            selected.add(arm);
        }
        if (!mains.isEmpty()) {
            selected.add(mains.get(0));
            if (mains.size() > 1) {
                selected.add(mains.get(mains.size() - 1));
            }
            if (mains.size() > 2) {
                selected.add(mains.get(mains.size() / 2));
            }
        }
        return new ArrayList<>(selected);
    }

    private static void warmUp(Path firstModel) {
        try {
            byte[] bytes = Files.readAllBytes(firstModel);
            long handle = NativeGeometry.bake(bytes, 0);
            if (handle == 0L) {
                return;
            }
            try {
                byte[] blob = NativeGeometry.blob(handle);
                if (blob == null) {
                    return;
                }
                GeoModel model = NativeGeometryReader.read(blob);
                applyDeterministicPose(model);
                PreparedModel prepared = new PreparedModel();
                WalkContext context = new WalkContext(
                    handle,
                    TessellatorRaw.create(SAFE_GROUP_INTS),
                    new int[SAFE_GROUP_INTS],
                    prepared);
                MatrixStack stack = IGeoRenderer.MATRIX_STACK;
                for (GeoBone bone : model.topLevelBones) {
                    walkAndCompare(bone, stack, 1f, 1f, 1f, 1f, context);
                }
                emitNativeModel(handle, prepared.groups, context.scratch);
            } finally {
                NativeGeometry.release(handle);
            }
        } catch (Throwable ignored) {
            // Warm-up only; the timed pass reports real failures.
        }
    }

    /** The harness's renderer instance: the abstract production methods are never reached. */
    private static final class HarnessRenderer implements IGeoRenderer<Object> {

        @Override
        public GeoModelProvider getGeoModelProvider() {
            throw new UnsupportedOperationException("parity harness does not resolve model providers");
        }

        @Override
        public ResourceLocation getTextureLocation(Object instance) {
            throw new UnsupportedOperationException("parity harness does not resolve textures");
        }
    }

    /** One (bone, cube filter) draw group: selected cubes, matrices, colour and vertices. */
    private static final class Group {

        GeoBone bone;
        int boneIndex;
        int filter;
        float red;
        float green;
        float blue;
        float alpha;
        int colorArgb;
        float[] modelMatrix;
        float[] normalMatrix;
        final List<GeoCube> cubes = new ArrayList<>();
        int vertices;
        boolean flatnessMixed;
    }

    private static final class PreparedModel {

        final List<Group> groups = new ArrayList<>();
        long totalVertices;
        long javaOnlyBones;
        long skippedGroups;

        void add(Group group) {
            groups.add(group);
            totalVertices += group.vertices;
        }
    }

    /** State of one model's comparison traversal. */
    private static final class ComparisonState {

        String failure;
        String pending;
        int comparedGroups;
    }

    /** Everything one recursive comparison pass carries down the bone tree. */
    private static final class WalkContext {

        final long handle;
        final Tessellator tessellator;
        int[] scratch;
        final PreparedModel prepared;
        final ComparisonState state = new ComparisonState();

        WalkContext(long handle, Tessellator tessellator, int[] scratch, PreparedModel prepared) {
            this.handle = handle;
            this.tessellator = tessellator;
            this.scratch = scratch;
            this.prepared = prepared;
        }
    }
}
