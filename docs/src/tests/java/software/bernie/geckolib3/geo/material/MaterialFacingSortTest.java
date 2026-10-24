package software.bernie.geckolib3.geo.material;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;

class MaterialFacingSortTest {

    static BakedFace baked(float z, float normalZ) {
        return MaterialBake.bake(new GeoQuad[] {MaterialBakeTest.face(0, 0, 1, 1, z, normalZ)},
            MaterialBakeTest.pixels(128), false).get(0);
    }

    @Test void orthographicReflectionAndReversedNormalUsePlaneWinding() {
        Matrix4f clip = new Matrix4f();
        clip.setIdentity();
        // Native signed cofactors can produce -0; facing uses numerical <= 0, not zero's bit pattern.
        assertArrayEquals(new float[] {0, 0, 1, 0}, MaterialFacing.coefficient(clip), 1e-6f);
        assertFalse(MaterialFacing.backFace(baked(1, 1), MaterialFacing.coefficient(clip)));
        assertFalse(MaterialFacing.backFace(baked(1, -1), MaterialFacing.coefficient(clip)));
        clip.m00 = -1; // mirror reverses projected winding even with unchanged normal
        assertTrue(MaterialFacing.backFace(baked(1, 1), MaterialFacing.coefficient(clip)));
        clip.m00 = 2; clip.m11 = 3; clip.m22 = .5f;
        assertEquals(6f, MaterialFacing.coefficient(clip)[2]);
    }

    @Test void perspectiveFacingIncludesPlaneDistanceAndDepthDividesByW() {
        Matrix4f clip = new Matrix4f();
        clip.setIdentity();
        clip.m32 = -1; clip.m33 = 0;
        assertArrayEquals(new float[] {0, 0, 0, 1}, MaterialFacing.coefficient(clip), 1e-6f);
        assertFalse(MaterialFacing.backFace(baked(-2, 1), MaterialFacing.coefficient(clip)));
        assertTrue(MaterialFacing.backFace(baked(2, 1), MaterialFacing.coefficient(clip)));
        assertEquals(-1f, MaterialFacing.depth(baked(-2, 1), clip));
        clip.m23 = 1;
        assertEquals(-.5f, MaterialFacing.depth(baked(-2, 1), clip));
        GeoQuad degenerate = MaterialBakeTest.face(0, 0, 1, 1, -2, 1);
        degenerate.vertices[1] = degenerate.vertices[0];
        BakedFace zero = MaterialBake.bake(new GeoQuad[] {degenerate}, MaterialBakeTest.pixels(255), false).get(0);
        assertEquals(0f, zero.windingSign);
        assertTrue(MaterialFacing.backFace(zero, MaterialFacing.coefficient(clip)));
    }

    @Test void nonculledBackNormalIsFlippedAndNonuniformTransformNormalized() {
        Matrix3f normal = new Matrix3f();
        normal.setIdentity(); normal.m22 = .25f;
        Vector3f result = MaterialFacing.normal(baked(0, 1), normal, false, true);
        assertEquals(0f, result.x, 1e-6f); assertEquals(0f, result.y, 1e-6f);
        assertEquals(-1f, result.z, 1e-6f);
    }

    @Test void nativeUnsignedSortKeysAreGlobalBackToFrontWithOutputOffsetTieBreak() {
        List<Float> depths = new ArrayList<>(Arrays.asList(-.5f, .8f, 0f, -.8f, .5f, Float.NaN));
        depths.sort((a, b) -> MaterialSort.compare(a, 0, b, 0));
        assertEquals(Arrays.asList(.8f, .5f, 0f, -.5f, -.8f, Float.NaN), depths);
        assertEquals(MaterialSort.depthKey(0f), MaterialSort.depthKey(-0f));
        assertEquals(-1, MaterialSort.depthKey(Float.POSITIVE_INFINITY));
        assertTrue(MaterialSort.compare(.5f, 4, .5f, 8) < 0);
    }

