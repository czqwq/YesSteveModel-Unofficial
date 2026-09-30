package com.fox.ysmu.network.message;

import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class S2CModelSyncPayload17 implements IMessage {

    /**
     * N-05: ceiling for the server-to-client direction. 1.7.10 Forge's {@code S3FPacketCustomPayload} rejects a
     * payload longer than {@code 0x1FFF9A} (= 2_097_050 = the 2 MiB maximum size of any MC packet minus that
     * packet's own framing), so that is the number to use here. The client-to-server direction is bounded by
     * {@link C2SModelSyncPayload17#MAX_SYNC_PAYLOAD_BYTES} instead.
     */
    public static final int MAX_SYNC_PAYLOAD_BYTES = 2_097_050;

    /** N-11: sync session this payload belongs to; the client drops payloads from any other session. */
    private int sessionId;
    /** null marks a malformed/truncated packet; the handler ignores it instead of throwing (N-10). */
    private byte[] data = new byte[0];

    public S2CModelSyncPayload17() {}

    public S2CModelSyncPayload17(int sessionId, byte[] data) {
        this.sessionId = sessionId;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.sessionId = buf.readInt();
            this.data = C2SModelSyncPayload17.readByteArray(buf, MAX_SYNC_PAYLOAD_BYTES);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring malformed OpenYSM server sync payload: {}", e.toString());
            this.data = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.sessionId);
        C2SModelSyncPayload17.writeByteArray(buf, this.data, MAX_SYNC_PAYLOAD_BYTES);
    }

    public int getSessionId() {
        return sessionId;
    }

    public byte[] getData() {
        return data;
    }

    public static class Handler implements IMessageHandler<S2CModelSyncPayload17, IMessage> {

        @Override
        public IMessage onMessage(S2CModelSyncPayload17 message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && message.data != null) {
                try {
                    OpenYsmModelSyncClient.handlePayload(message.sessionId, message.data);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to handle OpenYSM server sync payload", e);
                }
            }
            return null;
        }
    }
}
