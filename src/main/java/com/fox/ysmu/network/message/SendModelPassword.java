package com.fox.ysmu.network.message;

import java.util.UUID;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SendModelPassword implements IMessage {

    /** The password file is 40 bytes and the AES/PKCS5 blob is exactly 48; the cap only rejects bogus prefixes. */
    private static final int MAX_PASSWORD_BYTES = 4096;

    private UUID playerId;
    private byte[] password;

    public SendModelPassword() {}

    public SendModelPassword(UUID playerId, byte[] password) {
        this.playerId = playerId;
        this.password = password;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.playerId = new UUID(buf.readLong(), buf.readLong());
        // N-06: validate the peer-supplied length before allocating, and soft-fail instead of throwing (a
        // decode-time exception would be turned into a disconnect by FMLProxyPacket.processPacket).
        int length = buf.readInt();
        if (length < 0 || length > buf.readableBytes() || length > MAX_PASSWORD_BYTES) {
            ysmu.LOG.warn(
                "Ignoring malformed YSM model password packet: length={}, readableBytes={}",
                length,
                buf.readableBytes());
            this.password = null;
            return;
        }
        this.password = new byte[length];
        buf.readBytes(this.password);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.playerId.getMostSignificantBits());
        buf.writeLong(this.playerId.getLeastSignificantBits());
        byte[] safePassword = this.password == null ? new byte[0] : this.password;
        buf.writeInt(safePassword.length);
        buf.writeBytes(safePassword);
    }

    public static class Handler implements IMessageHandler<SendModelPassword, IMessage> {

        @Override
        public IMessage onMessage(SendModelPassword message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                try {
                    if (message.playerId == null || message.password == null || message.password.length == 0) {
                        ysmu.LOG.warn("Ignoring YSM model password packet without a usable payload");
                        return null;
                    }
                    ClientModelManager.PASSWORD_UUID = message.playerId;
                    ClientModelManager.PASSWORD = message.password;
                    // N-12: the password is what queued cache loads were waiting for, so continue them right away
                    // instead of letting them sit (or, before this change, letting a worker sleep on it).
                    RequestLoadModel.onPasswordAvailable();
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to apply YSM model password", e);
                }
            }
            return null;
        }
    }
}