    @Test void mirroredRecognizesThePortsOwnMirrorsAndNothingElse() {
        assertTrue(MaterialFacing.mirrored(dollChain(-30f).chain()), "the paper doll's glScalef(-scale, ...)");
        assertTrue(MaterialFacing.mirrored(bipedArmChain()), "the biped arm's glScalef(1, -1, 1)");
        assertFalse(MaterialFacing.mirrored(dollChain(1f).chain()), "the same chain without the mirror");
        assertFalse(MaterialFacing.mirrored(new Matrix4f()), "identity");

        // An even-handed change of basis is not a mirror: upstream's own arm mirror is scale(-1, -1, 1).
        Matrix4f evenScale = diagonal(-1, -1, 1);
        assertFalse(MaterialFacing.mirrored(evenScale));
        Matrix4f rolled = diagonal(-1, 1, -1);
        assertFalse(MaterialFacing.mirrored(rolled), "a 180 degree roll is even-handed");
        Matrix4f translated = new Matrix4f();
        translated.setIdentity();
        translated.m03 = 5f; translated.m13 = -2f;
        assertFalse(MaterialFacing.mirrored(translated), "a translation cannot mirror");

        // A bone a pack scaled negatively, which the native pose stack flips for as well
        // (pose_stack_impl.inc:56-59): a reflection composed with a rotation.
        Matrix4f boneRotation = new Matrix4f();
        boneRotation.rotY((float) Math.toRadians(45));
        Matrix4f mirroredBone = multiply(diagonal(1f, -1f, 1f), boneRotation);
        assertTrue(MaterialFacing.mirrored(mirroredBone));
        assertFalse(
            MaterialFacing.mirrored(multiply(diagonal(1f, 1f, 1f), boneRotation)),
            "the same rotation without the negative scale is not a mirror");
    }

    /**
     * A draw that mirrors the model must not drop faces, and an unmirrored one is unchanged.
     * <p>
     * This is the regression behind "the arm is full of holes": the CPU culling decides for every classified texture
     * ({@code MaterialDrawSession#collect}), and the two paths that mirror the model - the biped arm
     * ({@code FirstPersonHandRenderer#alignGeckoArmToVanillaBipedArm}, {@code glScalef(1, -1, 1)}) and the inventory
     * preview / paper doll ({@code com.fox.ysmu.util.RenderUtil#renderPlayerEntity},
     * {@code glScalef(-scale, scale, scale)} - vanilla's own {@code GuiInventory.func_147046_a:94} does the same) -
     * asked the native rule, whose value follows the determinant of the chain the cofactors come from. A mirror turns
     * "the side the viewer sees" into "the side the viewer is behind", so the near faces of every arm cube whose six
     * faces are opaque (those are the ones with a culling partition, {@code MaterialBake:44-53}) were dropped and the
     * far ones drawn: what the player saw through was the model's inside.
     */
    @Test void aMirroredDrawCullsNothingAndAnUnmirroredOneKeepsTheNativeRule() {
        // Six opaque faces make a culling partition (CUTOUT) under the forced-culling bake; a single face never does.
        GeoQuad solid = MaterialBakeTest.face(0, 0, 1, 1, 1, 1);
        List<BakedFace> cube = MaterialBake.bake(new GeoQuad[] {solid, solid, solid, solid, solid, solid},
            MaterialBakeTest.pixels(255), true);
        assertEquals(6, cube.size());
        for (BakedFace face : cube) {
            assertEquals(MaterialPartition.CUTOUT, face.partition, "the fixture must produce a culling partition");
            float[] worldCoefficient = MaterialFacing.coefficient(dollChain(1f).clip());
            assertEquals(
                MaterialFacing.backFace(face, worldCoefficient),
                MaterialFacing.culled(face, worldCoefficient, false),
                "an unmirrored draw culls exactly what the native rule says");
            assertFalse(
                MaterialFacing.culled(face, MaterialFacing.coefficient(dollChain(-30f).clip()), true),
                "a mirrored draw must not cull: the port cannot evaluate the native rule there");
        }

        // A partition that does not cull is kept either way - including under a mirror, where the old code could
        // still drop it because the facing test was inverted.
        List<BakedFace> single = MaterialBake.bake(new GeoQuad[] {solid}, MaterialBakeTest.pixels(255), false);
        assertFalse(single.get(0).partition.culling);
        assertFalse(MaterialFacing.culled(single.get(0), MaterialFacing.coefficient(dollChain(1f).clip()), false));
        assertFalse(MaterialFacing.culled(single.get(0), MaterialFacing.coefficient(dollChain(-30f).clip()), true));
    }

