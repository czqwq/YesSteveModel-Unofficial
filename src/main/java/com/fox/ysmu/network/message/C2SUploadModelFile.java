package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.model.upload.ModelUploadTarget;
import com.fox.ysmu.model.upload.ServerModelUploadManager;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 客户端到服务端（phase55）：一个上传分片。
 *
 * <p>字段：目标目录线号（{@link ModelUploadTarget}）、建议文件名、总长度、本片偏移、本片字节。客户端按偏移
 * 从 0 开始顺序发送，服务端严格校验连续性与长度上限（见
 * {@link ServerModelUploadManager#acceptChunk}）；任何不一致都丢弃整段传输。</p>
 *
 * <p><b>32 KB 硬上限。</b>1.7.10 的 {@code C17PacketCustomPayload} 拒绝 ≥32767 字节的载荷，所以单片数据
 * 上限是 {@link #MAX_CHUNK_BYTES}，文件名另有 {@link #MAX_FILE_NAME_BYTES} 上限；两部分加上包头字段仍远小于
 * 32767。</p>
 *
 * <p>解码按 N-10 软失败：长度越界/截断的包把 {@code data} 置 {@code null}，处理器直接忽略。处理器的权限检查
 * 与状态机在服务端管理器里，不在本类。</p>
 */
public class C2SUploadModelFile implements IMessage {

    /** 建议文件名的 UTF-8 字节上限；服务端还会再清洗一次。 */
    public static final int MAX_FILE_NAME_BYTES = 400;
    /**
     * 单片数据的最大字节数。
     * <p>
     * 1.7.10 的 {@code C17PacketCustomPayload} 拒绝 ≥32767 字节的载荷（见
     * {@code network/message/C2SModelSyncPayload17.java:17-23}），本包除数据外还有文件名（≤400 字节）、
     * 目标线号、总长度、偏移、数据长度四个 int：32000 + 约 420 远小于 32767，留足余量。
     * 这个上限定义在线协议类里（分片大小的语义属于线上的包），服务端管理器与客户端上传器都引用它。
     * </p>
     */
    public static final int MAX_CHUNK_BYTES = 32000;

    /** 目标目录线号；未知值在处理器里被丢弃。 */
    private int targetWireId;
    private String fileName = "";
    private int totalLength;
    private int offset;
    /** null 标记畸形/截断包；处理器忽略。 */
    private byte[] data = new byte[0];

    public C2SUploadModelFile() {}

    public C2SUploadModelFile(ModelUploadTarget target, String fileName, int totalLength, int offset, byte[] data) {
        this.targetWireId = target == null ? -1 : target.wireId();
        this.fileName = fileName == null ? "" : fileName;
        this.totalLength = totalLength;
        this.offset = offset;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.targetWireId = buf.readUnsignedByte();
            this.fileName = readString(buf);
            this.totalLength = buf.readInt();
            this.offset = buf.readInt();
            int length = buf.readInt();
            if (length < 0 || length > MAX_CHUNK_BYTES || length > buf.readableBytes()) {
                ysmu.LOG.warn(
                    "Ignoring a malformed YSM upload chunk: length {} (max {}, readable {})",
                    length,
                    MAX_CHUNK_BYTES,
                    buf.readableBytes());
                this.data = null;
                return;
            }
            this.data = new byte[length];
            buf.readBytes(this.data);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring a truncated YSM upload chunk: {}", e.toString());
            this.data = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        byte[] safeData = this.data == null ? new byte[0] : this.data;
        if (safeData.length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException(
                "YSM upload chunk is too large: " + safeData.length + " > "
                    + MAX_CHUNK_BYTES);
        }
        if (this.offset < 0 || this.totalLength < 0 || this.offset > this.totalLength - safeData.length) {
            throw new IllegalArgumentException(
                "Invalid YSM upload chunk range: total=" + this.totalLength + ", offset=" + this.offset + ", length="
                    + safeData.length);
        }
        buf.writeByte(this.targetWireId & 0xFF);
        writeString(buf, this.fileName);
        buf.writeInt(this.totalLength);
        buf.writeInt(this.offset);
        buf.writeInt(safeData.length);
        buf.writeBytes(safeData);
    }

    private static String readString(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return "";
        }
        int length = buf.readInt();
        if (length < 0 || length > MAX_FILE_NAME_BYTES || length > buf.readableBytes()) {
            ysmu.LOG.warn("Ignoring a YSM upload chunk with an invalid file-name length {}", length);
            return "";
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_NAME_BYTES) {
            throw new IllegalArgumentException("The YSM upload file name is too long: " + bytes.length + " bytes");
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public int getTargetWireId() {
        return this.targetWireId;
    }

    public String getFileName() {
        return this.fileName;
    }

    public int getTotalLength() {
        return this.totalLength;
    }

    public int getOffset() {
        return this.offset;
    }

    public byte[] getData() {
        return this.data;
    }

    public static class Handler implements IMessageHandler<C2SUploadModelFile, IMessage> {

        @Override
        public IMessage onMessage(C2SUploadModelFile message, MessageContext ctx) {
            if (ctx.side != Side.SERVER || message.data == null) {
                return null;
            }
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null) {
                    return null;
                }
                ModelUploadTarget target = ModelUploadTarget.fromWireId(message.targetWireId);
                if (target == null) {
                    ysmu.LOG.warn(
                        "Ignoring a YSM upload chunk with an unknown target id {} from {}",
                        message.targetWireId,
                        sender.getCommandSenderName());
                    return null;
                }
                ServerModelUploadManager.acceptChunk(
                    sender,
                    target,
                    message.fileName,
                    message.totalLength,
                    message.offset,
                    message.data);
            } catch (Exception e) {
                // N-10: keep a throwing body from reaching FML's catch(Throwable) -> rejectHandshake.
                ysmu.LOG.warn("Failed to handle a YSM upload chunk", e);
            }
            return null;
        }
    }
}
