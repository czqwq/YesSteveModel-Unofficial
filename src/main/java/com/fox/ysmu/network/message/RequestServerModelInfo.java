package com.fox.ysmu.network.message;


import java.util.List;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * N-09 (deprecated): id 10 is registered but has no sender anywhere in this repository - the model-management GUI
 * it belonged to was never wired on 1.7.10. The id stays reserved and must NOT be renumbered or reused. The
 * handler remains an intentional no-op; re-wiring it is a new feature, not a fix.
 */
public class RequestServerModelInfo implements IMessage {

    private List<Info> customModels;

    public RequestServerModelInfo() {}

    public RequestServerModelInfo(List<Info> customModels) {
        this.customModels = customModels;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-06/N-10: the entry count comes from the peer. Bound it before allocating and soft-fail (null list =>
        // the handler ignores the packet) instead of throwing, which FML would turn into a disconnect.
        try {
            int customModelsSize = buf.readInt();
            if (customModelsSize < 0 || customModelsSize > buf.readableBytes()) {
                ysmu.LOG.warn(
                    "Ignoring malformed YSM server model info request: size={}, readableBytes={}",
                    customModelsSize,
                    buf.readableBytes());
                this.customModels = null;
                return;
            }
            List<Info> parsed = Lists.newArrayList();
            for (int i = 0; i < customModelsSize; i++) {
                parsed.add(bufferToInfo(buf));
            }
            this.customModels = parsed;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM server model info request: {}", e.toString());
            this.customModels = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        List<Info> safeModels = this.customModels == null ? Lists.newArrayList() : this.customModels;
        buf.writeInt(safeModels.size());
        for (Info info : safeModels) {
            infoToBuffer(buf, info);
        }
    }

    public static class Handler implements IMessageHandler<RequestServerModelInfo, IMessage> {

        @Override
        public IMessage onMessage(RequestServerModelInfo message, MessageContext ctx) {
            // N-09: intentionally empty (never sent, see the class javadoc); guarded only to satisfy N-10.
            try {
                if (ctx.side == Side.CLIENT) {
                    // Model management GUI is not currently wired on 1.7.10.
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle YSM server model info request", e);
            }
            return null;
        }
    }

    private static void infoToBuffer(ByteBuf buf, Info info) {
        ByteBufUtils.writeUTF8String(buf, info.fileName);
        // 在1.7.10中没有直接的枚举写入方法，我们需要手动处理
        buf.writeInt(info.type.ordinal());
        buf.writeLong(info.size);
    }

    private static Info bufferToInfo(ByteBuf buf) {
        String fileName = ByteBufUtils.readUTF8String(buf);
        // 在1.7.10中没有直接的枚举读取方法，我们需要手动处理
        // N-06: the ordinal comes from the peer - an out-of-range value used to throw AIOOBE and thereby
        // disconnect the client, so clamp it instead.
        int ordinal = buf.readInt();
        Type[] types = Type.values();
        Type type = ordinal >= 0 && ordinal < types.length ? types[ordinal] : Type.UNKNOWN;
        long size = buf.readLong();
        return new Info(fileName, type, size);
    }

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
}
