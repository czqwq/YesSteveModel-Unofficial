package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * A-16: a pack script's {@code ysm.sync(...)} call, on its way to the server.
 * <p>
 * Upstream registers {@code sync} as a function ({@code client/animation/molang/YSMBinding.java:190}) whose body sends
 * the call's arguments to the server ({@code client/animation/molang/functions/Sync.java:29}); the server echoes them
 * back to everyone who can see that entity and each client runs the model's {@code @sync} script with them
 * ({@code network/forge/ControlHandler.java:144-172} then {@code CustomPlayerEntity.java:132-136}). The wire values
 * are plain doubles, which is what a script reads back as {@code args[i]}.
 */
public class C2SMolangSync implements IMessage {

    /**
     * The most values one call may synchronise. Upstream caps the same message by bytes
     * ({@code ProtocolLimits.MAX_MOLANG_SYNC_BYTES}); this is the local guard, so a script cannot make one packet
     * arbitrarily large.
     */
    public static final int MAX_VALUES = 16;

    private double[] values = new double[0];

    public C2SMolangSync() {}

    public C2SMolangSync(double[] values) {
        this.values = values;
    }

    public double[] getValues() {
        return values;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: a decode-time exception is turned into a disconnect by FMLProxyPacket.processPacket, so a truncated
        // call becomes an empty one (the handler then echoes nothing meaningful) instead.
        try {
            int count = Math.clamp(buf.readInt(), 0, MAX_VALUES);
            double[] parsed = new double[count];
            for (int i = 0; i < count; i++) {
                parsed[i] = buf.readDouble();
            }
            values = parsed;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM molang-sync call: {}", e.toString());
            values = new double[0];
        }
        if (buf.readableBytes() > 0) {
            buf.skipBytes(buf.readableBytes());
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int count = Math.min(values.length, MAX_VALUES);
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeDouble(values[i]);
        }
    }

    public static class Handler implements IMessageHandler<C2SMolangSync, IMessage> {

        @Override
        public IMessage onMessage(C2SMolangSync message, MessageContext ctx) {
            // N-10: guard the handler body; an exception here would be turned into a disconnect by FML.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null || message.getValues() == null) {
                    return null;
                }
                // The echo walks the entity's world player list, which is a plain ArrayList in 1.7.10 and is only
                // stable on the server thread (the listener also sends packets, which is normal from there), so the
                // whole broadcast is deferred like the other world-touching handlers.
                double[] values = message.getValues();
                DeferredWork.server(() -> echo(sender, values));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM molang-sync packet", e);
            }
            return null;
        }

        /** Server thread: echo the call to every player in the sender's world. */
        private static void echo(EntityPlayerMP sender, double[] values) {
            try {
                if (sender.isDead || sender.worldObj == null) {
                    return;
                }
                // Every player in the sender's world. Upstream echoes to the entity's tracking players plus itself
                // (network/forge/ControlHandler.java:160-172); a client that does not know the entity ignores the
                // packet when it handles it, so sending to the whole world is the cheap correct form here - and it
                // keeps the echo on the same channel helper the rest of this mod uses
                // (NetworkHandler#sendToClientPlayer) instead of a raw vanilla Packet.
                S2CMolangSync echo = new S2CMolangSync(sender.getEntityId(), values);
                for (EntityPlayer candidate : sender.worldObj.playerEntities) {
                    NetworkHandler.sendToClientPlayer(echo, candidate);
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to echo a YSM molang-sync call", e);
            }
        }
    }
}
