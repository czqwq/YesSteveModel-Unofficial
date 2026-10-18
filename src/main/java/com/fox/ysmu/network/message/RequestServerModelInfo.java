package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 服务端到客户端（phase55）：LgeacyYSM 模型管理 GUI 的列表数据——{@code config/ysmu/custom} 与
 * {@code config/ysmu/auth} 的顶层条目（名字、类型、字节数）。
 *
 * <p>这个 id（10）在 phase7 的 `N-09` 里是"注册但无发送方"的占位；所有者本轮要求移植 Lgeacy 的模型管理
 * GUI 后它有了真正的两端：服务端由 {@code /ysm manage} 与 {@link RefreshModelManage} 的处理器发送，客户端
 * {@link Handler} 打开 {@code client/gui/ModelManageScreen}。</p>
 *
 * <p>端口版与 Lgeacy 的差异：</p>
 * <ul>
 *   <li>两个列表（custom / auth），Lgeacy 的 ManageCommand 也分两个根；参考分支的 1.7.10 副本只有
 *       custom 一个列表。</li>
 *   <li>只会有 FOLDER 与 YSM 两类条目：本仓扫描器不加载 {@code .zip}（Lgeacy 的 Type 还有 ZIP），列出无法
 *       加载的文件只会误导管理员；UNKNOWN 在解码时兜底，不用于正常列表。</li>
 *   <li>解码按本仓 N-10 软失败：条目数与字符串长度先校验，畸形包解成 null 列表、处理器不打开 GUI，不抛异常
 *       （抛出去会被 FML 当成协议错误断开玩家）。</li>
 *   <li>打开 GUI 经 {@code Minecraft.func_152344_a} 回客户端线程（1.7.10 的 handler 在 Netty 线程）。</li>
 * </ul>
 */
public class RequestServerModelInfo implements IMessage {

    /** 单个列表的条目上限：管理界面一页 13 行，4096 已远超任何真实目录。 */
    public static final int MAX_ENTRIES = 4096;
    /** 文件名 UTF-8 字节上限（磁盘文件名实际远小于此）。 */
    private static final int MAX_NAME_BYTES = 512;

    /** null 表示畸形包：处理器不打开 GUI。 */
    private List<Info> customModels = Collections.emptyList();
    private List<Info> authModels = Collections.emptyList();

    public RequestServerModelInfo() {}

    public RequestServerModelInfo(List<Info> customModels, List<Info> authModels) {
        this.customModels = customModels == null ? Collections.emptyList() : customModels;
        this.authModels = authModels == null ? Collections.emptyList() : authModels;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.customModels = readInfos(buf);
            this.authModels = readInfos(buf);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring malformed YSM model-manage list: {}", e.toString());
            this.customModels = null;
            this.authModels = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeInfos(buf, customModels);
        writeInfos(buf, authModels);
    }

    private static List<Info> readInfos(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            throw new IllegalArgumentException("truncated model-manage count");
        }
        int count = buf.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid model-manage entry count " + count);
        }
        List<Info> infos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readString(buf);
            int ordinal = buf.readInt();
            long size = buf.readLong();
            Type[] types = Type.values();
            Type type = ordinal >= 0 && ordinal < types.length ? types[ordinal] : Type.UNKNOWN;
            infos.add(new Info(name, type, size));
        }
        return infos;
    }

    private static void writeInfos(ByteBuf buf, List<Info> infos) {
        List<Info> safe = infos == null ? Collections.emptyList() : infos;
        buf.writeInt(safe.size());
        for (Info info : safe) {
            writeString(buf, info.fileName);
            buf.writeInt(info.type.ordinal());
            buf.writeLong(info.size);
        }
    }

    private static String readString(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            throw new IllegalArgumentException("truncated model-manage name");
        }
        int length = buf.readInt();
        if (length < 0 || length > MAX_NAME_BYTES || length > buf.readableBytes()) {
            throw new IllegalArgumentException("invalid model-manage name length " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("model-manage name is too long: " + bytes.length + " bytes");
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public List<Info> getCustomModels() {
        return customModels;
    }

    public List<Info> getAuthModels() {
        return authModels;
    }

    /** 一个顶层条目：文件名（目录名或 {@code xxx.ysm}）、类型与字节数。 */
    public static class Info {

        private String fileName;
        private Type type;
        private long size;

        public Info() {}

        public Info(String fileName, Type type, long size) {
            this.fileName = fileName;
            this.type = type;
            this.size = size;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }
    }

    public static class Handler implements IMessageHandler<RequestServerModelInfo, IMessage> {

        @Override
        public IMessage onMessage(RequestServerModelInfo message, MessageContext ctx) {
            // 服务端：这个包只用于打开客户端 GUI，服务端直接放行。
            if (ctx.side == Side.SERVER) {
                return null;
            }
            try {
                if (message.customModels != null && message.authModels != null) {
                    // packet handler 在 Netty 线程，GUI 由客户端 tick 打开；客户端代码留在 ModelManageScreen 里，
                    // 服务端实例化本 handler 时不会加载客户端类。
                    com.fox.ysmu.util.DeferredWork.client(
                        () -> com.fox.ysmu.client.gui.ModelManageScreen
                            .openFromPacket(message.customModels, message.authModels));
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle YSM server model info request", e);
            }
            return null;
        }
    }
}
