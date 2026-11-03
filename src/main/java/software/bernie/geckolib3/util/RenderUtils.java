package software.bernie.geckolib3.util;

import net.geckominecraft.client.renderer.GlStateManager;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.util.vector.Quaternion;

import software.bernie.geckolib3.compat.Axis;
import software.bernie.geckolib3.compat.Utils;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;

/**
 * YSMU: Replaced GeckoLib's original LWJGL-only RenderUtils with JOML-based
 * matrix operations (self-contained, no dependency on mod code). Added
 * convenience methods: prepMatrixForBone(), invertAndMultiplyMatrices().
 */
public final class RenderUtils {

    /**
     * Scratch quaternions for the per-bone axis rotations below. The methods run on the render thread only (the
     * held-item and head layers call them per bone per frame), and {@link GlStateManager#rotate(Quaternion)} consumes
     * the value immediately into the GL matrix, so a single reusable pair is safe - and this used to allocate one
     * {@code Quaternionf} plus one LWJGL {@code Quaternion} per non-zero axis, i.e. up to six objects per bone per
     * rendered player per frame.
     */
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final Quaternion ROTATION_LWJGL = new Quaternion();

    public static void translateMatrixToBone(GeoBone bone) {
        GlStateManager.translate(-bone.getPositionX() / 16f, bone.getPositionY() / 16f, bone.getPositionZ() / 16f);
    }

    public static void rotateMatrixAroundBone(GeoBone bone) {
        if (bone.getRotationZ() != 0.0F) {
            rotateAroundZ(bone.getRotationZ());
        }
        if (bone.getRotationY() != 0.0F) {
            rotateAroundY(bone.getRotationY());
        }
        if (bone.getRotationX() != 0.0F) {
            rotateAroundX(bone.getRotationX());
        }
    }

    private static void rotateAroundZ(float radians) {
        ROTATION.rotationZ(radians);
        GlStateManager.rotate(toLwjgl(ROTATION));
    }

    private static void rotateAroundY(float radians) {
        ROTATION.rotationY(radians);
        GlStateManager.rotate(toLwjgl(ROTATION));
    }

    private static void rotateAroundX(float radians) {
        ROTATION.rotationX(radians);
        GlStateManager.rotate(toLwjgl(ROTATION));
    }

    /** Copies {@code source} into the reusable LWJGL quaternion, so the value is not retained anywhere. */
    private static Quaternion toLwjgl(Quaternionf source) {
        ROTATION_LWJGL.set(source.x, source.y, source.z, source.w);
        return ROTATION_LWJGL;
    }

    public static void rotateMatrixAroundCube(GeoCube cube) {
        Vector3f rotation = new Vector3f(cube.rotation.x, cube.rotation.y, cube.rotation.z);
        GlStateManager.rotate(j2l(new Quaternionf().rotationXYZ(0, 0, rotation.z())));
        GlStateManager.rotate(j2l(new Quaternionf().rotationXYZ(0, (float) rotation.y(), 0)));
        GlStateManager.rotate(j2l(new Quaternionf().rotationXYZ(rotation.x(), 0, 0)));
    }

    public static void scaleMatrixForBone(GeoBone bone) {
        GlStateManager.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    public static void translateToPivotPoint(GeoCube cube) {
        Vector3f pivot = new Vector3f(cube.pivot.x, cube.pivot.y, cube.pivot.z);
        GlStateManager.translate(pivot.x() / 16f, pivot.y() / 16f, pivot.z() / 16f);
    }

    public static void translateToPivotPoint(GeoBone bone) {
        GlStateManager.translate(bone.rotationPointX / 16f, bone.rotationPointY / 16f, bone.rotationPointZ / 16f);
    }

    public static void translateAwayFromPivotPoint(GeoCube cube) {
        Vector3f pivot = new Vector3f(cube.pivot.x, cube.pivot.y, cube.pivot.z);
        GlStateManager.translate(-pivot.x() / 16f, -pivot.y() / 16f, -pivot.z() / 16f);
    }

    public static void translateAwayFromPivotPoint(GeoBone bone) {
        GlStateManager.translate(-bone.rotationPointX / 16f, -bone.rotationPointY / 16f, -bone.rotationPointZ / 16f);
    }

    public static void translateAndRotateMatrixForBone(GeoBone bone) {
        translateToPivotPoint(bone);
        rotateMatrixAroundBone(bone);
    }

    public static void prepMatrixForBone(GeoBone bone) {
        translateMatrixToBone(bone);
        translateToPivotPoint(bone);
        rotateMatrixAroundBone(bone);
        scaleMatrixForBone(bone);
        translateAwayFromPivotPoint(bone);
    }

    public static Matrix4f invertAndMultiplyMatrices(Matrix4f baseMatrix, Matrix4f inputMatrix) {
        inputMatrix = new Matrix4f(inputMatrix);
        inputMatrix.invert();
        inputMatrix.mul(baseMatrix);
        return inputMatrix;
    }

    private static Quaternion j2l(Quaternionf jomlQuat) {
        return Utils.j2l(jomlQuat);
    }
}
