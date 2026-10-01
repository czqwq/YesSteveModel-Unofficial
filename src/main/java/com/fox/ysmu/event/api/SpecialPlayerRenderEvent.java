package com.fox.ysmu.event.api;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.client.entity.CustomPlayerEntity;

import cpw.mods.fml.common.eventhandler.Cancelable;
import cpw.mods.fml.common.eventhandler.Event;

/**
 * Posted before a living entity is rendered through YSM's replacement renderer.
 * <p>
 * The event was originally player-only. It now carries any {@link EntityLivingBase} so NPCs and modded
 * companions (for example Touhou Little Maid's maids) can be handed to the same pipeline. Player-only
 * listeners keep using {@link #getPlayer()}; new non-player listeners use {@link #getEntity()}.
 */
@Cancelable
public class SpecialPlayerRenderEvent extends Event {

    private final EntityLivingBase entity;
    private final CustomPlayerEntity customPlayer;
    private final ResourceLocation modelId;
    /**
     * A texture this render must use instead of the model's own, or {@code null}.
     * <p>
     * Mirrors upstream's {@code SpecialPlayerRenderEvent#setTextureLocationOverride}: it is how a listener supplies a
     * texture that is not one of the pack's - the built-in {@code steve}/{@code alex} models use the player's own
     * Minecraft skin, and a companion mod may have its own. The renderer and the first-person arm both prefer it over
     * the model's texture, which is the only place a texture outside the model's own list is allowed.
     */
    @Nullable
    private ResourceLocation textureLocationOverride;

    public SpecialPlayerRenderEvent(EntityLivingBase entity, CustomPlayerEntity customPlayer,
        ResourceLocation modelId) {
        this.entity = entity;
        this.customPlayer = customPlayer;
        this.modelId = modelId;
    }

    /** The entity being rendered; may be a player, an NPC or any other living entity. */
    public EntityLivingBase getEntity() {
        return entity;
    }

    /**
     * The rendered entity when it is a player.
     *
     * @return the player, or {@code null} when {@link #getEntity()} is not a player.
     */
    @Nullable
    public EntityPlayer getPlayer() {
        return entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
    }

    public CustomPlayerEntity getCustomPlayer() {
        return customPlayer;
    }

    public ResourceLocation getModelId() {
        return modelId;
    }

    /** The texture this render must use instead of the model's own; see the field's own note. */
    @Nullable
    public ResourceLocation getTextureLocationOverride() {
        return textureLocationOverride;
    }

    public void setTextureLocationOverride(@Nullable ResourceLocation textureLocationOverride) {
        this.textureLocationOverride = textureLocationOverride;
    }
}
