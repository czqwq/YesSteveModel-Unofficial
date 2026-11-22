package com.fox.ysmu.nativebridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.vecmath.Vector3f;

import net.minecraft.util.EnumFacing;

import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;

/**
 * Reads the native geometry materialisation blob into the engine's object graph.
 *
 * <p>
 * This is the Java half of the M3 geometry module ({@code .agent/phase78-rust-native-modules.md}): the Rust
 * bake ({@code rust/ysmu_native/src/geometry/bake.rs}) parses a geometry JSON exactly the way
 * {@code Converter} + {@code RawGeometryTree} + {@code GeoBuilder} would, and writes the field values of the
 * resulting {@code GeoModel -> GeoBone -> GeoCube -> GeoQuad -> GeoVertex} graph into a compact little-endian
 * blob ({@code rust/ysmu_native/src/geometry/blob.rs}). This class is the only Java reader of that layout; its
 * output must be equal, field for field, to what {@code GeoBuilder.constructGeoModel} returns - the parity
 * harness compares the two for every built-in geometry.
 *
 * <p>
 * <b>What is built here and what is not.</b> Only what the bake produces: the object fields, the parent/child
 * links, the locator chains ({@code leftHandBones} / {@code rightHandBones} / {@code elytraBones}, reversed to
 * root-first exactly like {@code GeoBuilder}), {@code firstPersonHead} / {@code firstPersonViewLocator},
 * {@code properties} and each bone's {@code nativeBoneIndex}. Everything the engine sets at runtime
 * (snapshots, render state, texture overrides) keeps its default. {@code GeoModel.nativeHandle} is left at
 * {@code 0}: the handle belongs to the caller ({@code ClientModelManager}), so the reader has no ownership
 * opinion and cannot leak a native handle from an error path.
 *
 * <p>
 * Every structural inconsistency throws {@link IllegalStateException}: the blob is native memory produced by
 * the same bake, so a mismatch is a contract violation, and the caller falls back to the Java bake rather
 * than installing a half-built model.
 *
 * <p>
 * Blob layout, version {@value #BLOB_VERSION} (all integers little-endian; {@code string} is a {@code u32}
 * byte length followed by UTF-8, with {@code 0xFFFFFFFF} meaning {@code null}; {@code tristate} is a
 * {@code u8} 0 = null / 1 = false / 2 = true; {@code opt<T>} is a {@code u8} presence flag followed by
 * {@code T}):
 *
 * <pre>
 * header        "YSMG", u32 version, u32 boneCount, u32 cubeCount, u32 quadCount
 * properties    opt&lt;ModelProperties&gt;
 * model lists   u32 topLevel + indices, u32 leftHand + indices, u32 rightHand + indices,
 *               u32 elytra + indices, i32 firstPersonHead, i32 firstPersonViewLocator
 * bones         boneCount records, depth-first order:
 *                 string name, i32 parent, u32 childCount + child indices,
 *                 tristate mirror, tristate dontRender, tristate reset, opt&lt;f64&gt; inflate,
 *                 f32 rotationPoint[3], f32 rotation[3], u32 cubeCount + cubes
 * cube          f32 size[3], f32 pivot[3], f32 rotation[3], f64 inflate,
 *               tristate mirror, u8 mesh, u8 hasNegSize, u32 quadSlots + (u8 present + quad)
 * quad          i8 direction (EnumFacing ordinal, -1 = null), i32 normal[3], f32 normalVector[3],
 *               i32 uvRotation, 4 * (f32 position[3], f32 u, f32 v)
 * </pre>
 *
 * {@code ModelProperties}: the eleven {@code animation*} / {@code preserve_model_pose} booleans, string
 * {@code identifier}, {@code opt<f64>} texture height/width, visible bounds height/offset/width,
 * {@code f64} height/width scale, {@code opt<ExtraInfo>}, tristate {@code render_layers_first}, string
 * {@code default_texture}, tristate {@code force_culling}. {@code ExtraInfo}: string name, string tips,
 * {@code opt<string[]>} extra animation names, {@code opt<string[]>} authors, string license.
 *
 * @see NativeGeometry#bake(byte[], int)
 * @see NativeGeometry#blob(long)
 */
public final class NativeGeometryReader {

    /** The blob layout version this reader understands; must equal `blob::BLOB_VERSION` on the Rust side. */
    public static final int BLOB_VERSION = 1;

