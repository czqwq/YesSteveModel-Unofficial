package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BiFunction;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * The pure half of the built-in {@code empty} fallback: which clip name is substituted and which name is rejected.
 * <p>
 * These are the two values the engine's consumers read. {@code AnimationFile.getAnimation} answers with the built-in
 * no-op clip only for a missing {@code empty}, and {@code putAnimation} drops a model-defined {@code empty} so the
 * built-in version is the only one in play (SOURCE
 * {@code YsmBuiltinAnimations.java:59-66}; installed by {@code ClientProxy.java:34-35} in the reference branch).
 * <p>
 * The hooks are static engine state, so every test restores what it found. Touching {@link YsmBuiltinAnimations} at all
 * installs them (its static initialiser calls {@code registerHooks()}, SOURCE {@code :45-47}), which is exactly why the
 * capture happens before the first reference.
 */
class YsmBuiltinAnimationsTest {

    private BiFunction<String, Animation, Animation> previousFallback;
    private Predicate<String> previousRejected;

    @BeforeEach
    void captureHooks() {
        previousFallback = AnimationFile.builtinFallback;
        previousRejected = AnimationFile.rejectedAnimationNames;
    }

    @AfterEach
    void restoreHooks() {
        AnimationFile.builtinFallback = previousFallback;
        AnimationFile.rejectedAnimationNames = previousRejected;
    }

    @Test
    void onlyAMissingEmptyIsSubstituted() {
        // DEBUG_EMPTY_VISIBLE is false, so the substitution happens for the name with no model clip only.
        assertTrue(
            YsmBuiltinAnimations.isBuiltinEmptyUsed(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME, null),
            "a model that does not define 'empty' must get the built-in one");
        assertFalse(
            YsmBuiltinAnimations.isBuiltinEmptyUsed(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME, new Animation()),
            "a model that defines its own 'empty' keeps it");
        assertFalse(YsmBuiltinAnimations.isBuiltinEmptyUsed(AnimationRegister.IDLE, null), "only 'empty' is substituted");
        assertFalse(YsmBuiltinAnimations.isBuiltinEmptyUsed("Empty", null), "the name is matched exactly");
    }

    @Test
    void onlyTheExactNameEmptyIsRejected() {
        assertTrue(YsmBuiltinAnimations.isForbiddenModelAnimation(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME));
        assertFalse(YsmBuiltinAnimations.isForbiddenModelAnimation(AnimationRegister.IDLE));
        assertFalse(YsmBuiltinAnimations.isForbiddenModelAnimation("Empty"));
        assertFalse(YsmBuiltinAnimations.isForbiddenModelAnimation(""));
    }

    @Test
    void theBuiltinEmptyIsASingleLoopingNoOpClip() {
        Animation empty = YsmBuiltinAnimations.getEmptyAnimation();

        assertSame(empty, YsmBuiltinAnimations.getEmptyAnimation(), "callers must share one instance");
        assertEquals(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME, empty.animationName);
        assertEquals(EDefaultLoopTypes.LOOP, empty.loop, "the no-op must not stop the controller");
        assertEquals(20.0d, empty.animationLength, 1.0e-6, "one second per cycle");
        assertNotNull(empty.boneAnimations);
        assertTrue(empty.boneAnimations.isEmpty(), "the no-op changes no bone");
        // The engine's own "nothing to play" predicate: this is the flag OpenYSM's parallel discovery skips on.
        assertTrue(empty.isEmpty());
    }

    @Test
    void registerHooksInstallsBothHalvesOnTheEngineFile() {
        AnimationFile.builtinFallback = null;
        AnimationFile.rejectedAnimationNames = null;

        YsmBuiltinAnimations.registerHooks();

        assertNotNull(AnimationFile.builtinFallback, "the read-path hook must be installed");
        assertNotNull(AnimationFile.rejectedAnimationNames, "the write-path hook must be installed");
    }

    @Test
    void theWriteHookDropsTheModelsOwnEmptyAndTheReadHookSubstitutesTheBuiltin() {
        YsmBuiltinAnimations.registerHooks();
        AnimationFile file = new AnimationFile();

        file.putAnimation(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME, new Animation());
        file.putAnimation(AnimationRegister.IDLE, new Animation());

        assertFalse(
            file.animations.containsKey(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME),
            "a model-defined 'empty' must never reach the map");
        assertTrue(file.animations.containsKey(AnimationRegister.IDLE), "other names are stored normally");
        assertSame(
            YsmBuiltinAnimations.getEmptyAnimation(),
            file.getAnimation(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME),
            "the read hook then answers with the built-in no-op");
        assertNotNull(file.getAnimation(AnimationRegister.IDLE));
        assertNull(file.getAnimation("walk"), "a name the model does not define stays missing");
    }

    @Test
    void anEmptyClipAlreadyInTheMapIsNotOverwritten() {
        // The fallback fills a hole; it does not replace what a file already holds (DEBUG_EMPTY_VISIBLE stays false).
        // This is what keeps a file installed before ClientProxy.init behaving as it did.
        YsmBuiltinAnimations.registerHooks();
        AnimationFile file = new AnimationFile();
        Animation own = new Animation();
        own.animationName = YsmBuiltinAnimations.EMPTY_ANIMATION_NAME;
        file.animations.put(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME, own);

        assertSame(own, file.getAnimation(YsmBuiltinAnimations.EMPTY_ANIMATION_NAME));
    }
}
