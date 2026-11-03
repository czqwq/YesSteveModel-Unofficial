package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SetStarModel implements IMessage {

    private String modelId;
    private boolean isAdd;

    public SetStarModel() {}

    private SetStarModel(ResourceLocation modelId, boolean isAdd) {
        this.modelId = modelId.toString();
        this.isAdd = isAdd;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: a decoder that throws is turned into a disconnect by FMLProxyPacket.processPacket, so a truncated
        // payload soft-fails into an ignored message (null modelId) instead.
        try {
            this.modelId = ByteBufUtils.readUTF8String(buf);
            this.isAdd = buf.readBoolean();
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM star-model packet: {}", e.toString());
            this.modelId = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        buf.writeBoolean(this.isAdd);
    }

    public static SetStarModel add(ResourceLocation modelId) {
        return new SetStarModel(modelId, true);
    }

    public static SetStarModel remove(ResourceLocation modelId) {
        return new SetStarModel(modelId, false);
    }

    public static class Handler implements IMessageHandler<SetStarModel, IMessage> {

        @Override
        public IMessage onMessage(SetStarModel message, MessageContext ctx) {
            // N-10: a handler that throws is turned into a disconnect by FMLProxyPacket.processPacket ->
            // rejectHandshake, so the body is guarded and a bad packet is only logged.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender != null && message.modelId != null) {
                    // The EEP is a plain HashSet, and the server tick / save path read it; the handler runs on the
                    // Netty thread, so write it from the server thread like the other EEP packets do.
                    DeferredWork.server(() -> handleEEP(message, sender));
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM star-model packet", e);
            }
            return null;
        }

        private void handleEEP(SetStarModel message, EntityPlayerMP sender) {
            ExtendedStarModels eep = ExtendedStarModels.get(sender);
            if (eep == null || message.modelId == null || message.modelId.isEmpty()) {
                // A null/empty id is not a model; storing null would poison the save data (saveNBTData calls
                // toString() on every entry), and no legitimate sender produces one.
                return;
            }
            ResourceLocation modelLoc = new ResourceLocation(message.modelId);
            if (message.isAdd) {
                eep.addModel(modelLoc);
            } else {
                eep.removeModel(modelLoc);
            }
        }
    }
}
