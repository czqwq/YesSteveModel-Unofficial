package com.fox.ysmu.network.message;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.api.EntityModelApi;
import com.fox.ysmu.api.ModelGuiApi;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: the model GUI picked a model and texture for an entity.
 * <p>
 * {@link #npcId} is the entity id the GUI was opened for. The handler resolves it on the server, applies the
 * registry change and lets the server API broadcast the result; it resolves the entity itself rather than
 * trusting a client-supplied identity. The work is deferred to the server thread because 1.7.10 runs packet
 * handlers on the Netty thread.
 * <p>
 * A payload with both strings empty is an explicit "clear the override". A payload that is non-empty but
 * malformed is rejected instead of clearing, so a bad picker value cannot silently wipe an entity's model.
 */
public class SetNpcModelAndTexture implements IMessage {

    private String modelId;
    private String selectTexture;
    private int npcId;

    public SetNpcModelAndTexture() {}

    public SetNpcModelAndTexture(net.minecraft.util.ResourceLocation modelId,
        net.minecraft.util.ResourceLocation selectTexture, int npcId) {
        this.modelId = modelId.toString();
        this.selectTexture = selectTexture.toString();
        this.npcId = npcId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = ByteBufUtils.readUTF8String(buf);
        this.selectTexture = ByteBufUtils.readUTF8String(buf);
        this.npcId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        ByteBufUtils.writeUTF8String(buf, this.selectTexture);
        buf.writeInt(this.npcId);
    }

    public static class Handler implements IMessageHandler<SetNpcModelAndTexture, IMessage> {

        @Override
        public IMessage onMessage(SetNpcModelAndTexture message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender == null) {
                return null;
            }
            String model = message.modelId;
            String texture = message.selectTexture;
            int npcId = message.npcId;
            DeferredWork.server(() -> apply(sender, npcId, model, texture));
            return null;
        }

        private static void apply(EntityPlayerMP sender, int npcId, String model, String texture) {
            if (npcId < 0) {
                return;
            }
            Entity entity = sender.worldObj.getEntityByID(npcId);
            if (entity == null) {
                return;
            }
            // The GUI may always target the sender's own player entry, and may target an entity the server
            // handed to this player through ModelGuiApi. Re-skinning anything else needs the permission level
            // a command would, so one player cannot repaint another player's NPCs.
            if (entity != sender && !ModelGuiApi.isSelectionGranted(sender, entity)
                && !sender.canCommandSenderUseCommand(2, "ysmu")) {
                return;
            }
            if (isBlank(model) && isBlank(texture)) {
                EntityModelApi.clearEntityModel(entity);
                return;
            }
            ResourceLocation modelId = parse(model);
            ResourceLocation textureId = parse(texture);
            if (modelId == null || textureId == null) {
                ysmu.LOG.warn(
                    "Rejected malformed SetNpcModelAndTexture from {} for entity id {} (model '{}', texture '{}')",
                    sender.getCommandSenderName(),
                    npcId,
                    model,
                    texture);
                return;
            }
            EntityModelApi.setEntityModel(entity, modelId, textureId);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isEmpty();
        }

        private static ResourceLocation parse(String value) {
            if (isBlank(value)) {
                return null;
            }
            try {
                return new ResourceLocation(value);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
