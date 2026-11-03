package com.fox.ysmu.client.animation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import com.fox.ysmu.data.PlayerMotionState;
import com.fox.ysmu.network.message.SyncPlayerMotionState;

/**
 * The client's copy of the player state that packet 11 ({@code SyncPlayerMotionState}) carries: on-ground/flying
 * flags (1.7.10 does not sync {@code onGround} for a remote player), plus the food level and experience level that
 * 1.7.10 only sends to the player themself.
 *
 * <p><b>Why the last two are here.</b> {@code S06PacketUpdateHealth} (food) and {@code S1FPacketSetExperience} are
 * addressed to their own player; a remote player's client-side {@code FoodStats}/{@code experienceLevel} never change.
 * Upstream keeps both as synced state on its server-driven tracker
 * ({@code capability/ServerDrivenPlayerPropertiesTracker.java:32-36,59-78,151-183}) and its bindings read them from
 * the tracker, so a remote model's {@code ysm.food_level} was the real value there and the stale default (20) here;
 * {@code wine_fox}'s hungry poses read that name. The getters below therefore fall back to the entity's own value when
 * this client has no synced entry yet (old server, or the packet has not arrived): that is exactly what the pre-sync
 * path read, so behaviour without a packet is unchanged.
 */
public final class RemotePlayerMotionStates {

    /** One synced packet's contents. */
    private static final class State {

        private final byte flags;
        private final int foodLevel;
        private final int experienceLevel;

        private State(byte flags, int foodLevel, int experienceLevel) {
            this.flags = flags;
            this.foodLevel = foodLevel;
            this.experienceLevel = experienceLevel;
        }
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private RemotePlayerMotionStates() {}

    /**
     * The one "is this the local player" test every reader of the synced values shares - a local player has the
     * authoritative entity state ({@code thePlayer}) and must never take the remote path.
     */
    public static boolean isRemotePlayer(EntityPlayer player) {
        return player != null && player != Minecraft.getMinecraft().thePlayer;
    }

    public static void update(UUID playerId, byte flags, int foodLevel, int experienceLevel) {
        if (playerId != null) {
            STATES.put(playerId, new State(flags, foodLevel, experienceLevel));
        }
    }

    public static void clear() {
        STATES.clear();
    }

    public static boolean isOnGround(EntityPlayer player) {
        State state = getState(player);
        return state != null ? PlayerMotionState.isOnGround(state.flags) : player.onGround;
    }

    public static boolean isFlying(EntityPlayer player) {
        State state = getState(player);
        return state != null ? PlayerMotionState.isFlying(state.flags) : player.capabilities.isFlying;
    }

    /**
     * The player's synced food level, or the entity's own value when nothing has been received - the latter is what
     * the pre-sync path read, and 1.7.10 leaves it at {@code SyncPlayerMotionState.DEFAULT_FOOD_LEVEL} (20) for a
     * remote player.
     */
    public static int foodLevel(EntityPlayer player) {
        State state = getState(player);
        if (state != null) {
            return state.foodLevel;
        }
        return player != null ? player.getFoodStats()
            .getFoodLevel() : SyncPlayerMotionState.DEFAULT_FOOD_LEVEL;
    }

    /** The player's synced experience level; falls back to the entity's own value (0 for a remote player). */
    public static int experienceLevel(EntityPlayer player) {
        State state = getState(player);
        if (state != null) {
            return state.experienceLevel;
        }
        return player != null ? player.experienceLevel : 0;
    }

    private static State getState(EntityPlayer player) {
        return player == null ? null : STATES.get(player.getUniqueID());
    }
}
