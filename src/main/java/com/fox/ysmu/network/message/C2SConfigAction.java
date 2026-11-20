package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ConfigAction;
import com.fox.ysmu.data.ConfigDeclarations;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 → 服务端：「模型设置」里的一次点击，语义是上游 {@code SubmitConfigActionRequest}（{@code
 * tmp/YesSteveModel-dev-1.20} 的 {@code network/protocol/control.proto:44-58}，id 22，8 KiB）。
 * <p>
 * 与上游一致，<b>wire 上只有语义路由与类型化值，没有 MoLang 源码</b>（{@code docs/standards/protocol-v1/README.md:
 * 104-112}）：程序来自各端本地模型载荷。发送端不本地执行；服务端校验后广播 {@link S2CConfigAction} 给跟踪者 +
 * 发送者自己，每端在 subject 玩家的真实模型作用域里跑本地程序（上游 {@code ControlHandler.handleSubmitConfigAction}，
 * {@code network/forge/ControlHandler.java:93-115}）。
 *
 * <p><b>布局（NETWORK_PROTOCOL 13 起）。</b></p>
 *
 * <pre>
 * identity   : UTF8 string，模型内容身份的 sha256，可为空串（空串=不比对身份）
 * buttonId   : UTF8 string，1..128 字符
 * formIndex  : unsigned short
 * kind       : byte (1 = bool, 2 = f32, 3 = label)
 * payload    : kind==1 -> byte 0/1
 *              kind==2 -> float32（有限值）
 *              kind==3 -> unsigned short labelIndex, UTF8 labelName (1..128)
 * </pre>
 *
 * <p><b>服务端校验（契约 §6.2）。</b>sender 非空、{@link ConfigAction#valid()}、发送者 EEP 当前模型非空、
 * {@code ServerModelManager.configDeclarationsFor} 找得到声明、identity 与声明一致（声明有 sha256 时）、
 * {@link ConfigDeclarations#accepts(ConfigAction)} 命中 button/form/label。任何一步失败都静默丢弃（最多 debug
 * 日志），handler 体包在 try/catch 里，写操作走 {@link DeferredWork#server}。</p>
 *
 * <p><b>移植边界（契约 §8 第 3/4 条）。</b>服务端没有该模型的声明快照时直接丢弃：客户端本地注册的模型
 * （{@code LocalModelLoader}/{@code /ysmclient}）在上游没有对应场景，这种动作不广播、不持久化。上游对未知模型同样
 * 拒绝——它的 {@code validCatalogAction} 只查自己 catalog 里的 {@code Settings}。</p>
 */
public class C2SConfigAction implements IMessage {

    /** 上游 id 22 的 8 KiB 上限（{@code ProtocolMessages.java:75,210-213}）。 */
    public static final int MAX_PACKET_BYTES = 8 * 1024;

    /**
     * identity 的 wire 上限。上游是 32 字节的 {@code Hash256}；移植承载的是模型内容的十六进制 sha256（通常 64
     * 字符），留一倍余量给将来更长的摘要，同时保证解码不会为一个字段先分配大缓冲。
     */
    public static final int MAX_IDENTITY_BYTES = 128;

    private String identity;
    private ConfigAction action;
    /** 解码是否完整成功；{@code false} 时 handler 丢弃整包。 */
    private boolean parsed;

    public C2SConfigAction() {}

    /** task-4 的 {@code ConfigActionRuntime.submit} 构造入口：{@code identity} 可为 {@code null}/空串。 */
    public C2SConfigAction(@Nullable String identity, ConfigAction action) {
        this.identity = identity == null ? "" : identity;
        this.action = action;
        this.parsed = true;
    }

    public String identity() {
        return this.identity;
    }

    public ConfigAction action() {
        return this.action;
    }

    /** 解码是否完整成功；{@code false} 时 handler 必须丢弃整包。 */
    public boolean isParsed() {
        return this.parsed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10：解码抛异常会被 FMLProxyPacket.processPacket 变成断线，所以先看总长、每个字段先与 readableBytes()
        // 比较，失败静默丢弃；字段只在完整解析后赋值。
        boolean parsedHere = false;
        String parsedIdentity = null;
        ConfigAction parsedAction = null;
        try {
            if (buf.readableBytes() <= MAX_PACKET_BYTES) {
                String identity = readString(buf, MAX_IDENTITY_BYTES);
                if (identity != null) {
                    ConfigAction action = readAction(buf);
                    if (action != null && action.valid()) {
                        parsedHere = true;
                        parsedIdentity = identity;
                        parsedAction = action;
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM config-action packet", e);
        }
        this.identity = parsedIdentity;
        this.action = parsedAction;
        this.parsed = parsedHere;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.identity == null ? "" : this.identity);
        writeAction(buf, this.action);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 两个 config-action 报文共用的 action codec（S2CConfigAction 直接复用，避免两份布局漂移）
    // ---------------------------------------------------------------------------------------------------------------

    /** 写 {@code buttonId/formIndex/kind/payload} 部分；{@code action} 为 null 时写一个不可解码的占位。 */
    static void writeAction(ByteBuf buf, @Nullable ConfigAction action) {
        if (action == null) {
            ByteBufUtils.writeUTF8String(buf, "");
            buf.writeShort(0);
            buf.writeByte(0);
            return;
        }
        ByteBufUtils.writeUTF8String(buf, action.buttonId == null ? "" : action.buttonId);
        buf.writeShort(action.formIndex);
        buf.writeByte(action.kind);
        switch (action.kind) {
            case ConfigAction.KIND_BOOL:
                buf.writeByte(action.boolValue ? 1 : 0);
                break;
            case ConfigAction.KIND_F32:
                buf.writeFloat(action.floatValue);
                break;
            case ConfigAction.KIND_LABEL:
                buf.writeShort(action.labelIndex);
                ByteBufUtils.writeUTF8String(buf, action.labelName == null ? "" : action.labelName);
                break;
            default:
                // 未知 kind：不带 payload；解码端读到未知 kind 会丢弃整包。
                break;
        }
    }

    /**
     * 读 {@link #writeAction} 写出的部分；缓冲不够或 kind 未知返回 {@code null}（= 整包作废）。
     * <p>
     * 这里只保证"结构可读 + 类型化值有界"，语义校验在 {@link ConfigAction#valid()} / 服务端
     * {@link ConfigDeclarations#accepts(ConfigAction)}。
     */
    @Nullable
    static ConfigAction readAction(ByteBuf buf) {
        String buttonId = readString(buf, ConfigAction.MAX_BUTTON_ID_LENGTH * 4);
        if (buttonId == null || buf.readableBytes() < 3) {
            return null;
        }
        int formIndex = buf.readUnsignedShort();
        byte kind = buf.readByte();
        switch (kind) {
            case ConfigAction.KIND_BOOL: {
                if (buf.readableBytes() < 1) {
                    return null;
                }
                byte value = buf.readByte();
                if (value != 0 && value != 1) {
                    return null;
                }
                return ConfigAction.boolAction(buttonId, formIndex, value == 1);
            }
            case ConfigAction.KIND_F32: {
                if (buf.readableBytes() < 4) {
                    return null;
                }
                return ConfigAction.floatAction(buttonId, formIndex, buf.readFloat());
            }
            case ConfigAction.KIND_LABEL: {
                if (buf.readableBytes() < 2) {
                    return null;
                }
                int labelIndex = buf.readUnsignedShort();
                String labelName = readString(buf, ConfigAction.MAX_LABEL_NAME_LENGTH * 4);
                if (labelName == null || labelName.isEmpty()) {
                    return null;
                }
                return ConfigAction.labelAction(buttonId, formIndex, labelName, labelIndex);
            }
            default:
                return null;
        }
    }

    /**
     * 读一个长度前缀的 UTF-8 串，长度前缀与内容都先与 {@code readableBytes()} 比较；超过上限或缓冲不够返回
     * {@code null}。长度 0 返回空串（identity 合法为空）。
     * <p>
     * <b>长度前缀是 VarInt，不是 short。</b>1.7.10 的 {@code cpw.mods.fml.common.network.ByteBufUtils#writeUTF8String}
     * （{@code build/rfg/minecraft-src/.../ByteBufUtils.java:130-136}）先 {@code writeVarInt} 写长度再写字节；
     * 写端是本类与 {@code S2CConfigAction} 共用的 {@link #writeAction}（也就是用 {@code writeUTF8String} 写的），
     * 所以读端必须按 {@code readVarInt(..., 2)} 的形状读。曾经这里按 {@code getUnsignedShort} 读：32 字符的 identity
     * 的第一个字节就被当成长度高字节，解码立即越界 → 整包 {@code parsed=false} 被静默丢弃，表现为"设置点下去两端都没反应、
     * GUI 预览也不再变化"（phase78 实机回归）。VarInt 最多 2 字节（FML 的 maxSize=2），超过即视为坏包。
     */
    @Nullable
    static String readString(ByteBuf buf, int maxBytes) {
        int length = 0;
        int shift = 0;
        boolean complete = false;
        for (int bytesRead = 0; bytesRead < 2; bytesRead++) {
            if (!buf.isReadable()) {
                return null;
            }
            int value = buf.readUnsignedByte();
            length |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) {
                complete = true;
                break;
            }
            shift += 7;
        }
        if (!complete || length < 0 || length > maxBytes || length > buf.readableBytes()) {
            return null;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 服务端 handler
    // ---------------------------------------------------------------------------------------------------------------

    public static class Handler implements IMessageHandler<C2SConfigAction, IMessage> {

        @Override
        public IMessage onMessage(C2SConfigAction message, MessageContext ctx) {
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null || message == null || !message.parsed || message.action == null) {
                    // 解码失败的包在 fromBytes 里已经 warn；这里再留一条诊断，避免"点下去两端都没反应"无从排查。
                    trace("server dropped: undecodable packet or no sender (parsed={})", message != null && message.parsed);
                    return null;
                }
                final String identity = message.identity;
                final ConfigAction action = message.action;
                // 服务端线程：读 EEP/模型声明并广播，与相邻 EEP 写入口同一约定（SetModelAndTexture / C2SRoamingChanges）。
                DeferredWork.server(() -> handle(sender, identity, action));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM config-action packet", e);
            }
            return null;
        }

        /** 照上游 {@code ControlHandler.authorizeConfigAction}（{@code :216-226}）的顺序逐条校验后广播。 */
        private static void handle(EntityPlayerMP sender, String identity, ConfigAction action) {
            try {
                if (sender.isDead || sender.worldObj == null) {
                    trace("server dropped: sender {} is dead or has no world", sender.getCommandSenderName());
                    return;
                }
                ExtendedModelInfo info = ExtendedModelInfo.get(sender);
                if (info == null || info.getModelId() == null) {
                    trace("server dropped: {} has no selected model", sender.getCommandSenderName());
                    return;
                }
                ResourceLocation modelId = info.getModelId();
                ConfigDeclarations declarations = ServerModelManager.configDeclarationsFor(modelId);
                if (declarations == null) {
                    // 契约 §8 第 3/4 条：服务端没有该模型声明（未知模型 / 客户端本地注册模型），无上游对应可校验，
                    // 静默丢弃。
                    trace("server dropped: no declarations for model {} (action={})", modelId, action);
                    return;
                }
                // identity：上游 matchesServerAuthority（ExpressionProtocol:55-70）逐字节比对；移植的同一份事实是
                // 模型内容 sha256。声明没有 sha256（legacy 平铺模型）时无从比对，跳过——与接收端"空 identity 不校验"
                // 同一规则。
                String expectedIdentity = declarations.identity();
                if (!expectedIdentity.isEmpty() && !expectedIdentity.equals(identity)) {
                    trace(
                        "server dropped: identity mismatch model={} packet='{}' server='{}'",
                        modelId,
                        identity,
                        expectedIdentity);
                    return;
                }
                if (!declarations.accepts(action)) {
                    trace("server dropped: declarations of {} do not accept action {}", modelId, action);
                    return;
                }
                trace("server accepted: model={} action={} -> broadcast", modelId, action);
                // 上游广播的事件带服务端自己算出的 identity（:223-225 用 message.identity()，只在已与本地权威相等
                // 时到达这里）；这里发服务端声明里的那一份，收件端拿它和本地模型内容哈希比对。
                S2CConfigAction event = new S2CConfigAction(sender.getEntityId(), expectedIdentity, action);
                // sendToTrackingPlayers 包含发送者自己（1.7.10 sendToAllAround 对自身距离 0 通过范围判定），
                // 与上游 broadcastToVisiblePlayersAndSelf（ControlHandler.java:111）一致：发送端不本地执行，靠回显。
                NetworkHandler.sendToTrackingPlayers(event, sender, 128.0d);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to authorize a YSM config action", e);
            }
        }

        /**
         * 与客户端 {@code ConfigActionRuntime} 同一条诊断日志（{@code Config.DEBUG_MODEL_LOAD} 门控）：把服务端静默拒绝
         * 的那几个校验点变成可查的日志，不改语义（上游同样静默）。
         */
        private static void trace(String format, Object... args) {
            if (Config.DEBUG_MODEL_LOAD) {
                ysmu.LOG.info("[YSMU-CONFIG] " + format, args);
            }
        }
    }
}

