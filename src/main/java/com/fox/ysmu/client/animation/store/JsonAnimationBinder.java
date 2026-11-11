package com.fox.ysmu.client.animation.store;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fox.ysmu.util.GsonHelper;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

/**
 * Binds one clip at a time out of an animation file's JSON, parsed once into a tree.
 *
 * <p>
 * This is the JSON half of the store's laziness. Today the install rule turns a whole file into engine objects before
 * the model is first drawn ({@code ClientModelManager#animationFileFromJson}, reached from
 * {@code registerAnimations}), which costs one {@code KeyFrame} per channel per keyframe time, each carrying a MoLang
 * expression - the expensive half, as that method's own javadoc says. Upstream pays it per name on first request
 * ({@code AnimationStore#resolve}, {@code AnimationStore.java:139-221}). The tree parse is cheap and happens once, in
 * the constructor, so a store built over 100 declared names holds no keyframe graph until one is asked for; this class
 * keeps only the parsed tree and hands each clip to {@link JsonAnimationUtils#deserializeJsonToAnimation} when the
 * store asks.
 *
 * <p>
 * The conversion is byte-for-byte the one {@code ClientModelManager#animationFileFromJson} performs today - same
 * {@code JsonAnimationUtils} entry point, same {@code attachAnimSpeed} step - because the store replaces that method's
 * output and any difference would show up as a different clip. {@code attachAnimSpeed} is copied rather than shared:
 * the original is private to {@code ClientModelManager}, and this package must stay additive until the wiring lands
 * (the copy is removed together with the original then).
 *
 * <p>
 * A file whose text is not a JSON object, or that is not readable as one, throws from the constructor: the caller
 * knows the model and the payload key and reports it the way the install rule reports a malformed file today
 * ({@code "Failed to parse animation file {} for model {}"}). A file that parses but declares no animations (a
 * controller file, or an empty set) yields an empty name set instead, which is also what the current install rule
 * answers.
 *
 * <p>
 * Client thread only, like {@link AnimationBinder}.
 */
public final class JsonAnimationBinder implements AnimationBinder {

    private final String key;
    private final MolangParser parser;
    /** The declared names, captured once: the store keeps its own copy and this list must not change when a source is freed. */
    private final Set<String> names;
    /** Tree mode: the file's parsed JSON, or {@code null} in clip-text mode. Entries are dropped once bound. */
    @Nullable
    private final Map<String, JsonElement> animations;
    /** Clip-text mode: each clip's own JSON text, or {@code null} in tree mode. Entries are dropped once bound. */
    @Nullable
    private final Map<String, String> clipTexts;

    /**
     * @param key the payload key this file arrived under, for diagnostics
     * @param json the file's bytes
     * @param parser the engine's parser, which turns every keyframe expression into a MoLang value
     * @throws IllegalArgumentException when the bytes are not a JSON object
     */
    public JsonAnimationBinder(String key, byte[] json, MolangParser parser) {
        this(key, parseTree(key, json), parser);
    }

    /**
     * @param key the payload key this file arrived under, for diagnostics
     * @param tree the file's already-parsed JSON object, for callers that parse off the client thread (the loader
     *        thread's {@code prepareAnimations} parses the same text today)
     * @param parser the engine's parser
     */
    public JsonAnimationBinder(String key, JsonObject tree, MolangParser parser) {
        this.key = key;
        this.parser = parser;
        Map<String, JsonElement> declared = new LinkedHashMap<>();
        // AnimationFile order, which is the file's own order: a deterministic name list is what the installer's
        // condition registration and its "inherited from the default" bookkeeping need.
        for (Map.Entry<String, JsonElement> entry : JsonAnimationUtils.getAnimations(tree)) {
            declared.put(entry.getKey(), entry.getValue());
        }
        this.names = Collections.unmodifiableSet(new LinkedHashSet<>(declared.keySet()));
        this.animations = declared;
        this.clipTexts = null;
    }

    /**
     * Clip-text mode: the binder holds each clip's own JSON text instead of the file's tree.
     *
     * <p>
     * This is what the install path uses. A Gson tree costs roughly ten times the JSON text it was parsed from (one
     * node object per value), and a store has to keep its source alive for later bindings, so holding the tree made the
     * lazy stores heavier than the keyframe graphs they replaced (measured by `.agent/verify-animation-store.md` §3.3:
     * 24-194 MB of trees against 10-84 MB of graphs). The text of one clip is a small string, the binder re-parses just
     * that clip when the store asks for it, and the whole-file tree is dropped after the loader thread has produced the
     * texts - so a store's retained source is the payload's own JSON size, not a node graph.
     *
     * <p>
     * Both modes drop a name's source as soon as the store has asked for it: the store caches the bound clip and never
     * asks again (a failed bind is remembered too), so keeping the text/tree entry afterwards would hold memory for a
     * clip that is already resolved. A model whose every clip has been played therefore keeps only its keyframe graphs,
     * the same footprint as the old whole-file install.
     *
     * @param key the payload key this file arrived under, for diagnostics
     * @param clipTexts each animation name mapped to its own JSON text, in file order
     * @param parser the engine's parser
     */
    private JsonAnimationBinder(String key, Map<String, String> clipTexts, MolangParser parser) {
        this.key = key;
        this.parser = parser;
        this.names = Collections.unmodifiableSet(new LinkedHashSet<>(clipTexts.keySet()));
        this.animations = null;
        this.clipTexts = new LinkedHashMap<>(clipTexts);
    }

