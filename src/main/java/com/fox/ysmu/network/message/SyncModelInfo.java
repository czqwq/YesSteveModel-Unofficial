package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SyncModelInfo implements IMessage {

    /** N-03: a remote player can show up a little later, so retry for at most 60 ticks (~3 s). */
    private static final int MAX_APPLY_ATTEMPTS = 60;

    private int entityId;
    private NBTTagCompound modelInfoNBT;

    public SyncModelInfo() {}

    public SyncModelInfo(int entityId, ExtendedModelInfo modelInfo) {
        this.entityId = entityId;
        this.modelInfoNBT = new NBTTagCompound();
        if (modelInfo != null) {
            modelInfo.saveNBTData(this.modelInfoNBT);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.modelInfoNBT = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        ByteBufUtils.writeTag(buf, this.modelInfoNBT);
    }

    public static class Handler implements IMessageHandler<SyncModelInfo, IMessage> {

        @Override
        public IMessage onMessage(SyncModelInfo message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                try {
                    handleEEP(message);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to apply synced YSM model info for entity " + message.entityId, e);
                }
            }
            return null;
        }

        /**
         * N-03: both the entity lookup and {@link ExtendedModelInfo#loadNBTData} must happen on the client thread.
         * This used to run on the shared pool: a worker read {@code world.loadedEntityList} and rewrote four EEP
         * fields that the renderer reads on the client thread without synchronisation, so a frame could observe a
         * half-applied state (new model id, old texture). The wait is driven by the client tick, which also keeps
         * the shared pool free.
         */
        private void handleEEP(SyncModelInfo message) {
            scheduleApply(message, 0);
        }

        private static void scheduleApply(SyncModelInfo message, int attempt) {
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.theWorld == null) {
                        return;
                    }
                    try {
                        Entity entity = mc.theWorld.getEntityByID(message.entityId);
                        if (entity == null) {
                            if (attempt < MAX_APPLY_ATTEMPTS) {
                                scheduleApply(message, attempt + 1);
                            }
                            return;
                        }
                        if (entity instanceof EntityPlayer player) {
                            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
                            if (eep != null) {
                                eep.loadNBTData(message.modelInfoNBT);
                            }
                        }
                    } catch (Exception e) {
                        ysmu.LOG.warn(
                            "Failed to apply synced YSM model info for entity " + message.entityId,
                            e);
                    }
                });
        }
    }
}
