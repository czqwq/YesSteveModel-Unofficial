package software.bernie.geckolib3.geo.raw.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Specifies the UV's for the face that stretches along the x and z axes, and
 * faces the -y axis
 * <p>
 * Specifies the UV's for the face that stretches along the z and y axes, and
 * faces the x axis
 * <p>
 * Specifies the UV's for the face that stretches along the x and y axes, and
 * faces the -z axis.
 * <p>
 * Specifies the UV's for the face that stretches along the x and y axes, and
 * faces the z axis
 * <p>
 * Specifies the UV's for the face that stretches along the x and z axes, and
 * faces the y axis
 * <p>
 * Specifies the UV's for the face that stretches along the z and y axes, and
 * faces the -x axis
 */
public class FaceUv {

    private String materialInstance;
    private double[] uv;
    private double[] uvSize;
    private Integer uvRotation;

    @JsonProperty("material_instance")
    public String getMaterialInstance() {
        return materialInstance;
    }

    @JsonProperty("material_instance")
    public void setMaterialInstance(String value) {
        this.materialInstance = value;
    }

    /**
     * Specifies the uv origin for the face. For this face, it is the upper-left
     * corner, when looking at the face with y being up.
     */
    @JsonProperty("uv")
    public double[] getUv() {
        return uv;
    }

    @JsonProperty("uv")
    public void setUv(double[] value) {
        this.uv = value;
    }

    /**
     * The face maps this many texels from the uv origin. If not specified, the box
     * dimensions are used instead.
     */
    @JsonProperty("uv_size")
    public double[] getUvSize() {
        return uvSize;
    }

    @JsonProperty("uv_size")
    public void setUvSize(double[] value) {
        this.uvSize = value;
    }

    @JsonProperty("uv_rotation")
    public Integer getUvRotation() {
        return uvRotation;
    }

    @JsonProperty("uv_rotation")
    public void setUvRotation(Integer value) {
        this.uvRotation = value;
    }

    /**
     * The rotation this engine applies to the face: always {@link Rotation#NONE}.
     * <p>
     * {@code uv_rotation} is still parsed and readable through {@link #getUvRotation()}, but it is deliberately not
     * applied. The upstream 1.20 client leaves the field commented out as unimplemented
     * ({@code tmp/YesSteveModel-dev-1.20/.../format/parser/pojo/model/FaceUv.java:10-15}, "暂未实现"), and its native
     * renderer ({@code tmp/YesSteveModel-Native-dev}) has no such concept: the bake consumes only the UV coordinates
     * a pack already declares, so the same pack draws unrotated on the reference client. Applying it here made the
     * legacy cube channel (this class' only consumer is {@link software.bernie.geckolib3.geo.render.built.GeoCube})
     * disagree with the baked/OpenYSM channel - which drops the field
     * ({@code com/fox/ysmu/model/resource/RawYsmModelAdapter:734-737}) - and with the reference client for exactly
     * the packs that declare it. All UV rotation is therefore treated as 0 by the geometry builder; the value and
     * the enum stay so a caller that wants to inspect the declaration can.
     */
    public Rotation getRotationEnum() {
        return Rotation.NONE;
    }

    /**
     * Pure value mapper, kept with the parsed data. The renderer does not consume its result - see
     * {@link #getRotationEnum()}.
     */
    public enum Rotation {

        NONE,
        CLOCKWISE_90,
        CLOCKWISE_180,
        CLOCKWISE_270;

        public static Rotation fromValue(int value) {
            int index = ((value % 360) + 360) % 360 / 90;
            switch (index) {
                case 1:
                    return CLOCKWISE_90;
                case 2:
                    return CLOCKWISE_180;
                case 3:
                    return CLOCKWISE_270;
                default:
                    return NONE;
            }
        }

        /**
         * Kept for completeness of the parsed data; {@link #getRotationEnum()} never returns anything but
         * {@link #NONE}, so the geometry builder always takes its default (unrotated) branch.
         */
        public float[] rotateUvs(float u, float v, float u2, float v2) {
            switch (this) {
                case CLOCKWISE_90:
                    return new float[] { u2, v, u2, v2, u, v2, u, v };
                case CLOCKWISE_180:
                    return new float[] { u2, v2, u, v2, u, v, u2, v };
                case CLOCKWISE_270:
                    return new float[] { u, v2, u, v, u2, v, u2, v2 };
                default:
                    return new float[] { u, v, u2, v, u2, v2, u, v2 };
            }
        }
    }
}
