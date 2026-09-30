package com.fox.ysmu.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.network.NetworkHandler;

/**
 * Fifth API: opens YSM's in-game model selection GUI for an arbitrary entity.
 * <p>
 * A companion mod calls {@link #openModelGui} on the logical server when the player asks to change an entity's
 * model. That both asks the client to open the screen and records a one-entity selection grant, so the ordinary
 * player may then change that entity without operator rights; every other client still needs permission level 2.
 * <p>
 * Grants are scoped to one entity, expire after {@link #GRANT_TTL_MILLIS}, and can be revoked early through
 * {@link #revokeSelectionGrant}. They are also dropped when the player logs out or the world unloads.
 */
public final class ModelGuiApi {

    /** How long an opened picker may keep changing the entity it was opened for. */
    private static final long GRANT_TTL_MILLIS = 5L * 60L * 1000L;

    /** How far the viewer may be from the entity when the picker is opened. */
    private static final double MAX_OPEN_DISTANCE = 128.0D;

    private static final Map<UUID, Map<Integer, Long>> SELECTION_GRANTS = new ConcurrentHashMap<>();

    private ModelGuiApi() {}

    /**
     * Asks {@code viewer}'s client to open the model selection GUI for {@code target}, and grants that viewer
     * temporary permission to change it.
     *
     * @return {@code true} when the target is reachable and the request was sent; {@code false} when the entity
     *         is dead, in another dimension or too far away, so the caller can tell the player why.
     */
    public static boolean openModelGui(EntityPlayerMP viewer, Entity target) {
        if (!isReachable(viewer, target)) {
            return false;
        }
        SELECTION_GRANTS.computeIfAbsent(viewer.getUniqueID(), id -> new ConcurrentHashMap<>())
            .put(target.getEntityId(), System.currentTimeMillis() + GRANT_TTL_MILLIS);
        NetworkHandler.sendOpenModelGui(viewer, target);
        return true;
    }

    /** Whether the player was granted the right to change this entity's model through the GUI. */
    public static boolean isSelectionGranted(EntityPlayer viewer, Entity target) {
        if (viewer == null || target == null) {
            return false;
        }
        Map<Integer, Long> grants = SELECTION_GRANTS.get(viewer.getUniqueID());
        if (grants == null) {
            return false;
        }
        Long expiry = grants.get(target.getEntityId());
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            grants.remove(target.getEntityId());
            return false;
        }
        return true;
    }

    /** Drops a grant early, for example when the companion mod sees its picker close. */
    public static void revokeSelectionGrant(EntityPlayer viewer, Entity target) {
        if (viewer != null && target != null) {
            revokeSelectionGrant(viewer, target.getEntityId());
        }
    }

    /** Drops a grant early by the entity id the picker was opened for. */
    public static void revokeSelectionGrant(EntityPlayer viewer, int entityId) {
        if (viewer == null) {
            return;
        }
        Map<Integer, Long> grants = SELECTION_GRANTS.get(viewer.getUniqueID());
        if (grants != null) {
            grants.remove(entityId);
        }
    }

    public static void clearSelectionGrants(EntityPlayer viewer) {
        if (viewer != null) {
            SELECTION_GRANTS.remove(viewer.getUniqueID());
        }
    }

    public static void clearAllSelectionGrants() {
        SELECTION_GRANTS.clear();
    }

    private static boolean isReachable(EntityPlayerMP viewer, Entity target) {
        if (viewer == null || target == null || target.isDead) {
            return false;
        }
        if (target.worldObj != viewer.worldObj) {
            return false;
        }
        return viewer.getDistanceSqToEntity(target) <= MAX_OPEN_DISTANCE * MAX_OPEN_DISTANCE;
    }
}
