package software.bernie.geckolib3.geo.material;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.List;

import javax.vecmath.Vector3f;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;

class MaterialBakeTest {
    static GeoQuad face(float u0, float v0, float u1, float v1, float z, float normalZ) {
        return new GeoQuad(new GeoVertex[] {
            new GeoVertex(new Vector3f(0, 0, z), u0, v0),
            new GeoVertex(new Vector3f(1, 0, z), u1, v0),
            new GeoVertex(new Vector3f(1, 1, z), u1, v1),
            new GeoVertex(new Vector3f(0, 1, z), u0, v1)}, new Vector3f(0, 0, normalZ));
    }

    static BufferedImage pixels(int... alpha) {
        BufferedImage image = new BufferedImage(alpha.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < alpha.length; x++) image.setRGB(x, 0, alpha[x] << 24);
        return image;
    }

    @Test void sixOriginalFacesPartitionWithoutChangingAlphaOrSharedUvs() {
        BufferedImage image = pixels(255, 0, 128, 255);
        GeoQuad solid = face(0, 0, .25f, 1, 0, 1);
        GeoQuad hole = face(0, 0, .5f, 1, 0, 1);
        GeoQuad partial = face(.5f, 0, .75f, 1, 0, 1);
        GeoQuad empty = face(.25f, 0, .5f, 1, 0, 1);
        GeoQuad[] geometry = {solid, hole, partial, empty, solid, partial};
        List<BakedFace> ordinary = MaterialBake.bake(geometry, image, false);
        List<BakedFace> forced = MaterialBake.bake(geometry, image, true);
        assertEquals(5, forced.size());
        assertEquals(MaterialPartition.CUTOUT_NO_CULLING, ordinary.get(0).partition);
        assertEquals(MaterialPartition.CUTOUT, forced.get(0).partition);
        assertEquals(BakedFace.AlphaKind.CUTOUT, forced.get(1).alphaKind);
        assertEquals(MaterialPartition.TRANSLUCENT_CULLING, forced.get(1).partition);
        assertEquals(BakedFace.AlphaKind.TRANSLUCENT, forced.get(2).alphaKind);
        assertEquals(.25f, solid.vertices[1].textureU);
        assertEquals(MaterialPartition.CUTOUT_NO_CULLING,
            MaterialBake.bake(new GeoQuad[] {solid}, image, true).get(0).partition);
        List<BakedFace> secondSkin = MaterialBake.bake(geometry, pixels(255, 255, 255, 255), false);
        assertEquals(6, secondSkin.size());
        assertTrue(secondSkin.stream().allMatch(f -> f.partition == MaterialPartition.CUTOUT));
        assertEquals(BakedFace.AlphaKind.CUTOUT, forced.get(1).alphaKind);
    }

    @Test void boundaryZeroUvClampsToLastPixelAndMutatesOnlyPrivateUvCopy() {
        GeoQuad point = face(1, 1, 1, 1, 0, 1);
        BakedFace baked = MaterialBake.bake(new GeoQuad[] {point}, pixels(255, 128), false).get(0);
        assertEquals(BakedFace.AlphaKind.TRANSLUCENT, baked.alphaKind);
        assertEquals(.5f, baked.uv(0, 0));
        assertEquals(1f, baked.uv(1, 0));
        assertEquals(0f, baked.uv(2, 1));
        assertEquals(1f, point.vertices[0].textureU);
        assertTrue(MaterialBake.bake(new GeoQuad[] {point}, pixels(255, 0), false).isEmpty());
        assertTrue(MaterialBake.bake(new GeoQuad[] {point}, pixels(255, 128), 6, false, false).isEmpty());
        assertEquals(1, MaterialBake.bake(new GeoQuad[] {point}, pixels(255, 0), 29, false, true).size());
    }

    @Test void quantizationThresholdAndZeroUvVersionRulesAreNumeric() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 1, 0xff000000);
        GeoQuad point = face(.125f, .125f, .125f, .125f, 0, 1);
        BakedFace modern = MaterialBake.bake(new GeoQuad[] {point}, image, 29, false, true).get(0);
        assertEquals(0f, modern.uv(0, 0)); // u threshold .6: .5 rounds down
        assertEquals(.25f, modern.uv(0, 1)); // v threshold .4: .5 rounds up
        assertEquals(0f, modern.uv(2, 1)); // zero v grows toward previous texel
        assertEquals(BakedFace.AlphaKind.EMPTY, modern.alphaKind);
        BakedFace legacy = MaterialBake.bake(new GeoQuad[] {point}, image, 0, false, true).get(0);
        assertEquals(0f, legacy.uv(0, 1));
        GeoQuad outside = face(-2, 0, 2, 1, 0, 1);
        assertEquals(BakedFace.AlphaKind.CUTOUT,
            MaterialBake.bake(new GeoQuad[] {outside}, image, false).get(0).alphaKind);
    }

    @Test void inversionIsGeometricNotNegativeSizeAndWindingIsIndependent() {
        GeoQuad[] six = new GeoQuad[6];
        for (int i = 0; i < 5; i++) six[i] = face(0, 0, 1, 1, 0, 1);
        six[5] = face(0, 0, 1, 1, 1, -1); // center outward +z, declared normal -z
        List<BakedFace> baked = MaterialBake.bake(six, pixels(128), false);
        assertTrue(baked.stream().allMatch(f -> f.partition == MaterialPartition.TRANSLUCENT_CULLING));
        assertEquals(-1f, baked.get(5).windingSign);
        assertEquals(1f, baked.get(5).planeD);
        assertEquals(MaterialPartition.TRANSLUCENT,
            MaterialBake.bake(new GeoQuad[] {six[5]}, pixels(128), false).get(0).partition);
    }
}
