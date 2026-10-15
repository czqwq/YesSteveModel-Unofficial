package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * A-16: the server's echo of a {@code ysm.sync(...)} call, delivered to every client that can see the entity. Running
 * the model's {@code @sync} script with these values is what makes a pack's custom event reach all viewers
 * (upstream: {@code network/forge/ControlHandler.java:160-172} → {@code CustomPlayerEntity#molangSync}).
 */
public class S2CMolangSync implements IMessage {

    private int entityId;
    private double[] values = new double[0];

    public S2CMolangSync() {}

    public S2CMolangSync(int entityId, double[] values) {
        this.entityId = entityId;
        this.values = values;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        int count = Math.min(Math.max(buf.readInt(), 0), C2SMolangSync.MAX_VALUES);
        values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = buf.readDouble();
        }
        if (buf.readableBytes() > 0) {
            buf.skipBytes(buf.readableBytes());
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        int count = Math.min(values.length, C2SMolangSync.MAX_VALUES);
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeDouble(values[i]);
        }
    }

    public static class Handler implements IMessageHandler<S2CMolangSync, IMessage> {

        @Override
        public IMessage onMessage(final S2CMolangSync message, MessageContext ctx) {
            // N-10: guard the handler body; an exception here would be turned into a disconnect by FML.
            try {
                final Minecraft mc = Minecraft.getMinecraft();
                // N-03: the entity lookup and everything reading model state must happen on the client thread.
                mc.func_152344_a(new Runnable() {

                    @Override
                    public void run() {
                        try {
                            Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(message.entityId);
                            if (!(entity instanceof EntityPlayer)) {
                                return;
                            }
                            ExtendedModelInfo eep = ExtendedModelInfo.get((EntityPlayer) entity);
                            if (eep == null) {
                                return;
                            }
                            // The renderer reuses one CustomPlayerEntity; the real entity is its stable
                            // upstream animatable-owner equivalent (CustomPlayerRenderer:253-273).
                            // A @sync script is evaluated outside any draw (this Runnable runs from the client
                            // tick's scheduled-task queue), while query.* / ysm.* in the shared table are suppliers
                            // closed over the entity of the last draw - a vehicle or an arrow, if one was drawn
                            // after this player. A reader with no entity of its own therefore publishes the entity
                            // its read is about for the duration of that read: this is the read point's counterpart
                            // of upstream's "every read carries IContext.entity()"
                            // (geckolib3/core/molang/context/IContext.java:23-24). See EntityQueryScope for the
                            // mechanism and for the frame-end half that keeps the table clean between frames.
                            try (com.fox.ysmu.client.audio.MolangSoundContext sound =
                                com.fox.ysmu.client.audio.MolangSoundContext.push(entity, entity,
                                    com.fox.ysmu.util.ModelIdUtil.getMainId(eep.getModelId()), null, false, true);
                                com.fox.ysmu.client.animation.molang.EntityQueryScope.Publication queries =
                                    com.fox.ysmu.client.animation.molang.EntityQueryScope.publishForRead(
                                        software.bernie.geckolib3.resource.GeckoLibCache.getInstance().parser,
                                        entity)) {
                                PackUserFunctions.fireHook("sync", eep.getModelId(), message.values);
                            }
                        } catch (Throwable failure) {
                            ysmu.LOG.warn("YSM molang-sync script failed", failure);
                        }
                    }
                });
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM molang-sync broadcast", e);
            }
            return null;
        }
    }
}
