package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.compat.ServerUtilitiesCompat;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.manage.ServerModelManage;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;
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
 *
 * <p><b>权限。</b>玩家必须通过 {@code command.ysmu.ysm.manage} 的 ServerUtilities rank 判定（显式 ALLOW 时
 * 跳过原版 4 级复检）或原版 4 级检查；未安装 ServerUtilities 或无 rank 条目时与原版逐字节一致。这是
 * {@code /ysm manage} 命令入口之外的第二道边界（同一节点），防止客户端绕过命令直接发刷新包。判定与拒绝提示都在
 * 服务端线程执行（{@link DeferredWork#server(Runnable)}，与文件枚举同一个延迟任务）—— ServerUtilities 的 rank
 * 表不是跨线程安全的，查询不能留在 Netty 线程。</p>
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
                // 权限判定与文件枚举都排在服务端线程上：ServerUtilities 的 rank 表不是为跨线程设计的，rank 查询与
                // 它自己的命令判定一样应留在服务端线程；拒绝提示也因此在服务端线程发出（反馈内容不变）。
                DeferredWork.server(() -> {
                    // ServerUtilities 的 rank 显式 ALLOW command.ysmu.ysm.manage 时跳过原版 4 级复检（与
                    // /ysm manage 命令入口同一节点、同一判定）；未安装或无 rank 条目时 permissionResult 返回
                    // DEFAULT，行为不变。
                    if (!ServerUtilitiesCompat.isExplicitlyAllowed(sender, ServerUtilitiesCompat.Subcommand.MANAGE)
                        && !sender.canCommandSenderUseCommand(4, "ysm")) {
                        sender.addChatMessage(new ChatComponentTranslation("commands.generic.permission"));
                        return;
                    }
                    sendModelInfo(sender);
                });
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
