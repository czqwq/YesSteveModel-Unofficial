package com.fox.ysmu.network.message;

import com.fox.ysmu.client.upload.UploadManager;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端到客户端（phase55）：一次分片模型上传已经结束，客户端可以离开"处理中"状态。LgeacyYSM 的
 * {@code CompleteFeedback}（id 12）就是这个用途，本仓此前是"注册但没有发送方"的占位（N-09）。
 *
 * <p>发送方：{@code ServerModelUploadManager} 在每次上传终态（成功、内容被拒、写盘失败、权限拒绝、传输被丢弃）
 * 发送一次。消费方：{@link UploadManager#finishUpload()}——复位上传状态，并让打开着的
 * {@code ModelManageScreen} 重新拉取列表（它是异步上传，确认后立刻刷新会看不到刚上传的条目）。</p>
 *
 * <p>id 12 是线协议的一部分，不重编号、不复用；空载荷形状保持与 Lgeacy 相同。</p>
 */
public class CompleteFeedback implements IMessage {

    public CompleteFeedback() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<CompleteFeedback, IMessage> {

        @Override
        public IMessage onMessage(CompleteFeedback message, MessageContext ctx) {
            // N-10: keep a throwing body from reaching FML's catch(Throwable) -> rejectHandshake.
            try {
                UploadManager.finishUpload();
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to finish YSM model upload", e);
            }
            return null;
        }
    }
}
