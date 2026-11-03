package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.Config;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

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
        // N-10: a decode-time exception is turned into a disconnect by FMLProxyPacket.processPacket, so a truncated
        // handshake becomes a sentinel version that fails the comparison below instead.
        try {
            this.protocol = buf.readInt();
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM network handshake: {}", e.toString());
            this.protocol = -1;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.protocol);
    }

    public static class Handler implements IMessageHandler<HandshakeMessage, IMessage> {

        @Override
        public IMessage onMessage(HandshakeMessage message, MessageContext ctx) {
            // N-10: the kick itself runs through DeferredWork (whose drain catches Throwable); guard onMessage too.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null) {
                    return null;
                }
                int protocol = message.protocol;
                // phase55: a matching handshake is the one reliable "this player's client runs YSMU" signal. The
                // /ysm upload command reads it to tell "the client will respond to the upload request" apart from
                // "this is a vanilla client, so the local path cannot be read" - without waiting for a timeout.
                if (protocol == NetworkHandler.NETWORK_PROTOCOL) {
                    com.fox.ysmu.model.upload.ServerModelUploadManager.markClientHandshake(sender);
                    // The server-side switches a client has to know, at the moment upstream's Forge config sync
                    // happens (login). See S2CServerConfig: 1.7.10's Configuration has no such exchange, so the
                    // CanSwitchModel flag two client key handlers read travels in answer to this handshake, which is
                    // re-sent after every world load or dimension change and therefore re-establishes it per world.
                    NetworkHandler.sendToClientPlayer(
                        new S2CServerConfig(Config.CAN_SWITCH_MODEL),
                        sender);
                }
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
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle YSMU network handshake", e);
            }
            return null;
        }
    }
}
