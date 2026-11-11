package com.fox.ysmu.client.animation.store;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * One model's (or one domain's) animation ownership: declared names, clips bound on demand, a fallback pointer and a
 * release.
 *
 * <p>
 * This is the port of upstream's {@code AnimationStore}
 * ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/resource/client/AnimationStore.java}), with
 * the parts that only exist because upstream bakes clips into per-name chunks left out. What is kept is the shape that
 * matters, and it is the shape the port does not have today:
 *
 * <ul>
 * <li><b>Ownership.</b> A store belongs to one model (in the port: one model's one domain) and holds that model's
 * clips. Nothing else can write into it, and it is released with the model ({@link #close()}), which is upstream's
 * {@code AnimationStore#close} ({@code :324-331}) reached from {@code ModelRenderTarget#destroy}
 * ({@code model/resource/client/ModelRenderTarget.java:136-143}). Today the port merges every model's clips into the
 * engine-wide cache and hands the <em>same</em> built-in default {@code Animation} instances to every model, which is
 * what let a model switch change another model's clip loop
 * ({@code .agent/phase75-loop-type-carried-across-animation-files.md}).</li>
 * <li><b>Laziness.</b> The store is created with the names its payload declares and binds a name only when it is asked
 * for it ({@code :139-221}, fast path {@code :158-163}), caching the result forever. A model whose clip was never
 * played holds no keyframe graph for it - upstream's measured behaviour
 * ({@code .agent/perf-audit-memory.md:257-264}).</li>
 * <li><b>Fallback.</b> A name the store does not define (or failed to bind) resolves through the fallback store - the
 * built-in default model's, pinned and shared read-only ({@code :223-228}; {@code DefaultAnimationRuntime.java:14},
 * {@code .agent/perf-audit-memory.md:268-270}). The fallback is a pointer, not a copy: the default's clips are parsed
 * once and never inserted into a model's store. Today's install rule inserts them by reference into every model
 * ({@code ClientModelManager#registerAnimations}, {@code :2658-2676}).</li>
 * </ul>
 *
 * <p>
 * <b>Answers.</b> {@link #resolve(String)} is the one operation; {@link #get(String)} is its animation and
 * {@link #contains(String)} its existence. The ladder is upstream's line by line: local first (bind on first ask,
 * cache), then {@link #throughFallback}; a name that failed to bind, and a name whose payload is not attached yet, go
 * through the fallback too and are answered <em>unstable</em> - upstream's {@code Resolution#unstable} ({@code :411-416})
 * - so a controller's resolution cache does not turn "not knowable yet" into "absent forever"
 * ({@code geckolib3/core/controller/ControllerAnimationCache.java:32-45}).
 *
 * <p>
 * <b>Two port-specific decisions, both stated where they are made:</b>
 * <ul>
 * <li>A closed store answers absence instead of throwing. Upstream's {@code requireOpen} ({@code :318-322}) throws
 * {@code IllegalStateException}; the port's store is released at the connection boundary while a render pass can still
 * be asking about the model it was drawing, and the upstream alternative - an exception on the render path - is a
 * client crash for a model that is merely gone. Absence is also literally true: a closed store can never hold that
 * clip again.</li>
 * <li>The engine's built-in {@code empty} hook is applied to the answer ({@link #applyBuiltinFallback}), because the
 * port has a hook upstream does not: {@code AnimationFile#getAnimation} substitutes the built-in no-op clip for a name
 * the model does not define ({@code AnimationFile.java:28-37}, installed by
 * {@code client/animation/YsmBuiltinAnimations#registerHooks}). Anything that replaces {@code AnimationFile#getAnimation}
 * as the lookup surface must keep answering the same way, or every controller that plays {@code "empty"} loses its
 * clip again. The same class's write-side filter is applied to declared names ({@link #isRejectedName}).</li>
 * </ul>
 *
 * <p>
 * <b>Thread.</b> Client thread only - the install rule, the render path and the GUI preview all run there. No locks,
 * deliberately: upstream synchronizes because its reads can run inside a read phase on a loader thread and it must
 * serve {@code openReadPhase} consumers ({@code :109-131}); the port has no read phases and no second thread that may
 * touch a store. Every mutable field is a plain {@code LinkedHashMap}/{@code LinkedHashSet} and every mutation is
 * idempotent for a given name, so a future move of the binding work to a loader thread is a deliberate change, not an
 * accident of this class.
 */
public final class AnimationStore implements AutoCloseable {

    @Nullable
    private AnimationBinder binder;
    @Nullable
    private AnimationStore fallback;
    /** Declared names, in declaration order; a {@code null} value is a name that is declared but not bound yet. */
    private final Map<String, Animation> local = new LinkedHashMap<>();
    /** Names whose bind threw, or which the payload turned out not to define. Never retried. */
    private final Set<String> failures = new LinkedHashSet<>();
    private boolean closed;

    private AnimationStore(@Nullable AnimationBinder binder, @Nullable AnimationStore fallback) {
        this.binder = binder;
        this.fallback = fallback;
    }

    /**
     * A store over the names a payload declares, binding each one the first time it is asked for.
     *
     * <p>
     * Upstream's {@code lazy(names, source, binder, fallback)} ({@code AnimationStore.java:48-65}) with the source and
     * the binder collapsed into one {@link AnimationBinder}: the names are the payload's own (a JSON file's animation
     * keys, or the structures' names), which the port enumerates instead of reading them from a baked manifest.
     *
     * @param names every animation name this store owns; duplicates are a caller bug and throw, as upstream's
     *        {@code lazyBound} does ({@code :71-79})
     * @param binder binds one of those names on demand, or {@code null} while the payload is not attached: the store
     *        then declares the names but answers them through the fallback as not cacheable
     * @param fallback the store to delegate names this one does not define to - normally
     *        {@link DefaultAnimationStores#store}, never this store itself
     */
    public static AnimationStore lazy(Collection<String> names, @Nullable AnimationBinder binder,
        @Nullable AnimationStore fallback) {
        AnimationStore store = new AnimationStore(binder, fallback);
        for (String name : names) {
            if (name == null || isRejectedName(name)) {
                continue;
            }
            if (store.local.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate animation name: " + name);
            }
            store.local.put(name, null);
        }
        return store;
    }

    /**
     * A store that already holds its clips - upstream's {@code eager} ({@code AnimationStore.java:83-93}).
     *
     * <p>
     * This is the built-in default model's store, the one eager set in the system
     * ({@code .agent/perf-audit-memory.md:268-270}), and it is shared by reference into every model store as the
     * fallback pointer. The clips are <b>not copied</b>: the store keeps the very instances it is handed, so nothing
     * may write to a clip obtained from a store (upstream's immutable definitions; the port's counterpart is
     * {@code AnimationController}'s per-playback loop override). {@code tmp/_probe/AnimationStoreProbe.java} asserts
     * that no store writes to a clip it hands out.
     *
     * @param animations the clips, normally {@link AnimationFile#animations}; {@code null} means the empty store
     */
    public static AnimationStore eager(@Nullable Map<String, Animation> animations) {
        AnimationStore store = new AnimationStore(null, null);
        if (animations != null) {
            animations.forEach((name, animation) -> {
                if (name == null || animation == null || isRejectedName(name)) {
                    return;
                }
                store.local.put(name, animation);
            });
        }
        return store;
    }

    /**
     * The clip for one name, or {@code null} for absence - {@code AnimationFile#getAnimation}'s contract, which is what
     * every lookup in the port asks for today.
     */
    @Nullable
    public Animation get(String name) {
        return resolve(name).animation();
    }

    /**
     * The full answer for one name: the clip (or {@code null}), whether it came from the fallback, and whether the
     * caller may cache it.
     */
    public AnimationResolution resolve(String name) {
        return applyBuiltinFallback(name, resolveUnhooked(name));
    }

    /**
     * Whether this store knows the name at all: declared locally (bound or not, and including a name whose bind failed,
     * which is upstream's rule for {@code containsKey}, {@code :237-242}) or declared by the fallback.
     *
     * <p>
     * Note that this answers {@code false} for the built-in {@code "empty"} clip that {@link #get(String)} substitutes
     * when nothing defines it - exactly the asymmetry the port has today between {@code AnimationFile#getAnimation}
     * and {@code AnimationFile#animations#containsKey}.
     */
    public boolean contains(String name) {
        if (this.closed) {
            return false;
        }
        return this.local.containsKey(name) || this.fallback != null && this.fallback.contains(name);
    }

    /**
     * Every name this store can answer for, its own and its fallback's - upstream's {@code keySet} ({@code :248-259}).
     *
     * <p>
     * The installer needs this to register a condition per name and to hand a controller set the names it may route
     * to; {@link #localNames()} is the subset this model's own payload declares, so the difference is "inherited from
     * the built-in default", which the installer must not report as this model's content.
     */
    public Set<String> names() {
        if (this.fallback == null) {
            return Collections.unmodifiableSet(this.local.keySet());
        }
        Set<String> names = new LinkedHashSet<>(this.local.keySet());
        names.addAll(this.fallback.names());
        return Collections.unmodifiableSet(names);
    }

    /** The names this store's own payload declares, bound or not - upstream's local key set ({@code :244-246}). */
    public Set<String> localNames() {
        return Collections.unmodifiableSet(this.local.keySet());
    }

    /** How many clips are actually materialised; the measure of laziness ({@code 0} for a store nobody asked). */
    public int boundCount() {
        int bound = 0;
        for (Animation animation : this.local.values()) {
            if (animation != null) {
                bound++;
            }
        }
        return bound;
    }

    /** Whether {@link #close()} has run; a closed store answers absence for everything. */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Releases every bound clip and drops the payload reference, after which the store answers absence - upstream's
     * {@code close} ({@code :324-331}).
     *
     * <p>
     * Upstream keeps its {@code source} pointer; the port drops its binder as well, because a port binder owns the
     * payload's parsed JSON tree, which is the memory this release exists to free
     * ({@code .agent/perf-audit-memory.md} R1/R7). Idempotent, like upstream's. The built-in default's stores are
     * never closed: they are pinned for the session (the same exemption the texture registry makes,
     * {@code ModelTextureRegistry#clear}).
     */
    @Override
    public void close() {
        this.closed = true;
        this.local.clear();
        this.failures.clear();
        this.binder = null;
        this.fallback = null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Resolution ladder - upstream AnimationStore#resolve :139-228, without the read-phase plumbing
    // ---------------------------------------------------------------------------------------------------------------

    private AnimationResolution resolveUnhooked(String name) {
        if (this.closed) {
            return AnimationResolution.ABSENT;
        }
        if (!this.local.containsKey(name)) {
            // Not this store's name: the fallback's answer stands as it is (upstream :219-220).
            return throughFallback(name, true);
        }
        if (this.failures.contains(name)) {
            // Upstream's State.FAILED branch (:164-166): a clip that could not be bound falls back, and its answer is
            // not cacheable because the local definition is not usable.
            return throughFallback(name, false);
        }
        Animation bound = this.local.get(name);
        if (bound != null) {
            return new AnimationResolution(bound, false, true);
        }
        if (this.binder == null) {
            // Declared, but the payload is not attached: upstream's unloaded slot without a source (:158-163) - nothing
            // is proven, so the caller must ask again later.
            return throughFallback(name, false);
        }
        Animation built;
        try {
            built = this.binder.bind(name);
        } catch (Exception failure) {
            this.failures.add(name);
            reportFailure(name, failure);
            return throughFallback(name, false);
        }
        if (built == null) {
            // Declared but the payload does not define it after all. Same treatment as a bind failure, and the same
            // result the whole-file rule had: the name is missing locally, so the default's clip is what plays.
            this.failures.add(name);
            return throughFallback(name, false);
        }
        this.local.put(name, built);
        return new AnimationResolution(built, false, true);
    }

    /**
     * Upstream's {@code fallbackAnimation} ({@code :223-228}): the fallback's answer, with {@code fallback} true only
     * when it actually produced a clip.
     *
     * @param cacheable whether this store could prove its own half; combined with the fallback's own flag
     */
    private AnimationResolution throughFallback(String name, boolean cacheable) {
        if (this.fallback == null) {
            return cacheable ? AnimationResolution.ABSENT : new AnimationResolution(null, false, false);
        }
        // The nested store is asked without its own built-in-empty substitution: the hook is applied once, at this
        // store's exit, so the answer a caller sees is exactly what AnimationFile#getAnimation would have produced.
        AnimationResolution resolved = this.fallback.resolveUnhooked(name);
        return new AnimationResolution(resolved.animation(), resolved.animation() != null,
            cacheable && resolved.cacheable());
    }

    /**
     * Applies the port's built-in {@code "empty"} substitution, which {@code AnimationFile#getAnimation:28-37} performs
     * on every lookup: if the name is {@code "empty"} and the model has no clip of that name, the built-in no-op clip
     * is what plays. Keeping it here is what makes the store a drop-in replacement for that method rather than a
     * subtly different one (see {@code client/animation/YsmBuiltinAnimations}).
     */
    private static AnimationResolution applyBuiltinFallback(String name, AnimationResolution resolution) {
        BiFunction<String, Animation, Animation> hook = AnimationFile.builtinFallback;
        if (hook == null) {
            return resolution;
        }
        Animation substituted = hook.apply(name, resolution.animation());
        if (substituted == null || substituted == resolution.animation()) {
            return resolution;
        }
        return new AnimationResolution(substituted, resolution.fallback(), resolution.cacheable());
    }

    /** The write-side half of the same hooks: a name {@code AnimationFile#putAnimation:43-48} would refuse is not declared. */
    private static boolean isRejectedName(String name) {
        return AnimationFile.rejectedAnimationNames != null && AnimationFile.rejectedAnimationNames.test(name);
    }

    /** The same per-clip containment and message the whole-file rule had ({@code ClientModelManager#animationFileFromJson}). */
    private static void reportFailure(String name, Exception failure) {
        ysmu.LOG.warn(
            "Failed to register animation {}: {}: {}",
            name,
            failure.getClass()
                .getSimpleName(),
            failure.getMessage() == null ? "" : failure.getMessage());
    }
}
