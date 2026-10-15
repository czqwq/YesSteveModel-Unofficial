package com.fox.ysmu.network.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.eep.ExtendedRoamingVariables;
import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: the roaming variables the local player just changed, for one model.
 * <p>
 * A <em>roaming variable</em> is a named float belonging to one model of one player, read by packs as
 * {@code v.roaming.<name>} and written by the model's {@code 模型设置} panel. Upstream reports the same change
 * through {@code PlayerStateReport.roaming} (a {@code RoamingState} of a model key and a variable list) and treats the
 * server as the authority; this message carries that payload over 1.7.10's {@code SimpleNetworkWrapper} instead of
 * protobuf.
 * <p>
 * Only deltas are sent, never a full replacement: the only writer is the settings panel, which changes one variable at
 * a time. See the Decision Log in {@code .agent/phase15-roaming-variables.md}.
 */
public class C2SRoamingChanges implements IMessage {

    private int modelKey;
    private Map<String, Double> changes = Collections.emptyMap();

    public C2SRoamingChanges() {}

    private C2SRoamingChanges(int modelKey, Map<String, Double> changes) {
        this.modelKey = modelKey;
        this.changes = changes;
    }

    /** One message per model; an empty change map produces a message that the server ignores. */
    public static C2SRoamingChanges of(int modelKey, @Nullable Map<String, Double> changes) {
        return new C2SRoamingChanges(modelKey, changes == null ? Collections.emptyMap() : new HashMap<>(changes));
    }

    public int getModelKey() {
        return modelKey;
    }

    public Map<String, Double> getChanges() {
        return changes;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: a decoder that throws is turned into a disconnect by FMLProxyPacket.processPacket, so this body
        // validates every length and gives up quietly instead. Fields are assigned only after a complete parse, so a
        // rejected payload leaves the message in its empty state rather than half filled.
        int parsedKey = 0;
        Map<String, Double> parsedChanges = Collections.emptyMap();
        try {
            if (buf.readableBytes() >= 6) {
                int key = buf.readInt();
                int count = buf.readUnsignedShort();
                if (count > 0 && count <= ModelRoamingLimits.MAX_VARIABLES_PER_MODEL) {
                    Map<String, Double> entries = readEntries(buf, count);
                    if (entries != null) {
                        parsedKey = key;
                        parsedChanges = entries;
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM roaming-change packet", e);
            parsedKey = 0;
            parsedChanges = Collections.emptyMap();
        }
        this.modelKey = parsedKey;
        this.changes = parsedChanges;
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
        buf.writeInt(this.modelKey);
        // Filter first: the count is written before the entries, so skipping an entry after writing the count would
        // desynchronise the decoder.
        Map<String, Double> written = new LinkedHashMap<>();
        if (this.changes != null) {
            for (Map.Entry<String, Double> entry : this.changes.entrySet()) {
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

    public static class Handler implements IMessageHandler<C2SRoamingChanges, IMessage> {

        @Override
        public IMessage onMessage(C2SRoamingChanges message, MessageContext ctx) {
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null || message == null || message.changes.isEmpty()) {
                    return null;
                }
                // The save data is a plain map and the handler runs on the Netty thread, so the write is queued for
                // the server thread rather than racing the tick that serializes it.
                final int modelKey = message.modelKey;
                final Map<String, Double> changes = new HashMap<>(message.changes);
                DeferredWork.server(() -> apply(sender, modelKey, changes));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM roaming-change packet", e);
            }
            return null;
        }

        private void apply(EntityPlayerMP sender, int modelKey, Map<String, Double> changes) {
            try {
                ExtendedRoamingVariables store = ExtendedRoamingVariables.get(sender);
                if (store == null) {
                    return;
                }
                store.apply(modelKey, changes);
                // Echo the authoritative map back to the sender and to everyone tracking them, so a second client
                // sees the same settings and the sender's own copy stops being merely local.
                S2CRoamingState.broadcast(sender, modelKey);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply a YSM roaming change", e);
            }
        }
    }
}
