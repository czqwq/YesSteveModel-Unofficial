package com.fox.ysmu.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetModelAndTexture;
import com.fox.ysmu.network.message.SetNpcModelAndTexture;

/**
 * Player target: state lives in {@link ExtendedModelInfo}. The local player updates itself; another player
 * reached through a companion mod's picker is updated as an NPC keyed by the granted entity id, which is held
 * on this instance rather than in a static field.
 */
final class PlayerModelTarget implements ModelSelectionTarget {

    private final EntityPlayer player;
    private final int npcId;

    PlayerModelTarget(EntityPlayer player, int npcId) {
        this.player = player;
        this.npcId = npcId;
    }

    @Override
    public EntityLivingBase getPreviewEntity() {
        return player;
    }

    @Override
    public ResourceLocation getModelId() {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        return eep == null ? null : eep.getModelId();
    }

    @Override
    public ResourceLocation getTextureId() {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        return eep == null ? null : eep.getSelectTexture();
    }

    @Override
    public boolean supportsStars() {
        return true;
    }

    @Override
    public boolean isStarred(ResourceLocation modelId) {
        ExtendedStarModels stars = ExtendedStarModels.get(player);
        return stars != null && modelId != null && stars.containModel(modelId);
    }

    @Override
    public int getGrantId() {
        return isLocalPlayer() ? -1 : npcId;
    }

    @Override
    public void apply(ResourceLocation modelId, ResourceLocation textureId) {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null) {
            eep.setModelAndTexture(modelId, textureId);
        }
        if (isLocalPlayer()) {
            NetworkHandler.CHANNEL.sendToServer(new SetModelAndTexture(modelId, textureId));
        } else {
            NetworkHandler.CHANNEL.sendToServer(new SetNpcModelAndTexture(modelId, textureId, npcId));
        }
    }

    private boolean isLocalPlayer() {
        return player.equals(Minecraft.getMinecraft().thePlayer);
    }
}