    /**
     * The same property against the asset the report was about: the built-in default model's arm, parsed and baked
     * with its own texture, cube by cube.
     * <p>
     * The synthetic faces above pin the rule; this pins that it holds for real pack data - box UVs, a mirrored cube,
     * the geometry's own winding, and the six-face partitions that are the only ones ever dropped.
     */
    @Test void aMirroredDrawKeepsEveryFaceOfTheBuiltInDefaultArmsCullingPartitions() throws Exception {
        File geometry = new File("src/main/resources/assets/ysmu/builtin/default/models/arm.json");
        File texture = new File("src/main/resources/assets/ysmu/builtin/default/textures/default.png");
        assumeTrue(geometry.isFile() && texture.isFile(), "the built-in default model ships with this repository");

        RawGeoModel raw = Converter.fromJsonString(
            new String(Files.readAllBytes(geometry.toPath()), StandardCharsets.UTF_8));
        GeoModel arm = GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(RawGeometryTree.parseHierarchy(raw));
        BufferedImage skin = ImageIO.read(texture);
        float[] worldCoefficient = MaterialFacing.coefficient(dollChain(1f).clip());
        float[] dollCoefficient = MaterialFacing.coefficient(dollChain(-30f).clip());

        int checked = 0;
        int cullingFaces = 0;
        for (GeoBone bone : arm.topLevelBones) {
            for (GeoCube cube : cubesOf(bone)) {
                for (BakedFace face : MaterialBake.bake(cube.quads, skin, false)) {
                    if (face.partition.culling) cullingFaces++;
                    assertFalse(
                        MaterialFacing.culled(face, dollCoefficient, true),
                        "the paper doll's mirror must not cull any face of " + bone.getName());
                    if (!MaterialFacing.culled(face, worldCoefficient, false)) {
                        // every face the unmirrored draw keeps, the mirrored one keeps too
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked >= 6, "the arm must contribute faces to check, saw " + checked);
        assertTrue(cullingFaces > 0, "the fixture is only meaningful if this arm really has culling partitions");
    }

    private static List<GeoCube> cubesOf(GeoBone bone) {
        List<GeoCube> cubes = new ArrayList<>(bone.childCubes);
        for (GeoBone child : bone.childBones) {
            cubes.addAll(cubesOf(child));
        }
        return cubes;
    }

    /**
     * A draw's two matrices: the model-to-eye chain, which is what
     * {@link MaterialFacing#mirrored(Matrix4f)} reads, and the model-to-clip product, which is what the face tests
     * read.
     */
    private record Draw(Matrix4f chain, Matrix4f clip) {}

    /**
     * The paper doll's chain as {@code com.fox.ysmu.util.RenderUtil#renderPlayerEntity} builds it - translate, mirror,
     * rotate - under a perspective projection. {@code mirrorScale} 1 is the same chain without the mirror.
     */
    private static Draw dollChain(float mirrorScale) {
        Matrix4f chain = new Matrix4f();
        chain.setIdentity();
        chain.m03 = 12f; chain.m13 = 30f; chain.m23 = -50f;
        if (mirrorScale != 1f) {
            Matrix4f mirror = diagonal(mirrorScale, 1f, 1f);
            chain = multiply(chain, mirror);
        }
        Matrix4f rotation = new Matrix4f();
        rotation.rotY((float) Math.toRadians(135));
        chain = multiply(chain, rotation);
        return new Draw(chain, clipOf(chain));
    }

    /** The biped arm's chain: the mirror {@code glScalef(1, -1, 1)} then the 180 degree roll. */
    private static Matrix4f bipedArmChain() {
        Matrix4f roll = new Matrix4f();
        roll.rotY((float) Math.PI);
        return multiply(diagonal(1f, -1f, 1f), roll);
    }

    private static Matrix4f clipOf(Matrix4f chain) {
        Matrix4f projection = diagonal(1f, 1f, -1f / 64f);
        projection.m23 = -1f;
        projection.m32 = -1f;
        projection.m33 = 0f;
        return multiply(projection, chain);
    }

    /** A scale matrix; vecmath's no-argument constructor is not the identity. */
    private static Matrix4f diagonal(float x, float y, float z) {
        Matrix4f matrix = new Matrix4f();
        matrix.setIdentity();
        matrix.m00 = x; matrix.m11 = y; matrix.m22 = z;
        return matrix;
    }

    private static Matrix4f multiply(Matrix4f left, Matrix4f right) {
        Matrix4f product = new Matrix4f();
        product.mul(left, right);
        return product;
    }
}
