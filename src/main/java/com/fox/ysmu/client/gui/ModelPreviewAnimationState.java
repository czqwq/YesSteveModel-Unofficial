package com.fox.ysmu.client.gui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.AnimationRegister;

/**
 * One GUI tile's preview-animation state, a transcription of upstream's {@code CatalogModelPreviewAnimationState}.
 * <p>
 * {@link #configure} is fed by {@link ModelPreviewRegistry} (the model's declared preview animation plus whether it
 * defines the reserved {@code hover}/{@code hover_fadeout}/{@code focus} animations), and {@link #apply} is called
 * once per frame with the tile's hover and focus state. Together they decide which of the three animation channels the
 * preview entity should play.
 */
public final class ModelPreviewAnimationState {

    private static final DoubleSupplier NO_FADEOUT = () -> 0;

    /** Per-model state, so a screen that is opened again replays from the start. */
    private static final Map<ResourceLocation, ModelPreviewAnimationState> STATES = new ConcurrentHashMap<>();

    private String previewAnimation = AnimationRegister.IDLE;
    private String hoverAnimation = AnimationRegister.EMPTY;
    private String hoverFadeoutAnimation = AnimationRegister.EMPTY;
    private String focusAnimation = AnimationRegister.EMPTY;
    private DoubleSupplier hoverFadeoutMillis = NO_FADEOUT;
    private long lastHoverTime = -1;
    private double activeFadeoutMillis;
    private boolean wasHovered;

    /** The state for {@code modelId}, configured from the model's own animations on first use. */
    public static ModelPreviewAnimationState forModel(@Nullable ResourceLocation modelId) {
        return STATES.computeIfAbsent(
            modelId == null ? new ResourceLocation("ysmu", "unknown") : modelId,
            key -> new ModelPreviewAnimationState().configuredFor(key));
    }

    /** Forgets every tile's interaction state, so re-entering a screen starts the animations over. */
    public static void resetAll() {
        STATES.clear();
    }

    private ModelPreviewAnimationState configuredFor(ResourceLocation modelId) {
        configure(
            ModelPreviewRegistry.previewFor(modelId),
            ModelPreviewRegistry.hasHover(modelId),
            ModelPreviewRegistry.hasHoverFadeout(modelId),
            () -> ModelPreviewRegistry.hoverFadeoutMillis(modelId),
            ModelPreviewRegistry.hasFocus(modelId));
        return this;
    }

    void configure(String previewAnimation, boolean hasHover, boolean hasHoverFadeout,
        DoubleSupplier hoverFadeoutMillis, boolean hasFocus) {
        this.previewAnimation = previewAnimation == null || previewAnimation.isEmpty() ? AnimationRegister.IDLE
            : previewAnimation;
        this.hoverAnimation = hasHover ? AnimationRegister.HOVER : AnimationRegister.EMPTY;
        this.hoverFadeoutAnimation = hasHoverFadeout ? AnimationRegister.HOVER_FADEOUT : AnimationRegister.EMPTY;
        this.focusAnimation = hasFocus ? AnimationRegister.FOCUS : AnimationRegister.EMPTY;
        this.hoverFadeoutMillis = hasHoverFadeout && hoverFadeoutMillis != null ? hoverFadeoutMillis : NO_FADEOUT;
        resetInteraction();
    }

    /** Writes this frame's channels into {@code target}. */
    public void apply(PreviewAnimationInfo target, boolean hovered, boolean focused, long now) {
        if (target == null) {
            return;
        }
        if (!target.hasPreview(this.previewAnimation)) {
            target.setPreview(this.previewAnimation);
        }
        if (hovered) {
            this.lastHoverTime = now;
            this.wasHovered = true;
            target.setHover(this.hoverAnimation);
        } else {
            if (this.wasHovered) {
                this.activeFadeoutMillis = Math.max(0, this.hoverFadeoutMillis.getAsDouble());
                this.wasHovered = false;
            }
            if (this.lastHoverTime >= 0 && now - this.lastHoverTime < this.activeFadeoutMillis) {
                target.setHover(this.hoverFadeoutAnimation);
            } else {
                target.setHover(AnimationRegister.EMPTY);
            }
        }
        target.setFocus(focused ? this.focusAnimation : AnimationRegister.EMPTY);
    }

    /** The animation this tile's preview should loop while nothing else is set. */
    public String previewAnimation() {
        return this.previewAnimation;
    }

    /** The animation currently requested for the hover channel. */
    public String hoverAnimation() {
        return this.hoverAnimation;
    }

    void reset() {
        this.previewAnimation = AnimationRegister.IDLE;
        this.hoverAnimation = AnimationRegister.EMPTY;
        this.hoverFadeoutAnimation = AnimationRegister.EMPTY;
        this.focusAnimation = AnimationRegister.EMPTY;
        this.hoverFadeoutMillis = NO_FADEOUT;
        resetInteraction();
    }

    private void resetInteraction() {
        this.lastHoverTime = -1;
        this.activeFadeoutMillis = 0;
        this.wasHovered = false;
    }

    /** Number of tiles that currently hold state; used by the diagnostics-free tests. */
    static int trackedModels() {
        return STATES.size();
    }
}
