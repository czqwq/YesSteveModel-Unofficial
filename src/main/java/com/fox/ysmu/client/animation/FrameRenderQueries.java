package com.fox.ysmu.client.animation;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.geo.RenderContext;

/**
 * The three pack-facing values that describe <em>which render pass this frame is</em> rather than what the entity is
 * doing: {@code ysm.texture_name}, {@code ysm.rendering_in_inventory} and {@code ysm.rendering_in_paperdoll}. All three
 * are read off the frame's {@link AnimationEvent} (the animatable and the pass the renderer bracketed for it), so they
 * live together and are published per frame by {@code AnimationRegister#setParserValue}.
 *
 * <p>Upstream (the authority for every rule below):
 * <ul>
 * <li>{@code playerVar("texture_name", new TextureNameVariable())} ({@code .../client/animation/molang/YSMBinding.java:151});
 * the variable answers {@code ctx.animatableEntity().getTextureName()}, or {@code null} for an animatable that is not
 * its {@code CustomPlayerEntity} ({@code .../variable/TextureNameVariable.java:10-15}), and that name is the model's
 * texture key - the file's base name without its extension, which is how the pack manifest names it
 * ({@code .../format/parser/RawModelAssembler.java:189-199} with the {@code removeExtension(baseName(path))} at
 * {@code :526-528}).</li>
 * <li>{@code entityVar("rendering_in_paperdoll", ctx -> ctx.animationEvent().getRenderContext().paperDoll())}
 * ({@code YSMBinding.java:99}), set for exactly one pass - the HUD extra-player overlay
 * ({@code util/RenderUtil.java:444-470}, {@code renderExtraPlayerEntity}).</li>
 * <li>{@code entityVar("rendering_in_inventory", PersonView::isInInventory)} ({@code YSMBinding.java:100}), which is
 * "the animatable is a preview entity, or the render context says inventory"
 * ({@code util/PersonView.java:35-37}). Upstream sets that flag for its own preview screens
 * ({@code util/RenderUtil.java:108}, {@code renderTextureScreenEntity}) and for the vanilla inventory screen's player
 * doll ({@code mixin/client/InventoryScreenMixin.java:14-22}).</li>
 * </ul>
 *
 * <p><b>Where the two pass flags come from.</b> Both are read off the frame's
 * {@link software.bernie.geckolib3.geo.RenderContext}, which the render path attaches to the event it hands the
 * engine. Upstream carries the same value on the same event ({@code ctx.animationEvent().getRenderContext()},
 * {@code .../client/animation/molang/YSMBinding.java:99-100}) and fills it from ambient flags. This port's equivalent
 * is the preview bracket {@code CustomPlayerRenderer#beginPreviewRender(RenderContext)}, opened by all three preview
 * entry points, plus one override for the events the engine builds itself:
 * <ul>
 * <li>the GUI tile renderer ({@code RenderUtil#renderEntityInInventory}) and the screen-owned form preview
 * ({@code RenderUtil#renderFormPreview}) attach {@link RenderContext#inventoryPass()};</li>
 * <li>{@code ClientEventHandler#renderSelfGuiPlayer} attaches the inventory pass while a screen is open and the
 * paper-doll pass when none is - one entry point serving both of upstream's
 * ({@code util/RenderUtil.java:445} sets {@code renderingInPaperDoll} for the doll,
 * {@code mixin/client/InventoryScreenMixin.java:16} sets {@code renderingInInventory} for the vanilla inventory
 * doll);</li>
 * <li>everything else - the world pass, and the events the engine builds on paths no preview bracket covers - is
 * {@link RenderContext#NONE}, which answers 0 for both queries exactly as the world pass did before.</li>
 * </ul>
 *
 * <p><b>Two residuals, both still open</b>, and both a consequence of that single entry point rather than of the
 * queries:
 * <ul>
 * <li>the HUD doll drawn while a non-covering screen is open (this port's animation roulette screen, which is
 * deliberately meant to show the doll) is classified as the inventory pass where upstream answers paper doll. Telling
 * the two apart needs the screen to say it wants the doll; upstream gets that from its own overlay class, which the
 * port has no counterpart of;</li>
 * <li>any other screen open during the world pass routes the local player's model through
 * {@code ClientEventHandler#renderSelfGuiPlayer} too, which is the inventory pass here where upstream's world pass sets
 * neither flag. Resolving that one would mean changing what the world pass answers, not just where the answer is read
 * from, so it is recorded instead.</li>
 * </ul>
 * Both are now visible in one place - the context attached at that entry point - instead of being re-derived from
 * {@code Minecraft.currentScreen} at read time.
 *
 * <p><b>Strings.</b> {@code ysm.texture_name} is a string comparison in packs
 * ({@code ysm.texture_name=='skin_pink'}, {@code tmp/YesSteveModel-dev-1.20 .../wine_fox/14_momo/animations/
 * main.animation.json}); this runtime compares strings as {@link MolangStringPool} ids, exactly like
 * {@code ysm.relative_block_name} - the parser interns the literal on the other side
 * ({@code core/molang/MolangParser.java:963}), so both sides land on the same id.
 */
public final class FrameRenderQueries {

    private FrameRenderQueries() {}

    /**
     * The pooled id of the frame animatable's selected texture name, or 0 - the pooled empty string, upstream's answer
     * for an animatable that is not a {@code CustomPlayerEntity} - when there is none.
     */
    public static double textureName(AnimationEvent<?> animationEvent) {
        if (animationEvent == null || !(animationEvent.getAnimatable() instanceof CustomPlayerEntity customPlayer)) {
            return MolangStringPool.EMPTY_ID;
        }
        return MolangStringPool.intern(textureNameOf(customPlayer.getTexture()));
    }

    /** 1 when this frame's attached pass is the inventory pass, 0 otherwise; see the class comment. */
    public static double renderingInInventory(AnimationEvent<?> animationEvent) {
        return animationEvent != null && animationEvent.getRenderContext()
            .inventory() ? 1.0D : 0.0D;
    }

    /** 1 when this frame's attached pass is the HUD paper-doll pass, 0 otherwise; see the class comment. */
    public static double renderingInPaperdoll(AnimationEvent<?> animationEvent) {
        return animationEvent != null && animationEvent.getRenderContext()
            .paperDoll() ? 1.0D : 0.0D;
    }

    /**
     * Upstream's {@code removeExtension(baseName(path))} ({@code RawModelAssembler.java:526-528}): the last path
     * segment of the texture id with its extension removed. The port's texture ids are {@code <modelId>/<name>}
     * ({@code ClientModelManager#uploadTextures:1834} over the {@code ModelIdUtil#getSubModelId} rule), and that
     * {@code <name>} is the pack's own texture key with its file extension (folder models keep it -
     * {@code YSMFolderDeserializer:364} stores the file name with extension), so removing the extension is what
     * reproduces the key a pack writes in its animation files.
     */
    public static String textureNameOf(ResourceLocation textureId) {
        if (textureId == null) {
            return "";
        }
        String name = ModelIdUtil.getSubNameFromId(textureId);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
