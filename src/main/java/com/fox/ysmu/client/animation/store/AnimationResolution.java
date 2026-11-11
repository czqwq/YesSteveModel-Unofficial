package com.fox.ysmu.client.animation.store;

import javax.annotation.Nullable;

import software.bernie.geckolib3.core.builder.Animation;

/**
 * One animation name's answer from an {@link AnimationStore}.
 *
 * <p>
 * Upstream's {@code AnimationStore.Resolution}
 * ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/resource/client/AnimationStore.java:411-416})
 * with the same three fields and the same rule, quoted from its own javadoc: <em>"A null value is proven absent only
 * when the full resolution is cacheable."</em>
 *
 * <ul>
 * <li>{@code animation} - the clip to play, or {@code null} for absence.</li>
 * <li>{@code fallback} - the clip came from the store's fallback (the built-in default model) rather than from the
 * store's own payload. Upstream sets it to {@code result.animation() != null} ({@code :227}), i.e. it says "this is
 * the default's clip", not "I asked the default".</li>
 * <li>{@code cacheable} - the answer is stable enough for a caller to keep instead of asking again. A controller caches
 * only cacheable answers ({@code ControllerAnimationCache.java:32-45}), which is what keeps a not-yet-bindable name
 * from being remembered as absent for the rest of the session.</li>
 * </ul>
 *
 * <p>
 * A record, like upstream's, so the three fields cannot be half-updated by a reader.
 */
public record AnimationResolution(@Nullable Animation animation, boolean fallback, boolean cacheable) {

    /** The proven-absence answer: nothing in this store or its fallback defines the name, and nothing will. */
    public static final AnimationResolution ABSENT = new AnimationResolution(null, false, true);

    /**
     * This answer marked not cacheable - upstream's {@code Resolution#unstable} ({@code :413-415}).
     *
     * <p>
     * Used where the store could not prove its answer: a clip that failed to bind, a name whose payload has not been
     * attached yet. The caller must ask again next time instead of remembering the absence.
     */
    public AnimationResolution unstable() {
        return this.cacheable ? new AnimationResolution(this.animation, this.fallback, false) : this;
    }
}
