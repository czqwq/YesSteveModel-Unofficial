package com.fox.ysmu.client.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import software.bernie.geckolib3.file.AnimationFile;

/**
 * One payload's animation sets, already built off the client thread.
 *
 * <p>
 * Building a model's animations is pure CPU (parse/build an {@code Animation} per clip, plus the optional-mod scan per
 * animation) but it used to happen on the client thread the first time the model was drawn, so a large pack cost a
 * visible hitch at that moment. The loader thread now produces this bundle as soon as the payload is parked, and the
 * install rule on the client thread consumes it instead of building again - the same split the geometry half uses
 * ({@code COMPLETED_BUILDS}).
 *
 * <p>
 * The bundle remembers the payload it was built from: a re-registration of the same model id replaces the parked
 * payload, and a bundle whose payload is not the current one must be ignored rather than installed as if it belonged
 * to the newer bytes.
 */
public final class PreparedAnimations {

    private final AnimationPayload payload;
    private final Map<String, AnimationFile> files;
    private final Map<String, Map<String, Set<String>>> modIds;

    public PreparedAnimations(AnimationPayload payload, Map<String, AnimationFile> files,
        Map<String, Map<String, Set<String>>> modIds) {
        this.payload = payload;
        this.files = files == null ? Collections.emptyMap() : new LinkedHashMap<>(files);
        this.modIds = modIds == null ? Collections.emptyMap() : new LinkedHashMap<>(modIds);
    }

    /** The payload these files were built from; only a bundle for the currently parked payload may be installed. */
    public AnimationPayload payload() {
        return this.payload;
    }

    /** The built animation file for one payload key, or {@code null} when that key is not an animation set. */
    @Nullable
    public AnimationFile file(String key) {
        return this.files.get(key);
    }

    /**
     * The optional-mod ids each animation of one file references, keyed by animation name - the same answer the
     * on-demand scan derives from the file's JSON text.
     */
    public Map<String, Set<String>> modsForFile(String key) {
        Map<String, Set<String>> mods = this.modIds.get(key);
        return mods == null ? Collections.emptyMap() : mods;
    }


}
