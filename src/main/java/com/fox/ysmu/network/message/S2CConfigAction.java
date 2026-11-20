package com.fox.ysmu.network.message;

import javax.annotation.Nullable;

import com.fox.ysmu.client.roaming.ConfigActionRuntime;
import com.fox.ysmu.data.ConfigAction;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 → 客户端：一次被授权的「模型设置」动作，语义是上游 {@code ConfigActionEvent}（{@code
 * tmp/YesSteveModel-dev-1.20} 的 {@code network/protocol/control.proto:60-71}，id 28，8 KiB）。
 * <p>
 * 发送者是服务端：{@link C2SConfigAction.Handler} 校验通过后广播给跟踪该玩家的客户端<b>加发送者自己</b>
 * （上游 {@code ControlHandler.java:111} 的 {@code broadcastToVisiblePlayersAndSelf}）。收件端按
 * {@code subjectEntityId} 找到玩家，用<b>该玩家当前模型</b>的本地声明与报文 identity 复核，然后在那个玩家的真实
 * {@code (entity, model)} 作用域里执行本地模型载荷里同名 form 的写程序或 label 动作程序（上游
 * {@code ClientControlHandler.handleConfigAction}，{@code :55-69,112-129}）。程序从不进 wire。
 *
 * <p><b>布局（NETWORK_PROTOCOL 13 起）。</b>在 {@link C2SConfigAction} 的 {@code identity + action} 之前多一个
 * {@code int subjectEntityId}（上游 {@code ConfigActionEvent.subject.entityId}）：</p>
 *
 * <pre>
 * int subjectEntityId, identity : UTF8 string, buttonId : UTF8 string,
 * formIndex : unsigned short, kind : byte, payload（同 C2SConfigAction）
 * </pre>
 *
 * <p>解码失败（含越界 identity/button/form/kind）静默丢弃整包（N-10）。真正的实体解析、身份复核与执行都在客户端
 * 线程，由 {@link ConfigActionRuntime#receive(int, String, ConfigAction)} 完成；本类只把 payload 交过去。</p>
 */
public class S2CConfigAction implements IMessage {

    /** 与 {@link C2SConfigAction#MAX_PACKET_BYTES} 同一上限（上游 id 28 的 8 KiB）。 */
    public static final int MAX_PACKET_BYTES = C2SConfigAction.MAX_PACKET_BYTES;

    private int subjectEntityId;
    private String identity;
    private ConfigAction action;
    /** 解码是否完整成功；{@code false} 时 handler 丢弃整包。 */
    private boolean parsed;

    public S2CConfigAction() {}

    /** 服务端广播入口（{@link C2SConfigAction.Handler} 使用）。 */
    public S2CConfigAction(int subjectEntityId, @Nullable String identity, ConfigAction action) {
        this.subjectEntityId = subjectEntityId;
        this.identity = identity == null ? "" : identity;
        this.action = action;
        this.parsed = true;
    }

    public int subjectEntityId() {
        return this.subjectEntityId;
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
        // N-10：见 C2SConfigAction#fromBytes —— 先看总长、逐段与 readableBytes() 比较，失败静默丢弃，只在完整
        // 解析后赋值。
        boolean parsedHere = false;
        int parsedEntityId = 0;
        String parsedIdentity = null;
        ConfigAction parsedAction = null;
        try {
            if (buf.readableBytes() >= 4 && buf.readableBytes() <= MAX_PACKET_BYTES) {
                int entityId = buf.readInt();
                String identity = C2SConfigAction.readString(buf, C2SConfigAction.MAX_IDENTITY_BYTES);
                if (identity != null) {
                    ConfigAction action = C2SConfigAction.readAction(buf);
                    if (action != null && action.valid()) {
                        parsedHere = true;
                        parsedEntityId = entityId;
                        parsedIdentity = identity;
                        parsedAction = action;
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM config-action event", e);
        }
        this.subjectEntityId = parsedEntityId;
        this.identity = parsedIdentity;
        this.action = parsedAction;
        this.parsed = parsedHere;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.subjectEntityId);
        ByteBufUtils.writeUTF8String(buf, this.identity == null ? "" : this.identity);
        C2SConfigAction.writeAction(buf, this.action);
    }

    public static class Handler implements IMessageHandler<S2CConfigAction, IMessage> {

        @Override
        public IMessage onMessage(S2CConfigAction message, MessageContext ctx) {
            // The server instantiates every registered handler; this one is client-only work.
            if (ctx.side == Side.SERVER) {
                return null;
            }
            try {
                if (message == null || !message.parsed || message.action == null) {
                    return null;
                }
                final int subjectEntityId = message.subjectEntityId;
                final String identity = message.identity;
                final ConfigAction action = message.action;
                // Entity resolution, the local content-hash check and the Molang scope all belong to the client
                // thread, so the payload is handed over there (same shape as S2CExecuteMolang).
                DeferredWork.client(() -> ConfigActionRuntime.receive(subjectEntityId, identity, action));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle a YSM config-action event", e);
            }
            return null;
        }
    }
}
