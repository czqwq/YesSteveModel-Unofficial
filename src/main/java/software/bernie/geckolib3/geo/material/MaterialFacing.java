package software.bernie.geckolib3.geo.material;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

/** Native render_state_impl.inc:35-50 and cube/transform_common.h:36-59,84-90. */
public final class MaterialFacing {
    private MaterialFacing() {}

    /** Cofactors of clip-space x/y/w rows; accepts perspective and orthographic transforms. */
    public static float[] coefficient(Matrix4f clip) {
        float[] x = {clip.m00, clip.m01, clip.m02, clip.m03};
        float[] y = {clip.m10, clip.m11, clip.m12, clip.m13};
        float[] w = {clip.m30, clip.m31, clip.m32, clip.m33};
        return new float[] {
            determinant(x[1], x[2], x[3], y[1], y[2], y[3], w[1], w[2], w[3]),
            -determinant(x[0], x[2], x[3], y[0], y[2], y[3], w[0], w[2], w[3]),
            determinant(x[0], x[1], x[3], y[0], y[1], y[3], w[0], w[1], w[3]),
            -determinant(x[0], x[1], x[2], y[0], y[1], y[2], w[0], w[1], w[2])};
    }

    private static float determinant(float a, float b, float c, float d, float e, float f,
        float g, float h, float i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    /**
     * Whether this model-to-eye matrix reverses handedness, i.e. whether the draw it belongs to mirrors the model.
     * <p>
     * The native renderer tracks this as its pose-stack orientation sign - {@code pose_stack_impl.inc:53-60} flips
     * {@code tangent_orientation} for a scale whose components have an odd number of negative signs, and
     * {@code render_state_impl.inc:55-62} multiplies the whole facing coefficient by it (together with the baked
     * handedness of the data). The port takes the same sign from the handedness of the chain instead of accumulating
     * it per bone, which is the same value for a bone and additionally covers the two mirrors its own <em>outer</em>
     * transforms add: the biped arm ({@code FirstPersonHandRenderer#alignGeckoArmToVanillaBipedArm},
     * {@code glScalef(1, -1, 1)}) and the inventory preview / paper doll
     * ({@code com.fox.ysmu.util.RenderUtil#renderPlayerEntity}, {@code glScalef(-scale, scale, scale)} - vanilla's own
     * {@code GuiInventory.func_147046_a:94} does the same). Each of those sites corrects the <em>GL</em> half with
     * {@code glFrontFace(GL_CW)}; the CPU half is handled by {@link #culled}, which is the decision for a classified
     * texture.
     * <p>
     * Pass the <b>model-to-eye</b> matrix, never a model-to-clip one: a perspective projection's own linear part is
     * already negative, so its sign says nothing about the model.
     */
    public static boolean mirrored(Matrix4f modelToEye) {
        float a = modelToEye.m00, b = modelToEye.m01, c = modelToEye.m02;
        float d = modelToEye.m10, e = modelToEye.m11, f = modelToEye.m12;
        float g = modelToEye.m20, h = modelToEye.m21, i = modelToEye.m22;
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g) < 0f;
    }

    /**
     * Whether this face is culled for the draw {@code coefficient} came from.
     * <p>
     * Only a face whose partition culls is ever dropped, and only when it is back-facing.
     *
     * @param mirrored whether the model-to-eye chain of that draw mirrors the model
     *                 ({@link #mirrored(Matrix4f)}). Such a draw is <b>not</b> culled here, because the native rule
     *                 below cannot answer for it: its value follows the determinant of the chain the cofactors come
     *                 from, so a mirror turns "the side the viewer sees" into "the side the viewer is behind" and the
     *                 near faces of a model are dropped - which is exactly how the biped arm and the paper doll ended
     *                 up full of holes. The port's mirrors sit <em>inside</em> the model-to-eye matrix (a
     *                 {@code glScalef} in the middle of a chain that continues with the model's own rotations), so
     *                 the segment cannot undo them from its own matrices; leaving the faces in is the same picture for
     *                 a closed model - GL's depth test keeps the far ones behind the near ones, and the whole segment
     *                 draws with GL culling off anyway ({@code MaterialDrawSession#drain}) - and it never removes a
     *                 face the player should see.
     */
    public static boolean culled(BakedFace face, float[] coefficient, boolean mirrored) {
        return face.partition.culling && !mirrored && backFace(face, coefficient);
    }

    /**
     * Whether the face is back-facing for a chain whose model-to-clip matrix produced {@code coefficient}, i.e. the
     * native rule ({@code render_state_impl.inc:35-50}): the viewer's side of the face's plane, taken through the
     * cofactors of the clip matrix and corrected by the data's own winding sign.
     * <p>
     * Only valid for a chain that does not mirror the model; use {@link #culled} for the decision itself.
     */
    public static boolean backFace(BakedFace face, float[] coefficient) {
        float facing = (face.normal(0) * coefficient[0] + face.normal(1) * coefficient[1]
            + face.normal(2) * coefficient[2] + face.planeD * coefficient[3]) * face.windingSign;
        return facing <= 0;
    }

    public static Vector3f normal(BakedFace face, Matrix3f inverseTranspose, boolean uniformScale,
        boolean backFace) {
        Vector3f normal = new Vector3f(face.normal(0), face.normal(1), face.normal(2));
        inverseTranspose.transform(normal);
        if (!uniformScale) {
            float length = normal.lengthSquared();
            if (length > 0 && Float.isFinite(length)) normal.scale(1f / (float) Math.sqrt(length));
            else normal.set(0, 0, 0);
        }
        if (backFace) normal.negate();
        return normal;
    }

    public static float depth(BakedFace face, Matrix4f clip) {
        float x = face.center(0), y = face.center(1), z = face.center(2);
        return (clip.m20 * x + clip.m21 * y + clip.m22 * z + clip.m23)
            / (clip.m30 * x + clip.m31 * y + clip.m32 * z + clip.m33);
    }
}
