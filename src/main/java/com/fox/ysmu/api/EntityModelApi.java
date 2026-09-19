package com.fox.ysmu.api;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.ysmu;

/**
 * Server-side entry point for attaching a YSM model and texture to any living entity.
 * <p>
 * This is the public surface a companion or NPC mod uses to hand an entity to YSMU. The server stays
 * authoritative: the registry is written here and every nearby client is told about the change, while a
 * joining client receives the whole registry through {@code SyncNpcDataMessage}.
 * <p>
 * Calls are ignored on the client; a client-side render override is still possible through
 * {@code com.fox.ysmu.client.renderer.EntityModelRenderApi}.
 */
public final class EntityModelApi {

    private EntityModelApi() {}

    public static boolean hasEntityModel(Entity entity) {
        return NPCData.contains(entity);
    }

    /**
     * Attaches or replaces an entity's model override on the logical server and broadcasts it.
     * <p>
     * A model the server does not have is refused rather than stored: writing an unknown id would make the
     * entity invisible on every client that also lacks it, so a failed return lets the caller surface the
     * problem instead.
     *
     * @param entity    the entity to re-skin; ignored when null or when called on a client world.
     * @param modelId   the YSM model id, for example {@code ysmu:example}.
     * @param textureId the texture within that model.
     * @return {@code true} when the override was stored and broadcast.
     */
    public static boolean setEntityModel(Entity entity, ResourceLocation modelId, ResourceLocation textureId) {
        if (entity == null || modelId == null || textureId == null || isClientSide(entity)) {
            return false;
        }
        if (!ServerModelManager.hasModel(modelId)) {
            ysmu.LOG.warn(
                "Refusing to apply unknown YSM model {} to entity id {}; the server has no such model loaded",
                modelId,
                entity.getEntityId());
            return false;
        }
        NPCData.put(entity.getEntityId(), modelId, textureId);
        NetworkHandler.broadcastNpcData(entity, entity.getEntityId(), modelId, textureId);
        return true;
    }

    /**
     * The model and texture an entity is currently wearing, or {@code null} when it wears none.
     * <p>
     * The read half of {@link #setEntityModel}, and the one a companion mod needs in order to notice a selection
     * the player made in YSMU's own picker: that screen writes straight through to this registry, so polling this
     * is how the mod that opened the picker learns what was picked. Unlike the writes it is safe on either side,
     * because a client holds the same registry through the sync message.
     *
     * @param entity the entity to ask about; {@code null} answers {@code null}.
     * @return what it wears, or {@code null}.
     */
    public static EntityModelData getEntityModel(Entity entity) {
        return entity == null ? null : NPCData.getData(entity);
    }

    /** Removes an entity's override on the logical server and broadcasts the removal. */
    public static void clearEntityModel(Entity entity) {
        if (entity == null || isClientSide(entity)) {
            return;
        }
        NPCData.remove(entity.getEntityId());
        NetworkHandler.broadcastNpcDataRemoval(entity, entity.getEntityId());
    }

    private static boolean isClientSide(Entity entity) {
        return entity.worldObj == null || entity.worldObj.isRemote;
    }
}
