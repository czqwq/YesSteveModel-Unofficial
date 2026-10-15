package com.fox.ysmu.client.animation.molang;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

/**
 * Per-frame host context for the legacy MoLang evaluation path.
 *
 * <p>
 * Two 1.7.10 migration gaps need to know which entity the current frame belongs to, and neither can ask the
 * engine: the physics runtime keys its scope internally and exposes no accessor, and the legacy
 * {@code core.molang.MolangParser} that evaluates animation-file expressions is stateless with respect to the
 * animatable.
 *
 * <ul>
 * <li>{@code query.position_delta(axis)} (A-05) needs the per-frame movement of the rendered entity; it used to
 * read three {@code public static double} fields that nothing ever wrote, so it always answered 0.</li>
 * <li>the {@code ctrl.hold} function (A-06③) needs the hand-held item of the current player so it can share
 * {@link com.fox.ysmu.client.animation.condition.InnerClassify#matchesHandCondition} with the controller
 * evaluator instead of being a constant-0 stub.</li>
 * <li>{@code ysm.play_sound}/{@code ysm.stop_sound}/{@code ysm.stop_all_sounds} need the model the frame belongs
 * to, so a sound name is resolved against the model that declared it rather than against every model the client
 * happens to hold ({@link #getMainModelId()}).</li>
 * </ul>
 *
 * <p>
 * The context is published once per frame per animatable and stored per thread, so it carries no cross-entity
 * state:
 * <ul>
 * <li>players: {@code AnimationRegister#setParserValue}, which runs before any controller is evaluated;</li>
 * <li>non-players: {@code AnimationManager#predicateEntityLocomotion}, the only per-frame entry that sees a
 * non-player animatable.</li>
 * </ul>
 * Both run on the render thread. The deltas use the same definition as {@code AnimationRegister}'s
 * {@code query.position_delta} variable ({@code posX - prevPosX} and friends). Outside a published frame - a
 * preview, or before the first render - the accessors answer 0/null rather than a previous entity's values,
 * because every publish overwrites the whole record.
 */
public final class MolangFrameContext {

    private static final ThreadLocal<MolangFrameContext> CURRENT = ThreadLocal.withInitial(MolangFrameContext::new);

    /**
     * The frame's subject. Deliberately {@link Entity} rather than {@code EntityLivingBase}: a projectile sub-entity
     * being drawn (an arrow, say) is not a living entity, and it is the subject whose position a
     * {@code ysm.particle(...)} in its timeline has to be offset from. {@link #getEntity()} keeps the narrower
     * contract for the callers that genuinely need a living entity.
     */
    private Entity entity;
    private ResourceLocation mainModelId;
    private double deltaX;
    private double deltaY;
    private double deltaZ;

    private MolangFrameContext() {}

    /**
     * Publishes {@code entity} as the frame's subject and snapshots its movement. A {@code null} entity clears
     * the context.
     */
    public static void begin(Entity entity) {
        begin(entity, null);
    }

    /**
     * Publishes the frame's subject together with the model it is being drawn with; {@code mainModelId} may be
     * {@code null} when the caller has no model to name.
     */
    public static void begin(Entity entity, @Nullable ResourceLocation mainModelId) {
        MolangFrameContext context = CURRENT.get();
        if (entity == null) {
            context.clear();
            return;
        }
        context.entity = entity;
        context.mainModelId = mainModelId;
        context.deltaX = entity.posX - entity.prevPosX;
        context.deltaY = entity.posY - entity.prevPosY;
        context.deltaZ = entity.posZ - entity.prevPosZ;
    }

    /** Clears the frame's subject; safe to call repeatedly. */
    public static void end() {
        CURRENT.get()
            .clear();
    }

    /** @return the frame's entity when it is a player, otherwise {@code null} (including outside a frame). */
    public static EntityPlayer getPlayer() {
        Entity entity = CURRENT.get().entity;
        return entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
    }

    /**
     * @return the frame's subject when it is a living entity, otherwise {@code null} - including outside a frame, and
     *         including a frame whose subject is a non-living entity such as a projectile. Callers that only need a
     *         position should use {@link #getFrameEntity()} instead.
     */
    @Nullable
    public static EntityLivingBase getEntity() {
        Entity entity = CURRENT.get().entity;
        return entity instanceof EntityLivingBase ? (EntityLivingBase) entity : null;
    }

    /**
     * @return the frame's subject whatever its type, or {@code null} outside a published frame. Use this when the
     *         caller needs only entity-level state (a position, a world, a body yaw) and must also work for a
     *         projectile, which is not a living entity.
     */
    @Nullable
    public static Entity getFrameEntity() {
        return CURRENT.get().entity;
    }

    /**
     * @return the main model id the frame is being drawn with, or {@code null} outside a published frame or when the
     *         caller published no model. This is the {@code <model>/main} id, not the model id.
     */
    @Nullable
    public static ResourceLocation getMainModelId() {
        return CURRENT.get().mainModelId;
    }

    /**
     * @param axis 0 = X, 1 = Y, 2 = Z
     * @return the frame entity's movement on that axis, or 0 outside a frame or for an unknown axis
     */
    public static double getPositionDelta(int axis) {
        MolangFrameContext context = CURRENT.get();
        if (context.entity == null) {
            return 0.0D;
        }
        if (axis == 0) {
            return context.deltaX;
        }
        if (axis == 1) {
            return context.deltaY;
        }
        if (axis == 2) {
            return context.deltaZ;
        }
        return 0.0D;
    }

    private void clear() {
        this.entity = null;
        this.mainModelId = null;
        this.deltaX = 0.0D;
        this.deltaY = 0.0D;
        this.deltaZ = 0.0D;
    }
}
