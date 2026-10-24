package com.fox.ysmu.client.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ControllerUtils;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.manager.AnimationData;

/**
 * Pins the first-person arm's own controller set and animation domain - upstream's {@code FPArmControllerCollection}
 * ({@code client/controller/collections/FPArmControllerCollection.java:22-32}) over its own animation store
 * ({@code client/entity/CustomFirstPersonArmEntity.java:88-91}).
 *
 * <p>
 * The set is what the arm is posed by, and it is deliberately not the player's own: {@code fp.arm.misc},
 * {@code fp.arm.parallel_0..7} and {@code fp.arm.armor_<slot>}, and nothing from
 * {@code PlayerControllerCollection}. An arm driven by the body's {@code main_controller} would run the model's idle /
 * walk / run state machine against the arm's bones, and the names matter beyond the Java wiring - the pack-script
 * binding ({@code PackUserFunctions#scriptForController}) and the model's declared controller lookup
 * ({@code OpenYsmPlayerControllerRuntime#tryApply}) both derive their key from the controller's name, so a name the
 * port invented for itself would match neither.
 *
 * <p>
 * The transition lengths are upstream's: every one of the three families is built with {@code 0}
 * ({@code FPArmControllerCollection.java:27-31}), and the engine pins a controller's clock at 0 until
 * {@code tick >= transitionLengthTicks} ({@code AnimationController#process}), so a non-zero value would spend the
 * first frames of every state change blending into the clip's first frame.
 *
 * <p>
 * No Minecraft class is touched: the set is built from plain collections, exactly as
 * {@code CustomPlayerEntityControllerSetTest} builds the player's.
 */
class CustomFirstPersonArmEntityControllerSetTest {

    private static final ResourceLocation MODEL = new ResourceLocation(ysmu.MODID, "first_person_arm_test");

    private static AnimationData armControllerSet() {
        AnimationData data = new AnimationData();
        new CustomFirstPersonArmEntity().registerControllers(data);
        return data;
    }

    private static CustomFirstPersonArmEntity armForModel(ResourceLocation modelId) {
        CustomFirstPersonArmEntity arm = new CustomFirstPersonArmEntity();
        arm.setMainModel(ModelIdUtil.getMainId(modelId));
        return arm;
    }

    @Test
    void theArmCarriesUpstreamsThreeFamilies() {
        AnimationData data = armControllerSet();

        assertNotNull(controller(data, ControllerUtils.FP_ARM_MISC_CONTROLLER), "upstream's fp.arm.misc");
        for (int i = 0; i < ControllerUtils.FP_ARM_PARALLEL_SLOTS; i++) {
            assertNotNull(
                controller(data, ControllerUtils.FP_ARM_PARALLEL_CONTROLLER_PREFIX + i),
                "upstream's fp.arm.parallel_" + i);
        }
        for (String slot : new String[] { "feet", "legs", "chest", "head" }) {
            assertNotNull(
                controller(data, ControllerUtils.FP_ARM_ARMOR_CONTROLLER_PREFIX + slot),
                "upstream's fp.arm.armor_" + slot);
        }
    }

    @Test
    void theArmCarriesNoneOfTheBodyControllers() {
        AnimationData data = armControllerSet();

        // The body's own state machine and its other families. `main_controller` is the one that matters most: it is
        // the controller that would drive the arm's bones with the model's locomotion clips.
        for (String bodyController : new String[] { ControllerUtils.MAIN_CONTROLLER, ControllerUtils.CAP_CONTROLLER,
            ControllerUtils.SWING_CONTROLLER, ControllerUtils.USE_CONTROLLER,
            ControllerUtils.HOLD_MAINHAND_CONTROLLER, ControllerUtils.HOLD_OFFHAND_CONTROLLER,
            ControllerUtils.OPENYSM_PRE_MAIN_CONTROLLER, ControllerUtils.OPENYSM_POST_MAIN_CONTROLLER,
            ControllerUtils.VEHICLE_CONTROLLER, ControllerUtils.PASSENGER_CONTROLLER,
            // The player's per-slot armour controllers are named `<slot>_controller`; the arm's are `fp.arm.armor_<slot>`.
            "head_controller", "chest_controller", "legs_controller", "feet_controller",
            // The player's numeric parallel family, which the arm's must not be confused with.
            "parallel_0_controller", "pre_parallel_0_controller" }) {
            assertFalse(
                data.getAnimationControllers()
                    .containsKey(bodyController),
                "the first-person arm must not carry the body's " + bodyController);
        }
    }

