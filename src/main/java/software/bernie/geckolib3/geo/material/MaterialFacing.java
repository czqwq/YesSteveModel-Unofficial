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
