package com.fox.ysmu.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/**
 * Registry of model/texture overrides keyed by tracked entity id.
 * <p>
 * Entity id, not UUID, is the key: for anything that is not a player 1.7.10 generates a fresh random
 * {@code entityUniqueID} on each side and never syncs it, so a client cannot match a server-side maid by
 * UUID. The tracked entity id is synced with the spawn packet and is identical on both sides, which is what
 * makes {@link Entity}-keyed overrides work for NPCs and companions. Entity ids are allocated from a single
 * JVM-wide counter and are never reused, so a stale entry can never point at a different entity.
 * <p>
 * The server is authoritative: it stores an entry and broadcasts changes to clients, and every joining
 * player receives the full map through {@code SyncNpcDataMessage}. Clients read the same registry during
 * rendering. The backing map is concurrent because a client may still write it from a packet thread while the
 * render thread reads it.
 */
public final class NPCData {

    private static volatile Map<Integer, EntityModelData> DATA = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> TOUCHED = new ConcurrentHashMap<>();

    private NPCData() {}

    public static void clear() {
        DATA.clear();
        TOUCHED.clear();
    }

    public static boolean isEmpty() {
        return DATA.isEmpty();
    }

    public static int size() {
        return DATA.size();
    }

    /**
     * Replaces the whole registry with a full sync.
     * <p>
     * The update happens on the existing map instead of swapping the field, so an override that a companion
     * mod added concurrently is not silently dropped by the snapshot.
     */
    public static void addAll(Map<Integer, EntityModelData> data) {
        DATA.clear();
        TOUCHED.clear();
        if (data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, EntityModelData> entry : data.entrySet()) {
            if (entry.getValue() != null) {
                DATA.put(entry.getKey(), entry.getValue());
                TOUCHED.put(entry.getKey(), now);
            }
        }
    }

    /** A defensive copy of the current registry, used to build a full sync. */
    public static Map<Integer, EntityModelData> snapshot() {
        return new ConcurrentHashMap<>(DATA);
    }

    public static void put(int entityId, ResourceLocation modelId, ResourceLocation textureId) {
        if (modelId == null || textureId == null) {
            return;
        }
        DATA.put(entityId, new EntityModelData(modelId, textureId));
        TOUCHED.put(entityId, System.currentTimeMillis());
    }

    public static void put(int entityId, EntityModelData data) {
        if (data == null) {
            return;
        }
        DATA.put(entityId, data);
        TOUCHED.put(entityId, System.currentTimeMillis());
    }

    public static void put(Entity entity, ResourceLocation modelId, ResourceLocation textureId) {
        if (entity != null) {
            put(entity.getEntityId(), modelId, textureId);
        }
    }

    public static void put(Entity entity, EntityModelData data) {
        if (entity != null) {
            put(entity.getEntityId(), data);
        }
    }

    public static boolean contains(int entityId) {
        return DATA.containsKey(entityId);
    }

    public static boolean contains(Entity entity) {
        return entity != null && contains(entity.getEntityId());
    }

    public static EntityModelData getData(int entityId) {
        return DATA.get(entityId);
    }

    public static EntityModelData getData(Entity entity) {
        return entity == null ? null : getData(entity.getEntityId());
    }

    public static void remove(int entityId) {
        DATA.remove(entityId);
        TOUCHED.remove(entityId);
    }

    public static void remove(Entity entity) {
        if (entity != null) {
            remove(entity.getEntityId());
        }
    }

    /**
     * Drops entries whose entity no longer exists in any world.
     * <p>
     * Entity ids are never reused, so a leaked entry is harmless to rendering and only costs memory. Entries
     * younger than {@code graceMillis} are kept: a tracked entity can be temporarily absent from every loaded
     * world when its chunk is unloaded, and the grace stops that from wiping a still-valid override.
     *
     * @param now         current time in milliseconds
     * @param alive       tests whether an entity id still exists in some loaded world
     * @param graceMillis how long an untracked entry is protected from pruning
     */
    public static void retainAll(long now, IntPredicate alive, long graceMillis) {
        for (Integer entityId : DATA.keySet()) {
            Long touched = TOUCHED.get(entityId);
            if (touched != null && now - touched < graceMillis) {
                continue;
            }
            if (!alive.test(entityId)) {
                DATA.remove(entityId);
                TOUCHED.remove(entityId);
            }
        }
    }
}
