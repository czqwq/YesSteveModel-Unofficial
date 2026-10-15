package com.fox.ysmu.network.message;

import java.util.List;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.debug.MolangDebugSnapshot;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: query Molang variable values and display in chat.
 * Triggered by {@code /ysm debug query <name>}.
 *
 * 客户端收到后读取当前 Molang 变量值并直接在本地聊天框输出，
 * 无需服务端回包（所有值均为客户端本地状态）。
 *
 * <p>整套消费者挂在默认关闭的 {@link Config#DEBUG_MOLANG_OVERLAY} 之后：开关关闭时这里直接丢弃消息。</p>
 */
public class PacketQueryMolangVar implements IMessage {

    private List<String> varNames;

    public PacketQueryMolangVar() {}

    public PacketQueryMolangVar(List<String> varNames) {
        this.varNames = varNames;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int size = buf.readInt();
        varNames = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            varNames.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(varNames.size());
        for (String name : varNames) {
            ByteBufUtils.writeUTF8String(buf, name);
        }
    }

    public List<String> getVarNames() {
        return varNames;
    }

    public static class Handler implements IMessageHandler<PacketQueryMolangVar, IMessage> {
        @Override
        public IMessage onMessage(PacketQueryMolangVar msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            if (!Config.DEBUG_MOLANG_OVERLAY) return null;
            if (msg.varNames == null || msg.varNames.isEmpty()) return null;

            // 调度到主线程执行，确保 player 状态（motionY, onGround 等）可正确读取
            final List<String> names = msg.varNames;
            Minecraft.getMinecraft().func_152344_a(new Runnable() {
                @Override
                public void run() {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.thePlayer == null) return;
                    mc.thePlayer.addChatMessage(MolangDebugSnapshot
                        .prefixed("message.yes_steve_model.debug.querying", names.size()));
                    // 帧快照是"上一帧 end() 时捕获的那一份"（见 MolangDebugSnapshot 的移植注：按需捕获，
                    // 参考分支每帧无条件复制，本仓只在需要时复制）。所以先请求捕获，再等下一帧渲染完读 ——
                    // 这一跳 func_152344_a 在下一个客户端 tick 执行，而那一帧的渲染已经跑过 end()，
                    // 读到的是请求之后捕获的新表，不是请求之前的旧表。
                    MolangDebugSnapshot.requestCapture();
                    mc.func_152344_a(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                for (String name : names) {
                                    MolangDebugSnapshot.printSingleToChat(name);
                                }
                            } finally {
                                MolangDebugSnapshot.releaseCapture();
                            }
                        }
                    });
                }
            });
            return null;
        }
    }
}
