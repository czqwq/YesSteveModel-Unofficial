package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.manage.ServerModelManage;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 客户端到服务端（phase55，LgeacyYSM 的 {@code RefreshModelManage}）：管理界面在删除/移动/重命名/上传之后
 * 请求服务端重列 {@code config/ysmu/custom} 与 {@code config/ysmu/auth}。
 *
 * <p>Lgeacy 的这个包自带权限检查（{@code hasPermissions(4)}）并把结果用 {@link RequestServerModelInfo}
 * 发回；端口保持同一形状，只把"没有权限"从静默丢弃改成一句聊天提示（本仓其它命令的惯例，GUI 里否则会看起来
 * 毫无反应）。列表由 {@link ServerModelManage} 生成。</p>
 */
public class RefreshModelManage implements IMessage {

    public RefreshModelManage() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 空载荷：不读任何字段，因此不会被截断包影响。
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 空载荷。
    }

    public static class Handler implements IMessageHandler<RefreshModelManage, IMessage> {

        @Override
        public IMessage onMessage(RefreshModelManage message, MessageContext ctx) {
            if (ctx.side != Side.SERVER) {
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
                // 文件枚举很轻（两次顶层 list），但仍排在服务端线程上，不在 Netty 线程碰文件系统。
                com.fox.ysmu.util.DeferredWork.server(() -> sendModelInfo(sender));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle a YSM model-manage refresh", e);
            }
            return null;
        }

        private static void sendModelInfo(EntityPlayerMP sender) {
            try {
                NetworkHandler.CHANNEL.sendTo(
                    new RequestServerModelInfo(
                        ServerModelManage.listEntries(ServerModelManager.CUSTOM),
                        ServerModelManage.listEntries(ServerModelManager.AUTH)),
                    sender);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to list the YSM model directories for {}", sender.getCommandSenderName(), e);
            }
        }
    }
}
