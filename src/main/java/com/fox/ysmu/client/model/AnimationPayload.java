package com.fox.ysmu.client.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * A payload's animation files in whichever form the delivering channel produced them.
 *
 * <p>
 * The OpenYSM binary channel carries animation structures and the client builds the engine's {@code Animation}
 * objects from them directly ({@code RawAnimationBuilder}); a folder model, the built-in default and the legacy
 * container carry JSON bytes and go through the engine's JSON reader. Both kinds of payload reach the same install rule
 * ({@code ClientModelManager.registerAnimations}), which needs to know which is which, and the eviction subsystem
 * ({@code AnimationProvider}) has to be able to hand the same thing back after a reload - that is the whole purpose of
 * this carrier. It also guarantees the two maps never disagree about the key set: a key appears in exactly one of them,
 * because the adapter writes a file either as bytes or as a structure, never both.
 */
public final class AnimationPayload {

    private static final AnimationPayload EMPTY = new AnimationPayload(null, Collections.emptyMap(), Collections.emptyMap());

    private final String identity;
    private final Map<String, byte[]> files;
    private final Map<String, RawYsmModel.RawAnimationFile> structured;

    private AnimationPayload(String identity, Map<String, byte[]> files,
        Map<String, RawYsmModel.RawAnimationFile> structured) {
        this.identity = identity;
        this.files = files;
        this.structured = structured;
    }

    /**
     * The payload's content identity ({@link ModelData#getBakeIdentity()}), used as the disk cache key, or
     * {@code null} when the payload arrived without one (then nothing is cached).
     */
    @Nullable
    public String identity() {
        return this.identity;
    }

    /** The animation half of a payload, or the empty instance when it declares none. */
    public static AnimationPayload of(@Nullable ModelData data) {
        if (data == null) {
            return EMPTY;
        }
        Map<String, byte[]> files = data.getAnimation();
        Map<String, RawYsmModel.RawAnimationFile> structured = data.getPreParsedAnimations();
        boolean noFiles = files == null || files.isEmpty();
        boolean noStructured = structured == null || structured.isEmpty();
        if (noFiles && noStructured) {
            return EMPTY;
        }
        return new AnimationPayload(data.getBakeIdentity(), noFiles ? Collections.emptyMap() : files,
            noStructured ? Collections.emptyMap() : structured);
    }

    public boolean isEmpty() {
        return this.files.isEmpty() && this.structured.isEmpty();
    }

    /** Every key of the payload, JSON files first and then structures, each in its own insertion order. */
    public Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>(this.files.keySet());
        keys.addAll(this.structured.keySet());
        return keys;
    }

    /** The JSON bytes for a key, or {@code null} when that key is carried as a structure. */
    @Nullable
    public byte[] bytes(String key) {
        return this.files.get(key);
    }

    /** The structure for a key, or {@code null} when that key is carried as JSON bytes. */
    @Nullable
    public RawYsmModel.RawAnimationFile structured(String key) {
        return this.structured.get(key);
    }

    /** The JSON files, for callers that only understand bytes (weight accounting, diagnostics). */
    public Map<String, byte[]> files() {
        return this.files;
    }

    /** The structures, for callers that only understand structures (diagnostics). */
    public Map<String, RawYsmModel.RawAnimationFile> structures() {
        return this.structured;
    }

    /** A copy with the given key removed; used by the "this key is a controller" routing. */
    public AnimationPayload without(String key) {
        if (!this.files.containsKey(key) && !this.structured.containsKey(key)) {
            return this;
        }
        Map<String, byte[]> remainingFiles = new LinkedHashMap<>(this.files);
        remainingFiles.remove(key);
        Map<String, RawYsmModel.RawAnimationFile> remainingStructured = new LinkedHashMap<>(this.structured);
        remainingStructured.remove(key);
        return new AnimationPayload(this.identity, remainingFiles, remainingStructured);
    }
}
