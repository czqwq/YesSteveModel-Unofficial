package com.fox.ysmu.network.message;

import com.fox.ysmu.client.upload.UploadManager;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * N-09 (deprecated): id 12 is registered but has no sender anywhere in this repository. It belongs to the OpenYSM
 * upload feature, and {@code UploadManager} is an unwired placeholder. The id stays reserved and must NOT be
 * renumbered or reused; do not grow this handler into a real upload path here.
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
