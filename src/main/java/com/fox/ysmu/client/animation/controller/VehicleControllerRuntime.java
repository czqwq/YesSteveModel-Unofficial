package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ActiveAnimation;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ImplicitAnimationSelector;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The animations a <b>vehicle</b> sub-entity plays, in the shape upstream's {@code VehicleControllerCollection} gives
 * it ({@code client/controller/collections/VehicleControllerCollection.java:19-44}).
 *
 * <p>This is not a second controller runtime: the machine evaluation lives once, in
 * {@link ProjectileControllerRuntime}, which takes the fallback used when a model declares no JSON controller as an
 * {@link ImplicitAnimationSelector}. This class supplies the vehicle's fallback - upstream's <em>coded</em> controllers
 * - and nothing else. A vehicle whose pack ships {@code files.vehicles[].controller} therefore runs those definitions
 * through the same code path a projectile does.</p>
 *
 * <p>The coded set, in upstream's registration order (which is also the bone-overwrite priority, because the caller's
 * list order is):
 * <ol>
 * <li>{@code vehicle.pre_parallel0..7} - {@code VehicleControllerCollection.java:27-28}, the family that runs before
 * {@code main};</li>
 * <li>{@code vehicle.main} - {@code VehicleMainPredicate.java:12,20-26}: {@code water}, else {@code ground}, else
 * {@code fly};</li>
 * <li>{@code vehicle.move} - {@code VehicleMovePredicate.java:12,20-25}: {@code forward} when the horizontal speed
 * exceeds 0.05, else {@code idle};</li>
 * <li>{@code vehicle.ride} - {@code VehicleRidePredicate.java:12,20-23}: {@code has_ride} with a passenger, else
 * {@code not_ride};</li>
 * <li>{@code vehicle.parallel0..7} - {@code VehicleControllerCollection.java:37-38}, the highest priority.</li>
 * </ol>
 * {@code vehicle.pre_main} ({@code :30}) and {@code vehicle.post_main} ({@code :35}) are empty predicates upstream -
 * they select nothing unless the model's own controller JSON defines them, which the shared runtime evaluates.
 *
 * <p>The two parallel families are selected by "the model declares the clip", which is this port's one rule for a
 * parallel slot: upstream decides the same question while it builds the model's resource set and skips a slot whose
 * controller data is absent ({@code ParallelControllerDiscovery.java:64-71}), while the port registers a fixed pool and
 * checks existence at the hand-over ({@code AnimationManager#playParallelIfPresent},
 * {@code OpenYsmAnimationControllerRegistry.java:100-103}). Using the port's rule here keeps one mechanism instead of
 * two.
 *
 * <p><b>{@code vehicle.origin} is deliberately absent.</b> Upstream's {@code VehicleOriginController} is a compat hook,
 * not an entity-derived rotation: its only two providers are {@code SimplePlaneCompat.getRotation} (a
 * {@code xyz.przemyk.simpleplanes.entities.PlaneEntity}, {@code SimplePlaneCompat.java:29-37}) and
 * {@code ImmersiveAircraftCompat.getRotation} (an {@code immersive_aircraft.entity.AircraftEntity},
 * {@code ImmersiveAircraftCompat.java:28-38}), both 1.20 mods with no 1.7.10 counterpart in this repository. With
 * neither installed, upstream's controller leaves its rotation {@code null} and visits no bone queue at all
 * ({@code VehicleOriginController.java:57-69}), i.e. the model is oriented by the renderer's yaw - which is exactly
 * what {@code VehicleRenderer} does. So there is nothing to wire, and inventing an entity-yaw "origin controller"
 * would be a mechanism upstream does not have.
 *
 * <p>State is keyed per (entity, animation set); a vehicle that leaves the world is forgotten through
 * {@link #cleanupEntity}.
 */
public final class VehicleControllerRuntime {

    /** The eight {@code parallel} slots upstream's vehicle family carries ({@code VehicleControllerCollection.java:37}). */
    private static final int MAX_PARALLEL_SLOT = 8;

    /** {@code VehicleMovePredicate.java:22}'s threshold, unchanged. */
    private static final double MOVING_SPEED_THRESHOLD = 0.05D;

    /**
     * The entity state the coded controllers select from.
     * <p>
     * Upstream reads it off the entity inside each predicate ({@code VehicleMainPredicate.java:16-26},
     * {@code VehicleMovePredicate.java:20-25}, {@code VehicleRidePredicate.java:16-23}); 1.7.10 has the same four
     * facts, with {@code riddenByEntity} for "has a passenger" (a 1.7.10 entity carries at most one) and
     * {@code motionX}/{@code motionZ} for {@code getDeltaMovement()} (both are the per-tick velocity).
     */
    public static final class VehicleState {

        final boolean inWater;
        final boolean onGround;
        final boolean hasRide;
        final boolean moving;

        public VehicleState(boolean inWater, boolean onGround, boolean hasRide, boolean moving) {
            this.inWater = inWater;
            this.onGround = onGround;
            this.hasRide = hasRide;
            this.moving = moving;
        }

        public static VehicleState of(Entity entity) {
            double speed = Math.sqrt(entity.motionX * entity.motionX + entity.motionZ * entity.motionZ);
            return new VehicleState(
                entity.isInWater(),
                entity.onGround,
                entity.riddenByEntity != null,
                speed > MOVING_SPEED_THRESHOLD);
        }
    }

    /** The coded clips selected last frame and the tick each was entered on, per (entity, animation set). */
    private static final class CodedState {

        final Set<String> selected = new HashSet<>();
        final Map<String, Double> enteredTick = new HashMap<>();
    }

    private static final Map<String, CodedState> STATES = new ConcurrentHashMap<>();

    private VehicleControllerRuntime() {}

    /**
     * The animations this vehicle should play this frame, each with its clock origin: a coded clip is timed from the
     * tick its controller selected it, which is what makes a state switch restart it.
     *
     * <p>That restart is upstream's behaviour - its coded controllers are real GeckoLib controllers, so a switch calls
     * {@code setAnimation} and the clip starts over - and it is the same rule
     * {@link ProjectileControllerRuntime} already applies to a JSON controller's states
     * ({@code ActiveAnimation}'s javadoc: a {@code hold_on_last_frame} clip sampled against the entity's total age
     * would sit parked on its last frame forever).</p>
     */
    public static List<ActiveAnimation> getActiveAnimationEntries(int entityId, ResourceLocation animId,
        double ageInTicks, VehicleState state) {
        ImplicitAnimationSelector selector = (id, result, managedAnims, age) -> selectCodedAnimations(
            entityId,
            id,
            result,
            managedAnims,
            age,
            state);
        return ProjectileControllerRuntime.getActiveAnimationEntries(entityId, animId, ageInTicks, selector);
    }

    /** Drops one entity's coded-controller state, for a vehicle that left the world. */
    public static void cleanupEntity(int entityId) {
        String prefix = entityId + "|";
        STATES.keySet()
            .removeIf(key -> key.startsWith(prefix));
    }

    /** Drops every vehicle's coded-controller state, for a model reload or a server change. */
    public static void clear() {
        STATES.clear();
    }

    /**
     * Appends the coded clips to {@code result}, in upstream's controller order, and records each newly selected
     * clip's entry tick.
     */
    private static void selectCodedAnimations(int entityId, ResourceLocation animId, List<ActiveAnimation> result,
        Set<String> managedAnims, double ageInTicks, VehicleState state) {
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(animId);
        if (file == null || file.animations == null) {
            // Nothing to select, so the geometry stays in its bind pose.
            return;
        }

        List<String> selected = new ArrayList<>(4);
        // vehicle.pre_parallel0..7 (VehicleControllerCollection.java:27-28): the family that runs before main, so the
        // lowest priority. The port's player path registers the same family (CustomPlayerEntity.java:70-71).
        for (int i = 0; i < MAX_PARALLEL_SLOT; i++) {
            addIfPresent(selected, file, managedAnims, "pre_parallel" + i);
        }
        // vehicle.main: water first, then ground, then fly - one of the three, never two.
        addIfPresent(selected, file, managedAnims, state.inWater ? "water" : state.onGround ? "ground" : "fly");
        // vehicle.move.
        addIfPresent(selected, file, managedAnims, state.moving ? "forward" : "idle");
        // vehicle.ride.
        addIfPresent(selected, file, managedAnims, state.hasRide ? "has_ride" : "not_ride");
        // vehicle.parallel0..7, the highest priority.
        for (int i = 0; i < MAX_PARALLEL_SLOT; i++) {
            addIfPresent(selected, file, managedAnims, "parallel" + i);
        }

        CodedState coded = STATES.computeIfAbsent(entityId + "|" + animId, ignored -> new CodedState());
        for (String name : selected) {
            if (!coded.selected.contains(name)) {
                // Newly selected this frame: the clip restarts here.
                coded.enteredTick.put(name, ageInTicks);
            }
        }
        coded.selected.clear();
        coded.selected.addAll(selected);
        for (String name : selected) {
            Double entered = coded.enteredTick.get(name);
            result.add(new ActiveAnimation(name, entered != null ? entered : ageInTicks));
        }
        if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
            ysmu.LOG.info(
                "[YSMU-VEHICLE-CTRL] entityId={}, animId={}, coded={} (water={}, ground={}, ride={}, moving={})",
                entityId,
                animId,
                selected,
                state.inWater,
                state.onGround,
                state.hasRide,
                state.moving);
        }
    }

    /**
     * Adds {@code name} when the model declares it and no JSON controller already owns it, keeping the list free of
     * duplicates so a name cannot be sampled twice with two different clock origins.
     */
    private static void addIfPresent(List<String> selected, AnimationFile file, Set<String> managedAnims,
        String name) {
        if (managedAnims.contains(name) || selected.contains(name)) {
            return;
        }
        if (file.animations.containsKey(name)) {
            selected.add(name);
        }
    }
}
