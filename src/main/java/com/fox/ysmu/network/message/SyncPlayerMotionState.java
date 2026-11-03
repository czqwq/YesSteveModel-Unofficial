package com.fox.ysmu.network.message;

import java.util.UUID;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: one player's movement/gameplay state that 1.7.10 does not sync to other players.
 *
 * <p>Wire layout (clientbound id 11, {@code NETWORK_PROTOCOL} 11 and later):
 *
 * <pre>
 *   UUID  playerId            2 x long
 *   byte  flags               0x01 onGround, 0x02 flying (see {@link com.fox.ysmu.data.PlayerMotionState})
 *   byte  foodLevel           unsigned, saturating at 255; vanilla range 0..20
 *   int   experienceLevel     full int, the server's {@code EntityPlayer.experienceLevel}
 * </pre>
 *
 * <p><b>Why the last two exist.</b> 1.7.10 sends {@code S06PacketUpdateHealth} (food/saturation) and
 * {@code S1FPacketSetExperience} only to the player themself, and a remote player's client-side {@code FoodStats} /
 * {@code experienceLevel} are never updated - so {@code ysm.food_level} answered the client-side default (20) and
 * {@code query.player_level} answered 0 for every other player. Upstream carries both on its server-driven state
 * tracker ({@code capability/ServerDrivenPlayerPropertiesTracker.java:32-36,59-78}, published in
 * {@code populateFull:151-183}) and its bindings read them from there; this packet is the 1.7.10 channel for the same
 * two facts. The built-in {@code wine_fox} models read {@code ysm.food_level} for their hungry poses, so without it a
 * remote player never looked hungry.
 *
 * <p><b>Flags stay a byte and keep their bit meanings</b> so the only change to the layout is the appended tail; the
 * protocol version still had to be bumped because every peer must reject a mismatched partner before decoding
 * (1.7.10's {@code SimpleNetworkWrapper} does no negotiation of its own; see {@code NetworkHandler#NETWORK_PROTOCOL}).
 *
 * <p>The decoder follows the repository's N-10 soft-fail rule: a truncated payload sets {@link #isValid()} false and
 * is dropped by the handler instead of throwing (a decode-time exception is turned into a disconnect by
 * {@code FMLProxyPacket.processPacket}).
 */
public class SyncPlayerMotionState implements IMessage {

    /** The food level a receiver assumes before the first packet: vanilla max, and what 1.7.10 leaves on a remote
     *  player's client-side {@code FoodStats}. */
    public static final int DEFAULT_FOOD_LEVEL = 20;
    /** {@code foodLevel} is written as one unsigned byte; anything above this saturates (no vanilla source does). */
    public static final int MAX_FOOD_LEVEL = 255;

    private UUID playerId;
    private byte flags;
    private int foodLevel = DEFAULT_FOOD_LEVEL;
    private int experienceLevel;
    /** false when the payload was truncated/malformed; the handler must ignore the message then. */
    private boolean valid = true;

    public SyncPlayerMotionState() {}

    public SyncPlayerMotionState(UUID playerId, byte flags, int foodLevel, int experienceLevel) {
        this.playerId = playerId;
        this.flags = flags;
        this.foodLevel = foodLevel;
        this.experienceLevel = experienceLevel;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: a decoder that throws is turned into a disconnect by FMLProxyPacket.processPacket, so a truncated
        // payload is dropped (valid=false) rather than allowed to throw.
        try {
            UUID parsedPlayer = new UUID(buf.readLong(), buf.readLong());
            byte parsedFlags = buf.readByte();
            int parsedFood = buf.readUnsignedByte();
            int parsedExperience = buf.readInt();
            this.playerId = parsedPlayer;
            this.flags = parsedFlags;
            this.foodLevel = parsedFood;
            this.experienceLevel = parsedExperience;
            this.valid = true;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM player motion-state packet: {}", e.toString());
            this.valid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        UUID id = this.playerId == null ? new UUID(0L, 0L) : this.playerId;
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
        buf.writeByte(this.flags);
        buf.writeByte(clampFoodLevel(this.foodLevel));
        buf.writeInt(this.experienceLevel);
    }

    /** Saturating encode for the one-byte field: a negative/malformed value can never wrap into a plausible level. */
    private static int clampFoodLevel(int foodLevel) {
        if (foodLevel < 0) {
            return 0;
        }
        return Math.min(foodLevel, MAX_FOOD_LEVEL);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public byte getFlags() {
        return flags;
    }

    public int getFoodLevel() {
        return foodLevel;
    }

    public int getExperienceLevel() {
        return experienceLevel;
    }

    public boolean isValid() {
        return valid;
    }

    public static class Handler implements IMessageHandler<SyncPlayerMotionState, IMessage> {

        @Override
        public IMessage onMessage(SyncPlayerMotionState message, MessageContext ctx) {
            // N-10: never let a handler body escape into FML's catch(Throwable) -> rejectHandshake.
            try {
                if (ctx.side == Side.CLIENT && message != null && message.isValid() && message.getPlayerId() != null) {
                    ysmu.proxy.handlePlayerMotionState(message);
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply synced YSM player motion state", e);
            }
            return null;
        }
    }
}
