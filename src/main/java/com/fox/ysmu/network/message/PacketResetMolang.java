package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.molang.MolangReset;
import com.fox.ysmu.client.debug.MolangDebugSnapshot;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;

/**
 * Server → Client: reset Molang variable state. Triggered by
 * {@code /ysm reset <selector>} (resetting OTHER players; resetting yourself
 * is client-side via {@code /ysmclient reset}).
 *
 * 客户端收到后清空自己的 Molang 变量状态（漫游变量、帧间物理/控制器状态、v.*
 * 残留），保留模型定义与实时 query/ysm 变量。模型文件缓存不受影响，无需重载。
 *
 * <p>整套消费者挂在默认关闭的 {@link Config#DEBUG_MOLANG_OVERLAY} 之后：开关关闭时这里直接丢弃消息
 * （{@code /ysmclient} 命令本身在开关关闭时也不会注册）。</p>
 */
public class PacketResetMolang implements IMessage {

    public PacketResetMolang() {}

    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {}

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketResetMolang, IMessage> {
        @Override
        public IMessage onMessage(PacketResetMolang msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            if (!Config.DEBUG_MOLANG_OVERLAY) return null;
            // 调度到主线程执行，确保与渲染线程的访问一致
            Minecraft.getMinecraft().func_152344_a(new Runnable() {
                @Override
                public void run() {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.thePlayer == null) return;
                    MolangReset.resetSelf();
                    mc.thePlayer.addChatMessage(
                        MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.reset_done"));
                }
            });
            return null;
        }
    }
}
