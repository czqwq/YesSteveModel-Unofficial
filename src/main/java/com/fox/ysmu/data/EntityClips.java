package com.fox.ysmu.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;

import software.bernie.geckolib3.core.builder.ILoopType;

/**
 * Animation clips that a host mod resolves itself and pushes for an entity YSMU renders but does not own the
 * animation logic for (Touhou Little Maid's maids).
 * <p>
 * Keyed by tracked entity id rather than by UUID: 1.7.10 generates a random per-side
 * {@code entityUniqueID} for every non-player entity, so a UUID key cannot be matched between the host that
 * resolved the clip and the renderer that plays it. {@link NPCData} keys the same data the same way.
 * <p>
 * Pushed by the host (each frame, or whenever its clip changes) and read by
 * {@code com.fox.ysmu.client.animation.AnimationManager} while it renders a non-player animatable.
 */
public final class EntityClips {

    /** A clip name and how the engine should end it. */
    public static final class Clip {

        private final String name;
        private final ILoopType loopType;

        Clip(String name, ILoopType loopType) {
            this.name = name;
            this.loopType = loopType;
        }

        public String getName() {
            return name;
        }

        public ILoopType getLoopType() {
            return loopType;
        }

        @Override
        public String toString() {
            return name + "(" + loopType + ")";
        }
    }

    // Written from the host's client tick, read from the render thread.
    private static final Map<Integer, Clip> CLIPS = new ConcurrentHashMap<>();

    private EntityClips() {}

    public static void put(Entity entity, String name, ILoopType loopType) {
        if (entity == null || name == null || name.isEmpty()) {
            return;
        }
        CLIPS.put(entity.getEntityId(), new Clip(name, loopType));
    }

    /** The pushed clip, or {@code null} when the host has not pushed one for this entity. */
    public static Clip get(Entity entity) {
        return entity == null ? null : CLIPS.get(entity.getEntityId());
    }

    public static void clear(Entity entity) {
        if (entity != null) {
            CLIPS.remove(entity.getEntityId());
        }
    }

    public static void clear() {
        CLIPS.clear();
    }
}
