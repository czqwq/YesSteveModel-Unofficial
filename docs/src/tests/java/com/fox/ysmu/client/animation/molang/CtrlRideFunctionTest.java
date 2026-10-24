package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@code ctrl.ride(type, matcher)} - the animation-file / timeline / {@code .molang}-script half of the name.
 *
 * <p>wiki (动画控制器「函数」) documents exactly two forms:
 * {@code ctrl.ride('vehicle', '$minecraft:pig')} and {@code ctrl.ride('passenger', '$minecraft:pig')}; upstream
 * implements them in {@code functions/RideCheck.java:28-72} and registers the name at
 * {@code CtrlBinding.java:82}. Before this port the mclib function table had no {@code ctrl.ride} at all - only the
 * controller-condition path answered, and it answered just "is the player riding anything" - so a keyframe or a
 * script naming it failed to parse and the whole channel was dropped.</p>
 *
 * <p>The matcher rule is pure and is pinned here; the vehicle/passenger resolution needs a live
 * {@code EntityLivingBase} (and a {@code World} to construct one) and is therefore only asserted at the registration
 * and no-frame-entity level.</p>
 */
class CtrlRideFunctionTest {

    private static MolangParser parserWithHostFunctions() {
        AnimationRegister.registerMolangHooks();
        return new MolangParser();
    }

    @Test
    void dollarMatcherAcceptsBothSpellingsOfTheRegistryName() {
        // 1.7.10's EntityList string ("Pig") and a pack's minecraft:pig go through the same normalisation the
        // `vehicle$<id>` animation names use (ConditionalEntityIdMatch.normalizedId).
        assertTrue(CtrlRideFunction.matchesId("$minecraft:pig", "Pig"));
        assertTrue(CtrlRideFunction.matchesId("$minecraft:pig", "minecraft:pig"));
        assertTrue(CtrlRideFunction.matchesId("$Pig", "minecraft:pig"));
        assertFalse(CtrlRideFunction.matchesId("$minecraft:pig", "Cow"));
        assertFalse(CtrlRideFunction.matchesId("$minecraft:cow", "Pig"));
        // A modded entity whose EntityList name already carries a namespace.
        assertTrue(CtrlRideFunction.matchesId("$mymod:speeder", "mymod:speeder"));
    }

    /** 1.7.10 has no entity-tag manager, so the {@code #} half can only answer false - upstream's "tag not matched". */
    @Test
    void theTagHalfIsUnsupportedAndAnswersFalse() {
        assertFalse(CtrlRideFunction.matchesId("#minecraft:pigs", "Pig"));
        assertFalse(CtrlRideFunction.matchesId("#Pig", "Pig"));
    }

    @Test
    void malformedMatchersAnswerFalse() {
        // Upstream: StringUtils.isBlank(input) -> FALSE (RideCheck.java:33-35), and only $/# have a branch (:52-71).
        assertFalse(CtrlRideFunction.matchesId(null, "Pig"));
        assertFalse(CtrlRideFunction.matchesId("", "Pig"));
        assertFalse(CtrlRideFunction.matchesId("pig", "Pig"));
        assertFalse(CtrlRideFunction.matchesId("$minecraft:pig", null));
    }

    @Test
    void withoutAnEntityEveryTypeAnswersFalse() {
        assertFalse(CtrlRideFunction.ridingMatches(null, "vehicle", "$minecraft:pig"));
        assertFalse(CtrlRideFunction.ridingMatches(null, "passenger", "$minecraft:pig"));
        assertNull(CtrlRideFunction.riddenEntity(null, "vehicle"));
        assertNull(CtrlRideFunction.riddenEntity(null, "passenger"));
        // The type vocabulary is upstream's two words; anything else has no branch (RideCheck.java:38-44).
        assertNull(CtrlRideFunction.riddenEntity(null, "horse"));
    }

    @Test
    void registeredAsAFunctionWithUpstreamsArity() throws Exception {
        MolangParser parser = parserWithHostFunctions();
        assertNotNull(parser.functions.get("ctrl.ride"), "ctrl.ride must resolve to a function class");

        // Upstream's validateArgumentSize is 2 or 3 (RideCheck.java:74-77); mclib only checks a lower bound, so a
        // 2-argument call must pass and a 3-argument one must too.
        assertDoesNotThrow(() -> parser.parseExpression("ctrl.ride('vehicle', '$minecraft:pig')"));
        assertDoesNotThrow(() -> parser.parseExpression("ctrl.ride('passenger', '$minecraft:pig', 0)"));
        // One argument is a model error and has to stay one, not become a silent 0.
        assertThrows(MolangException.class, () -> parser.parseExpression("ctrl.ride('vehicle')"));
    }

    @Test
    void withNoFrameEntityTheCallEvaluatesToZero() throws Exception {
        MolangParser parser = parserWithHostFunctions();
        // Outside a published frame the context is empty; clear any entity a previous test left on this thread.
        MolangFrameContext.end();

        assertEquals(
            0.0d,
            parser.parseExpression("ctrl.ride('vehicle', '$minecraft:pig')")
                .get(),
            0.0d);
    }

    @Test
    void theUnregisteredFunctionProbeFindsNoGap() {
        MolangParser parser = parserWithHostFunctions();

        assertTrue(
            MolangInstructionExecutor.unregisteredFunctions(parser, "ctrl.ride('vehicle', '$minecraft:pig')")
                .isEmpty(),
            "ctrl.ride must not be reported as missing");
    }
}
