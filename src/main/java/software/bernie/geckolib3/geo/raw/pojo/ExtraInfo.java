package software.bernie.geckolib3.geo.raw.pojo;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code extra_info} block of a Bedrock geometry.
 * <p>
 * YSMU: this class is reachable from {@link ModelProperties#getExtraInfo()} and therefore from a built
 * {@code GeoModel.properties}, which the client's baked-geometry cache serialises. It was the only class in that graph
 * without {@link Serializable}, so a model that declares {@code extra_info} (every YSM pack does) failed to serialise
 * with {@code NotSerializableException: ExtraInfo}. Adding the interface is the whole fix; the fields are already
 * plain {@link String} values. See {@code .agent/plan-bake-cache-and-structured-bridge.md} and {@code PROVENANCE.md}.
 */
public class ExtraInfo implements Serializable {

    private static final long serialVersionUID = 42L;

    private String name;
    private String tips = "";
    private String[] extraAnimationNames = null;
    private String[] authors = null;
    private String license = "All Rights Reserved";

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty("tips")
    public String getTips() {
        return tips;
    }

    @JsonProperty("tips")
    public void setTips(String tips) {
        this.tips = tips;
    }

    @JsonProperty("extra_animation_names")
    public String[] getExtraAnimationNames() {
        return extraAnimationNames;
    }

    @JsonProperty("extra_animation_names")
    public void setExtraAnimationNames(String[] extraAnimationNames) {
        this.extraAnimationNames = extraAnimationNames;
    }

    @JsonProperty("authors")
    public String[] getAuthors() {
        return authors;
    }

    @JsonProperty("authors")
    public void setAuthors(String[] authors) {
        this.authors = authors;
    }

    @JsonProperty("license")
    public String getLicense() {
        return license;
    }

    @JsonProperty("license")
    public void setLicense(String license) {
        this.license = license;
    }
}
