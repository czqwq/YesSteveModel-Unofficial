package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.client.debug.MolangDebugSnapshot;
import com.fox.ysmu.client.gui.debug.DebugOverlay;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: {@code /ysm debug overlay [on|off|toggle]} 的客户端执行体。
 *
 * <p>Debug Overlay 是纯客户端状态（{@code DebugOverlay} 直接引用
 * {@code net.minecraft.client.*} 与 LWJGL），原先服务端命令在服务端调用它：连专用服务器时
 * 服务端根本没有那些类，子命令会以类解析失败告终。服务端只负责转发请求，真正的切换与反馈
 * 在目标客户端做（与 {@link S2CPlaySound} 同一模式）。</p>
 *
 * <p>overlay 本身挂在默认关闭的 {@code Config.DEBUG_MOLANG_OVERLAY} 之后：
 * {@link DebugOverlay#toggle()} 在开关关闭时不会打开它，所以这个包在默认配置下是空操作。</p>
 */
public class S2CSetDebugOverlay implements IMessage {

    public static final byte MODE_OFF = 0;
    public static final byte MODE_ON = 1;
    public static final byte MODE_TOGGLE = 2;

    private byte mode = MODE_TOGGLE;

    public S2CSetDebugOverlay() {}

    public S2CSetDebugOverlay(byte mode) {
        this.mode = mode;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.mode = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(mode);
    }

    public byte getMode() {
        return mode;
    }

    public static class Handler implements IMessageHandler<S2CSetDebugOverlay, IMessage> {

        @Override
        public IMessage onMessage(S2CSetDebugOverlay message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            final byte mode = message.mode;
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    boolean wasActive = DebugOverlay.isActive();
                    if (mode == MODE_OFF) {
                        if (wasActive) DebugOverlay.toggle();
                    } else if (mode == MODE_ON) {
                        if (!wasActive) DebugOverlay.toggle();
                    } else {
                        DebugOverlay.toggle();
                    }
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.thePlayer != null) {
                        mc.thePlayer.addChatMessage(MolangDebugSnapshot.prefixed(DebugOverlay.isActive()
                            ? "message.yes_steve_model.debug.overlay_state_on"
                            : "message.yes_steve_model.debug.overlay_state_off"));
                    }
                    if (DebugOverlay.isActive()) {
                        DebugOverlay.tryShowToggleHint();
                    }
                });
            return null;
        }
    }
}
