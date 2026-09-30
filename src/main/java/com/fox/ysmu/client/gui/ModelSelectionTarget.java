package com.fox.ysmu.client.gui;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * What the model selection GUI edits.
 * <p>
 * The screen used to be hard-wired to an {@link EntityPlayer}. Making the edited object explicit lets the same
 * screen drive a non-player entity whose model lives in {@link com.fox.ysmu.data.NPCData}, which is how a
 * companion mod hands a maid over for in-game model selection.
 */
public interface ModelSelectionTarget {

    /** The entity drawn in the preview box. */
    EntityLivingBase getPreviewEntity();

    /** Model currently applied to the target, or {@code null} when none is set. */
    ResourceLocation getModelId();

    /** Texture currently applied to the target, or {@code null} when none is set. */
    ResourceLocation getTextureId();

    /** Whether the star/collection category applies; only player targets keep a collection. */
    boolean supportsStars();

    boolean isStarred(ResourceLocation modelId);

    /** Applies the selection locally and tells the server, which re-broadcasts it. */
    void apply(ResourceLocation modelId, ResourceLocation textureId);

    /**
     * The entity id the server granted permission for, so the screen can revoke it when it closes, or
     * {@code -1} when this target needs no grant (the local player editing itself).
     * <p>
     * NF-01: the revoke is <b>not wired end to end yet</b> — it needs a C2S packet that does not exist (the
     * packet belongs to {@code network/**}). Until then the server side only drops a grant through
     * {@code api/ModelGuiApi}'s 5-minute TTL, on logout, or on world unload; {@code PlayerModelScreen}
     * closes the loop as far as the client can, by consulting this id in {@code onGuiClosed()}.
     */
    default int getGrantId() {
        return -1;
    }

    /**
     * Target for a player. {@code player} may be {@code null} when the screen is opened before a world is
     * joined; {@link PlayerModelTarget} then answers safe values ({@code null} model/texture, no-op apply),
     * which is what keeps the legacy no-argument {@code PlayerModelScreen} constructor from crashing.
     */
    static ModelSelectionTarget of(EntityPlayer player) {
        return new PlayerModelTarget(player, -1);
    }

    /** A player reached through an NPC picker; {@code npcId} is the id the server granted. */
    static ModelSelectionTarget of(EntityPlayer player, int npcId) {
        return new PlayerModelTarget(player, npcId);
    }

    static ModelSelectionTarget of(EntityLivingBase entity, int npcId) {
        return new EntityModelTarget(entity, npcId);
    }
}