    /** A binder over per-clip JSON text; see the private constructor. */
    public static JsonAnimationBinder fromClipTexts(String key, Map<String, String> clipTexts, MolangParser parser) {
        return new JsonAnimationBinder(key, clipTexts, parser);
    }

    /** The payload key this file arrived under. */
    public String key() {
        return this.key;
    }

    /**
     * Every animation name this file declares, in file order, without having bound any of them.
     *
     * <p>
     * This is what the store takes as its declared names, and the port's counterpart of upstream's baked name index
     * ({@code BakedAssetView.java:36,95}): upstream enumerates the names from the manifest and binds the body later,
     * the port enumerates them from the file's own animation entries. The set is a snapshot taken at construction, so it
     * stays complete while the binder frees each name's source as it binds.
     */
    public Set<String> names() {
        return this.names;
    }

    /**
     * Binds one clip, exactly as {@code animationFileFromJson} would have bound it during a whole-file conversion.
     *
     * @return the clip, or {@code null} when this file does not declare {@code name}
     */
    @Override
    @Nullable
    public Animation bind(String name) throws Exception {
        JsonElement element;
        if (this.animations != null) {
            element = this.animations.get(name);
        } else {
            String text = this.clipTexts.get(name);
            element = text == null ? null
                : GsonHelper.fromJson(ysmu.GSON, text, JsonElement.class);
        }
        if (element == null) {
            return null;
        }
        try {
            // JsonAnimationUtils#getAnimation's own shape: the name paired with its element, so the engine reads the
            // requested name even when the entry is malformed and the conversion throws.
            Map.Entry<String, JsonElement> entry = new AbstractMap.SimpleEntry<>(name, element);
            Animation animation = JsonAnimationUtils.deserializeJsonToAnimation(entry, this.parser);
            attachAnimSpeed(animation, entry);
            return animation;
        } finally {
            // The store caches the result (or the failure) and never asks again, so this name's source is dead weight
            // from here on; dropping it is what keeps a fully-played model at the keyframe graphs only.
            if (this.animations != null) {
                this.animations.remove(name);
            } else {
                this.clipTexts.remove(name);
            }
        }
    }

    private static JsonObject parseTree(String key, @Nullable byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalArgumentException("Animation file " + key + " carries no bytes");
        }
        JsonObject tree = GsonHelper.fromJson(ysmu.GSON, new String(json, StandardCharsets.UTF_8), JsonObject.class);
        if (tree == null) {
            throw new IllegalArgumentException("Animation file " + key + " is not a JSON object");
        }
        return tree;
    }

    /**
     * YSMU: 把模型动画 JSON 的 {@code anim_speed}（逐动画播放倍率，数字或 MoLang 表达式）与
     * {@code anim_time_update}（自定义时间推进）挂到引擎 {@link Animation#animSpeed} /
     * {@link Animation#animTimeUpdate}，以及 {@code blend_weight}。
     *
     * <p>
     * 逐行复制自 {@code ClientModelManager#attachAnimSpeed}（本仓的参考分支移植，见那里的完整说明）：那是全仓对
     * 这三个字段的写入点，懒绑定后必须由这里继续写，否则每个懒绑定的模型都丢掉 {@code anim_speed}。两份实现
     * 在 M3 接线移除 {@code ClientModelManager} 的那一份后只剩这一份。
     */
    private static void attachAnimSpeed(Animation animation, Map.Entry<String, JsonElement> animEntry) {
        if (animEntry == null || !animEntry.getValue()
            .isJsonObject()) {
            return;
        }
        JsonObject animObject = animEntry.getValue()
            .getAsJsonObject();
        JsonElement speed = animObject.get("anim_speed");
        if (speed != null && speed.isJsonPrimitive()) {
            animation.animSpeed = speed.getAsString();
        }
        JsonElement weight = animObject.get("blend_weight");
        if (weight != null && weight.isJsonPrimitive()) {
            animation.blendWeight = weight.getAsString();
        }
        JsonElement timeUpdate = animObject.get("anim_time_update");
        if (timeUpdate != null && timeUpdate.isJsonPrimitive()) {
            animation.animTimeUpdate = timeUpdate.getAsString();
        }
    }
}
