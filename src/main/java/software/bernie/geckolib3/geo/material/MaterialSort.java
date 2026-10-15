package software.bernie.geckolib3.geo.material;

/** Native buffer/quad_ref.h:12-22: unsigned ascending key gives back-to-front NDC depth. */
public final class MaterialSort {
    private MaterialSort() {}

    public static int depthKey(float depth) {
        if (!Float.isFinite(depth)) return -1;
        if (depth == 0) depth = 0f; // canonicalise negative zero like native
        int bits = Float.floatToRawIntBits(depth);
        return ~(bits ^ (bits < 0 ? -1 : Integer.MIN_VALUE));
    }

    /** Native packed QuadRef compares depth key first, then unsigned vertex offset. */
    public static int compare(float firstDepth, int firstIndex, float secondDepth, int secondIndex) {
        int byDepth = Integer.compareUnsigned(depthKey(firstDepth), depthKey(secondDepth));
        return byDepth != 0 ? byDepth : Integer.compareUnsigned(firstIndex, secondIndex);
    }
}
