package software.bernie.geckolib3.geo.material;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

import org.junit.jupiter.api.Test;

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
}
