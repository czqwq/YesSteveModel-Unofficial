package com.fox.ysmu.client.gui;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetNpcModelAndTexture;

/**
 * Non-player target: state lives in {@link NPCData} on this client; the selection is sent as an NPC update keyed by
 * the id the server used to open the GUI.
 */
final class EntityModelTarget implements ModelSelectionTarget {

    private final EntityLivingBase entity;
    private final int npcId;

    EntityModelTarget(EntityLivingBase entity, int npcId) {
        this.entity = entity;
        this.npcId = npcId;
    }

    @Override
    public EntityLivingBase getPreviewEntity() {
        return entity;
    }

    @Override
    public ResourceLocation getModelId() {
        EntityModelData data = NPCData.getData(entity);
        return data == null ? null : data.getModelId();
    }

    @Override
    public ResourceLocation getTextureId() {
        EntityModelData data = NPCData.getData(entity);
        return data == null ? null : data.getTextureId();
    }

    @Override
    public boolean supportsStars() {
        return false;
    }

    @Override
    public int getGrantId() {
        return npcId;
    }

    @Override
    public boolean isStarred(ResourceLocation modelId) {
        return false;
    }

    @Override
    public void apply(ResourceLocation modelId, ResourceLocation textureId) {
        // Optimistic local update; the server rebroadcast confirms it.
        NPCData.put(entity, modelId, textureId);
        NetworkHandler.CHANNEL.sendToServer(new SetNpcModelAndTexture(modelId, textureId, npcId));
    }
}
