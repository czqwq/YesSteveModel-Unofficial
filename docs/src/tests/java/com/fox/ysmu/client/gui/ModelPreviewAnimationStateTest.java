package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationRegister;

/**
 * The GUI preview channels, transcribed from upstream's {@code CatalogModelPreviewAnimationState}. The transitions are
 * pinned here because a wrong one is invisible in game: the tile simply plays the wrong animation (or none), which
 * looks exactly like the frozen pose this feature exists to remove.
 */
class ModelPreviewAnimationStateTest {

    private static final long NOW = 10_000L;

    private static PreviewAnimationInfo info() {
        return new PreviewAnimationInfo();
    }

    @Test
    void previewChannelUsesTheConfiguredAnimationAndFallsBackToIdle() {
        PreviewAnimationInfo target = info();
        ModelPreviewAnimationState state = new ModelPreviewAnimationState();
        state.configure("gui", false, false, () -> 0d, false);

        state.apply(target, false, false, NOW);
        assertEquals("gui", target.getPreview());
        assertTrue(target.hasPreview("gui"));

        target.clear();
        state.configure("", false, false, () -> 0d, false);
        state.apply(target, false, false, NOW);
        assertEquals(AnimationRegister.IDLE, target.getPreview());
    }

    @Test
    void hoverChannelFollowsThePointerAndFadesOut() {
        PreviewAnimationInfo target = info();
        ModelPreviewAnimationState state = new ModelPreviewAnimationState();
        state.configure("idle", true, true, () -> 400d, false);

        state.apply(target, true, false, NOW);
        assertEquals(AnimationRegister.HOVER, target.getHover());

        // Leaving the tile keeps the fade-out animation for the configured window...
        long left = NOW + 50L;
        state.apply(target, false, false, left);
        assertEquals(AnimationRegister.HOVER_FADEOUT, target.getHover());

        // ...and stops when the window has passed.
        state.apply(target, false, false, left + 401L);
        assertEquals(AnimationRegister.EMPTY, target.getHover());
    }

    @Test
    void hoverWithoutAFadeoutWindowStopsImmediately() {
        PreviewAnimationInfo target = info();
        ModelPreviewAnimationState state = new ModelPreviewAnimationState();
        state.configure("idle", true, false, () -> 0d, false);

        state.apply(target, true, false, NOW);
        assertEquals(AnimationRegister.HOVER, target.getHover());

        state.apply(target, false, false, NOW + 1L);
        assertEquals(AnimationRegister.EMPTY, target.getHover());
    }

    @Test
    void modelWithoutHoverOrFocusNeverEnablesThem() {
        PreviewAnimationInfo target = info();
        ModelPreviewAnimationState state = new ModelPreviewAnimationState();
        // This is what a model without those reserved animations configures to.
        state.configure("idle", false, false, () -> 0d, false);

        state.apply(target, true, true, NOW);

        assertEquals(AnimationRegister.EMPTY, target.getHover());
        assertEquals(AnimationRegister.EMPTY, target.getFocus());
        assertEquals("idle", target.getPreview());
    }

    @Test
    void focusChannelFollowsSelection() {
        PreviewAnimationInfo target = info();
        ModelPreviewAnimationState state = new ModelPreviewAnimationState();
        state.configure("idle", false, false, () -> 0d, true);

        state.apply(target, false, true, NOW);
        assertEquals(AnimationRegister.FOCUS, target.getFocus());

        state.apply(target, false, false, NOW);
        assertEquals(AnimationRegister.EMPTY, target.getFocus());
    }

    @Test
    void registryFallsBackToIdleAndTreatsEmptyAsUnplayable() {        ResourceLocation modelId = new ResourceLocation("ysmu", "test_preview_model");

        assertEquals(AnimationRegister.IDLE, ModelPreviewRegistry.previewFor(null));
        assertEquals(AnimationRegister.IDLE, ModelPreviewRegistry.previewFor(modelId));

        ModelPreviewRegistry.accept(modelId, "gui", false);
        assertEquals("gui", ModelPreviewRegistry.previewFor(modelId));
        assertFalse(ModelPreviewRegistry.previewRotationDisabled(modelId));

        // `disable_preview_rotation` travels in the same payload and switches upstream's framing variant.
        ModelPreviewRegistry.accept(modelId, "gui", true);
        assertTrue(ModelPreviewRegistry.previewRotationDisabled(modelId));
        ModelPreviewRegistry.accept(modelId, "gui", false);
        assertFalse(ModelPreviewRegistry.previewRotationDisabled(modelId));

        ModelPreviewRegistry.accept(modelId, "", false);
        assertEquals(AnimationRegister.IDLE, ModelPreviewRegistry.previewFor(modelId));

        // The model is not loaded in a unit test, so it declares nothing and the reserved channels stay disabled.
        assertFalse(ModelPreviewRegistry.hasHover(modelId));
        assertFalse(ModelPreviewRegistry.hasFocus(modelId));
        assertTrue(ModelPreviewRegistry.isPlayable(modelId, AnimationRegister.HOVER));
        assertFalse(ModelPreviewRegistry.isPlayable(modelId, ""));
        assertFalse(ModelPreviewRegistry.isPlayable(modelId, AnimationRegister.EMPTY));

        ModelPreviewRegistry.clear();
    }
}
