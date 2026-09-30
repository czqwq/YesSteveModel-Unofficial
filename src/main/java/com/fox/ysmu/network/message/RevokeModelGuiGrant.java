package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.api.ModelGuiApi;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * NF-01 server side: the client tells the server that the model selection GUI it opened through
 * {@code api/ModelGuiApi.openModelGui} was closed, so the temporary one-entity selection grant can be dropped
 * immediately instead of waiting for its 5 minute TTL.
 * <p>
 * The payload is a single entity id - the same id the client was handed as the grant id - and the server never
 * trusts it for anything except releasing a grant that belongs to the sending player, so a client cannot revoke
 * somebody else's grant.
 * <p>
 * The client send line itself is not part of this task: {@code PlayerModelScreen.onGuiClosed} already reads
 * {@code ModelSelectionTarget.getGrantId()} and carries the TODO for it, but that file lives in
 * {@code client/gui/**}, outside this task's inScope. Until it is switched on, deduplicated grants still expire
 * through the ModelGuiApi TTL, on logout and on world unload.
 */
public class RevokeModelGuiGrant implements IMessage {

    private int entityId;

    public RevokeModelGuiGrant() {}

    public RevokeModelGuiGrant(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
    }

    public int getEntityId() {
        return entityId;
    }

    public static class Handler implements IMessageHandler<RevokeModelGuiGrant, IMessage> {

        @Override
        public IMessage onMessage(RevokeModelGuiGrant message, MessageContext ctx) {
            // N-10: guard the handler body - an exception here would be turned into a disconnect by
            // FMLProxyPacket.processPacket -> rejectHandshake.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender != null && message.entityId >= 0) {
                    // Only ever touches the sender's own grants (ModelGuiApi keys them by player UUID).
                    ModelGuiApi.revokeSelectionGrant(sender, message.entityId);
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to revoke a YSM model selection grant", e);
            }
            return null;
        }
    }
}
