package com.fox.ysmu.client.animation.store;

import javax.annotation.Nullable;

import software.bernie.geckolib3.core.builder.Animation;

/**
 * Turns one animation name into the engine's {@link Animation}, on demand.
 *
 * <p>
 * Upstream has the same seam - {@code AnimationStore.AnimationBinder}
 * ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/resource/client/AnimationStore.java:338-341}),
 * one method, {@code Animation bind(AnimationProto)}. Upstream pairs it with a separate {@code AnimationSource} that
 * loads a name's raw form ({@code :57-65}, {@code :333-336}), because its clips live in per-animation chunks of a baked
 * container. The port has no proto layer and no per-name chunk: a payload reaches the client either as JSON bytes for a
 * whole file or as {@code RawYsmModel.RawAnimationFile} structures, so the binder closes over its payload and answers
 * by name - upstream's two steps (load the raw form, bind it) in one call. The store's contract is unchanged: it asks
 * for one name, caches what comes back, and never asks for the same name twice.
 *
 * <p>
 * Two implementations exist, one per payload shape: {@link JsonAnimationBinder} (a file's JSON, parsed once) and
 * {@link StructuredAnimationBinder} (the binary channel's structures). A payload key that is a controller file has no
 * animations at all and is simply not given a binder.
 *
 * <p>
 * <b>Called on the client thread only</b>, at most once per name per store. Any {@link Exception} means "this clip
 * could not be bound"; the store records the name as failed, warns once exactly as the whole-file rule used to
 * ({@code ClientModelManager#animationFileFromJson}) and answers from its fallback from then on. An {@link Error} is
 * deliberately not caught - it says nothing about the clip's content (out of memory, a linkage failure), so the store
 * must not remember the name as broken.
 */
@FunctionalInterface
public interface AnimationBinder {

    /**
     * @param name the animation name to bind
     * @return the clip, or {@code null} when this binder's payload does not define {@code name}
     * @throws Exception when the clip exists but could not be built; recorded by the store, never retried
     */
    @Nullable
    Animation bind(String name) throws Exception;
}
