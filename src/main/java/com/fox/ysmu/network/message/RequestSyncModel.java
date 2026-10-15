package com.fox.ysmu.network.message;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class RequestSyncModel implements IMessage {

    public RequestSyncModel() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<RequestSyncModel, IMessage> {

        @Override
        public IMessage onMessage(RequestSyncModel message, MessageContext ctx) {
            // N-10: guard the handler body. The deduplication/fallback half of N-02 lives outside this task
            // (ClientModelManager and the server trigger); this only keeps a failure from disconnecting the client.
            try {
                if (ctx.side == Side.CLIENT) {
                    ClientModelManager.sendSyncModelMessage();
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to start YSM model sync", e);
            }
            return null;
        }
    }
}