    private static final byte[] MAGIC = { 'Y', 'S', 'M', 'G' };
    private static final int NULL_STRING = -1;

    private NativeGeometryReader() {}

    /**
     * Materialises one blob into the engine's geometry graph.
     *
     * @param blob the bytes {@link NativeGeometry#blob(long)} returned
     * @return the model, equal field for field to the Java {@code GeoBuilder} bake
     * @throws IllegalArgumentException when {@code blob} is {@code null}
     * @throws IllegalStateException    when the blob is truncated, malformed or declares inconsistent counts
     */
    public static GeoModel read(byte[] blob) {
        if (blob == null) {
            throw new IllegalArgumentException("native geometry blob is null");
        }
        Cursor cursor = new Cursor(blob);
        byte[] magic = cursor.bytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IllegalStateException("native geometry blob: bad magic");
        }
        int version = cursor.u32();
        if (version != BLOB_VERSION) {
            throw new IllegalStateException("native geometry blob: unsupported version " + version);
        }
        int boneCount = cursor.length("bone count");
        int cubeCount = cursor.length("cube count");
        int quadCount = cursor.length("quad count");
        boolean hasProperties = cursor.u8() == 1;
        ModelProperties properties = hasProperties ? readProperties(cursor) : null;
        if (properties == null) {
            throw new IllegalStateException("native geometry blob: missing properties");
        }

        int[] topLevel = cursor.indices(boneCount);
        int[] leftHand = cursor.indices(boneCount);
        int[] rightHand = cursor.indices(boneCount);
        int[] elytra = cursor.indices(boneCount);
        int firstPersonHead = cursor.i32();
        int firstPersonViewLocator = cursor.i32();

        GeoBone[] bones = new GeoBone[boneCount];
        int[] parents = new int[boneCount];
        int[][] children = new int[boneCount][];
        int cubesRead = 0;
        int quadsRead = 0;
        for (int index = 0; index < boneCount; index++) {
            GeoBone bone = new GeoBone();
            bone.setModelRendererName(cursor.string());
            bone.nativeBoneIndex = index;
            parents[index] = cursor.i32();
            children[index] = cursor.indices(boneCount);
            bone.mirror = cursor.tristate();
            bone.dontRender = cursor.tristate();
            bone.reset = cursor.tristate();
            bone.inflate = cursor.optionalDouble();
            bone.rotationPointX = cursor.f32();
            bone.rotationPointY = cursor.f32();
            bone.rotationPointZ = cursor.f32();
            bone.setRotationX(cursor.f32());
            bone.setRotationY(cursor.f32());
            bone.setRotationZ(cursor.f32());
            int boneCubeCount = cursor.length("bone cube count");
            for (int cubeIndex = 0; cubeIndex < boneCubeCount; cubeIndex++) {
                GeoCube cube = readCube(cursor, properties);
                for (GeoQuad quad : cube.quads) {
                    if (quad != null) {
                        quadsRead++;
                    }
                }
                bone.childCubes.add(cube);
            }
            cubesRead += boneCubeCount;
            bones[index] = bone;
        }
        if (cubesRead != cubeCount) {
            throw new IllegalStateException("native geometry blob: cube count " + cubesRead + " != " + cubeCount);
        }
        if (quadsRead != quadCount) {
            throw new IllegalStateException("native geometry blob: quad count " + quadsRead + " != " + quadCount);
        }
        if (!cursor.atEnd()) {
            throw new IllegalStateException("native geometry blob: " + cursor.remaining() + " trailing bytes");
        }

        // Links are built after all bones exist, so a forward reference (a malformed blob) cannot install a
        // partially linked graph.
        for (int index = 0; index < boneCount; index++) {
            if (parents[index] < -1 || parents[index] >= boneCount) {
                throw new IllegalStateException("native geometry blob: bad parent " + parents[index]);
            }
            bones[index].parent = parents[index] < 0 ? null : bones[parents[index]];
            for (int child : children[index]) {
                if (child < 0 || child >= boneCount) {
                    throw new IllegalStateException("native geometry blob: bad child index " + child);
                }
                bones[index].childBones.add(bones[child]);
            }
        }

