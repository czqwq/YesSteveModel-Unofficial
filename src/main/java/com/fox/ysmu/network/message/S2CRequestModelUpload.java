package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import com.fox.ysmu.client.upload.UploadManager;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 服务端到客户端（phase55）：请执行 {@code /ysm upload} 的玩家读取**自己机器上**的 {@code path}，把它作为
 * {@code target} 目录的模型上传回服务端。
 *
 * <p>为什么需要这个包：{@code /ysm} 是服务端命令，而命令里的路径是玩家客户端磁盘上的路径；1.7.10 的服务端
 * 进程读不到它，只能让客户端读。控制台执行同一命令时没有这个往返（见
 * {@code model/upload/ServerModelUploadManager#installFromServerPath}）。</p>
 *
 * <p>方向由构造决定：只有服务端会发它（{@code ServerModelUploadManager#requestClientUpload}），客户端处理器
 * 用 {@code ctx.side != Side.CLIENT} 守卫；客户端回传走 {@link C2SUploadModelFile}。解码按本仓 N-10 规则
 * 软失败：畸形/截断路径解成 {@code targetWireId = -1}，处理器直接忽略，不抛异常（抛出去会被 FML 当成协议
 * 错误断开玩家）。</p>
 */
public class S2CRequestModelUpload implements IMessage {

    /** 路径的 UTF-8 字节上限；Windows/Linux 的路径远短于此。 */
    public static final int MAX_PATH_BYTES = 4096;
    /** 目标目录线号；{@code -1} 表示解码失败，处理器忽略整包。 */
    private int targetWireId = -1;
    private String path = "";

    public S2CRequestModelUpload() {}

    public S2CRequestModelUpload(int targetWireId, String path) {
        this.targetWireId = targetWireId;
        this.path = path == null ? "" : path;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.targetWireId = buf.readUnsignedByte();
            this.path = readString(buf);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring a malformed YSM model upload request: {}", e.toString());
            this.targetWireId = -1;
            this.path = "";
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.targetWireId & 0xFF);
        writeString(buf, this.path);
    }

    /** 4 字节大端长度 + UTF-8，读时同时比较声明长度与可读字节数（N-06/N-10）。 */
    private static String readString(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return "";
        }
        int length = buf.readInt();
        if (length < 0 || length > MAX_PATH_BYTES || length > buf.readableBytes()) {
            ysmu.LOG.warn(
                "Ignoring a YSM model upload request with an invalid path length {} (max {}, readable {})",
                length,
                MAX_PATH_BYTES,
                buf.readableBytes());
            return "";
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PATH_BYTES) {
            throw new IllegalArgumentException("The upload path is too long: " + bytes.length + " bytes");
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public int getTargetWireId() {
        return this.targetWireId;
    }

    public String getPath() {
        return this.path;
    }

    public static class Handler implements IMessageHandler<S2CRequestModelUpload, IMessage> {

        @Override
        public IMessage onMessage(S2CRequestModelUpload message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) {
                return null;
            }
            try {
                if (message.targetWireId < 0 || message.path.isEmpty()) {
                    return null;
                }
                // 读取/校验/发送都在后台线程（UploadManager 自己起任务）；这里只做入口校验。
                UploadManager.onUploadRequested(message.targetWireId, message.path);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle a YSM model upload request", e);
            }
            return null;
        }
    }
}
