package com.fox.ysmu.client.animation.store;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.fox.ysmu.client.animation.AnimationDomain;

import software.bernie.geckolib3.file.AnimationFile;

/**
 * The built-in default model's two pinned animation stores, one per {@link AnimationDomain domain}, shared by every
 * model store as its fallback.
 *
 * <p>
 * Upstream's counterpart is {@code DefaultAnimationRuntime}
 * ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/resource/client/render/DefaultAnimationRuntime.java}):
 * a holder of one source per domain, published once after a default target has fully bound its animations
 * ({@code :11}, {@code :25-34}), read as the fallback of every model store
 * ({@code ModelRenderTargetLoader.java:529-541}), and never released with the model list
 * ({@code .agent/perf-audit-memory.md:265-270}). The port has the same two domains as
 * {@link AnimationDomain#ALL} - the model proper and the first-person arm - because the fallback is resolved per
 * domain ({@code ModelRenderTargetLoader.java:375-379}).
 *
 * <p>
 * <b>The published stores are eager and read-only.</b> The default's clips are parsed once and shared by reference;
 * nothing ever copies them into a model's store, and nothing may write to them at playback (that is the defect class
 * this port removes, {@code .agent/phase75-loop-type-carried-across-animation-files.md}). They are pinned: this holder
 * never closes them, matching "the built-in default is initialised once and required for the whole session"
 * ({@code ClientModelManager#isFallbackAvailable}, the texture registry's {@code isDefaultModelId} exemption).
 *
 * <p>
 * <b>Publication order matters and is the caller's responsibility.</b> {@link #publish} replaces the domain's store
 * object, and a model store captures its fallback <em>pointer</em> when it is created. So every model store must be
 * created after its domain has been published - the order upstream guarantees by publishing the default before any
 * render target exists ({@code DefaultAnimationRuntime.java:11}) and the port has by construction, because the built-in
 * default model is registered eagerly at client start ({@code ClientModelManager#loadDefaultModel}) before any synced
 * model. A re-publication (a `/ysm reload`, or the default being re-registered) is only safe while the model stores
 * are rebuilt as well, which is exactly what {@code reloadPacks} + {@code registerAll} do.
 *
 * <p>
 * <b>Why {@link #publish} takes the installed file rather than reading a static field.</b>
 * {@code ClientModelManager.DEFAULT_ANIMATION_FILE} / {@code DEFAULT_FIRST_PERSON_ANIMATION_FILE} are no longer filled:
 * the authoritative copy of the default's clips is the one <em>installed</em> under the default model's own animation
 * ids, and the static fields exist only as an empty last resort
 * ({@code ClientModelManager#defaultAnimationFile}, {@code :2965-2995}, and its javadoc for the regression that
 * reading the empty statics once caused). The wiring therefore publishes the same object
 * {@code ClientModelManager#defaultAnimationFile(domain)} returns - after it has been installed, which is upstream's
 * "published only after a default target has fully bound its animations". Building this holder from the statics instead
 * would pin two empty stores and silently drop every inherited clip (`misc/4_default_controllers` would lose
 * {@code walk}/{@code run}/{@code idle}, the regression described at {@code :2977-2984}).
 *
 * <p>
 * Client thread only, like {@link AnimationStore}.
 */
public final class DefaultAnimationStores {

    private static final DefaultAnimationStores INSTANCE = new DefaultAnimationStores();

    /** One pinned store per domain; replaced (never closed) by {@link #publish}. */
    private final Map<String, AnimationStore> pinned = new LinkedHashMap<>();

    private DefaultAnimationStores() {
        for (String domain : AnimationDomain.ALL) {
            // Empty until the default model is registered: a model whose store is built before that inherits nothing,
            // which is the same answer the missing default gives today.
            this.pinned.put(domain, AnimationStore.eager(null));
        }
    }

    /** The one holder of the built-in default's stores. */
    public static DefaultAnimationStores instance() {
        return INSTANCE;
    }

    /**
     * The pinned, read-only store for one domain - what a model store takes as its fallback.
     *
     * @param domain {@link AnimationDomain#MAIN} or {@link AnimationDomain#FIRST_PERSON}
     * @throws IllegalArgumentException for any other domain string, so a wiring mistake is reported rather than served
     *         as an empty fallback
     */
    public AnimationStore store(String domain) {
        AnimationStore store = this.pinned.get(domain);
        if (store == null) {
            throw new IllegalArgumentException("Unknown animation domain: " + domain);
        }
        return store;
    }

    /**
     * Publishes one domain's clips, replacing the domain's pinned store - upstream's {@code publishSources}
     * ({@code DefaultAnimationRuntime.java:25-34}), which is also one-shot-per-publish for the same reason: the clips
     * are bound once and shared.
     *
     * @param domain {@link AnimationDomain#MAIN} or {@link AnimationDomain#FIRST_PERSON}
     * @param file the default model's animation file for that domain, as installed; {@code null} publishes nothing
     * @throws IllegalArgumentException for an unknown domain string
     */
    public void publish(String domain, @Nullable AnimationFile file) {
        if (!this.pinned.containsKey(domain)) {
            throw new IllegalArgumentException("Unknown animation domain: " + domain);
        }
        this.pinned.put(domain, AnimationStore.eager(file == null ? null : file.animations));
    }

    /** Whether one domain has clips - i.e. whether the built-in default has been published there yet. */
    public boolean isPublished(String domain) {
        return !store(domain).localNames()
            .isEmpty();
    }
}
