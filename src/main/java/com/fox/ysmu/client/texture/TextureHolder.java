package com.fox.ysmu.client.texture;

import net.minecraft.util.ResourceLocation;

/**
 * The weak handle an owner keeps for a managed texture, ported from upstream's
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/texture/TextureHolder.java:5-7}.
 *
 * <p>Handing out a holder instead of the texture itself is what makes the release trigger expressible: the owner holds
 * the holder for exactly as long as it needs the texture, and {@link CustomTextureManager} keeps only a weak view of
 * it, so dropping the holder is what eventually releases the texture.
 *
 * <p>Upstream's interface has one method, {@code id()}, because in 1.20 the synthetic id it returns is the id the
 * caller must bind. In this port the id is the model's own texture id - the one every render site already binds and
 * the one the registration was made under - so {@code id()} answers that same id; see
 * {@link CustomTextureManager.Registry#nextId()}.
 */
public interface TextureHolder {

    /** The id this texture is published under; valid until the holder is dropped or released. */
    ResourceLocation id();
}
