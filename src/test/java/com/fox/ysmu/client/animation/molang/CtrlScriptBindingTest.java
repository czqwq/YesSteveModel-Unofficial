package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * Guards the mapping from an OpenYSM pack script's return value to a play state.
 * <p>
 * A pack script bound to a controller decides that controller's frame, and the two ways it can go wrong are both
 * silent: a state code mapped to the wrong {@code PlayState}, or {@code state_bypass} no longer falling back to the
 * built-in predicate - which would leave a controller that has no opinion stuck on whatever the script last set.
 * <p>
 * The codes are upstream's ({@code client/animation/molang/CtrlBinding.java:35-38}) and so is the mapping
 * ({@code geckolib3/core/controller/CodedAnimationController.java:116-121}: only continue/pause/stop are states,
 * everything else - {@code state_bypass} included - means "use the coded predicate"). A script that runs off its end
 * returns 0, which is deliberately nothing.
 * <p>
 * The scripts used here are constants, so no player, controller or Minecraft class is needed.
 */
class CtrlScriptBindingTest {

    private static PlayState run(String source) {
        MolangParser parser = new MolangParser();
        CtrlScriptBinding.register(parser, Collections.emptyList());
        return CtrlScriptBinding.evaluate(PackFunctionScript.parse(source), parser, null, null, null);
    }

    @Test
    void theThreeStatesMapToTheirPlayStates() {
        assertEquals(PlayState.CONTINUE, run("return ctrl.state_continue;"));
        assertEquals(PlayState.STOP, run("return ctrl.state_stop;"));
        // A-07 gave the engine the third state, so a script's `state_pause` now holds the pose the way upstream's
        // does, instead of being folded into CONTINUE as a degradation.
        assertEquals(PlayState.PAUSE, run("return ctrl.state_pause;"));
    }

    @Test
    void bypassAndFallingOffTheEndMeanNoOpinion() {
        assertNull(run("return ctrl.state_bypass;"), "state_bypass must fall back to the built-in predicate");
        assertNull(run("return 0;"), "a script that returns nothing must not decide the frame");
        assertNull(run("return 999;"), "an unmapped code must not decide the frame");
    }
}
