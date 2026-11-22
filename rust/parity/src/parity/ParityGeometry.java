package parity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fox.ysmu.nativebridge.NativeGeometry;

import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * M3 — geometry bake parity and benchmark.
 *
 * <p>
 * Java side: {@code Converter.fromJsonString} + {@code RawGeometryTree.parseHierarchy} +
 * {@code GeoBuilder.constructGeoModel}, i.e. exactly what {@code ClientModelManager.buildGeo} runs on
 * the loading thread. Rust side: {@code NativeGeometry.bake} + {@code NativeGeometry.blob} +
 * {@code NativeGeometryReader.read}, the compact-blob materialisation path M3 adds.
 *
 * <p>
 * The comparison is order-sensitive and bit-exact by construction: both models are flattened by
 * {@link GeoDump} into canonical lines (bones, cubes, quads and vertices in declaration order, every
 * numeric field as its raw f32/f64 bit pattern) and the line lists must be equal. A mismatch reports the
 * model name and the first differing line with both values, which is the actionable form for the M3
 * owner. {@code NativeGeometry.info} counts are checked separately.
 *
 * <p>
 * The corpus is every {@code models/*.json} of the built-in packs. If the M3 functions are not in the
 * loaded artifact yet, the module reports PENDING with the exact missing piece and still prints the Java
 * bake baseline, so the report stays regenerable while the milestone is in progress.
 */
public final class ParityGeometry {

    private ParityGeometry() {}

    public static void run(Path repo, Report report) {
        if (!NativeBootstrap.available()) {
            report.pending("geometry", "bake parity", "native layer unavailable: " + NativeBootstrap.status());
            return;
        }

        List<Path> files;
        try {
            files = Corpus.geometryFiles(repo);
        } catch (IOException failure) {
            report.fail("geometry", "corpus", "", "", "", "could not walk the geometry corpus: " + failure);
            return;
        }
        if (files.isEmpty()) {
            report.fail("geometry", "corpus", "", "", "", "no models/*.json found under " + Corpus.BUILTIN);
            return;
        }

        // ── Java bake baseline ─────────────────────────────────────────────────────────────────────
        List<BakedGeometry> javaModels = new ArrayList<>(files.size());
        long javaParseNanos = 0L;
        long javaBuildNanos = 0L;
        long javaBytes = 0L;
        List<String> javaFailures = new ArrayList<>();
        warmUpJava(repo);
        for (Path file : files) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException failure) {
                javaFailures.add(Corpus.name(repo, file) + ": read failed: " + failure);
                continue;
            }
            try {
                String json = new String(bytes, StandardCharsets.UTF_8);
                long parseStart = System.nanoTime();
                RawGeoModel rawModel = software.bernie.geckolib3.geo.raw.pojo.Converter.fromJsonString(json);
                long parseNanos = System.nanoTime() - parseStart;
                long buildStart = System.nanoTime();
                RawGeometryTree tree = RawGeometryTree.parseHierarchy(rawModel);
                GeoModel model = GeoBuilder.getGeoBuilder("ysmu")
                    .constructGeoModel(tree);
                long buildNanos = System.nanoTime() - buildStart;
                javaModels.add(new BakedGeometry(Corpus.name(repo, file), bytes, model, parseNanos, buildNanos));
                javaParseNanos += parseNanos;
                javaBuildNanos += buildNanos;
                javaBytes += bytes.length;
            } catch (Throwable failure) {
                javaFailures.add(Corpus.name(repo, file) + ": Java bake threw: " + failure);
            }
        }
        if (javaModels.isEmpty()) {
            report.fail(
                "geometry",
                "Java bake baseline",
                "",
                "",
                "",
                "the Java path could not bake a single built-in geometry: " + firstOrEmpty(javaFailures));
            return;
        }
        if (!javaFailures.isEmpty()) {
            report.fail(
                "geometry",
                "Java bake baseline",
                javaModels.size() + "/" + files.size() + " models",
                "",
                "",
                "Java bake failed for " + javaFailures.size() + " model(s): " + firstOrEmpty(javaFailures));
        }
        long javaBakeNanos = javaParseNanos + javaBuildNanos;
        String javaPerModel = String.format(
            java.util.Locale.ROOT,
            "%.2f ms/model",
            javaBakeNanos / 1_000_000.0 / javaModels.size());
        report.pass(
            "geometry",
            "Java bake baseline",
            javaPerModel,
            "",
            "",
            javaModels.size() + " models, " + Corpus.humanBytes(javaBytes));
        report.pass(
            "geometry",
            "Java bake split (Jackson parse / tree+build)",
            String.format(
                java.util.Locale.ROOT,
                "parse %.2f ms/model, build %.2f ms/model",
                javaParseNanos / 1_000_000.0 / javaModels.size(),
                javaBuildNanos / 1_000_000.0 / javaModels.size()),
            "",
            "",
            "timed separately per model; the CSV breakdown is in out/geometry-timings.txt");
        BakedGeometry largest = javaModels.get(0);
        for (BakedGeometry model : javaModels) {
            if (model.bytes.length > largest.bytes.length) {
                largest = model;
            }
        }

        // ── Native bake ────────────────────────────────────────────────────────────────────────────
        if (!NativeGeometry.isUsable()) {
            report.pending(
                "geometry",
                "bake parity",
                "NativeGeometry.isUsable() is false: " + NativeBootstrap.status()
                    + (NativeGeometryApi.hasInfo() ? "" : "; " + NativeGeometryApi.infoProblem())
                    + "; " + NativeGeometryApi.readerAvailability());
            return;
        }
        warmUpNative(repo);
        List<NativeGeometryModel> nativeModels = new ArrayList<>(javaModels.size());
        long nativeBakeNanos = 0L;
        int declined = 0;
        String firstDecline = null;
        for (BakedGeometry javaModel : javaModels) {
            long start = System.nanoTime();
            long handle = NativeGeometry.bake(javaModel.bytes, 0);
            long elapsed = System.nanoTime() - start;
            if (handle == 0L) {
                declined++;
                if (firstDecline == null) {
                    firstDecline = javaModel.name;
                }
                continue;
            }
            nativeModels.add(new NativeGeometryModel(javaModel, handle, elapsed));
            nativeBakeNanos += elapsed;
        }
        try {
            if (nativeModels.isEmpty()) {
                report.pending(
                    "geometry",
                    "bake parity",
                    NativeBootstrap.status() + " - every NativeGeometry.bake returned 0 for valid geometry (M3 JNI bake"
                        + " is not implemented in the loaded artifact yet, or the geometry module was disabled after a"
                        + " link failure)");
                return;
            }
            if (declined > 0) {
                report.fail(
                    "geometry",
                    "native bake acceptance",
                    nativeModels.size() + "/" + javaModels.size() + " models",
                    "",
                    "",
                    "NativeGeometry.bake declined " + declined + " model(s) the Java path accepts; first: " + firstDecline);
            } else {
                report.pass(
                    "geometry",
                    "native bake acceptance",
                    javaModels.size() + " models",
                    nativeModels.size() + " models",
                    "",
                    "every built-in geometry the Java path accepts is baked natively");
            }

            // ── Blob + info ───────────────────────────────────────────────────────────────────────
            long blobReadNanos = 0L;
            long infoNanos = 0L;
            long blobBytes = 0L;
            List<MaterialisedGeometry> blobs = new ArrayList<>(nativeModels.size());
            int blobMissing = 0;
            String firstBlobMissing = null;
            for (NativeGeometryModel nativeModel : nativeModels) {
                long infoStart = System.nanoTime();
                int[] info = NativeGeometryApi.hasInfo() ? NativeGeometryApi.info(nativeModel.handle) : null;
                long infoElapsed = System.nanoTime() - infoStart;
                long start = System.nanoTime();
                byte[] blob = NativeGeometry.blob(nativeModel.handle);
                long elapsed = System.nanoTime() - start;
                if (blob == null || blob.length == 0) {
                    blobMissing++;
                    if (firstBlobMissing == null) {
                        firstBlobMissing = nativeModel.javaModel.name;
                    }
                    continue;
                }
                blobs.add(new MaterialisedGeometry(nativeModel, blob, info, infoElapsed, elapsed));
                infoNanos += infoElapsed;
                blobReadNanos += elapsed;
                blobBytes += blob.length;
            }
            if (blobs.isEmpty()) {
                report.pending(
                    "geometry",
                    "bake parity",
                    "NativeGeometry.blob returned null/empty for every baked model (M3 blob materialisation not wired"
                        + " yet)");
                reportBakeBenchmark(report, javaBakeNanos, javaModels.size(), nativeBakeNanos, nativeModels.size());
                return;
            }
            if (blobMissing > 0) {
                report.fail(
                    "geometry",
                    "native blob materialisation",
                    blobs.size() + "/" + nativeModels.size() + " models",
                    "",
                    "",
                    "NativeGeometry.blob returned null/empty for " + blobMissing + " model(s); first: " + firstBlobMissing);
            } else {
                report.pass(
                    "geometry",
                    "native blob materialisation",
                    nativeModels.size() + " models",
                    Corpus.humanBytes(blobBytes),
                    "",
                    String.format(
                        java.util.Locale.ROOT,
                        "%.3f ms/model blob read",
                        blobReadNanos / 1_000_000.0 / blobs.size()));
            }

            // ── Java vs native object graph ────────────────────────────────────────────────────────
            String readerProblem = NativeGeometryApi.readerAvailability();
            if (!readerProblem.startsWith("available")) {
                report.pending(
                    "geometry",
                    "bake field parity",
                    "native bake and blob work but the reader is missing: " + readerProblem);
                reportBakeBenchmark(report, javaBakeNanos, javaModels.size(), nativeBakeNanos, nativeModels.size());
                reportNativeStageBenchmark(report, blobReadNanos, blobs.size(), blobBytes);
                return;
            }

            int exact = 0;
            int mismatched = 0;
            String firstDifference = null;
            List<String> mismatchedModels = new ArrayList<>();
            int countMismatches = 0;
            String firstCountDifference = null;
            long materialiseNanos = 0L;
            boolean infoAvailable = NativeGeometryApi.hasInfo();
            for (MaterialisedGeometry materialised : blobs) {
                GeoModel nativeModel;
                long start = System.nanoTime();
                try {
                    nativeModel = NativeGeometryApi.read(materialised.blob);
                } catch (Throwable failure) {
                    mismatched++;
                    if (firstDifference == null) {
                        firstDifference = materialised.nativeModel.javaModel.name + ": reader threw: " + failure;
                    }
                    continue;
                }
                long elapsed = System.nanoTime() - start;
                materialised.readNanos = elapsed;
                materialiseNanos += elapsed;

                List<String> javaDump = GeoDump.dump(materialised.nativeModel.javaModel.model);
                List<String> nativeDump = GeoDump.dump(nativeModel);
                if (javaDump.equals(nativeDump)) {
                    exact++;
                } else {
                    mismatched++;
                    String difference = describeDifference(materialised.nativeModel.javaModel.name, javaDump, nativeDump);
                    if (firstDifference == null) {
                        firstDifference = difference;
                    }
                    mismatchedModels.add(difference);
                }

                if (infoAvailable) {
                    int[] info = materialised.info;
                    if (info == null || info.length < 4) {
                        countMismatches++;
                        if (firstCountDifference == null) {
                            firstCountDifference = materialised.nativeModel.javaModel.name
                                + ": NativeGeometry.info returned "
                                + (info == null ? "null" : info.length + " ints");
                        }
                    } else {
                        int[] javaCounts = counts(materialised.nativeModel.javaModel.model);
                        if (javaCounts[0] != info[0] || javaCounts[1] != info[1] || javaCounts[2] != info[2]
                            || javaCounts[3] != (info[3] & 7)) {
                            countMismatches++;
                            if (firstCountDifference == null) {
                                firstCountDifference = materialised.nativeModel.javaModel.name + ": Java [bones="
                                    + javaCounts[0] + ", cubes=" + javaCounts[1] + ", quads=" + javaCounts[2] + ", flags="
                                    + javaCounts[3] + "] native [bones=" + info[0] + ", cubes=" + info[1] + ", quads="
                                    + info[2] + ", flags=" + (info[3] & 7) + "]";
                            }
                        }
                    }
                }
            }
            String compared = blobs.size() + " models, "
                + String.format(
                    java.util.Locale.ROOT,
                    "%.3f ms/model materialise",
                    materialiseNanos / 1_000_000.0 / blobs.size());
            if (mismatched == 0) {
                report.pass(
                    "geometry",
                    "bake field parity",
                    exact + "/" + javaModels.size() + " models",
                    compared,
                    "",
                    "every bone/cube/quad/vertex/property bit matches GeoBuilder's object graph in declaration order");
            } else {
                report.fail("geometry", "bake field parity", exact + "/" + blobs.size() + " models", "", "", firstDifference);
                writeMismatchFile(mismatchedModels);
            }
            if (infoAvailable) {
                if (countMismatches == 0) {
                    report.pass(
                        "geometry",
                        "info counts parity",
                        javaModels.size() + " models",
                        "",
                        "",
                        "bone/cube/quad counts and flat/neg/mesh flags match NativeGeometry.info");
                } else {
                    report.fail(
                        "geometry",
                        "info counts parity",
                        countMismatches + " mismatched",
                        "",
                        "",
                        firstCountDifference);
                }
            } else {
                report.skip("geometry", "info counts parity", NativeGeometryApi.infoProblem());
            }

            reportBakeBenchmark(report, javaBakeNanos, javaModels.size(), nativeBakeNanos, nativeModels.size());
            reportNativeStageBenchmark(report, blobReadNanos, blobs.size(), blobBytes);
            report.pass(
                "geometry",
                "materialise speed (blob -> GeoModel)",
                "",
                String.format(java.util.Locale.ROOT, "%.3f ms/model", materialiseNanos / 1_000_000.0 / blobs.size()),
                "",
                "native-only stage: Java produces the object graph directly during bake");
            reportTimingSplit(
                report,
                javaModels,
                blobs,
                javaParseNanos,
                javaBuildNanos,
                nativeBakeNanos,
                infoNanos,
                blobReadNanos,
                materialiseNanos,
                largest);
        } finally {
            for (NativeGeometryModel nativeModel : nativeModels) {
                NativeGeometry.release(nativeModel.handle);
            }
        }
    }

    /**
     * Per-stage timing split, aggregate and for the largest document, plus a per-model CSV next to the
     * report. Method: one un-timed warm-up call of each implementation, then for every model one timed
     * {@code System.nanoTime()} call around each stage (Java: Jackson parse, then tree+build; native: the
     * JNI {@code bake} call, then {@code info}, then {@code blob} (which includes the Java-side copy of
     * the blob bytes), then {@code NativeGeometryReader.read}). One model at a time, no interleaving.
     */
    private static void reportTimingSplit(Report report, List<BakedGeometry> javaModels,
        List<MaterialisedGeometry> blobs, long javaParseNanos, long javaBuildNanos, long nativeBakeNanos,
        long infoNanos, long blobNanos, long readNanos, BakedGeometry largest) {
        int javaCount = javaModels.size();
        int nativeCount = blobs.size();
        if (javaCount <= 0 || nativeCount <= 0) {
            return;
        }
        double parseMs = javaParseNanos / 1_000_000.0 / javaCount;
        double buildMs = javaBuildNanos / 1_000_000.0 / javaCount;
        double bakeMs = nativeBakeNanos / 1_000_000.0 / nativeCount;
        double infoMs = infoNanos / 1_000_000.0 / nativeCount;
        double blobMs = blobNanos / 1_000_000.0 / nativeCount;
        double readMs = readNanos / 1_000_000.0 / nativeCount;
        report.pass(
            "geometry",
            "timing split (aggregate, per model)",
            String.format(Locale.ROOT, "Jackson parse %.2f ms, tree+build %.2f ms", parseMs, buildMs),
            String.format(
                Locale.ROOT,
                "native bake %.2f ms, info %.3f ms, blob %.3f ms, reader read %.2f ms",
                bakeMs,
                infoMs,
                blobMs,
                readMs),
            Report.timeSpeedup(parseMs + buildMs, bakeMs),
            "one warm-up call per implementation, then one timed call per model; per-model CSV in"
                + " out/geometry-timings.txt");
        report.pass(
            "geometry",
            "Jackson parse vs native bake",
            String.format(Locale.ROOT, "parse %.2f ms/model", parseMs),
            String.format(Locale.ROOT, "bake %.2f ms/model", bakeMs),
            Report.timeSpeedup(parseMs, bakeMs),
            "isolates JSON parsing: the native bake also builds the geometry, so a Rust win here means its"
                + " parse+bake is cheaper than Jackson parsing alone");
        MaterialisedGeometry largestNative = null;
        for (MaterialisedGeometry materialised : blobs) {
            if (materialised.nativeModel.javaModel.name.equals(largest.name)) {
                largestNative = materialised;
                break;
            }
        }
        if (largestNative != null) {
            report.pass(
                "geometry",
                "largest model split (" + largest.name + ", " + Corpus.humanBytes(largest.bytes.length) + ")",
                String.format(
                    Locale.ROOT,
                    "Jackson parse %.2f ms, tree+build %.2f ms",
                    largest.parseNanos / 1_000_000.0,
                    largest.buildNanos / 1_000_000.0),
                String.format(
                    Locale.ROOT,
                    "native bake %.2f ms, info %.3f ms, blob %.3f ms, reader read %.2f ms",
                    largestNative.nativeModel.nanos / 1_000_000.0,
                    largestNative.infoNanos / 1_000_000.0,
                    largestNative.blobNanos / 1_000_000.0,
                    largestNative.readNanos / 1_000_000.0),
                Report.timeSpeedup(
                    (largest.parseNanos + largest.buildNanos) / 1_000_000.0,
                    largestNative.nativeModel.nanos / 1_000_000.0),
                "the biggest geometry document dominates the aggregate; source is declaration order, not"
                    + " wall-clock interleaving");
        }
        writeTimingFile(javaModels, blobs);
    }

    private static void writeTimingFile(List<BakedGeometry> javaModels, List<MaterialisedGeometry> blobs) {
        try {
            Path target = Path.of("rust/parity/out/geometry-timings.txt");
            Files.createDirectories(target.getParent());
            Map<String, MaterialisedGeometry> byName = new java.util.HashMap<>();
            for (MaterialisedGeometry materialised : blobs) {
                byName.put(materialised.nativeModel.javaModel.name, materialised);
            }
            List<BakedGeometry> sorted = new ArrayList<>(javaModels);
            sorted.sort((a, b) -> Long.compare(b.bytes.length, a.bytes.length));
            StringBuilder builder = new StringBuilder(8192);
            builder.append("# Geometry bake timing breakdown (ms). One timed call per model per stage;\n")
                .append("# Java: Converter.fromJsonString (parse) then RawGeometryTree+GeoBuilder (build).\n")
                .append("# Native: NativeGeometry.bake JNI call, then info, then blob (includes the Java copy),\n")
                .append("# then NativeGeometryReader.read (materialisation).\n")
                .append("model\tbytes\tjava_parse_ms\tjava_build_ms\tnative_bake_ms\tnative_info_ms\t")
                .append("native_blob_ms\tnative_read_ms\n");
            for (BakedGeometry javaModel : sorted) {
                MaterialisedGeometry materialised = byName.get(javaModel.name);
                builder.append(javaModel.name)
                    .append('\t')
                    .append(javaModel.bytes.length)
                    .append('\t')
                    .append(formatMs(javaModel.parseNanos))
                    .append('\t')
                    .append(formatMs(javaModel.buildNanos))
                    .append('\t');
                if (materialised == null) {
                    builder.append("\t\t\t\n");
                } else {
                    builder.append(formatMs(materialised.nativeModel.nanos))
                        .append('\t')
                        .append(formatMs(materialised.infoNanos))
                        .append('\t')
                        .append(formatMs(materialised.blobNanos))
                        .append('\t')
                        .append(formatMs(materialised.readNanos))
                        .append('\n');
                }
            }
            Files.writeString(target, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The aggregate rows are already in the report; the CSV is a convenience for the owner.
        }
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static void reportBakeBenchmark(Report report, long javaNanos, int javaCount, long rustNanos, int rustCount) {
        if (javaCount <= 0 || rustCount <= 0) {
            return;
        }
        double javaMs = javaNanos / 1_000_000.0 / javaCount;
        double rustMs = rustNanos / 1_000_000.0 / rustCount;
        report.pass(
            "geometry",
            "bake speed (JSON -> object graph)",
            String.format(java.util.Locale.ROOT, "%.2f ms/model", javaMs),
            String.format(java.util.Locale.ROOT, "%.2f ms/model", rustMs),
            Report.timeSpeedup(javaMs, rustMs),
            javaCount + " Java models vs " + rustCount + " native bakes (native timing excludes blob read/materialise)");
    }

    private static void reportNativeStageBenchmark(Report report, long blobNanos, int count, long blobBytes) {
        if (count <= 0) {
            return;
        }
        report.pass(
            "geometry",
            "native blob read speed",
            "",
            String.format(java.util.Locale.ROOT, "%.3f ms/model", blobNanos / 1_000_000.0 / count),
            "",
            Corpus.humanBytes(blobBytes) + " of compact blobs for " + count + " models");
    }

    private static GeoModel javaBake(byte[] bytes) throws IOException {
        String json = new String(bytes, StandardCharsets.UTF_8);
        RawGeoModel rawModel = software.bernie.geckolib3.geo.raw.pojo.Converter.fromJsonString(json);
        RawGeometryTree tree = RawGeometryTree.parseHierarchy(rawModel);
        return GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(tree);
    }

    private static void warmUpJava(Path repo) {
        try {
            List<Path> files = Corpus.geometryFiles(repo);
            if (files.isEmpty()) {
                return;
            }
            // Warm up on the largest documents (they exercise every Jackson/GeoBuilder code path the
            // timed pass will use) and twice each, so the timed pass runs on C2-compiled code.
            files.sort((a, b) -> {
                try {
                    return Long.compare(Files.size(b), Files.size(a));
                } catch (IOException ignored) {
                    return 0;
                }
            });
            for (int i = 0; i < Math.min(2, files.size()); i++) {
                byte[] bytes = Files.readAllBytes(files.get(i));
                javaBake(bytes);
                javaBake(bytes);
            }
        } catch (Throwable ignored) {
            // Warm-up only; a real failure will be reported by the timed pass.
        }
    }

    private static void warmUpNative(Path repo) {
        try {
            List<Path> files = Corpus.geometryFiles(repo);
            if (files.isEmpty()) {
                return;
            }
            files.sort((a, b) -> {
                try {
                    return Long.compare(Files.size(b), Files.size(a));
                } catch (IOException ignored) {
                    return 0;
                }
            });
            for (int i = 0; i < Math.min(2, files.size()); i++) {
                byte[] bytes = Files.readAllBytes(files.get(i));
                long handle = NativeGeometry.bake(bytes, 0);
                if (handle != 0L) {
                    NativeGeometry.blob(handle);
                    NativeGeometry.release(handle);
                }
            }
        } catch (Throwable ignored) {
            // Warm-up only.
        }
    }

    /** {@code [boneCount, cubeCount, quadCount, flags]} — flags bit0 flat, bit1 neg-size, bit2 mesh. */
    private static int[] counts(GeoModel model) {
        int[] counts = new int[4];
        for (GeoBone bone : model.topLevelBones) {
            countBone(bone, counts);
        }
        return counts;
    }

    private static void countBone(GeoBone bone, int[] counts) {
        counts[0]++;
        for (GeoCube cube : bone.childCubes) {
            if (cube == null) {
                continue;
            }
            counts[1]++;
            if (cube.mesh) {
                counts[3] |= 4;
            }
            if (cube.hasNegSize) {
                counts[3] |= 2;
            }
            if (!cube.mesh && (cube.size.x == 0f || cube.size.y == 0f || cube.size.z == 0f)) {
                counts[3] |= 1;
            }
            if (cube.quads != null) {
                for (software.bernie.geckolib3.geo.render.built.GeoQuad quad : cube.quads) {
                    if (quad != null) {
                        counts[2]++;
                    }
                }
            }
        }
        for (GeoBone child : bone.childBones) {
            countBone(child, counts);
        }
    }

    private static String describeDifference(String modelName, List<String> javaLines, List<String> nativeLines) {        int limit = Math.min(javaLines.size(), nativeLines.size());
        for (int index = 0; index < limit; index++) {
            String javaLine = javaLines.get(index);
            String nativeLine = nativeLines.get(index);
            if (!javaLine.equals(nativeLine)) {
                return modelName + ": first differing field at dump line " + (index + 1) + "\n      Java: "
                    + clip(javaLine) + "\n      Rust: " + clip(nativeLine);
            }
        }
        return modelName + ": dump lengths differ (Java " + javaLines.size() + " lines, Rust " + nativeLines.size()
            + " lines); first extra Java line: " + clip(javaLines.size() > limit ? javaLines.get(limit) : "<none>")
            + ", first extra Rust line: " + clip(nativeLines.size() > limit ? nativeLines.get(limit) : "<none>");
    }

    private static String clip(String value) {
        return value.length() <= 700 ? value : value.substring(0, 700) + "…";
    }

    /**
     * Writes every mismatched model's first differing field to {@code out/geometry-mismatches.txt}, so
     * the M3 owner gets the complete actionable list (the summary row only has room for the first one).
     */
    private static void writeMismatchFile(List<String> differences) {
        try {
            Path target = Path.of("rust/parity/out/geometry-mismatches.txt");
            Files.createDirectories(target.getParent());
            StringBuilder builder = new StringBuilder(4096);
            builder.append("# Geometry parity mismatches — Java GeoBuilder vs NativeGeometryReader\n\n")
                .append("One block per model: model path, then the first differing dump line (object path and")
                .append(" every field of that object) with the Java and Rust values.\n\n");
            for (String difference : differences) {
                builder.append("## ")
                    .append(difference)
                    .append("\n\n");
            }
            Files.writeString(target, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The report already names the first difference; the auxiliary file is a convenience.
        }
    }

    private static String firstOrEmpty(List<String> failures) {
        return failures.isEmpty() ? "" : failures.get(0);
    }

    private static final class BakedGeometry {

        final String name;
        final byte[] bytes;
        final GeoModel model;
        final long parseNanos;
        final long buildNanos;

        BakedGeometry(String name, byte[] bytes, GeoModel model, long parseNanos, long buildNanos) {
            this.name = name;
            this.bytes = bytes;
            this.model = model;
            this.parseNanos = parseNanos;
            this.buildNanos = buildNanos;
        }
    }

    private static final class NativeGeometryModel {

        final BakedGeometry javaModel;
        final long handle;
        final long nanos;

        NativeGeometryModel(BakedGeometry javaModel, long handle, long nanos) {
            this.javaModel = javaModel;
            this.handle = handle;
            this.nanos = nanos;
        }
    }

    private static final class MaterialisedGeometry {

        final NativeGeometryModel nativeModel;
        final byte[] blob;
        final int[] info;
        final long infoNanos;
        final long blobNanos;
        long readNanos;

        MaterialisedGeometry(NativeGeometryModel nativeModel, byte[] blob, int[] info, long infoNanos, long blobNanos) {
            this.nativeModel = nativeModel;
            this.blob = blob;
            this.info = info;
            this.infoNanos = infoNanos;
            this.blobNanos = blobNanos;
        }
    }
}
