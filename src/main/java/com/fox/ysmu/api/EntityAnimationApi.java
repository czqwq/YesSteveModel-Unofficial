package com.fox.ysmu.api;

import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.common.FMLCommonHandler;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.store.AnimationStore;
import com.fox.ysmu.data.EntityClips;
import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.ILoopType;

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
 * <p>
 * D-A6: these entry points are effective on the client only, and a server-side call is silently ineffective -
 * it never throws. Nothing here sends a packet and every structure the clip methods touch is a client-side one:
 * {@code EntityClips} for the pushed clip, and the per-model animation stores ({@link #modelHasClip}) - so on a
 * dedicated server a pushed clip is stored but no renderer reads it, the read-backs
 * ({@link #getEntityClip(EntityLivingBase)} and {@link #getEntityClipLoop(EntityLivingBase)}) answer from that same
 * server-side map, and {@link #modelHasClip(EntityLivingBase, String)} answers {@code false} because the store lookup
 * is physically guarded (the stores live in the client's classes, which a dedicated server cannot even load).
 * {@link #parseLoop(String)} is the exception: it only maps a name onto an engine enum and touches no client state, so
 * it works on either side. In single player the integrated server and the client share one JVM, which is why a
 * common-side call from a host there does take effect locally - push clips from the client that renders the entity.
 */
public final class EntityAnimationApi {

    private static boolean unknownLoopModeLogged = false;

    private EntityAnimationApi() {}

    /**
     * D-03 / M-13:默认动画文件跟随 {@code Config.DEFAULT_MODEL_ID},不再硬编码 {@code "default"};
     * 模型名先经 {@link ModelIdUtil#getInternalModelId},否则非安全字符的配置值会让
     * {@link ResourceLocation} 的构造抛异常。每次调用都重算,避免静态常量把 preInit 之前的值固化下来。
     */
    private static ResourceLocation defaultAnimationId() {
        return ModelIdUtil.getMainId(
            new ResourceLocation(ysmu.MODID, ModelIdUtil.getInternalModelId(Config.DEFAULT_MODEL_ID)));
    }

    /**
     * Publishes the clip an entity should play.
     * <p>
     * D-A6: client-effective only. Called on a dedicated server it still stores the clip and returns
     * {@code true}, but no renderer reads that push, so it is silently ineffective - it never throws.
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
     * <p>
     * D-A6: same client-only effectiveness as {@link #setEntityClip(EntityLivingBase, String, String)}; the
     * registry it clears is the one on the calling side, and a server-side clear is never seen by a renderer.
     * It returns {@code false} rather than throwing when nothing was pushed.
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
     * <p>
     * D-A6: client-effective only. The clip names live in the client's per-model animation stores; a dedicated
     * server cannot load those classes at all, so {@link #animationStoreForModel} answers {@code null} there (behind
     * an explicit physical-side guard) and this returns {@code false} instead of throwing.
     */
    public static boolean modelHasClip(EntityLivingBase entity, String clip) {
        if (entity == null || clip == null || clip.isEmpty()) {
            return false;
        }
        AnimationStore store = animationStoreForModel(mainIdFor(entity));
        return store != null && store.contains(clip);
    }

    /**
     * The animation store a clip is looked up in for this model: the model's own when it has one, the built-in
     * default's otherwise.
     * <p>
     * The model's own store already answers for the built-in default through its fallback pointer
     * ({@code client/animation/store/AnimationStore#contains} is local <em>or</em> fallback), so this is the same
     * set of names the merged animation file used to hold for this model, asked without the copy. The second rung is
     * for the entity whose model id this client does not know at all: the old lookup answered out of the default's
     * file there, and the answer must keep coming from the same place.
     * <p>
     * The fallback is not cosmetic: {@code CustomPlayerEntity.getAnimation()} answers with the built-in default while
     * a model has no animation file of its own, and the engine then plays the default's clips. An answer about
     * "does this model define clip X" that skipped the same fallback would disagree with what actually plays, which
     * is exactly what a host mod uses this to decide.
     * <p>
     * The install is forced rather than deferred ({@code ClientModelManager#ensureAnimationsImmediately}): the render
     * path deliberately waits for the loader-thread bundle, while this host-facing query has always answered
     * immediately and must not see a transient "no file" for a model the client already owns.
     */
    @Nullable
    static AnimationStore animationStoreForModel(@Nullable ResourceLocation mainId) {
        if (!FMLCommonHandler.instance().getSide().isClient()) {
            // D-A6: the stores live in the client's classes, which a dedicated server cannot load at all
            // (NoClassDefFoundError, see .agent/phase76-animation-store-port.md "实机反馈修复"). Answering "no store"
            // keeps this entry point's documented server-side contract: effective on the client only, never throws.
            return null;
        }
        ClientModelManager.ensureAnimationsImmediately(mainId);
        AnimationStore own = ClientModelManager.animationStoreFor(mainId);
        return own != null ? own : ClientModelManager.animationStoreFor(defaultAnimationId());
    }

    /**
     * The engine loop type for a published name, or {@code null} when the name is not one of the three.
     * <p>
     * The names are the engine's own on purpose: two vocabularies for one idea is how a mapping ends up
     * half-applied, and the half that is wrong plays a clip that never finishes.
     * <p>
     * D-A6: unlike the clip methods above, this one is side-independent - it only maps a name onto an engine
     * enum and reads no client state, so a server-side call behaves exactly like a client-side one.
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
     * The main model id of the entity's model, or {@code null} when this client has no model id for it.
     * <p>
     * Only the id: the store it resolves to, and the built-in default that stands in when it resolves to nothing, are
     * {@link #animationStoreForModel}'s business. The model's own id is returned even before its animations have been
     * built, because {@code ClientModelManager.animationStoreFor} builds them on request; answering with the built-in
     * default instead would make a host mod look for its clips in the wrong store.
     */
    @Nullable
    private static ResourceLocation mainIdFor(EntityLivingBase entity) {
        EntityModelData override = NPCData.getData(entity);
        if (override != null) {
            return ModelIdUtil.getMainId(override.getModelId());
        }
        if (entity instanceof EntityPlayer player) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null && eep.getModelId() != null) {
                return ModelIdUtil.getMainId(eep.getModelId());
            }
        }
        return null;
    }
}
