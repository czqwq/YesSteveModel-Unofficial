package com.fox.ysmu.client.roaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.model.roaming.ModelRoamingKey;

/**
 * Which roaming-variable namespace a model uses, remembered on the client from the OpenYSM sync payload.
 * <p>
 * A <em>roaming variable</em> is a named float belonging to one model of one player, read by packs as
 * {@code v.roaming.<name>}. Both sides have to agree on the key that namespaces it, and they derive it from the
 * model's own content hash, which only the payload carries; see {@link ModelRoamingKey} for the rule and
 * {@code .agent/phase15-roaming-variables.md} for the design.
 * <p>
 * No {@code @SideOnly(Side.CLIENT)}: like {@code ModelPreviewAnimationState}, this class is meant to be covered by a
 * plain unit test.
 */
public final class ClientRoamingKeys {

    /** Model id -> roaming key. */
    private static final Map<ResourceLocation, Integer> KEYS = new ConcurrentHashMap<>();

    private ClientRoamingKeys() {}

    /**
     * Records the key of one model; called when the model arrives over the sync channel.
     *
     * @param modelId      the model's resource location, which is also the canonical identifier used when the hash is
     *                     unusable, so both sides must build the same {@link ResourceLocation#toString()}
     * @param modelHashHex the model's content hash, normally {@code RawProperties.sha256}; may be {@code null}
     */
    public static void accept(ResourceLocation modelId, @Nullable String modelHashHex) {
        if (modelId == null) {
            return;
        }
        KEYS.put(modelId, ModelRoamingKey.of(modelHashHex, modelId.toString()));
    }

    /**
     * The roaming key of a model, derived on demand when the model was registered before this registry existed.
     * Deriving from the id alone is what {@link ModelRoamingKey} does for an unusable hash, so an unknown model
     * simply gets its fallback namespace instead of {@code null}.
     */
    public static int keyFor(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return ModelRoamingKey.of(null, null);
        }
        Integer known = KEYS.get(modelId);
        return known != null ? known : ModelRoamingKey.of(null, modelId.toString());
    }

    /** Drops every remembered key, for a resource reload or a server change. */
    public static void clear() {
        KEYS.clear();
    }
}
