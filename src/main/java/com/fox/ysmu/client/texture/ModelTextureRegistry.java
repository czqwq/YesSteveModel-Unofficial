package com.fox.ysmu.client.texture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.util.ModelIdUtil;
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
 *       a model's texture. It is also the call that hands the registry's id to the platform
 *       ({@code CustomTextureManager.register(texture, id)}), so the texture is published under the model's own
 *       texture id - the one {@code CustomPlayerModel.textureFor} resolves and every render site binds - rather than
 *       under a synthetic per-registration id. See {@code CustomTextureManager}'s "The registration id" section.</li>
 *   <li>{@link #clear} is called from {@code ClientModelManager}'s {@code clearRuntimeModelCaches}, i.e. the existing
 *       model-unload / reconnect path ({@code prepareModelSync}), where the whole model list those textures belong to
 *       is dropped. It releases every holder immediately, which is upstream's explicit trigger
 *       ({@code CustomTextureManager.release}, upstream {@code :33-36}).</li>
 *   <li>{@link #tick} is called from {@code ClientModelManager.tick}, the client tick, and is the same render-thread
 *       step upstream drives from its client tick (upstream {@code client/event/ClientTickEvent.java:28}).</li>
 * </ul>
 *
 * <p>Upstream's release is the <em>only</em> release policy here. The reference branch's idle policy
 * ({@code unloadIdleTextures}, reference {@code ClientModelManager.java:1845-1896} - a 30 s idle timestamp table plus
 * {@code Config.TEXTURE_RELEASE_BYTES_ON_IDLE}) is still <b>not</b> ported: this class adds no idle timeout and no
 * heap-byte release, and the open owner decision recorded in {@code .agent/phase32-asset-eviction-subsystem.md}
 * member 3 and {@code .agent/phase40-eviction-members.md} still stands - the release half needs a restore seam that
 * lives in {@code ClientModelManager} (see {@link #enforceVramBudget} for the exact boundary).
 *
 * <p>What <em>is</em> here is the reference's <b>VRAM budget</b> ({@link Config#TEXTURE_VRAM_BUDGET_MB},
 * {@link #enforceVramBudget}): when the uploaded footprint exceeds the budget, whole models' GPU copies are freed
 * LRU-first while their registration, platform map entry and heap bytes stay put, so the next bind re-uploads
 * directly. That half is expressible entirely inside this package (the free/re-upload pair is
 * {@link OuterFileTexture#freeGlTexture()} / {@link OuterFileTexture#getGlTextureId()}), which is why it is delivered
 * and the idle-bytes half is not. With the budget at 0 - the documented "off" value - nothing is recorded and
 * nothing is evicted; see {@link #touch}.
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

    /**
     * Last bind time (ms) per texture id, the reference's {@code TEXTURE_LAST_USED} (reference
     * {@code ClientModelManager.java:223}). Only written while {@link Config#TEXTURE_VRAM_BUDGET_MB} is on, and only
     * from {@link #touch}, which every bind reaches through {@link OuterFileTexture#getGlTextureId()}.
     */
    private static final Map<ResourceLocation, Long> TEXTURE_LAST_USED = new HashMap<>();

    /**
     * How long (ms) a texture's last bind keeps it out of budget eviction. The reference's own anti-thrash window
     * (reference {@code ClientModelManager.java:233}, {@code TEXTURE_PROTECT_MS = 5_000}): freeing a texture that is
     * about to be bound again would upload → evict → re-upload every frame.
     */
    private static final long TEXTURE_PROTECT_MS = 5_000L;

    /**
     * Total VRAM footprint (bytes) of uploaded textures this registry owns. Maintained by
     * {@link OuterFileTexture} upload/free callbacks through {@link #onTextureGpuBytesChanged}, so
     * {@link #enforceVramBudget} is one comparison away from a no-op (the reference keeps the same counter, reference
     * {@code ClientModelManager.java:240}).
     */
    private static long textureGpuBytes;

    private ModelTextureRegistry() {}

    /**
     * Registers one model texture under the model's own texture {@code id}, returning its holder.
     *
     * <p>Re-uploading an id that was already live replaces its holder, and the replaced holder is <b>dropped, not
     * released</b>: dropping it makes it unreachable, so the mechanism's own trigger (`Cleaner` in
     * {@link CustomTextureManager}) starts the delayed release for it, and its cleanup event carries the nonce of a
     * superseded activation - which {@link TextureRegistrationState#release} answers with {@code IGNORED}
     * ({@code TextureRegistrationState.java:47-50}). Calling {@code CustomTextureManager.release} here instead would
     * add nothing: the id has already been handed to the new registration, so the explicit release would no longer be
     * about the object it names, and the identity-checked {@code MinecraftHost.release} would decline it anyway.
     *
     * <p>The id is also what the platform publishes the texture under
     * ({@link CustomTextureManager#register(AbstractTexture, ResourceLocation)}), because it is the id
     * {@code CustomPlayerModel.textureFor} resolves and every render site binds.
     */
    public static TextureHolder register(ResourceLocation id, AbstractTexture texture) {
        if (id == null || texture == null) {
            return null;
        }
        Holder previous = TEXTURES.remove(id);
        if (previous != null) {
            // Forget the old holder; the manager's weak view lets the trigger fire on the next collection.
            previous.holder = null;
            // The replaced texture keeps its GL copy until the platform re-registration deletes it
            // (CustomTextureManager.MinecraftHost.register), but it can never be bound again, so its accounting must
            // leave the budget counter now or the counter would grow by one model per re-sync. The reference never
            // needs this step because it skips a re-registration whose bytes are unchanged (its TEXTURE_CONTENT_HASH,
            // reference ClientModelManager.java:1172-1189); this port re-registers every id on every publish.
            forgetGpuAccounting(previous.owner);
        }
        // Before the platform registration: that call runs loadTexture -> uploadImage, and the upload already reports
        // to the budget counter, so the object must know which id it belongs to by then.
        if (texture instanceof OuterFileTexture outer) {
            outer.setRegistryId(id);
        }
        // The id goes with the texture: the platform must publish this texture under the id every bind site uses
        // (the model's own texture id), not under a synthetic per-registration one - see CustomTextureManager's
        // "The registration id" section.
        TextureHolder holder = CustomTextureManager.register(texture, id);
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
        // The LRU table is reset even when nothing is live: a texture that was already bound can still be collecting
        // (or already have collected) its timestamp after this call, because its object stays reachable from the
        // platform's texture map until the delayed release runs, and a stale timestamp would then outlive the
        // registration it describes.
        List<Holder> owned = new ArrayList<>(TEXTURES.values());
        TEXTURES.clear();
        TEXTURE_LAST_USED.clear();
        int count = 0;
        for (Holder entry : owned) {
            // Before the release starts, and for every entry including an already-replaced one: this drops the
            // texture's VRAM contribution and its ownership marker, and the marker is what stops the platform's
            // old-entry lookup from re-uploading a texture that is on its way out (see
            // OuterFileTexture#forgetGpuAccounting).
            forgetGpuAccounting(entry.owner);
            if (entry.holder == null) {
                // Already replaced before this clear: its registration has no strong holder left, so the reachability
                // trigger will start its delayed release on the next collection. An explicit release here would name
                // an id that now belongs to the newer registration - the identity-checked Host#release declines it, so
                // it would only add a no-op slot.
                continue;
            }
            entry.holder = null;
            CustomTextureManager.release(entry.owner);
            count++;
        }
        // Defensive: no registration this registry owns survives this call, so nothing it tracks may keep the
        // footprint above the budget. The GL names the released textures still hold are freed by their delayed
        // release, not here.
        textureGpuBytes = 0L;
        if (count > 0) {
            ysmu.LOG.info("YSM client released {} model texture(s) for a delayed free", count);
        }
    }

    /**
     * The render-thread step of the mechanism: drains cleanup events, performs due releases, and enforces the VRAM
     * budget.
     *
     * <p>The budget check is the port's addition to this hook. The reference enforces it right after each upload
     * (reference {@code ClientModelManager.enforceVramBudget}, called from {@code OuterFileTexture.upload},
     * reference {@code :135}) and that call is kept here too; the tick check covers the one gap the eager port has -
     * this port's upload happens inside the platform registration, before {@link #register} has put the new entry in
     * {@link #TEXTURES}, so a sweep fired from the upload cannot see (and therefore cannot free) the texture that
     * just pushed the footprint over the budget. One tick later it can. When the footprint is under budget - the
     * normal case - this is a single comparison.
     */
    public static void tick() {
        CustomTextureManager.tick();
        enforceVramBudget();
    }

    /**
     * Records that {@code id} was bound just now.
     *
     * <p>This is the port's placement of the reference's {@code touchTextureUsed} (reference
     * {@code ClientModelManager.java:1906-1908}). The reference calls it from {@code OuterFileTexture.upload},
     * because its upload is deferred to the first bind; this port's upload is eager (it happens while the model is
     * published), so a timestamp written there would mean "registered", not "used", and every freshly synced model
     * would be protected from eviction for {@link #TEXTURE_PROTECT_MS} before it was ever drawn. {@link
     * OuterFileTexture#getGlTextureId()} is the same "this texture is in use" signal and is reached from every bind
     * on 1.7.10, including the per-bone TextureManager bypass in {@code IGeoRenderer}.
     *
     * <p>No-op while {@link Config#TEXTURE_VRAM_BUDGET_MB} is 0, so the documented "off" value costs nothing and
     * changes no behaviour - not even a map write.
     */
    public static void touch(@Nullable ResourceLocation id) {
        if (id == null || Config.TEXTURE_VRAM_BUDGET_MB <= 0) {
            return;
        }
        TEXTURE_LAST_USED.put(id, System.currentTimeMillis());
    }

    /**
     * Adjusts the running VRAM footprint counter by {@code delta} bytes; called by {@link OuterFileTexture} on
     * upload (positive) and on a GPU free (negative). Same shape and same clamp as the reference's
     * {@code onTextureGpuBytesChanged} (reference {@code ClientModelManager.java:1915-1920}).
     */
    public static void onTextureGpuBytesChanged(long delta) {
        textureGpuBytes += delta;
        if (textureGpuBytes < 0L) {
            textureGpuBytes = 0L;
        }
    }

    /**
     * Frees the GPU copy of whole models, least-recently-used first, until the uploaded footprint is back under the
     * budget; see {@link Config#TEXTURE_VRAM_BUDGET_MB}. 0 disables the check entirely.
     *
     * <p>Every step is the reference's {@code enforceVramBudget} (reference {@code ClientModelManager.java:1939-2001})
     * with the reference's thresholds, read line by line:
     * <ul>
     *   <li>the budget is {@code MB * 1024 * 1024} and the eviction target is 75% of it - hysteresis, so the next
     *       upload does not immediately re-trigger a sweep;</li>
     *   <li>a model whose newest bind is within {@link #TEXTURE_PROTECT_MS} is not a candidate; a model never bound
     *       ({@code lastUsed == 0}) always is, which is what makes "uploaded but never drawn" collectable;</li>
     *   <li>only the <b>GPU copy</b> is freed ({@link OuterFileTexture#freeGlTexture()}). The registration, the
     *       platform's map entry and the heap bytes all stay, so the next bind re-uploads directly - the reference's
     *       own reason for keeping eviction away from the byte release (reference {@code :1929-1932}).</li>
     * </ul>
     *
     * <p>Deliberately absent: the reference also frees heap bytes here when
     * {@link Config#TEXTURE_RELEASE_BYTES_ON_IDLE} is on. That half is not ported - see the class javadoc and
     * {@code .agent/phase40-eviction-members.md} member 4.
     *
     * <p>One cost this port pays that the reference does not, stated plainly: this port uploads eagerly at publish
     * time, so a texture that was just registered and has not been drawn yet is a candidate (never bound = least
     * recently used). A large library therefore uploads, evicts and - for the models actually drawn - re-uploads
     * once. That is the intended shape of "bound the VRAM peak": the alternative, protecting never-drawn textures,
     * would leave exactly the library's bulk in VRAM and make the budget unreachable. The second upload is a plain
     * {@code TextureUtil} upload from the retained image, not a decode or a decrypt.
     */
    public static void enforceVramBudget() {
        long budget = (long) Config.TEXTURE_VRAM_BUDGET_MB * 1024L * 1024L;
        if (budget <= 0L || textureGpuBytes <= budget) {
            return;
        }
        long target = budget * 3L / 4L; // hysteresis floor (reference :1944)
        long now = System.currentTimeMillis();

        // 1. Candidate models: at least one uploaded texture, and not bound within the protect window.
        Map<ResourceLocation, List<ResourceLocation>> texturesByModel = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Holder> entry : TEXTURES.entrySet()) {
            if (!isUploaded(entry.getValue())) {
                continue;
            }
            texturesByModel.computeIfAbsent(
                ModelIdUtil.getParentModelId(entry.getKey()),
                key -> new ArrayList<>())
                .add(entry.getKey());
        }
        List<EvictionCandidate> candidates = new ArrayList<>(texturesByModel.size());
        for (Map.Entry<ResourceLocation, List<ResourceLocation>> entry : texturesByModel.entrySet()) {
            long lastUsed = lastUsed(entry.getValue());
            if (lastUsed != 0L && now - lastUsed < TEXTURE_PROTECT_MS) {
                continue; // still in use - protected from thrash (reference :1968-1970)
            }
            candidates.add(new EvictionCandidate(entry.getKey(), lastUsed));
        }
        // 2. Least-recently-used first; never-bound (0) sorts first, which is the reference's own order (:1974-1979).
        candidates.sort((a, b) -> Long.compare(a.lastUsed(), b.lastUsed()));

        for (EvictionCandidate candidate : candidates) {
            if (textureGpuBytes <= target) {
                break;
            }
            long freed = 0L;
            List<ResourceLocation> textureIds = texturesByModel.get(candidate.modelId());
            if (textureIds == null) {
                continue;
            }
            for (ResourceLocation textureId : textureIds) {
                Holder holder = TEXTURES.get(textureId);
                if (holder != null && holder.owner instanceof OuterFileTexture outer && outer.isUploaded()) {
                    freed += outer.getGpuBytes();
                    outer.freeGlTexture();
                }
            }
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
                ysmu.LOG.info(
                    "[YSMU-TEX] VRAM budget: evicted GPU textures for {} ({} MB, last used {} ms ago); now {} MB / {} MB budget",
                    candidate.modelId(),
                    freed / 1048576L,
                    now - candidate.lastUsed(),
                    textureGpuBytes / 1048576L,
                    Config.TEXTURE_VRAM_BUDGET_MB);
            }
        }
    }

    /** Whether {@code holder}'s texture currently has a GPU copy. */
    private static boolean isUploaded(@Nullable Holder holder) {
        return holder != null && holder.owner instanceof OuterFileTexture outer && outer.isUploaded();
    }

    /** The newest bind time among {@code textureIds}; 0 when none of them was ever bound. */
    private static long lastUsed(List<ResourceLocation> textureIds) {
        long newest = 0L;
        for (ResourceLocation textureId : textureIds) {
            Long used = TEXTURE_LAST_USED.get(textureId);
            if (used != null && used > newest) {
                newest = used;
            }
        }
        return newest;
    }

    /**
     * Drops {@code texture}'s contribution to the budget counter, for a texture {@link #register} replaced. The GL
     * name it still holds is deleted by the platform re-registration that follows, so only the accounting moves here.
     */
    private static void forgetGpuAccounting(AbstractTexture texture) {
        if (texture instanceof OuterFileTexture outer) {
            outer.forgetGpuAccounting();
        }
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

    /** One model's eviction ordering key: the newest bind time among its uploaded textures (0 = never bound). */
    private record EvictionCandidate(ResourceLocation modelId, long lastUsed) {
    }
}
