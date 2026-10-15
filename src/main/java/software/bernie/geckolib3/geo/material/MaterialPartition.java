package software.bernie.geckolib3.geo.material;

/** Native bake partitions, not a global render-type override (baked_model.cc:685-734). */
public enum MaterialPartition {
    CUTOUT(true, false), CUTOUT_NO_CULLING(false, false),
    TRANSLUCENT(false, true), TRANSLUCENT_CULLING(true, true);

    public final boolean culling;
    public final boolean translucent;

    MaterialPartition(boolean culling, boolean translucent) {
        this.culling = culling;
        this.translucent = translucent;
    }
}
