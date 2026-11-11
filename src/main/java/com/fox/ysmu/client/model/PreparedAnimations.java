package com.fox.ysmu.client.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * One payload's animation files, already parsed into per-clip JSON text off the client thread.
 *
 * <p>
 * Parsing a model's animation files is pure CPU (Gson over every file's text, plus the optional-mod scan per animation)
 * but it used to happen on the client thread the first time the model was drawn, so a large pack cost a visible hitch at
 * that moment. The loader thread now produces each clip's own JSON text as soon as the payload is parked, and the
 * install rule on the client thread hands those texts to the stores'
 * {@link com.fox.ysmu.client.animation.store.JsonAnimationBinder}s - the same split the geometry half uses
 * ({@code COMPLETED_BUILDS}).
 *
 * <p>
 * It carries per-clip text, not the file's parsed tree: a Gson tree costs roughly ten times the text it was parsed from,
 * and a store must keep its source alive for later bindings, so keeping trees made the lazy stores heavier than the
 * keyframe graphs they replaced (measured in `.agent/verify-animation-store.md` §3.3). The tree is produced and dropped
 * here on the loader thread; what survives is the payload's own JSON size. Structured (OpenYSM binary) payloads need no
 * preparation - their {@code RawAnimationFile} is already the raw per-clip form.
 *
 * <p>
 * The bundle remembers the payload it was built from: a re-registration of the same model id replaces the parked
 * payload, and a bundle whose payload is not the current one must be ignored rather than installed as if it belonged
 * to the newer bytes.
 */
public final class PreparedAnimations {

    private final AnimationPayload payload;
    private final Map<String, Map<String, String>> clips;
    private final Set<String> controllerFiles;
    private final Map<String, Map<String, Set<String>>> modIds;

    public PreparedAnimations(AnimationPayload payload, Map<String, Map<String, String>> clips,
        Set<String> controllerFiles, Map<String, Map<String, Set<String>>> modIds) {
        this.payload = payload;
        this.clips = clips == null ? Collections.emptyMap() : new LinkedHashMap<>(clips);
        this.controllerFiles = controllerFiles == null ? Collections.emptySet() : Set.copyOf(controllerFiles);
        this.modIds = modIds == null ? Collections.emptyMap() : new LinkedHashMap<>(modIds);
    }

    /** The payload these texts were built from; only a bundle for the currently parked payload may be installed. */
    public AnimationPayload payload() {
        return this.payload;
    }

    /**
     * The per-clip JSON text of one payload key (name to text, in file order), or {@code null} when that key is not an
     * animation set (a controller file, a structured payload, or a file that failed to parse).
     */
    @Nullable
    public Map<String, String> clips(String key) {
        return this.clips.get(key);
    }

    /** Whether this key was a controller file, which the install rule routes to the controller registry instead. */
    public boolean isControllerFile(String key) {
        return this.controllerFiles.contains(key);
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
