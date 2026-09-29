package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SetPlayAnimation implements IMessage {

    private static final int STOP = -1;
    private int extraAnimationId;

    public SetPlayAnimation() {}

    public SetPlayAnimation(int extraAnimationId) {
        this.extraAnimationId = extraAnimationId;
    }

    public static SetPlayAnimation stop() {
        return new SetPlayAnimation(STOP);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.extraAnimationId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.extraAnimationId);
    }

    public static class Handler implements IMessageHandler<SetPlayAnimation, IMessage> {

        @Override
        public IMessage onMessage(SetPlayAnimation message, MessageContext ctx) {
            // N-10: guard the handler body; an exception here would be turned into a disconnect by FML.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender != null && STOP <= message.extraAnimationId && message.extraAnimationId < 8) {
                    handleEEP(message, sender);
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM play-animation packet", e);
            }
            return null;
        }

        private void handleEEP(SetPlayAnimation message, EntityPlayerMP sender) {
            ExtendedModelInfo modelIdEEP = ExtendedModelInfo.get(sender);
            if (modelIdEEP != null) {
                if (message.extraAnimationId == STOP) {
                    modelIdEEP.stopAnimation();
                } else {
                    modelIdEEP.playAnimation("extra" + message.extraAnimationId);
                }
            }
        }
    }
}
