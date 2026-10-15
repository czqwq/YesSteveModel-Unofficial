package com.fox.ysmu.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/**
 * Registry of vehicle model overrides keyed by tracked entity id - the port's counterpart of upstream's
 * {@code VehicleModelInfoCapability}.
 *
 * <p>Upstream hangs a capability on <b>every</b> entity that is neither a player nor a projectile
 * ({@code event/CapabilityEvent.java:78-81}), writes the <em>owner's</em> model hash into it when the owner starts
 * riding ({@code mixin/ServerPlayerMixin.java:14-29} → {@code CapabilityEvent.onVehicleSetModel:244-258}), and never
 * clears it: {@code capability/VehicleModelInfoCapability.java:7-34} has only {@code update}/{@code copyFrom}/getters,
 * and a repository-wide search for {@code stopRiding|dismount|clearVehicle} finds no clear path. A vehicle therefore
 * keeps the model of whoever owned it, after they dismount. This table is that fact.
 *
 * <p>Entity id, not UUID, is the key, for the reason {@link NPCData} documents at its own lines 13-17: 1.7.10 gives
 * non-player entities a fresh random {@code entityUniqueID} on each side and never syncs it, while the tracked entity
 * id travels with the spawn packet and is identical on both sides. Entity ids are allocated from one JVM-wide counter
 * and never reused, so a stale entry can never point at a different entity - which is also why entries are not pruned
 * on dismount or death: the model is meant to outlive both.
 *
 * <p>The value carries the owner as well, because upstream needs it twice more: a client that starts tracking the
 * vehicle is resent the state ({@code EntityTrackerEntry:456} → {@code PlayerEvent.StartTracking}), and when the owner
 * changes model while riding the state is rewritten ({@code CapabilityEvent.java:198-211}).
 *
 * <p>The backing map is concurrent because a client writes it from a packet thread while the render thread reads it,
 * exactly like {@link NPCData}.
 */
public final class VehicleModelStates {

    /** One vehicle's override: the owner's model id at the moment they mounted, and who that owner was. */
    public static final class State {

        private final ResourceLocation modelId;
        private final UUID ownerId;

        State(ResourceLocation modelId, @Nullable UUID ownerId) {
            this.modelId = modelId;
            this.ownerId = ownerId;
        }

        public ResourceLocation getModelId() {
            return modelId;
        }

        @Nullable
        public UUID getOwnerId() {
            return ownerId;
        }
    }

    private static final Map<Integer, State> DATA = new ConcurrentHashMap<>();

    private VehicleModelStates() {}

    public static void put(int entityId, ResourceLocation modelId, @Nullable UUID ownerId) {
        if (modelId == null) {
            return;
        }
        DATA.put(entityId, new State(modelId, ownerId));
    }

    public static void put(Entity vehicle, ResourceLocation modelId, @Nullable UUID ownerId) {
        if (vehicle != null) {
            put(vehicle.getEntityId(), modelId, ownerId);
        }
    }

    @Nullable
    public static State get(int entityId) {
        return DATA.get(entityId);
    }

    @Nullable
    public static State get(Entity vehicle) {
        return vehicle == null ? null : get(vehicle.getEntityId());
    }

    public static boolean contains(int entityId) {
        return DATA.containsKey(entityId);
    }

    public static void remove(int entityId) {
        DATA.remove(entityId);
    }

    public static void clear() {
        DATA.clear();
    }

    public static int size() {
        return DATA.size();
    }
}
