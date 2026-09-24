package com.fox.ysmu.data;

import java.util.Objects;

import net.minecraft.util.ResourceLocation;

/**
 * A model/texture override attached to an entity through {@link NPCData}.
 * <p>
 * This replaces the raw {@code Pair<ResourceLocation, ResourceLocation>} the registry used to store: a
 * named type keeps the entity dimension explicit and stops model and texture from being silently swapped
 * by index.
 */
public final class EntityModelData {

    private final ResourceLocation modelId;
    private final ResourceLocation textureId;

    public EntityModelData(ResourceLocation modelId, ResourceLocation textureId) {
        this.modelId = modelId;
        this.textureId = textureId;
    }

    /** Model id in the YSM namespace (for example {@code ysmu:example}). Never {@code null}. */
    public ResourceLocation getModelId() {
        return modelId;
    }

    /** Selected texture resource location. Never {@code null}. */
    public ResourceLocation getTextureId() {
        return textureId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EntityModelData)) {
            return false;
        }
        EntityModelData other = (EntityModelData) obj;
        return Objects.equals(modelId, other.modelId) && Objects.equals(textureId, other.textureId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelId, textureId);
    }

    @Override
    public String toString() {
        return "EntityModelData{" + modelId + " -> " + textureId + "}";
    }
}
