package com.fox.ysmu.network.message;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.NPCData;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: one entity's model override changed.
 * <p>
 * The key is the tracked entity id (see {@link NPCData} for why it is not a UUID). An empty model or texture
 * string means "remove the override", which is how an entity is cleaned up when it dies; keeping the two
 * strings preserves a fixed payload shape.
 */
public class UpdateNpcDataMessage implements IMessage {

    private int entityId;
    private ResourceLocation modelId;
    private ResourceLocation textureId;

    public UpdateNpcDataMessage() {}

    public UpdateNpcDataMessage(int entityId, ResourceLocation modelId, ResourceLocation textureId) {
        this.entityId = entityId;
        this.modelId = modelId;
        this.textureId = textureId;
    }

    /** Builds a message that clears the entity's override on the client. */
    public static UpdateNpcDataMessage removal(int entityId) {
        return new UpdateNpcDataMessage(entityId, null, null);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        String model = ByteBufUtils.readUTF8String(buf);
        String texture = ByteBufUtils.readUTF8String(buf);
        this.modelId = model.isEmpty() ? null : new ResourceLocation(model);
        this.textureId = texture.isEmpty() ? null : new ResourceLocation(texture);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        ByteBufUtils.writeUTF8String(buf, this.modelId == null ? "" : this.modelId.toString());
        ByteBufUtils.writeUTF8String(buf, this.textureId == null ? "" : this.textureId.toString());
    }

    public static class Handler implements IMessageHandler<UpdateNpcDataMessage, IMessage> {

        @Override
        public IMessage onMessage(UpdateNpcDataMessage message, MessageContext ctx) {
            if (message.modelId == null || message.textureId == null) {
                NPCData.remove(message.entityId);
            } else {
                NPCData.put(message.entityId, message.modelId, message.textureId);
            }
            return null;
        }
    }
}
