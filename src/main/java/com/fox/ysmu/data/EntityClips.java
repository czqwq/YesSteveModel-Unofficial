package com.fox.ysmu.data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

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
    /** entityId -> 最后一次被宿主推送的时间,供 D-A1 的裁剪使用。 */
    private static final Map<Integer, Long> TOUCHED = new ConcurrentHashMap<>();

    private EntityClips() {}

    public static void put(Entity entity, String name, ILoopType loopType) {
        if (entity == null || name == null || name.isEmpty()) {
            return;
        }
        CLIPS.put(entity.getEntityId(), new Clip(name, loopType));
        TOUCHED.put(entity.getEntityId(), System.currentTimeMillis());
    }

    /** The pushed clip, or {@code null} when the host has not pushed one for this entity. */
    public static Clip get(Entity entity) {
        return entity == null ? null : CLIPS.get(entity.getEntityId());
    }

    public static void clear(Entity entity) {
        if (entity != null) {
            CLIPS.remove(entity.getEntityId());
            TOUCHED.remove(entity.getEntityId());
        }
    }

    public static void clear() {
        CLIPS.clear();
        TOUCHED.clear();
    }

    public static int size() {
        return CLIPS.size();
    }

    /**
     * D-A1:按"实体是否还存在于当前世界"裁剪推送表,与 {@link NPCData#retainAll(long, IntPredicate, long)}
     * 同形。宿主只推一次(例如只在 clip 变化时推)时 TTL 会误删仍然有效的 clip,所以这里用存活集合判定,
     * {@code graceMillis} 只用来保护"刚推过、实体暂时不在已加载区块里"的条目。
     *
     * @param now        当前时间(毫秒)
     * @param alive      判断实体 id 是否还存在于某个已加载世界
     * @param graceMillis 推送后多久之内不做存活判定
     * @return 被裁掉的条目数
     */
    public static int retainAll(long now, IntPredicate alive, long graceMillis) {
        int removed = 0;
        for (Integer entityId : CLIPS.keySet()) {
            Long touched = TOUCHED.get(entityId);
            if (touched != null && now - touched < graceMillis) {
                continue;
            }
            if (alive != null && !alive.test(entityId)) {
                CLIPS.remove(entityId);
                TOUCHED.remove(entityId);
                removed++;
            }
        }
        return removed;
    }
}
