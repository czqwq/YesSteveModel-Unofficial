package com.fox.ysmu.client.texture;

import java.awt.image.BufferedImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

/** Angelica holder-owned component, never registered as a skin. Upstream CustomPBRTextureSet:160-166. */
public final class PbrComponentTexture extends AbstractTexture implements AutoCloseable {
    private final BufferedImage image;
    private final OuterFileTexture owner;
    private long bytes;

    PbrComponentTexture(BufferedImage image, OuterFileTexture owner) {
        this.image = image;
        this.owner = owner;
    }

    @Override
    public void loadTexture(IResourceManager manager) {
        deleteGlTexture();
        // Upstream uploads original component pixels, with no colour/normal-map resize invented here.
        TextureUtil.uploadTextureImageAllocate(super.getGlTextureId(), image, false, true);
        bytes = (long) image.getWidth() * image.getHeight() * 4;
        owner.componentBytesChanged(bytes);
    }

    @Override
    public void deleteGlTexture() {
        super.deleteGlTexture();
        if (bytes != 0) {
            owner.componentBytesChanged(-bytes);
            bytes = 0;
        }
    }

    @Override
    public void close() { deleteGlTexture(); }
}
