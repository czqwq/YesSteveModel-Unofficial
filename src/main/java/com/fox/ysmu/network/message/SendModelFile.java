package com.fox.ysmu.network.message;

import java.io.File;
import java.util.Locale;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SendModelFile implements IMessage {

    private byte[] data;

    public SendModelFile() {}

    public SendModelFile(byte[] data) {
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-06: the length prefix comes from the peer. Validate it against the readable bytes before allocating
        // and soft-fail (null data => the handler ignores the packet) instead of throwing, because a decode-time
        // exception is turned into a disconnect by FMLProxyPacket.processPacket -> rejectHandshake.
        int length = buf.readInt();
        if (length < 0 || length > buf.readableBytes()) {
            ysmu.LOG.warn(
                "Ignoring malformed YSM model file packet: length={}, readableBytes={}",
                length,
                buf.readableBytes());
            this.data = null;
            return;
        }
        this.data = new byte[length];
        buf.readBytes(this.data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        byte[] safeData = this.data == null ? new byte[0] : this.data;
        buf.writeInt(safeData.length);
        buf.writeBytes(safeData);
    }

    public static class Handler implements IMessageHandler<SendModelFile, IMessage> {

        @Override
        public IMessage onMessage(SendModelFile message, MessageContext ctx) {
            if (message.data == null) {
                return null;
            }
            byte[] data = message.data;
            // N-04: the write itself (up to 1.9 MB) must not happen on the packet handler thread, which is the
            // client main thread; the handler only schedules it.
            //
            // The old "48 bytes means password" branch was removed here (N-10): the encrypted model blob is always
            // 24 + AES/PKCS5 ciphertext (a multiple of 16), i.e. 40/56/72/..., so 48 is unreachable, and even when
            // hit it set PASSWORD without PASSWORD_UUID, which made every RequestLoadModel wait out its full retry
            // budget. The legacy password now travels as SendModelPassword (that blob is exactly 48 bytes).
            try {
                ThreadTools.THREAD_POOL.submit(() -> receiveFile(data));
            } catch (RuntimeException e) {
                ysmu.LOG.warn("Failed to schedule YSM model cache save", e);
            }
            return null;
        }

        private void receiveFile(byte[] data) {
            try {
                // The name is the payload's own MD5, upper case, exactly as before. This used to be a private
                // digest+hex loop; it is now Md5Utils.md5Hex, which has the same per-call MessageDigest (M-07) and
                // the same lower-case two-digits-per-byte form (HexFormat, upstream's mechanism at
                // model/domain/Hash256.java:10,99) that toUpperCase below consumes. One operation, one implementation
                // (AGENTS.md).
                String fileName = Md5Utils.md5Hex(data)
                    .toUpperCase(Locale.US);
                File file = ServerModelManager.CACHE_CLIENT.resolve(fileName)
                    .toFile();
                FileUtils.writeByteArrayToFile(file, data);
                ClientModelManager.rememberCachedModel(fileName);
                RequestLoadModel.loadModel(fileName);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to save YSM model cache file", e);
            }
        }
    }
}
