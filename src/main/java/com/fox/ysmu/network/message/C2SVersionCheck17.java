package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.Config;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class C2SVersionCheck17 implements IMessage {

    private String version = "";

    public C2SVersionCheck17() {}

    public C2SVersionCheck17(String version) {
        this.version = version == null ? "" : version;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.version = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.version);
    }

    public String getVersion() {
        return version;
    }

    public static class Handler implements IMessageHandler<C2SVersionCheck17, IMessage> {

        @Override
        public IMessage onMessage(C2SVersionCheck17 message, MessageContext ctx) {
            // N-10: guard the handler body (startSync iterates the server model index, which /ysm reload rebuilds).
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null || !Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) {
                    return null;
                }
                if (!NetworkHandler.PROTOCOL_VERSION.equals(message.version)) {
                    ysmu.LOG.warn(
                        "Skipping OpenYSM model sync for {} because protocol versions differ: client={}, server={}",
                        sender.getCommandSenderName(),
                        message.version,
                        NetworkHandler.PROTOCOL_VERSION);
                    return null;
                }
                // 17 是附加的快速路径:legacy 请求已由 ServerModelManager.sendRequestSyncModelMessage 无条件发出,
                // 所以这里不接任何 gate,也不关心 startSync 的返回值 —— 17 起不来时 legacy 那一路照样送达。
                OpenYsmModelSyncServer.startSync(sender);
            } catch (Exception e) {
                // legacy 的送达与 17 是否成功无关(见上),异常只记录,不做通道交接。
                ysmu.LOG.warn("Failed to start OpenYSM model sync", e);
            }
            return null;
        }
    }
}
