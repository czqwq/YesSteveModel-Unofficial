package software.bernie.geckolib3.geo.material;

import software.bernie.geckolib3.geo.render.built.GeoQuad;

/** Texture-specific immutable snapshot; never changes the shared quad's positions or UVs. */
public final class BakedFace {
    public enum AlphaKind { EMPTY, SOLID, CUTOUT, TRANSLUCENT }

    private final float[] positions;
    private final float[] uv;
    private final float[] normal;
    public final AlphaKind alphaKind;
    public final MaterialPartition partition;
    public final float planeD;
    public final float windingSign;

    BakedFace(GeoQuad source, float[] uv, AlphaKind kind, MaterialPartition partition) {
        this.positions = new float[12];
        for (int i = 0; i < 4; i++) {
            positions[i * 3] = source.vertices[i].position.x;
            positions[i * 3 + 1] = source.vertices[i].position.y;
            positions[i * 3 + 2] = source.vertices[i].position.z;
        }
        normal = source.normalVector == null
            ? new float[] {source.normal.getX(), source.normal.getY(), source.normal.getZ()}
            : new float[] {source.normalVector.x, source.normalVector.y, source.normalVector.z};
        this.uv = uv.clone();
        this.alphaKind = kind;
        this.partition = partition;
        // Native baked_model.cc:275-299: plane normal is declared, winding is geometric.
        planeD = -(normal[0] * positions[0] + normal[1] * positions[1] + normal[2] * positions[2]);
        float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
        float bx = positions[6] - positions[0], by = positions[7] - positions[1], bz = positions[8] - positions[2];
        float orientation = normal[0] * (ay * bz - az * by)
            + normal[1] * (az * bx - ax * bz) + normal[2] * (ax * by - ay * bx);
        windingSign = orientation < 0 ? -1 : orientation > 0 ? 1 : 0;
    }

    public float position(int vertex, int axis) { return positions[vertex * 3 + axis]; }
    public float uv(int vertex, int axis) { return uv[vertex * 2 + axis]; }
    public float normal(int axis) { return normal[axis]; }
    public float center(int axis) {
        return (position(0, axis) + position(1, axis) + position(2, axis) + position(3, axis)) * 0.25f;
    }
}
