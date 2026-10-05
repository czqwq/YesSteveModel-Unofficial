package com.fox.ysmu.client.texture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.ysmu;

/**
 * The port's ownership of a model's uploaded textures: one {@link TextureHolder} per texture id, held for exactly as
 * long as the model list that owns it.
 *
 * <h3>Why this class has to exist (and why upstream does not have it)</h3>
 * <p>Upstream's release trigger is reachability: its callers - the model-selection GUI's tiles
 * ({@code tmp/YesSteveModel-dev-1.20/.../gui/button/CatalogModelCardState.java:49-53}), the pack covers
 * ({@code .../gui/button/PackButton.java:35}) - hold a {@code TextureHolder} as a field and replace it when the tile
 * is paged, so the old holder simply becomes unreachable.
 *
 * <p>This port's model textures have no such owner: {@code ClientModelManager.registerTexture} uploads them and
 * nothing keeps a per-texture object at all. Worse, once the texture is in the platform's texture map it is
 * <b>strongly reachable no matter what the mod does</b> - 1.7.10's {@code TextureManager.mapTextureObjects} has no
 * removal API (see {@link TextureMapAccess}). A holder kept nowhere would therefore never become unreachable, and the
 * mechanism would be inert. So this class is the missing owner, and it is deliberately the smallest one that makes
 * the port's existing model-unload path able to drop the holder:
 *
 * <ul>
 *   <li>{@link #register} is called from {@code ClientModelManager.registerTexture} - the one place this port uploads
 *       a model's texture.</li>
 *   <li>{@link #clear} is called from {@code ClientModelManager}'s {@code clearRuntimeModelCaches}, i.e. the existing
 *       model-unload / reconnect path ({@code prepareModelSync}), where the whole model list those textures belong to
 *       is dropped. It releases every holder immediately, which is upstream's explicit trigger
 *       ({@code CustomTextureManager.release}, upstream {@code :33-36}).</li>
 *   <li>{@link #tick} is called from {@code ClientModelManager.tick}, the client tick, and is the same render-thread
 *       step upstream drives from its client tick (upstream {@code client/event/ClientTickEvent.java:28}).</li>
 * </ul>
 *
 * <p>Nothing here invents an eviction policy: it adds no idle timeout, no timestamp table and no budget, so it does
 * not duplicate the reference branch's {@code TEXTURE_LAST_USED}/{@code unloadIdleTextures} design - which is still
 * absent and still the open owner decision recorded in {@code .agent/phase32-asset-eviction-subsystem.md} member 3.
 * Release happens only at the point the port already unloads models.
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li>The holder is stored <b>here and only here</b>. Dropping it in {@link #clear} makes it unreachable, so
 *       {@code Cleaner}'s callback fires - the mechanism's own trigger - and the explicit
 *       {@link CustomTextureManager#release} in {@link #clear} is upstream's second trigger, not a replacement
 *       for it.</li>
 *   <li>{@link #clear} runs in the same call as the model-list clear
 *       ({@code ClientModelManager.clearRuntimeModelCaches}), so no render site can still be told to bind a released
 *       id: {@code CustomPlayerModel.textureFor} resolves through that same model list and falls back to the built-in
 *       default while it is empty ({@code client/model/CustomPlayerModel.java:81-92}).</li>
 *   <li>A released texture is re-created on demand: the next model registration calls {@link #register} again, and a
 *       resource reload / re-sync re-requests the model and re-uploads every texture
 *       ({@code ClientModelManager.uploadTextures}). {@link #register} forgets the previous holder for that id, so a
 *       re-upload can never keep an old registration alive.</li>
 * </ul>
 */
public final class ModelTextureRegistry {

    /** Live model textures, in registration order (diagnostics only; nothing iterates it per frame). */
    private static final Map<ResourceLocation, Holder> TEXTURES = new LinkedHashMap<>();

    private ModelTextureRegistry() {}

    /**
     * Registers one model texture that was just uploaded under {@code id}, returning its holder.
     *
     * <p>Re-uploading an id that was already live replaces its holder, and the replaced holder is <b>dropped, not
     * released</b>: dropping it makes it unreachable, so the mechanism's own trigger (`Cleaner` in
     * {@link CustomTextureManager}) starts the delayed release for it, and its cleanup event carries the nonce of a
     * superseded activation - which {@link TextureRegistrationState#release} answers with {@code IGNORED}
     * ({@code TextureRegistrationState.java:47-50}). Calling {@code CustomTextureManager.release} here instead would
     * be wrong for a second reason: the id was already re-registered with the platform by the time this runs, and the
     * release path keys the physical release by id, so it would free the <em>new</em> entry.
     */
    public static TextureHolder register(ResourceLocation id, AbstractTexture texture) {
        if (id == null || texture == null) {
            return null;
        }
        Holder previous = TEXTURES.remove(id);
        if (previous != null) {
            // Forget the old holder; the manager's weak view lets the trigger fire on the next collection.
            previous.holder = null;
        }
        TextureHolder holder = CustomTextureManager.register(texture);
        TEXTURES.put(id, new Holder(texture, holder));
        return holder;
    }

    /**
     * Releases every texture this registry owns and forgets the holders. Called from the existing model-unload /
     * reconnect path; see the class javadoc. Never touches a texture outside this registry.
     *
     * <p>Both of upstream's triggers are used here, in the order that matters: the strong holder is dropped first, so
     * the reachability trigger is armed; then the explicit release is upstream's second, immediate trigger
     * ({@code CustomTextureManager.release}, upstream {@code :33-36}), which is what actually starts the delayed
     * release on this tick instead of waiting for a collection. A texture a {@link #register} already replaced has no
     * holder left to drop and is skipped - its registration is already unreachable, and releasing it here would key
     * the manager's id-based release onto whatever now owns that id.
     */
    public static void clear() {
        if (TEXTURES.isEmpty()) {
            return;
        }
        List<Holder> owned = new ArrayList<>(TEXTURES.values());
        TEXTURES.clear();
        int count = 0;
        for (Holder entry : owned) {
            if (entry.holder == null) {
                // Already replaced before this clear: its registration has no strong holder left, so the reachability
                // trigger will start its delayed release on the next collection. Releasing it here would be wrong -
                // the manager's release is keyed by id and would find whatever now owns that id.
                continue;
            }
            entry.holder = null;
            CustomTextureManager.release(entry.owner);
            count++;
        }
        if (count > 0) {
            ysmu.LOG.info("YSM client released {} model texture(s) for a delayed free", count);
        }
    }

    /** The render-thread step of the mechanism: drains cleanup events and performs due releases. */
    public static void tick() {
        CustomTextureManager.tick();
    }

    /** Live registrations; diagnostics and tests only. */
    public static int size() {
        return TEXTURES.size();
    }

    /** A texture and the holder keeping its registration alive. */
    private static final class Holder {

        private final AbstractTexture owner;
        /** Dropped by {@link #clear} and by a replacement; never read after being dropped. */
        private TextureHolder holder;

        private Holder(AbstractTexture owner, TextureHolder holder) {
            this.owner = owner;
            this.holder = holder;
        }
    }
}
