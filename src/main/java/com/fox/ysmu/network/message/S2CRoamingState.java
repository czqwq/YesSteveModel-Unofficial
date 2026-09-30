package com.fox.ysmu.network.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.eep.ExtendedRoamingVariables;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: one player's roaming variables for one model, as the authority holds them.
 * <p>
 * A <em>roaming variable</em> is a named float belonging to one model of one player, read by packs as
 * {@code v.roaming.<name>}. Upstream delivers the same payload as {@code PlayerStateUpdate.roaming}, both to the
 * player who changed something and to every client tracking them, which is what makes a change visible to other
 * players rather than only local. The whole namespace travels, so a receiver adopts exactly what the server holds
 * instead of merging into a copy that may have drifted.
 */
public class S2CRoamingState implements IMessage {

    private UUID playerId;
    private int modelKey;
    private Map<String, Double> values = Collections.emptyMap();

    public S2CRoamingState() {}

    private S2CRoamingState(UUID playerId, int modelKey, Map<String, Double> values) {
        this.playerId = playerId;
        this.modelKey = modelKey;
        this.values = values;
    }

    /** Builds a state message; the server uses {@link #broadcast} instead, this exists for tests and callers. */
    public static S2CRoamingState of(UUID playerId, int modelKey, @Nullable Map<String, Double> values) {
        return new S2CRoamingState(
            playerId,
            modelKey,
            values == null ? Collections.emptyMap() : new HashMap<>(values));
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getModelKey() {
        return modelKey;
    }

    public Map<String, Double> getValues() {
        return values;
    }

    /**
     * Sends the server's current values for one model of one player to that player and to everyone tracking them.
     * <p>
     * Call on the server thread: it reads the player's save data.
     */
    public static void broadcast(EntityPlayerMP player, int modelKey) {
        if (player == null) {
            return;
        }
        ExtendedRoamingVariables store = ExtendedRoamingVariables.get(player);
        if (store == null) {
            return;
        }
        Map<String, Double> snapshot = new HashMap<>();
        for (Map.Entry<String, Float> entry : store.values(modelKey)
            .entrySet()) {
            snapshot.put(entry.getKey(), (double) entry.getValue());
        }
        S2CRoamingState message = new S2CRoamingState(player.getUniqueID(), modelKey, snapshot);
        // One call covers both halves: the sender is at their own position, so the around-target includes them, and
        // every client tracking them adopts the same authoritative map.
        NetworkHandler.sendToTrackingPlayers(message, player, 128.0d);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: see C2SRoamingChanges#fromBytes - validate, never throw, and assign only after a complete parse.
        UUID parsedPlayer = null;
        int parsedKey = 0;
        Map<String, Double> parsedValues = Collections.emptyMap();
        try {
            if (buf.readableBytes() >= 22) {
                UUID playerId = new UUID(buf.readLong(), buf.readLong());
                int key = buf.readInt();
                int count = buf.readUnsignedShort();
                if (count > 0 && count <= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                    Map<String, Double> entries = readEntries(buf, count);
                    if (entries != null) {
                        parsedPlayer = playerId;
                        parsedKey = key;
                        parsedValues = entries;
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM roaming-state packet", e);
            parsedPlayer = null;
            parsedKey = 0;
            parsedValues = Collections.emptyMap();
        }
        this.playerId = parsedPlayer;
        this.modelKey = parsedKey;
        this.values = parsedValues;
    }

    /** Reads {@code count} name/value pairs, or {@code null} when the buffer runs out before the last one. */
    @Nullable
    private static Map<String, Double> readEntries(ByteBuf buf, int count) {
        Map<String, Double> parsed = new HashMap<>();
        for (int i = 0; i < count; i++) {
            if (buf.readableBytes() < 2) {
                return null;
            }
            String name = ByteBufUtils.readUTF8String(buf);
            if (buf.readableBytes() < 8) {
                return null;
            }
            double value = buf.readDouble();
            if (ModelRoamingLimits.isAcceptableName(name) && ModelRoamingLimits.isAcceptableValue(value)) {
                parsed.put(name, value);
            }
        }
        return parsed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        UUID id = this.playerId == null ? new UUID(0L, 0L) : this.playerId;
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
        buf.writeInt(this.modelKey);
        // Filter first: the count precedes the entries, so a skipped entry would desynchronise the decoder.
        Map<String, Double> written = new LinkedHashMap<>();
        if (this.values != null) {
            for (Map.Entry<String, Double> entry : this.values.entrySet()) {
                if (!ModelRoamingLimits.isAcceptableName(entry.getKey()) || entry.getValue() == null
                    || !ModelRoamingLimits.isAcceptableValue(entry.getValue())) {
                    continue;
                }
                written.put(entry.getKey(), entry.getValue());
                if (written.size() >= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                    break;
                }
            }
        }
        buf.writeShort(written.size());
        for (Map.Entry<String, Double> entry : written.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, entry.getKey());
            buf.writeDouble(entry.getValue());
        }
    }

    public static class Handler implements IMessageHandler<S2CRoamingState, IMessage> {

        @Override
        public IMessage onMessage(S2CRoamingState message, MessageContext ctx) {
            try {
                if (message == null || message.playerId == null) {
                    return null;
                }
                final UUID playerId = message.playerId;
                final int modelKey = message.modelKey;
                final Map<String, Double> values = new HashMap<>(message.values);
                // Client state and the Molang scope are both touched from the client thread.
                DeferredWork.client(() -> apply(playerId, modelKey, values));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM roaming-state packet", e);
            }
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void apply(UUID playerId, int modelKey, Map<String, Double> values) {
            ClientRoamingStore.acceptState(playerId, modelKey, values);
            EntityPlayer player = findLocalPlayer(playerId);
            if (player != null) {
                // The world render reads its roaming values out of the engine's store, so the received copy has to
                // land there as well as in our own store.
                software.bernie.geckolib3.core.molang.RemoteAnimationVariables.putMap(player, values);
            }
        }

        @SideOnly(Side.CLIENT)
        @Nullable
        private EntityPlayer findLocalPlayer(UUID playerId) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.theWorld == null) {
                return null;
            }
            List<?> players = mc.theWorld.playerEntities;
            for (Object candidate : players) {
                if (candidate instanceof EntityPlayer entity && playerId.equals(entity.getUniqueID())) {
                    return entity;
                }
            }
            return null;
        }
    }
}
