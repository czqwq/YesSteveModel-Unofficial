package com.fox.ysmu.api;

import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.data.EntityClips;
import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Client-side animation entry point for entities YSMU renders but does not own the animation logic for.
 * <p>
 * A host mod that already decides which animation an entity plays - Touhou Little Maid keeps a whole clip
 * priority table of its own, including the {@code sit} pose - resolves the clip itself and pushes the single
 * resulting name here. YSMU plays exactly that clip in the entity's main controller. The direction is
 * deliberate: duplicating the host's table inside YSMU would put the same clip names in two places and make
 * a rename half-applied, and reading the host's state directly would require either a compile-time
 * dependency on it or another layer of reflection.
 * <p>
 * Nothing here touches players; player animation comes from YSMU's own state machine. When no clip is
 * pushed, a non-player entity keeps the built-in locomotion (run/walk/idle) and the model's own play state.
 * <p>
 * Everything in this class is common-side safe, so a host may resolve it on either side; the calls
 * themselves only have meaning on the client that renders the entity.
 */
public final class EntityAnimationApi {

    /** The built-in default animation file, mirrored from {@code CustomPlayerModel} to keep this class client-free. */
    private static final ResourceLocation DEFAULT_ANIMATION = ModelIdUtil
        .getMainId(new ResourceLocation(ysmu.MODID, "default"));

    private static boolean unknownLoopModeLogged = false;

    private EntityAnimationApi() {}

    /**
     * Publishes the clip an entity should play.
     *
     * @param entity   the entity YSMU is rendering; client side only in practice
     * @param clip     the clip name, as it appears in the model's animation file
     * @param loopMode one of the engine's three names - {@code LOOP}, {@code PLAY_ONCE} or
     *                 {@code HOLD_ON_LAST_FRAME} - case-insensitive; {@code null} means {@code LOOP}. A clip
     *                 authored as a transition into a pose (a maid's {@code sit}) only holds under
     *                 {@code HOLD_ON_LAST_FRAME}: asked for {@code LOOP} it replays the transition forever,
     *                 and asked for {@code PLAY_ONCE} it releases the pose after a single frame.
     * @return {@code false} when the entity or clip is unusable, or the loop mode is not one of the three
     */
    public static boolean setEntityClip(EntityLivingBase entity, String clip, String loopMode) {
        if (entity == null || clip == null || clip.isEmpty()) {
            return false;
        }
        ILoopType loop = parseLoop(loopMode);
        if (loop == null) {
            if (!unknownLoopModeLogged) {
                unknownLoopModeLogged = true;
                ysmu.LOG.warn(
                    "Unknown animation loop mode '{}' (expected LOOP, PLAY_ONCE or HOLD_ON_LAST_FRAME); the clip was not applied. Further occurrences will not be logged.",
                    loopMode);
            }
            return false;
        }
        EntityClips.put(entity, clip, loop);
        return true;
    }

    /**
     * Drops an entity's pushed clip, so it falls back to YSMU's own locomotion.
     *
     * @return whether a clip was actually published for the entity
     */
    public static boolean clearEntityClip(EntityLivingBase entity) {
        if (entity == null) {
            return false;
        }
        boolean had = EntityClips.get(entity) != null;
        EntityClips.clear(entity);
        return had;
    }

    /** The clip currently pushed for the entity, or {@code null} when there is none. */
    @Nullable
    public static String getEntityClip(EntityLivingBase entity) {
        EntityClips.Clip clip = EntityClips.get(entity);
        return clip == null ? null : clip.getName();
    }

    /** The loop mode currently pushed for the entity, or {@code null} when there is none. */
    @Nullable
    public static String getEntityClipLoop(EntityLivingBase entity) {
        EntityClips.Clip clip = EntityClips.get(entity);
        return clip == null ? null : clip.getLoopType()
            .toString();
    }

    /**
     * Whether the animation file YSMU is using for this entity defines the clip.
     * <p>
     * A host that resolves its own clip table has an "is this clip in her model" filter of its own; this is
     * the same question asked of the model YSMU will actually play. Pushing a clip the model does not define
     * is not an error - YSMU falls back to locomotion - but asking first keeps the two renderers agreeing on
     * which clip is showing.
     */
    public static boolean modelHasClip(EntityLivingBase entity, String clip) {
        if (entity == null || clip == null || clip.isEmpty()) {
            return false;
        }
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(animationIdFor(entity));
        return file != null && file.animations.containsKey(clip);
    }

    /**
     * The engine loop type for a published name, or {@code null} when the name is not one of the three.
     * <p>
     * The names are the engine's own on purpose: two vocabularies for one idea is how a mapping ends up
     * half-applied, and the half that is wrong plays a clip that never finishes.
     */
    @Nullable
    public static ILoopType parseLoop(String loopMode) {
        if (loopMode == null || loopMode.isEmpty()) {
            return ILoopType.EDefaultLoopTypes.LOOP;
        }
        switch (loopMode.trim()
            .toUpperCase(Locale.ROOT)) {
            case "LOOP":
                return ILoopType.EDefaultLoopTypes.LOOP;
            case "PLAY_ONCE":
                return ILoopType.EDefaultLoopTypes.PLAY_ONCE;
            case "HOLD_ON_LAST_FRAME":
                return ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
            default:
                return null;
        }
    }

    /**
     * The animation file YSMU uses for the entity, mirroring {@code CustomPlayerEntity#getAnimation}: the
     * entity's own model when its animations are loaded, the built-in default otherwise.
     */
    private static ResourceLocation animationIdFor(EntityLivingBase entity) {
        ResourceLocation main = null;
        EntityModelData override = NPCData.getData(entity);
        if (override != null) {
            main = ModelIdUtil.getMainId(override.getModelId());
        } else if (entity instanceof EntityPlayer player) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null && eep.getModelId() != null) {
                main = ModelIdUtil.getMainId(eep.getModelId());
            }
        }
        if (main != null && GeckoLibCache.getInstance()
            .getAnimations()
            .containsKey(main)) {
            return main;
        }
        return DEFAULT_ANIMATION;
    }
}
