package com.fox.ysmu.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ControllerUtils;

import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.manager.AnimationData;

/**
 * Pins the player controller set's transition lengths to upstream's.
 * <p>
 * The numbers are not cosmetic. The engine holds a controller in {@code AnimationState.Transitioning} with its clock
 * pinned at 0 until {@code tick >= transitionLengthTicks} ({@code AnimationController#process}), so a length larger
 * than upstream's spends the first frames of every state change blending into the clip's first frame and cuts the tail
 * off a short one-shot: {@code swing_hand} is 7.5 ticks while a 1.7.10 swing lasts 6, so upstream's {@code 0} is what
 * lets the whole clip play. Every value below is upstream's, from
 * {@code client/controller/collections/PlayerControllerCollection.java:31-71}.
 * <p>
 * This originally diverged in seven places at once (main 2 vs 0.1, both holds 0 vs 0.1, swing 2 vs 0, use 2 vs 0.1,
 * cap 2 vs 0, both GUI channels 1 vs 0), which is exactly the kind of change a single re-tuning edit would undo
 * silently.
 * <p>
 * No Minecraft classes are touched: the controller set is built from plain collections.
 */
class CustomPlayerEntityControllerSetTest {

    @Test
    void transitionLengthsMatchUpstream() {
        AnimationData data = new AnimationData();
        new CustomPlayerEntity().registerControllers(data);

        assertTransition(data, ControllerUtils.MAIN_CONTROLLER, 0.1f);
        assertTransition(data, ControllerUtils.HOLD_MAINHAND_CONTROLLER, 0.1f);
        assertTransition(data, ControllerUtils.HOLD_OFFHAND_CONTROLLER, 0.1f);
        assertTransition(data, ControllerUtils.SWING_CONTROLLER, 0f);
        assertTransition(data, ControllerUtils.USE_CONTROLLER, 0.1f);
        assertTransition(data, ControllerUtils.CAP_CONTROLLER, 0f);
        assertTransition(data, ControllerUtils.HOVER_CONTROLLER, 0f);
        assertTransition(data, ControllerUtils.FOCUS_CONTROLLER, 0f);
    }

    @Test
    void theParallelAxesAndPrePostSlotsStayInstant() {
        AnimationData data = new AnimationData();
        new CustomPlayerEntity().registerControllers(data);

        // Upstream creates these with 0 as well; they are the animation-driven slots where a blend would mix two
        // unrelated clips rather than soften one change.
        assertTransition(data, "parallel_0_controller", 0f);
        assertTransition(data, "parallel_7_controller", 0f);
        assertTransition(data, "pre_parallel_3_controller", 0f);
        assertTransition(data, ControllerUtils.OPENYSM_PRE_MAIN_CONTROLLER, 0f);
        assertTransition(data, ControllerUtils.OPENYSM_POST_USE_CONTROLLER, 0f);
        assertTransition(data, "head_controller", 0f);
    }

    private static void assertTransition(AnimationData data, String controllerName, float expected) {
        AnimationController<?> controller = data.getAnimationControllers()
            .get(controllerName);
        assertNotNull(controller, "no controller named " + controllerName);
        // Compare as the widened float the constructor stored, so the assertion reads "upstream's 0.1f".
        assertEquals(
            (double) expected,
            controller.transitionLengthTicks,
            controllerName + " must keep upstream's transition length");
    }
}
