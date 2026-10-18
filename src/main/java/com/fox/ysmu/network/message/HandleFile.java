package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.model.manage.ServerModelManage;
import com.fox.ysmu.model.upload.ModelUploadTarget;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 客户端到服务端（phase55，LgeacyYSM 的 {@code HandleFile}）：模型管理界面上的删除/移动/重命名。
 *
 * <p>字段：条目名、来源根线号（{@link ModelUploadTarget}）、动作、重命名目标名。动作枚举只有
 * {@link Action#DELETE}/{@link Action#MOVE}/{@link Action#RENAME}；Lgeacy 用字符串（{@code "delete"} 等），
 * 端口改成枚举，避免未知字符串落进三个 if 都不命中而静默无操作。</p>
 *
 * <p>与 Lgeacy 的行为差异：</p>
 * <ul>
 *   <li>名字不可信：服务端先经 {@link ServerModelManage#sanitizeEntryName} 清洗并确认解析出的路径恰好在目标根
 *       下（禁止路径穿越），只允许操作管理界面会列出的顶层目录或 {@code .ysm} 文件。</li>
 *   <li>目标已存在时拒绝而不是覆盖（Lgeacy 的 {@code FileUtils.moveFile} 会抛 IOException 然后被吞掉，
 *       客户端看不到任何反馈）。</li>
 *   <li>失败会回一句聊天提示；成功不回话，GUI 随后自己发 {@link RefreshModelManage} 刷新列表。</li>
 * </ul>
 */
public class HandleFile implements IMessage {

    /** 文件名 UTF-8 字节上限（磁盘文件名实际远小于此）。 */
    private static final int MAX_NAME_BYTES = 512;

    /** 管理界面上的三类动作。线号是协议的一部分，不要重排。 */
    public enum Action {
        DELETE(0),
        MOVE(1),
        RENAME(2);

        private final int wireId;

        Action(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Action fromWireId(int wireId) {
            for (Action action : values()) {
                if (action.wireId == wireId) {
                    return action;
                }
            }
            return null;
        }
    }

    /** 来源根线号；未知值在处理器里被丢弃。 */
    private int targetWireId;
    private String name = "";
    private Action action;
    private String rename = "";
    /** 畸形包标记：处理器什么都不做。 */
    private boolean malformed;

    public HandleFile() {}

    public HandleFile(ModelUploadTarget target, String name, Action action, String rename) {
        this.targetWireId = target == null ? -1 : target.wireId();
        this.name = name == null ? "" : name;
        this.action = action;
        this.rename = rename == null ? "" : rename;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.targetWireId = buf.readUnsignedByte();
            this.name = readString(buf);
            int actionWireId = buf.readUnsignedByte();
            this.action = Action.fromWireId(actionWireId);
            if (this.action == null) {
                throw new IllegalArgumentException("unknown model-manage action " + actionWireId);
            }
            this.rename = readString(buf);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring a malformed YSM model-manage file action: {}", e.toString());
            this.malformed = true;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (this.action == null) {
            throw new IllegalArgumentException("A model-manage file action needs an action");
        }
        buf.writeByte(this.targetWireId & 0xFF);
        writeString(buf, this.name);
        buf.writeByte(this.action.wireId());
        writeString(buf, this.rename);
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

    public int getTargetWireId() {
        return targetWireId;
    }

    public String getName() {
        return name;
    }

    public Action getAction() {
        return action;
    }

    public String getRename() {
        return rename;
    }

    public static class Handler implements IMessageHandler<HandleFile, IMessage> {

        @Override
        public IMessage onMessage(HandleFile message, MessageContext ctx) {
            if (ctx.side != Side.SERVER || message.malformed) {
                return null;
            }
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null) {
                    return null;
                }
                if (!sender.canCommandSenderUseCommand(4, "ysm")) {
                    sender.addChatMessage(new ChatComponentTranslation("commands.generic.permission"));
                    return null;
                }
                ModelUploadTarget target = ModelUploadTarget.fromWireId(message.targetWireId);
                if (target == null) {
                    return null;
                }
                com.fox.ysmu.util.DeferredWork.server(() -> run(sender, target, message));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle a YSM model-manage file action", e);
            }
            return null;
        }

        private static void run(EntityPlayerMP sender, ModelUploadTarget target, HandleFile message) {
            try {
                ServerModelManage.Outcome outcome;
                switch (message.action) {
                    case DELETE:
                        outcome = ServerModelManage.delete(target.root(), message.name);
                        break;
                    case MOVE:
                        outcome = ServerModelManage.move(
                            target.root(),
                            target == ModelUploadTarget.CUSTOM ? ModelUploadTarget.AUTH.root()
                                : ModelUploadTarget.CUSTOM.root(),
                            message.name);
                        break;
                    case RENAME:
                        outcome = ServerModelManage.rename(target.root(), message.name, message.rename);
                        break;
                    default:
                        outcome = ServerModelManage.Outcome.INVALID_NAME;
                        break;
                }
                if (outcome != ServerModelManage.Outcome.OK) {
                    ysmu.LOG.warn(
                        "[YSMU-MANAGE] {} action on '{}' failed: {}",
                        message.action,
                        message.name,
                        outcome);
                    sender.addChatMessage(new ChatComponentTranslation(outcome.messageKey(), message.name));
                }
            } catch (Exception e) {
                ysmu.LOG.warn("[YSMU-MANAGE] Failed to run a model-manage file action", e);
                sender.addChatMessage(
                    new ChatComponentTranslation("commands.yes_steve_model.manage.error.io_error", message.name));
            }
        }
    }
}
