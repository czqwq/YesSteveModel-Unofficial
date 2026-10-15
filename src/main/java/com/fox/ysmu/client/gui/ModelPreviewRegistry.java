package com.fox.ysmu.client.gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.util.ModelIdUtil;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * What the model-selection GUI should animate for a model, mirroring upstream's per-card player resources.
 * <p>
 * The preview animation comes from the model's own {@code preview_animation} setting (carried by the OpenYSM payload
 * and remembered here), falling back to {@code idle} like upstream's {@code AnimationRegister.IDLE} default. The
 * hover/focus variants are not configuration: a model enables them by defining animations with those reserved names,
 * and the fade-out window is the length of its {@code hover_fadeout} animation - exactly how
 * {@code CatalogModelCardState} derives them from {@code animations.containsKey(...)}.
 */
@SideOnly(Side.CLIENT)
public final class ModelPreviewRegistry {

    /** Model id -> the model's declared {@code preview_animation}. Empty means "not declared". */
    private static final Map<ResourceLocation, String> PREVIEW_ANIMATIONS = new ConcurrentHashMap<>();

    /** Model ids whose {@code disable_preview_rotation} is set; upstream frames those differently. */
    private static final java.util.Set<ResourceLocation> ROTATION_DISABLED = ConcurrentHashMap.newKeySet();

    private ModelPreviewRegistry() {}

    /** Records what a model declared for its GUI preview; called when a model arrives over the sync channel. */
    public static void accept(ResourceLocation modelId, @Nullable String previewAnimation,
        boolean disablePreviewRotation) {
        if (modelId == null) {
            return;
        }
        if (previewAnimation == null || previewAnimation.isEmpty()) {
            PREVIEW_ANIMATIONS.remove(modelId);
        } else {
            PREVIEW_ANIMATIONS.put(modelId, previewAnimation);
        }
        if (disablePreviewRotation) {
            ROTATION_DISABLED.add(modelId);
        } else {
            ROTATION_DISABLED.remove(modelId);
        }
    }

    /**
     * Whether the model asked for the GUI preview's rotation to be disabled ({@code disable_preview_rotation}).
     * Upstream then drops the -10 degree pitch and lifts the model by 5.5 blocks instead
     * ({@code RenderUtil:329-334}); the flag already travels in the sync payload.
     */
    public static boolean previewRotationDisabled(@Nullable ResourceLocation modelId) {
        return modelId != null && ROTATION_DISABLED.contains(modelId);
    }

    /** Drops every remembered declaration, for a resource reload or a server change. */
    public static void clear() {
        PREVIEW_ANIMATIONS.clear();
        ROTATION_DISABLED.clear();
    }

    /** The animation a preview of {@code modelId} should loop; never blank. */
    public static String previewFor(@Nullable ResourceLocation modelId) {
        String declared = modelId == null ? null : PREVIEW_ANIMATIONS.get(modelId);
        return declared == null || declared.isEmpty() ? AnimationRegister.IDLE : declared;
    }

    /** Whether the model's own animations define {@code name}. */
    public static boolean hasAnimation(@Nullable ResourceLocation modelId, @Nullable String name) {
        return animation(modelId, name) != null;
    }

    /** The length of {@code name} in milliseconds, or 0 when the model does not define it. */
    public static double animationLengthMillis(@Nullable ResourceLocation modelId, @Nullable String name) {
        Animation animation = animation(modelId, name);
        if (animation == null || animation.animationLength == null) {
            return 0d;
        }
        // The engine stores the length in seconds (upstream multiplies by 50 the same way).
        return animation.animationLength * 50d;
    }

    /** Whether the model declares the reserved {@code hover} animation. */
    public static boolean hasHover(@Nullable ResourceLocation modelId) {
        return hasAnimation(modelId, AnimationRegister.HOVER);
    }

    /** Whether the model declares the reserved {@code hover_fadeout} animation. */
    public static boolean hasHoverFadeout(@Nullable ResourceLocation modelId) {
        return hasAnimation(modelId, AnimationRegister.HOVER_FADEOUT);
    }

    /** Whether the model declares the reserved {@code focus} animation. */
    public static boolean hasFocus(@Nullable ResourceLocation modelId) {
        return hasAnimation(modelId, AnimationRegister.FOCUS);
    }

    /** How long the fade-out animation should keep playing after the pointer leaves, in milliseconds. */
    public static double hoverFadeoutMillis(@Nullable ResourceLocation modelId) {
        return animationLengthMillis(modelId, AnimationRegister.HOVER_FADEOUT);
    }

    /** Whether a name can actually be played, i.e. is neither blank nor the {@code empty} sentinel on a model without it. */
    public static boolean isPlayable(@Nullable ResourceLocation modelId, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (AnimationRegister.EMPTY.equals(name)) {
            // Upstream uses "empty" as its "play nothing" sentinel; only a model that really defines it should be
            // asked for it, so packs without that animation keep their previous behaviour instead of a lookup miss.
            return hasAnimation(modelId, name);
        }
        return true;
    }

    @Nullable
    private static Animation animation(@Nullable ResourceLocation modelId, @Nullable String name) {
        if (modelId == null || name == null || name.isEmpty()) {
            return null;
        }
        AnimationFile file = ClientModelManager.animationFileFor(ModelIdUtil.getMainId(modelId));
        return file == null ? null : file.getAnimation(name);
    }
}