    @Test
    void theBodySetGainedNoneOfTheArmsControllers() {
        AnimationData data = new AnimationData();
        new CustomPlayerEntity().registerControllers(data);

        assertFalse(
            data.getAnimationControllers()
                .containsKey(ControllerUtils.FP_ARM_MISC_CONTROLLER),
            "the fp.arm family belongs to the arm's animatable only");
        assertFalse(
            data.getAnimationControllers()
                .containsKey(ControllerUtils.FP_ARM_PARALLEL_CONTROLLER_PREFIX + "0"),
            "the fp.arm family belongs to the arm's animatable only");
        assertFalse(
            data.getAnimationControllers()
                .containsKey(ControllerUtils.FP_ARM_ARMOR_CONTROLLER_PREFIX + "head"),
            "the fp.arm family belongs to the arm's animatable only");
    }

    @Test
    void everyArmControllerStartsInstantAndTheParallelAxisIsAdditive() {
        AnimationData data = armControllerSet();

        assertFalse(
            data.getAnimationControllers()
                .isEmpty(),
            "the arm must have a controller set at all");
        for (String name : data.getAnimationControllers()
            .keySet()) {
            assertEquals(0.0d, controller(data, name).transitionLengthTicks, name + " must keep upstream's 0");
            if (name.startsWith(ControllerUtils.FP_ARM_PARALLEL_CONTROLLER_PREFIX)) {
                // 并行动画族的旋转是相加的：FPArmControllerCollection.java:28-29 passes blendRotation=true.
                assertTrue(controller(data, name).isAdditiveRotation(), name + " must blend as additive rotation");
            } else {
                assertFalse(controller(data, name).isAdditiveRotation(), name + " is not a parallel slot");
            }
        }
    }

    @Test
    void theArmReadsTheFirstPersonDomainAndTheBodyTheMainOne() {
        CustomPlayerEntity body = new CustomPlayerEntity();
        body.setMainModel(ModelIdUtil.getMainId(MODEL));
        CustomFirstPersonArmEntity arm = armForModel(MODEL);

        // Upstream: the arm answers `getAnimation(name)` from the render target's fp_arm store
        // (client/entity/CustomFirstPersonArmEntity.java:88-91), which this port installs under the model's `arm` id
        // (AnimationDomain.animationFileId). The body keeps the `main` id.
        assertEquals(ModelIdUtil.getArmId(MODEL), arm.getAnimationFileId());
        assertEquals(ModelIdUtil.getMainId(MODEL), body.getAnimationFileId());
        assertNotEquals(arm.getAnimationFileId(), body.getAnimationFileId());
    }

    @Test
    void theArmIsNotAHumanoidSoItFiresNoHumanoidHooks() {
        // Upstream's arm is a plain CustomEntity (client/entity/CustomFirstPersonArmEntity.java:16), not a
        // CustomHumanoidEntity, so its tick must not run a pack's `@player_update` script a second time for a frame
        // the world's own tick already covered.
        assertFalse(armForModel(MODEL).firesHumanoidHooks());
        assertTrue(new CustomPlayerEntity().firesHumanoidHooks());
    }

    private static AnimationController<?> controller(AnimationData data, String name) {
        return data.getAnimationControllers()
            .get(name);
    }
}
