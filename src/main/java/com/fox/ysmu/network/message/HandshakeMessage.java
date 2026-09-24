package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: the client's network protocol version.
 * <p>
 * 1.7.10's {@code SimpleNetworkWrapper} performs no version negotiation, so a client and server running
 * different YSMU builds would connect happily and then decode packets using the wrong layout. This message is
 * sent once per join; a mismatch disconnects the client with a readable reason instead of misbehaving later.
 */
public class HandshakeMessage implements IMessage {

    private int protocol;

    public HandshakeMessage() {}

    public HandshakeMessage(int protocol) {
        this.protocol = protocol;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.protocol = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.protocol);
    }

    public static class Handler implements IMessageHandler<HandshakeMessage, IMessage> {

        @Override
        public IMessage onMessage(HandshakeMessage message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender == null) {
                return null;
            }
            int protocol = message.protocol;
            // Kick from the server thread; disconnect handling touches connection state, not the Netty pipeline.
            DeferredWork.server(() -> {
                if (protocol != NetworkHandler.NETWORK_PROTOCOL) {
                    sender.playerNetServerHandler.kickPlayerFromServer(
                        "YSMU network protocol mismatch (server " + NetworkHandler.NETWORK_PROTOCOL
                            + ", client "
                            + protocol
                            + "). Install matching Yes Steve Model Unofficial versions.");
                }
            });
            return null;
        }
    }
}
