package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SetModelAndTexture implements IMessage {

    private String modelId;
    private String selectTexture;

    public SetModelAndTexture() {}

    public SetModelAndTexture(ResourceLocation modelId, ResourceLocation selectTexture) {
        this.modelId = modelId.toString();
        this.selectTexture = selectTexture.toString();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = ByteBufUtils.readUTF8String(buf);
        this.selectTexture = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        ByteBufUtils.writeUTF8String(buf, this.selectTexture);
    }

    public static class Handler implements IMessageHandler<SetModelAndTexture, IMessage> {

        @Override
        public IMessage onMessage(SetModelAndTexture message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender == null) {
                return null;
            }
            String model = message.modelId;
            String texture = message.selectTexture;
            DeferredWork.server(() -> handleEEP(sender, model, texture));
            return null;
        }

        private static void handleEEP(EntityPlayerMP sender, String model, String texture) {
            ExtendedModelInfo modelInfo = ExtendedModelInfo.get(sender);
            if (modelInfo != null) {
                ResourceLocation modelLoc = model.isEmpty() ? null : new ResourceLocation(model);
                ResourceLocation textureLoc = texture.isEmpty() ? null : new ResourceLocation(texture);
                modelInfo.setModelAndTexture(modelLoc, textureLoc);
            }
            // The renderer lets an NPC override win over the player's own selection, so an explicit self
            // selection must drop any stale override for the same player or the picker looks broken.
            if (NPCData.contains(sender)) {
                NPCData.remove(sender);
                NetworkHandler.broadcastNpcDataRemoval(sender, sender.getEntityId());
            }
        }
    }
}
