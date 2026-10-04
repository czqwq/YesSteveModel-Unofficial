package software.bernie.geckolib3.geo;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

/**
 * The state a model is drawn with, chosen before the draw instead of inside it - this engine's stand-in for
 * Minecraft 1.20's {@code RenderType}, which 1.7.10 does not have.
 * <p>
 * Upstream picks one through {@code IGeoRenderer#getRenderType} and hands it to the draw as a parameter
 * ({@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:18-39}). Its two branches are
 * {@code CustomTranslucentRenderType}, which wraps {@code RenderType.entityTranslucent}
 * ({@code geckolib3/geo/CustomTranslucentRenderType.java:12-41}), and {@code RenderType.entityCutoutNoCull}. This
 * class carries only the part of those two that 1.7.10's fixed-function pipeline can express - the texture, blending,
 * back-face culling and the alpha test - and the two factories are named after upstream's branches and differ in
 * exactly the way those do: translucency blends and culls, cutout does neither and alpha-tests instead.
 * <p>
 * Nothing decides anything here. The decision lives in {@link IGeoRenderer#getRenderType}, with upstream's
 * parameters, defaults and branches; this type only carries its result. It exists because the platform has no
 * {@code RenderType}, not because the port needs a new mechanism.
 */
public final class YsmRenderType {

    /** Alpha reference the cutout branch tests against, the value vanilla uses for entity cutout. */
    private static final float CUTOUT_ALPHA_REF = 0.1F;

    private final ResourceLocation texture;
    private final boolean blend;
    private final boolean cull;
    private final boolean alphaTest;
    private final float alphaRef;

    private YsmRenderType(ResourceLocation texture, boolean blend, boolean cull, boolean alphaTest, float alphaRef) {
        this.texture = texture;
        this.blend = blend;
        this.cull = cull;
        this.alphaTest = alphaTest;
        this.alphaRef = alphaRef;
    }

    /**
     * Upstream's {@code RenderType.entityCutoutNoCull}: cutout, which alpha-tests and does not blend, and no culling.
     * It is the default branch of {@link IGeoRenderer#getRenderType} and the behaviour every model had before the
     * render type existed.
     */
    public static YsmRenderType cutoutNoCull(@Nullable ResourceLocation texture) {
        return new YsmRenderType(texture, false, false, true, CUTOUT_ALPHA_REF);
    }

    /**
     * Upstream's {@code CustomTranslucentRenderType}, i.e. {@code RenderType.entityTranslucent}: blending and
     * back-face culling, no alpha test. A model whose art is translucent is drawn with this, and a flat decal depends
     * on the culling - see {@link IGeoRenderer#getRenderType}.
     */
    public static YsmRenderType translucent(@Nullable ResourceLocation texture) {
        return new YsmRenderType(texture, true, true, false, 0.0F);
    }

    @Nullable
    public ResourceLocation getTexture() {
        return texture;
    }

    public boolean isBlend() {
        return blend;
    }

    public boolean isCull() {
        return cull;
    }

    public boolean isAlphaTest() {
        return alphaTest;
    }

    public float getAlphaRef() {
        return alphaRef;
    }
}
