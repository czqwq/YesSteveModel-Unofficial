package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * {@link VehicleControllerRuntime}'s coded controllers - upstream's {@code VehicleControllerCollection} shape: the
 * three predicates ({@code water}/{@code ground}/{@code fly}, {@code forward}/{@code idle}, {@code has_ride}/
 * {@code not_ride}), and the registration order, which is also the bone-overwrite priority (the caller plays the list
 * in order, so the last clip written for a bone wins - upstream's {@code :27-38} order is what is asserted here).
 *
 * <p>The clip set is driven through the real entry point: an {@link AnimationFile} in {@code GeckoLibCache} declares
 * which names the model has, and a {@link VehicleControllerRuntime.VehicleState} supplies the four facts. That is the
 * same input the renderer gives it, with no client.
 */
class VehicleControllerRuntimeTest {

    private static final ResourceLocation ANIM_ID = new ResourceLocation("ysmu", "vehicle_probe");
    private static final int ENTITY_ID = 4242;

    @BeforeEach
    void reset() {
        VehicleControllerRuntime.clear();
        GeckoLibCache.getInstance()
            .getAnimations()
            .remove(ANIM_ID);
    }

    @AfterEach
    void cleanup() {
        VehicleControllerRuntime.clear();
        GeckoLibCache.getInstance()
            .getAnimations()
            .remove(ANIM_ID);
    }

    private static void declare(String... names) {
        AnimationFile file = new AnimationFile();
        for (String name : names) {
            file.animations.put(name, new Animation());
        }
        GeckoLibCache.getInstance()
            .getAnimations()
            .put(ANIM_ID, file);
    }

    private static List<String> selected(VehicleControllerRuntime.VehicleState state, double ageInTicks) {
        List<String> names = new ArrayList<>();
        for (ProjectileControllerRuntime.ActiveAnimation entry : VehicleControllerRuntime
            .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, ageInTicks, state)) {
            names.add(entry.name);
        }
        return names;
    }

    /** The main predicate: water first, then ground, then fly - exactly one of the three. */
    @Test
    void theMainPredicatePicksWaterGroundOrFly() {
        declare("water", "ground", "fly");

        assertEquals(
            Arrays.asList("water"),
            selected(new VehicleControllerRuntime.VehicleState(true, true, false, false), 0.0D),
            "in water wins over on ground");
        assertEquals(
            Arrays.asList("ground"),
            selected(new VehicleControllerRuntime.VehicleState(false, true, false, false), 0.0D));
        assertEquals(
            Arrays.asList("fly"),
            selected(new VehicleControllerRuntime.VehicleState(false, false, false, false), 0.0D));
    }

    /** The move predicate: forward above the threshold, idle otherwise. */
    @Test
    void theMovePredicatePicksForwardOrIdle() {
        declare("forward", "idle");

        assertEquals(
            Arrays.asList("forward"),
            selected(new VehicleControllerRuntime.VehicleState(false, true, false, true), 0.0D));
        assertEquals(
            Arrays.asList("idle"),
            selected(new VehicleControllerRuntime.VehicleState(false, true, false, false), 0.0D));
    }

    /** The ride predicate: has_ride with a passenger, not_ride without one. */
    @Test
    void theRidePredicatePicksHasRideOrNotRide() {
        declare("has_ride", "not_ride");

        assertEquals(
            Arrays.asList("has_ride"),
            selected(new VehicleControllerRuntime.VehicleState(false, true, true, false), 0.0D));
        assertEquals(
            Arrays.asList("not_ride"),
            selected(new VehicleControllerRuntime.VehicleState(false, true, false, false), 0.0D));
    }

    /**
     * The whole order, which is the priority: {@code pre_parallel0..7} (lowest), then the three state clips, then
     * {@code parallel0..7} (highest). A later clip overwrites the bones an earlier one wrote.
     */
    @Test
    void theSelectionOrderIsUpstreamsRegistrationOrderAndSoThePriority() {
        declare(
            "pre_parallel0", "pre_parallel1", "pre_parallel2", "pre_parallel3",
            "pre_parallel4", "pre_parallel5", "pre_parallel6", "pre_parallel7",
            "water", "ground", "fly", "forward", "idle", "has_ride", "not_ride",
            "parallel0", "parallel1", "parallel2", "parallel3",
            "parallel4", "parallel5", "parallel6", "parallel7");

        assertEquals(
            Arrays.asList(
                "pre_parallel0", "pre_parallel1", "pre_parallel2", "pre_parallel3",
                "pre_parallel4", "pre_parallel5", "pre_parallel6", "pre_parallel7",
                "water", "forward", "has_ride",
                "parallel0", "parallel1", "parallel2", "parallel3",
                "parallel4", "parallel5", "parallel6", "parallel7"),
            selected(new VehicleControllerRuntime.VehicleState(true, false, true, true), 0.0D));
    }

    /** A clip the model does not declare is not selected - the state simply has no clip for that case. */
    @Test
    void aClipTheModelDoesNotDeclareIsNotSelected() {
        declare("water", "fly");

        List<String> onGround = selected(new VehicleControllerRuntime.VehicleState(false, true, false, false), 0.0D);
        assertTrue(onGround.isEmpty(), "no ground clip declared: " + onGround);
    }

    /**
     * A coded clip is timed from the tick its controller <em>selected</em> it: a state switch restarts it, an
     * unchanged state keeps the origin. (A {@code hold_on_last_frame} clip sampled against the entity's total age
     * would sit parked on its last frame forever.)
     */
    @Test
    void aNewlySelectedClipRestartsAndAnUnchangedOneKeepsItsOrigin() {
        declare("water", "ground");

        List<ProjectileControllerRuntime.ActiveAnimation> first = VehicleControllerRuntime
            .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, 10.0D, new VehicleControllerRuntime.VehicleState(
                false, true, false, false));
        assertEquals(1, first.size());
        assertEquals("ground", first.get(0).name);
        assertEquals(10.0D, first.get(0).startTick, 0.0D);

        List<ProjectileControllerRuntime.ActiveAnimation> sameState = VehicleControllerRuntime
            .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, 25.0D, new VehicleControllerRuntime.VehicleState(
                false, true, false, false));
        assertEquals(10.0D, sameState.get(0).startTick, 0.0D, "the same clip keeps its clock origin");

        List<ProjectileControllerRuntime.ActiveAnimation> switched = VehicleControllerRuntime
            .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, 30.0D, new VehicleControllerRuntime.VehicleState(
                true, false, false, false));
        assertEquals("water", switched.get(0).name);
        assertEquals(30.0D, switched.get(0).startTick, 0.0D, "a newly selected clip restarts here");
    }

    /** The state is per entity: forgetting one entity restarts its clips, and clearing forgets all of them. */
    @Test
    void cleanupForgetsOneEntitysStateAndClearForgetsEveryEntitys() {
        declare("ground");

        assertEquals(
            10.0D,
            VehicleControllerRuntime
                .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, 10.0D, new VehicleControllerRuntime.VehicleState(
                    false, true, false, false))
                .get(0).startTick,
            0.0D);

        VehicleControllerRuntime.cleanupEntity(ENTITY_ID);
        assertEquals(
            20.0D,
            VehicleControllerRuntime
                .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, 20.0D, new VehicleControllerRuntime.VehicleState(
                    false, true, false, false))
                .get(0).startTick,
            0.0D,
            "after cleanup the clip is new again");

        VehicleControllerRuntime.clear();
        assertEquals(
            30.0D,
            VehicleControllerRuntime
                .getActiveAnimationEntries(ENTITY_ID, ANIM_ID, 30.0D, new VehicleControllerRuntime.VehicleState(
                    false, true, false, false))
                .get(0).startTick,
            0.0D);
    }

    /** The four facts come off the entity: in water, on ground, carrying somebody, and moving above the threshold. */
    @Test
    void theStateIsReadOffTheEntityWithTheDocumentedMovingThreshold() {
        BoatStub boat = new BoatStub();
        boat.setInWater(true);
        boat.onGround = true;
        boat.riddenByEntity = new BoatStub();
        boat.motionX = 0.05D;
        boat.motionZ = 0.0D;

        VehicleControllerRuntime.VehicleState atThreshold = VehicleControllerRuntime.VehicleState.of(boat);
        assertTrue(atThreshold.inWater);
        assertTrue(atThreshold.onGround);
        assertTrue(atThreshold.hasRide);
        assertFalse(atThreshold.moving, "0.05 is the threshold, not yet moving");

        boat.motionX = 0.04D;
        boat.motionZ = 0.04D; // sqrt(0.0032) > 0.05
        assertTrue(VehicleControllerRuntime.VehicleState.of(boat).moving, "the two axes are combined");

        boat.motionX = 0.0D;
        boat.motionZ = 0.0D;
        boat.onGround = false;
        boat.riddenByEntity = null;
        VehicleControllerRuntime.VehicleState still = VehicleControllerRuntime.VehicleState.of(boat);
        assertFalse(still.moving);
        assertFalse(still.onGround);
        assertFalse(still.hasRide);
    }

    /** A boat whose world is null (as in a unit JVM); the one field {@code VehicleState.of} needs is exposed here. */
    private static final class BoatStub extends EntityBoat {

        BoatStub() {
            super(null);
        }

        void setInWater(boolean inWater) {
            this.inWater = inWater;
        }
    }
}
