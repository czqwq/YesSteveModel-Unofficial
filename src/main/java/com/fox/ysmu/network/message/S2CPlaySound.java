package com.fox.ysmu.network.message;

import com.fox.ysmu.client.audio.YSMSoundManager;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: {@code /ysm playsound [name]} 的客户端执行体。
 *
 * <p>音效库与播放能力都只在客户端（{@code YSMSoundManager} 走 {@code Minecraft.getMinecraft()}
 * 的 SoundHandler / paulscode SoundSystem，模型音效字节也是客户端按需解密）。原先服务端命令
 * 直接调用它：连专用服务器时命令是在**服务端**执行的，{@code Minecraft.getMinecraft()} 在那边
 * 没有实例，于是「列出缓存音效」永远为空、"播放"会在取 SoundHandler 时失败。</p>
 *
 * <p>所以服务端只负责把这个请求转给发起命令的玩家自己的客户端（与
 * {@code ShowBufferInfo}/{@code PacketQueryMolangVar}/{@code SetWelcomePath} 等子命令同一模式：
 * 服务端校验权限，真正需要客户端状态的动作在客户端做）。客户端动作本身住在 {@link YSMSoundManager}，
 * 本类**不得出现任何 Minecraft 客户端类型**：FML 在专用服务器注册消息时会反射实例化本类与 Handler，
 * 验证器会因此解析这类类型，而 1.7.10 的 {@code Minecraft.thePlayer} 是
 * {@code net.minecraft.client.entity.EntityClientPlayerMP}（服务端被 SideTransformer 拒绝）。</p>
 *
 * <p>{@code soundName} 为空表示"列出本客户端已知的音效"。</p>
 */
public class S2CPlaySound implements IMessage {

    private String soundName = "";

    public S2CPlaySound() {}

    public S2CPlaySound(String soundName) {
        this.soundName = soundName == null ? "" : soundName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.soundName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, soundName == null ? "" : soundName);
    }

    public static class Handler implements IMessageHandler<S2CPlaySound, IMessage> {

        @Override
        public IMessage onMessage(S2CPlaySound message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            // 一次委托，客户端细节全在那边（本类在服务端注册时也会被实例化，见类型 javadoc）。
            YSMSoundManager.handlePlaySoundRequest(message.soundName);
            return null;
        }
    }
}
