package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class C2SModelSyncPayload17 implements IMessage {

    /** Header budget: garbage header (at most 2+63) + type byte + count varInt + session id (4) + slack. */
    private static final int HEADER_BUDGET = 96;
    /**
     * N-05: hard ceiling for the client-to-server direction. 1.7.10's {@code C17PacketCustomPayload} rejects a
     * payload of 32767 bytes or more (it throws "Payload may not be larger than 32k" from its constructor), so the
     * budget has to stay below that. The server-to-client direction has a different ceiling; see
     * {@link S2CModelSyncPayload17#MAX_SYNC_PAYLOAD_BYTES}.
     */
    public static final int MAX_SYNC_PAYLOAD_BYTES = 32767 - HEADER_BUDGET;

    /** N-11: sync session this payload belongs to; 0 means "unknown" and is accepted for robustness. */
    private int sessionId;
    /** null marks a malformed/truncated packet; the handler ignores it instead of throwing (N-10). */
    private byte[] data = new byte[0];

    public C2SModelSyncPayload17() {}

    public C2SModelSyncPayload17(int sessionId, byte[] data) {
        this.sessionId = sessionId;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.sessionId = buf.readInt();
            this.data = readByteArray(buf, MAX_SYNC_PAYLOAD_BYTES);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring malformed OpenYSM client sync payload: {}", e.toString());
            this.data = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.sessionId);
        writeByteArray(buf, this.data, MAX_SYNC_PAYLOAD_BYTES);
    }

    public int getSessionId() {
        return sessionId;
    }

    public byte[] getData() {
        return data;
    }

    /**
     * N-06: reads the length prefix and the bytes, comparing the length with both the caller's business limit and
     * the readable bytes. Returns null (packet dropped by the caller) instead of throwing.
     */
    static byte[] readByteArray(ByteBuf buf, int maxBytes) {
        int length = buf.readInt();
        if (length < 0 || length > maxBytes || length > buf.readableBytes()) {
            ysmu.LOG.warn(
                "Ignoring OpenYSM sync payload with invalid length {} (max {}, readable {})",
                length,
                maxBytes,
                buf.readableBytes());
            return null;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    /** Writing stays strict: an oversized payload is a bug in the caller and must not be truncated silently. */
    static void writeByteArray(ByteBuf buf, byte[] bytes, int maxBytes) {
        byte[] safeBytes = bytes == null ? new byte[0] : bytes;
        if (safeBytes.length > maxBytes) {
            throw new IllegalArgumentException(
                "OpenYSM sync payload is too large: " + safeBytes.length + " > " + maxBytes);
        }
        buf.writeInt(safeBytes.length);
        buf.writeBytes(safeBytes);
    }

    public static class Handler implements IMessageHandler<C2SModelSyncPayload17, IMessage> {

        @Override
        public IMessage onMessage(C2SModelSyncPayload17 message, MessageContext ctx) {
            if (message.data == null) {
                return null;
            }
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender != null) {
                    OpenYsmModelSyncServer.handlePayload(message.sessionId, sender.getUniqueID(), message.data);
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle OpenYSM client sync payload", e);
            }
            return null;
        }
    }
}
