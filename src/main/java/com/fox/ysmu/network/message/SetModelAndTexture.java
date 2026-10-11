package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.api.ModelAuthorization;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SetModelAndTexture implements IMessage {

    private String modelId;
    private String selectTexture;

    public SetModelAndTexture() {}

    public SetModelAndTexture(ResourceLocation modelId, ResourceLocation selectTexture) {
        this.modelId = modelId.toString();
        this.selectTexture = selectTexture.toString();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = ByteBufUtils.readUTF8String(buf);
        this.selectTexture = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        ByteBufUtils.writeUTF8String(buf, this.selectTexture);
    }

    public static class Handler implements IMessageHandler<SetModelAndTexture, IMessage> {

        @Override
        public IMessage onMessage(SetModelAndTexture message, MessageContext ctx) {
            // N-10: the body itself runs through DeferredWork (whose drain catches Throwable), but the onMessage
            // part is still guarded so nothing can escape into FML's catch(Throwable) -> rejectHandshake.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null) {
                    return null;
                }
                String model = message.modelId;
                String texture = message.selectTexture;
                DeferredWork.server(() -> handleEEP(sender, model, texture));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM set-model packet", e);
            }
            return null;
        }

        /**
         * Applies the client's selection after the authorization check.
         * <p>
         * <b>Authorization (E1).</b> Before this check the handler turned whatever string the client sent straight
         * into a {@code ResourceLocation} and stored it - any client could apply any model. Upstream decides this in
         * {@code ServerModelSession.select} ({@code
         * tmp/YesSteveModel-dev-1.20/.../model/session/server/ServerModelSession.java:147-180}): an unauthorized
         * request does <em>not</em> keep the previous selection, it resets the session to the intrinsic default
         * ({@code :163-167} calls {@code selectIntrinsicDefault}), and the protocol handler then writes that default
         * into the player's persistence ({@code network/forge/SessionProtocolHandler.java:563-573}). The port has no
         * separate session layer, so the same observable outcome is produced directly: an unauthorized selection is
         * replaced by the default selection and the player is told why. {@code api/ModelAuthorization} holds the
         * decision and its default-allow rule.
         */
        private static void handleEEP(EntityPlayerMP sender, String model, String texture) {
            ResourceLocation modelLoc = model.isEmpty() ? null : new ResourceLocation(model);
            ResourceLocation textureLoc = texture.isEmpty() ? null : new ResourceLocation(texture);
            if (!ModelAuthorization.maySelect(sender, modelLoc)) {
                ResourceLocation defaultModel = ExtendedModelInfo.defaultModelId();
                ysmu.LOG.info(
                    "Rejecting unauthorized YSM model selection {} for {}; resetting to the default model",
                    model,
                    sender.getCommandSenderName());
                sender.addChatMessage(
                    new ChatComponentTranslation(
                        "message.yes_steve_model.auth.denied",
                        ModelIdUtil.getModelDisplayName(modelLoc)));
                applySelection(sender, defaultModel, ExtendedModelInfo.defaultTextureId(defaultModel));
                return;
            }
            applySelection(sender, modelLoc, textureLoc);
        }
    }

    /**
     * Writes one server-authoritative selection and propagates it.
     * <p>
     * Shared by the packet path above and by the revoke path of {@code /ysm auth remove|clear}, which resets a player
     * whose model is no longer authorized - the same split upstream has between its packet handler and
     * {@code AuthCommand}'s reset, both funnelling into {@code ControlHandler.applyAcceptedModelSelection}
     * ({@code network/forge/ControlHandler.java:206-230}).
     */
    public static void applySelection(EntityPlayerMP sender, ResourceLocation modelLoc, ResourceLocation textureLoc) {
        ExtendedModelInfo modelInfo = ExtendedModelInfo.get(sender);
        if (modelInfo != null) {
            modelInfo.setModelAndTexture(modelLoc, textureLoc);
        }
        // The renderer lets an NPC override win over the player's own selection, so an explicit self
        // selection must drop any stale override for the same player or the picker looks broken.
        if (NPCData.contains(sender)) {
            NPCData.remove(sender);
            NetworkHandler.broadcastNpcDataRemoval(sender, sender.getEntityId());
        }
        // The vehicle this player owns carries the model they had when they mounted, so a model change while
        // riding has to rewrite it - upstream re-runs onVehicleSetModel from its own model-update path
        // (event/CapabilityEvent.java:198-211).
        S2CVehicleModelState.onOwnerModelChanged(sender);
    }
}
