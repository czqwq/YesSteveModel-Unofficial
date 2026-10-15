package com.fox.ysmu.network.message;

import java.util.Collections;
import java.util.Set;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.ysmu;
import com.google.common.collect.Sets;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SyncStarModels implements IMessage {

    private Set<ResourceLocation> starModels;

    public SyncStarModels() {}

    public SyncStarModels(Set<ResourceLocation> starModels) {
        this.starModels = starModels;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-06/N-10: the size comes from the peer. Compare it with the readable bytes before allocating (each
        // entry needs at least a two-byte length prefix) and soft-fail instead of throwing.
        try {
            int size = buf.readInt();
            if (size < 0 || size > buf.readableBytes()) {
                ysmu.LOG.warn(
                    "Ignoring malformed YSM star model list: size={}, readableBytes={}",
                    size,
                    buf.readableBytes());
                this.starModels = null;
                return;
            }
            Set<ResourceLocation> parsed = Sets.newHashSet();
            for (int i = 0; i < size; i++) {
                String modelIdStr = ByteBufUtils.readUTF8String(buf);
                parsed.add(new ResourceLocation(modelIdStr));
            }
            this.starModels = parsed;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM star model list: {}", e.toString());
            this.starModels = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        Set<ResourceLocation> safeStarModels = this.starModels == null ? Collections.emptySet() : this.starModels;
        buf.writeInt(safeStarModels.size());
        for (ResourceLocation modelId : safeStarModels) {
            ByteBufUtils.writeUTF8String(buf, modelId.toString());
        }
    }

    public static class Handler implements IMessageHandler<SyncStarModels, IMessage> {

        @Override
        public IMessage onMessage(SyncStarModels message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && message.starModels != null) {
                // N-10: never let a handler body escape into FML's catch(Throwable) -> rejectHandshake.
                try {
                    ysmu.proxy.handleStarModels(message);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to apply synced YSM star models", e);
                }
            }
            return null;
        }
    }

    public Set<ResourceLocation> getStarModels() {
        return starModels;
    }
}
