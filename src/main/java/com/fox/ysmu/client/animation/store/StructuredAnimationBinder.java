package com.fox.ysmu.client.animation.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.RawAnimationBuilder;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * Binds one clip at a time out of the binary channel's {@link RawYsmModel.RawAnimationFile} structures.
 *
 * <p>
 * The structures are already the payload's in-memory form (decrypted and deserialized once by the sync path), so the
 * only work a bind does is the per-clip conversion {@link RawAnimationBuilder} performs for a whole file. Upstream's
 * {@code AnimationStore} binds one animation proto per name ({@code AnimationStore.java:139-221}); this is the same
 * granularity over this port's structure carrier, and it goes through the very entry point the whole-file builder uses
 * for each clip ({@link RawAnimationBuilder#toAnimation}), so a clip bound through the store is field-for-field the
 * clip the whole-file path used to produce - {@code tmp/_probe/AnimationStoreProbe.java} asserts exactly that against
 * {@link RawAnimationBuilder#toAnimationFile}.
 *
 * <p>
 * The declared name set reproduces {@code RawAnimationBuilder#toAnimationFile}'s own two rules: a structure with a
 * blank name is not an animation, and the engine key is the structure's {@code name} field (not its map key). A later
 * structure with the same name replaces an earlier one, which is what {@code AnimationFile#putAnimation} does when the
 * whole-file builder installs them in order.
 *
 * <p>
 * Client thread only, like {@link AnimationBinder}.
 */
public final class StructuredAnimationBinder implements AnimationBinder {

    private final String key;
    private final Map<String, RawYsmModel.RawAnimation> animations;
    private final MolangParser parser;

    /**
     * @param key the payload key these structures arrived under, for diagnostics
     * @param file the structures
     * @param parser the engine's parser, which turns every keyframe expression into a MoLang value
     */
    public StructuredAnimationBinder(String key, @Nullable RawYsmModel.RawAnimationFile file, MolangParser parser) {
        this.key = key;
        this.parser = parser;
        Map<String, RawYsmModel.RawAnimation> declared = new LinkedHashMap<>();
        if (file != null) {
            for (RawYsmModel.RawAnimation animation : file.animations.values()) {
                if (animation == null || StringUtils.isBlank(animation.name)) {
                    continue;
                }
                declared.put(animation.name, animation);
            }
        }
        this.animations = Collections.unmodifiableMap(declared);
    }

    /** The payload key these structures arrived under. */
    public String key() {
        return this.key;
    }

    /** Every animation name these structures declare, in payload order, without having bound any of them. */
    public Set<String> names() {
        return this.animations.keySet();
    }

    /**
     * Builds one clip.
     *
     * @return the clip, or {@code null} when these structures do not declare {@code name}
     */
    @Override
    @Nullable
    public Animation bind(String name) throws Exception {
        RawYsmModel.RawAnimation source = this.animations.get(name);
        return source == null ? null : RawAnimationBuilder.toAnimation(source, this.parser);
    }
}
