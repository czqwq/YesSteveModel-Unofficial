package com.fox.ysmu.network.message;

import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.google.common.collect.Maps;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the full entity model registry, sent once when a player logs in.
 * <p>
 * The map is keyed by tracked entity id and carries a named {@link EntityModelData} value. The decode path
 * caps the advertised size so a malformed packet cannot force a huge allocation.
 */
public class SyncNpcDataMessage implements IMessage {

    private static final int MAX_ENTRIES = 4096;

    private Map<Integer, EntityModelData> data;

    public SyncNpcDataMessage() {}

    public SyncNpcDataMessage(Map<Integer, EntityModelData> data) {
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.data = Maps.newHashMap();
        int size = buf.readInt();
        if (size < 0 || size > MAX_ENTRIES) {
            return;
        }
        for (int i = 0; i < size; i++) {
            int entityId = buf.readInt();
            ResourceLocation modelId = new ResourceLocation(ByteBufUtils.readUTF8String(buf));
            ResourceLocation textureId = new ResourceLocation(ByteBufUtils.readUTF8String(buf));
            this.data.put(entityId, new EntityModelData(modelId, textureId));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int size = this.data == null ? 0 : this.data.size();
        buf.writeInt(size);
        if (size == 0) {
            return;
        }
        for (Map.Entry<Integer, EntityModelData> entry : this.data.entrySet()) {
            EntityModelData value = entry.getValue();
            buf.writeInt(entry.getKey());
            ByteBufUtils.writeUTF8String(buf, value.getModelId().toString());
            ByteBufUtils.writeUTF8String(buf, value.getTextureId().toString());
        }
    }

    public static class Handler implements IMessageHandler<SyncNpcDataMessage, IMessage> {

        @Override
        public IMessage onMessage(SyncNpcDataMessage message, MessageContext ctx) {
            // The registry is independent of the local player, so it can be applied before the world is ready;
            // entities that have not spawned yet simply pick up their entry when they render.
            NPCData.addAll(message.data);
            return null;
        }
    }
}
