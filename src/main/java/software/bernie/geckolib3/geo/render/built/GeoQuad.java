package software.bernie.geckolib3.geo.render.built;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import javax.vecmath.Vector3f;

import net.geckominecraft.util.math.Vec3i;
import net.minecraft.util.EnumFacing;

import software.bernie.geckolib3.geo.raw.pojo.FaceUv;

public class GeoQuad implements Serializable {

    private static final long serialVersionUID = 42L;
    public GeoVertex[] vertices;
    public final Vec3i normal;
    public final Vector3f normalVector;
    /**
     * YSMU: {@code net.minecraft.util.EnumFacing} is a plain 1.7.10 enum and is not {@link Serializable}, so a built
     * model could not be written to the client's baked-geometry cache while this field was part of the default
     * serialisation. It is written as an ordinal in {@link #writeObject(ObjectOutputStream)} instead.
     * {@code normal}/{@code normalVector} stay serialised: {@code Vec3i} is this engine's own {@link Serializable}
     * class and {@code javax.vecmath.Vector3f} is serialisable too. See
     * {@code .agent/plan-bake-cache-and-structured-bridge.md} and {@code PROVENANCE.md}.
     */
    public transient EnumFacing direction;
    public int uvRotation;

    public GeoQuad(GeoVertex[] verticesIn, float u1, float v1, float uSize, float vSize, int uvRotation, float texWidth,
        float texHeight, Boolean mirrorIn, EnumFacing directionIn) {
        this.direction = directionIn;
        this.vertices = verticesIn;
        this.uvRotation = uvRotation;

        /*
         * u1 is the distance from the very left of the texture to where the uv region
         * starts v1 is the distance from the very top of the texture to where the uv
         * region starts u2 is the horizontal distance from u1 to where the uv region
         * ends v2 is the vertical distance from the v1 to where the uv region ends
         */

        float u2 = u1 + uSize;
        float v2 = v1 + vSize;

        float uWidth = u2 / texWidth;
        float vHeight = v2 / texHeight;
        u1 /= texWidth;
        v1 /= texHeight;

        int nx = directionIn.getFrontOffsetX();
        int ny = directionIn.getFrontOffsetY();
        int nz = directionIn.getFrontOffsetZ();

        boolean mirror = mirrorIn != null && mirrorIn;

        if (!mirror) {
            float temp = uWidth;
            uWidth = u1;
            u1 = temp;
        } else {
            nx *= -1;
        }

        float[] uvs = FaceUv.Rotation.fromValue(uvRotation)
            .rotateUvs(u1, v1, uWidth, vHeight);
        vertices[0] = verticesIn[0].setTextureUV(uvs[0], uvs[1]);
        vertices[1] = verticesIn[1].setTextureUV(uvs[2], uvs[3]);
        vertices[2] = verticesIn[2].setTextureUV(uvs[4], uvs[5]);
        vertices[3] = verticesIn[3].setTextureUV(uvs[6], uvs[7]);
        this.normal = new Vec3i(nx, ny, nz);
        this.normalVector = new Vector3f(nx, ny, nz);
    }

    public GeoQuad(GeoVertex[] verticesIn, double[] uvCoords, double[] uvSize, int uvRotation, float texWidth,
        float texHeight, Boolean mirrorIn, EnumFacing directionIn) {
        this(
            verticesIn,
            (float) uvCoords[0],
            (float) uvCoords[1],
            (float) uvSize[0],
            (float) uvSize[1],
            uvRotation,
            texWidth,
            texHeight,
            mirrorIn,
            directionIn);
    }

    public GeoQuad(GeoVertex[] verticesIn, float u1, float v1, float uSize, float vSize, float texWidth,
        float texHeight, Boolean mirrorIn, EnumFacing directionIn) {
        this(verticesIn, u1, v1, uSize, vSize, 0, texWidth, texHeight, mirrorIn, directionIn);
    }

    public GeoQuad(GeoVertex[] verticesIn, double[] uvCoords, double[] uvSize, float texWidth, float texHeight,
        Boolean mirrorIn, EnumFacing directionIn) {
        this(
            verticesIn,
            (float) uvCoords[0],
            (float) uvCoords[1],
            (float) uvSize[0],
            (float) uvSize[1],
            0,
            texWidth,
            texHeight,
            mirrorIn,
            directionIn);
    }

    public GeoQuad(GeoVertex[] verticesIn, Vector3f normalIn) {
        this.direction = null;
        this.vertices = verticesIn;
        this.uvRotation = 0;
        this.normal = new Vec3i(Math.round(normalIn.x), Math.round(normalIn.y), Math.round(normalIn.z));
        this.normalVector = new Vector3f(normalIn);
    }

    /**
     * YSMU: {@link #direction} is {@code transient} (see its field comment), so it travels as an ordinal. 1.7.10's
     * {@code EnumFacing} is a fixed six-value enum and a cache file is only reused for the same mod version, so the
     * ordinal is stable for as long as a cached file can be read at all.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt(this.direction == null ? -1 : this.direction.ordinal());
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int ordinal = in.readInt();
        EnumFacing[] values = EnumFacing.values();
        this.direction = ordinal < 0 || ordinal >= values.length ? null : values[ordinal];
    }
}
