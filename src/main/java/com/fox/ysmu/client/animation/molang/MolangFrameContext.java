package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

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

    private EntityLivingBase entity;
    private double deltaX;
    private double deltaY;
    private double deltaZ;

    private MolangFrameContext() {}

    /**
     * Publishes {@code entity} as the frame's subject and snapshots its movement. A {@code null} entity clears
     * the context.
     */
    public static void begin(EntityLivingBase entity) {
        MolangFrameContext context = CURRENT.get();
        if (entity == null) {
            context.clear();
            return;
        }
        context.entity = entity;
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
        EntityLivingBase entity = CURRENT.get().entity;
        return entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
    }

    /** @return the frame's entity, or {@code null} outside a published frame. */
    public static EntityLivingBase getEntity() {
        return CURRENT.get().entity;
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
        this.deltaX = 0.0D;
        this.deltaY = 0.0D;
        this.deltaZ = 0.0D;
    }
}
