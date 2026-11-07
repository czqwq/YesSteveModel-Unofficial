package com.fox.ysmu.client.compat.tacz;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.EntityLivingBase;

/**
 * Upstream's {@code tacGunAnimationNeedReload} flag, which it stores on the player's animatable capability
 * ({@code PlayerAnimatableCapabilityProvider.CAP} in {@code client/compat/tacz/TacEvent.java:18-20}). 1.7.10 has no
 * capability system for this (the port's {@code IExtendedEntityProperties} are player/NBT state and the flag is a
 * transient client-side hint), so it is a per-entity map here - the shape the TacZ handoff document suggests
 * ({@code docs/YSM_COMPAT_HANDOFF.md} §4.7: "1.7.10 用 IExtendedEntityProperties 或 YSM 自定义管理器").
 *
 * <p>
 * Weak keys: the flag only lives from a gun event to the next render of that entity, and an entity leaving the world
 * must not be pinned by it. Events and rendering both run on the client thread in 1.7.10, so the map needs no lock.
 */
public final class TacGunAnimationState {

    private static final Map<EntityLivingBase, Boolean> NEED_RELOAD = Collections
        .synchronizedMap(new WeakHashMap<EntityLivingBase, Boolean>());

    private TacGunAnimationState() {}

    public static void markNeedReload(EntityLivingBase entity) {
        if (entity != null) {
            NEED_RELOAD.put(entity, Boolean.TRUE);
        }
    }

    /** Reads and clears the flag, like upstream's {@code isTacGunAnimationNeedReload()} + {@code set...(false)} pair. */
    public static boolean consumeNeedReload(EntityLivingBase entity) {
        return entity != null && NEED_RELOAD.remove(entity) != null;
    }

    public static void clear() {
        NEED_RELOAD.clear();
    }
}
