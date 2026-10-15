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
            scheduleApply(message);
        }

        private static void scheduleApply(SyncModelInfo message) {
            long generation = com.fox.ysmu.client.ClientModelManager.currentGeneration();
            Minecraft.getMinecraft().func_152344_a(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                if (generation != com.fox.ysmu.client.ClientModelManager.currentGeneration() || mc.theWorld == null) {
                    return;
                }
                Entity entity = mc.theWorld.getEntityByID(message.entityId);
                if (entity != null) {
                    applyToEntity(message, entity);
                    return;
                }
                // Reference SyncModelInfo.java:65-79 waits through TickScheduler. In 1.7.10,
                // Minecraft.java:2993-2997 calls client-thread callbacks inline, so recursion burns all attempts now.
                final net.minecraft.client.multiplayer.WorldClient world = mc.theWorld;
                final long deadline = System.currentTimeMillis() + MAX_APPLY_ATTEMPTS * 50L;
                com.fox.ysmu.util.TickScheduler.Handle[] retry = new com.fox.ysmu.util.TickScheduler.Handle[1];
                retry[0] = com.fox.ysmu.util.TickScheduler.client().scheduleEvery(50L, () -> {
                    Minecraft client = Minecraft.getMinecraft();
                    if (client.theWorld != world
                        || generation != com.fox.ysmu.client.ClientModelManager.currentGeneration()) {
                        retry[0].cancel();
                        return;
                    }
                    Entity found = client.theWorld == null ? null : client.theWorld.getEntityByID(message.entityId);
                    if (found == null && client.theWorld != null && System.currentTimeMillis() < deadline) {
                        return;
                    }
                    retry[0].cancel();
                    applyToEntity(message, found);
                });
            });
        }

        private static void applyToEntity(SyncModelInfo message, Entity entity) {
            try {
                if (entity instanceof EntityPlayer player) {
                    ExtendedModelInfo eep = ExtendedModelInfo.get(player);
                    if (eep != null) {
                        eep.loadNBTData(message.modelInfoNBT);
                    }
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply synced YSM model info for entity " + message.entityId, e);
            }
        }
    }
}
