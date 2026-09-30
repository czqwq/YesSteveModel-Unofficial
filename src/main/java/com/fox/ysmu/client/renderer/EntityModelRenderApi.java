package com.fox.ysmu.client.renderer;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientProxy;
import com.fox.ysmu.data.NPCData;

/**
 * Client-side entry point for rendering a non-player entity through YSM's replacement renderer.
 * <p>
 * A companion or NPC mod that owns its own renderer should register the entity's model once, then call
 * {@link #render} from its render pass - the same coordinates it would pass to a vanilla renderer. YSMU sets
 * the shared animatable, posts {@code SpecialPlayerRenderEvent} so other listeners can still override the
 * texture, and draws the GeckoLib model.
 * <p>
 * {@link #render} returns whether a frame was drawn, so a caller can fall back to its own renderer when this
 * client has not loaded the model. {@link #isModelLoaded} answers the same question without drawing.
 * <p>
 * Mods whose renderer still routes through {@code RendererLivingEntity#doRender} do not need to call this;
 * YSMU intercepts {@code RenderLivingEvent.Pre} for entities already present in the registry.
 */
public final class EntityModelRenderApi {

    private EntityModelRenderApi() {}

    /** Whether the entity currently has a model override registered on this client. */
    public static boolean hasModel(EntityLivingBase entity) {
        return NPCData.contains(entity);
    }

    /** Whether this client can actually draw the entity's registered override. */
    public static boolean isModelLoaded(EntityLivingBase entity) {
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
        return renderer != null && entity != null && renderer.hasModelFor(entity);
    }

    /** Registers a client-side override, for example from a packet the companion mod handles itself. */
    public static void setModel(EntityLivingBase entity, ResourceLocation modelId, ResourceLocation textureId) {
        NPCData.put(entity, modelId, textureId);
    }

    public static void clearModel(EntityLivingBase entity) {
        NPCData.remove(entity);
    }

    /**
     * Renders the entity with the model currently registered for it.
     *
     * @param partialTicks partial render tick, as passed to the caller's render method.
     * @return {@code true} when YSM produced a frame; {@code false} when the model this entity asked for is
     *         not loaded on this client, so the caller should render the entity itself instead of allowing
     *         YSM's generic default model to stand in for it.
     */
    public static boolean render(EntityLivingBase entity, double x, double y, double z, float entityYaw,
        float partialTicks) {
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
        if (renderer == null) {
            return false;
        }
        return renderer.doRenderModel(entity, x, y, z, entityYaw, partialTicks);
    }
}