        GeoModel model = new GeoModel();
        model.properties = properties;
        for (int index : topLevel) {
            model.topLevelBones.add(requireBone(bones, index, "top level"));
        }
        for (int index : leftHand) {
            model.leftHandBones.add(requireBone(bones, index, "left hand"));
        }
        for (int index : rightHand) {
            model.rightHandBones.add(requireBone(bones, index, "right hand"));
        }
        for (int index : elytra) {
            model.elytraBones.add(requireBone(bones, index, "elytra"));
        }
        model.firstPersonHead = firstPersonHead < 0 ? null : requireBone(bones, firstPersonHead, "first person head");
        model.firstPersonViewLocator = firstPersonViewLocator < 0 ? null
            : requireBone(bones, firstPersonViewLocator, "first person view locator");
        return model;
    }

    private static GeoBone requireBone(GeoBone[] bones, int index, String what) {
        if (index < 0 || index >= bones.length) {
            throw new IllegalStateException("native geometry blob: bad " + what + " bone index " + index);
        }
        return bones[index];
    }

    private static GeoCube readCube(Cursor cursor, ModelProperties properties) {
        GeoCube cube = blankCube(properties);
        cube.size.set(cursor.f32(), cursor.f32(), cursor.f32());
        cube.pivot = new Vector3f(cursor.f32(), cursor.f32(), cursor.f32());
        cube.rotation = new Vector3f(cursor.f32(), cursor.f32(), cursor.f32());
        cube.inflate = cursor.f64();
        cube.mirror = cursor.tristate();
        cube.mesh = cursor.u8() == 1;
        cube.hasNegSize = cursor.u8() == 1;
        int quadSlots = cursor.length("cube quad slots");
        GeoQuad[] quads = new GeoQuad[quadSlots];
        for (int slot = 0; slot < quadSlots; slot++) {
            if (cursor.u8() == 1) {
                quads[slot] = readQuad(cursor);
            }
        }
        cube.quads = quads;
        return cube;
    }

    /**
     * A `GeoCube` with every field at its constructor default, ready for the reader to overwrite.
     *
     * <p>
     * {@code GeoCube}'s only public factories are {@code createFromPojoCube} and {@code createFromPolyMesh},
     * and its constructor is private. {@code createFromPolyMesh(null, properties)} is the engine's own
     * "blank cube" path: it returns before inspecting the mesh, i.e. exactly the object with six {@code null}
     * quads this reader then replaces. No other engine change was needed to materialise a native cube.
     */
    private static GeoCube blankCube(ModelProperties properties) {
        return GeoCube.createFromPolyMesh(null, properties);
    }

    private static GeoQuad readQuad(Cursor cursor) {
        int direction = cursor.i8();
        int normalX = cursor.i32();
        int normalY = cursor.i32();
        int normalZ = cursor.i32();
        Vector3f normalVector = new Vector3f(cursor.f32(), cursor.f32(), cursor.f32());
        int uvRotation = cursor.i32();
        GeoVertex[] vertices = new GeoVertex[4];
        for (int index = 0; index < 4; index++) {
            float x = cursor.f32();
            float y = cursor.f32();
            float z = cursor.f32();
            float u = cursor.f32();
            float v = cursor.f32();
            vertices[index] = new GeoVertex(x, y, z).setTextureUV(u, v);
        }
        // `GeoQuad(GeoVertex[], Vector3f)` is the only constructor that stores a normal without recomputing
        // uvs: it rounds the vector into `normal` (which must equal the stored integers - checked below) and
        // copies the precise vector into `normalVector`, keeping an arbitrary poly-mesh normal intact.
        GeoQuad quad = new GeoQuad(vertices, normalVector);
        if (quad.normal.getX() != normalX || quad.normal.getY() != normalY || quad.normal.getZ() != normalZ) {
            throw new IllegalStateException(
                "native geometry blob: quad normal " + normalX + "," + normalY + "," + normalZ + " does not round to itself");
        }
        quad.direction = direction < 0 ? null : EnumFacing.values()[direction];
        quad.uvRotation = uvRotation;
        return quad;
    }

    private static ModelProperties readProperties(Cursor cursor) {
        ModelProperties properties = new ModelProperties();
        properties.setAnimationArmsDown(cursor.tristate());
        properties.setAnimationArmsOutFront(cursor.tristate());
        properties.setAnimationDontShowArmor(cursor.tristate());
        properties.setAnimationInvertedCrouch(cursor.tristate());
        properties.setAnimationNoHeadBob(cursor.tristate());
        properties.setAnimationSingleArmAnimation(cursor.tristate());
        properties.setAnimationSingleLegAnimation(cursor.tristate());
        properties.setAnimationStationaryLegs(cursor.tristate());
        properties.setAnimationStatueOfLibertyArms(cursor.tristate());
        properties.setAnimationUpsideDown(cursor.tristate());
        properties.setPreserveModelPose(cursor.tristate());
        properties.setIdentifier(cursor.string());
        properties.setTextureHeight(cursor.optionalDouble());
        properties.setTextureWidth(cursor.optionalDouble());
        properties.setVisibleBoundsHeight(cursor.optionalDouble());
        properties.setVisibleBoundsOffset(cursor.optionalDoubleArray());
        properties.setVisibleBoundsWidth(cursor.optionalDouble());
        properties.setHeightScale(cursor.f64());
        properties.setWidthScale(cursor.f64());
        properties.setExtraInfo(cursor.u8() == 1 ? readExtraInfo(cursor) : null);
        properties.setRenderLayersFirst(cursor.tristate());
        properties.setDefaultTexture(cursor.string());
        properties.setForceCulling(cursor.tristate());
        return properties;
    }

    private static ExtraInfo readExtraInfo(Cursor cursor) {
        ExtraInfo extraInfo = new ExtraInfo();
        extraInfo.setName(cursor.string());
        extraInfo.setTips(cursor.string());
        extraInfo.setExtraAnimationNames(cursor.optionalStrings());
        extraInfo.setAuthors(cursor.optionalStrings());
        extraInfo.setLicense(cursor.string());
        return extraInfo;
    }

    /** Little-endian cursor over the blob; every read validates that enough bytes remain. */
    private static final class Cursor {

        private final ByteBuffer buffer;
        private final int length;

        private Cursor(byte[] blob) {
            this.buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
            this.length = blob.length;
        }

        private void require(int count) {
            if (count < 0 || buffer.remaining() < count) {
                throw new IllegalStateException(
                    "native geometry blob: truncated (need " + count + " bytes, have " + buffer.remaining() + ")");
            }
        }

        private boolean atEnd() {
            return !buffer.hasRemaining();
        }

        private int remaining() {
            return buffer.remaining();
        }

        private byte[] bytes(int count) {
            require(count);
            byte[] bytes = new byte[count];
            buffer.get(bytes);
            return bytes;
        }

        private int u8() {
            require(1);
            return buffer.get() & 0xFF;
        }

        private int i8() {
            require(1);
            return buffer.get();
        }

        private int u32() {
            require(4);
            return buffer.getInt();
        }

        private int i32() {
            return u32();
        }

        private float f32() {
            require(4);
            return buffer.getFloat();
        }

        private double f64() {
            require(8);
            return buffer.getDouble();
        }

        /** A declared count or length: never negative and never larger than the remaining bytes. */
        private int length(String what) {
            int value = u32();
            if (value < 0 || value > length) {
                throw new IllegalStateException("native geometry blob: bad " + what + " " + value);
            }
            return value;
        }

        private String string() {
            int bytes = u32();
            if (bytes == NULL_STRING) {
                return null;
            }
            if (bytes < 0) {
                throw new IllegalStateException("native geometry blob: negative string length " + bytes);
            }
            return new String(bytes(bytes), StandardCharsets.UTF_8);
        }

        private Boolean tristate() {
            switch (u8()) {
                case 0:
                    return null;
                case 1:
                    return Boolean.FALSE;
                case 2:
                    return Boolean.TRUE;
                default:
                    throw new IllegalStateException("native geometry blob: bad tristate");
            }
        }

        private Double optionalDouble() {
            return u8() == 1 ? f64() : null;
        }

        private double[] optionalDoubleArray() {
            if (u8() == 0) {
                return null;
            }
            int count = length("double array length");
            double[] values = new double[count];
            for (int index = 0; index < count; index++) {
                values[index] = f64();
            }
            return values;
        }

        private String[] optionalStrings() {
            if (u8() == 0) {
                return null;
            }
            int count = length("string array length");
            String[] values = new String[count];
            for (int index = 0; index < count; index++) {
                values[index] = string();
            }
            return values;
        }

        private int[] indices(int bound) {
            int count = length("index list length");
            int[] values = new int[count];
            for (int index = 0; index < count; index++) {
                int value = i32();
                if (value < 0 || value >= bound) {
                    throw new IllegalStateException("native geometry blob: index " + value + " outside [0, " + bound + ")");
                }
                values[index] = value;
            }
            return values;
        }
    }
}
