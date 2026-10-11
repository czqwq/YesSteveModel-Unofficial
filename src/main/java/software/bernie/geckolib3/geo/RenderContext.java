package software.bernie.geckolib3.geo;

/**
 * Which pass of the frame is asking for a pose, and whether that pass promises the animatable's own state is fixed
 * for the whole frame.
 *
 * <p>Modelled on upstream's record of the same name
 * ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/geckolib3/geo/RenderContext.java:9-21}). Its
 * {@code immutable} bit is what makes a GUI preview present one stable pose per frame: with it set,
 * {@code AnimatedGeoModel#setLivingAnimations} refuses to advance the animation a second time inside one frame
 * ({@code .../client/entity/AnimatableEntity.java:344} and {@code :373-374}, in {@code tickAnimation}'s
 * {@code :341-389} block) and reuses the pose that frame already produced ({@code :265-277}, the single
 * {@code mainRenderData}).
 *
 * <p><b>Upstream fields this port does not model</b>, each because its input does not exist in 1.7.10:
 * <ul>
 * <li>{@code level} ({@code RenderContext.java:9}): upstream's "the level pass is running" flag, which it also hands
 *     to its native renderer through {@code nativeType()} ({@code :23-28}). This port has no native renderer layer
 *     and derives the world draw from the renderer it was called from, so nothing would read the flag.</li>
 * <li>{@code irisShadow} ({@code :9}): Iris has no 1.7.10 counterpart, so there is no shadow pass to name. Upstream's
 *     only consumer of it is the exception in {@code client/entity/CustomPlayerEntity.java:43-51}, which has nothing
 *     to attach to here.</li>
 * <li>{@code offScreen} ({@code :9}): upstream's own extraction point passes {@code false} unconditionally
 *     ({@code util/RenderUtil.java:57-67}), so it carries no behaviour to port.</li>
 * <li>{@code nativeType()} ({@code :23-28}): it answers a {@code RenderContextType} for the native renderer; this
 *     port draws through the fixed-function pipeline.</li>
 * </ul>
 * One consequence is worth stating, because it changes what the whitelist can ask: upstream's
 * {@code AnimatableEntity:456-461} answers {@code level() || irisShadow() || offScreen()} for an animatable that has
 * not declared otherwise, and none of those three exists here. {@code IAnimatable#determineImmutableContext} is
 * therefore a plain {@code false}, and only an animatable that deliberately overrides it - or a pass that declares
 * {@link #withImmutable(boolean)} - enters the immutable path.
 *
 * <p><b>Why there are per-pass factories and no {@code levelImmutable()}.</b> Upstream declares immutability in two
 * places and this port keeps both: through the animatable ({@code AnimatableEntity:452-454} resolves the context with
 * {@code determineImmutableContext}, and {@code client/gui/CustomGuiPlayerEntity.java:43-46} answers {@code true} for
 * the GUI preview entity) and through the caller (upstream's {@code client/entity/CustomEntity.java:247} hands its
 * {@code update} an already-immutable {@code RenderContext.levelImmutable()} and never goes through
 * {@code resolveRenderContext} at all). The factory names carry a {@code Pass} suffix only because a record's own
 * accessors already occupy the bare {@code inventory()} / {@code paperDoll()} / {@code firstPersonMod()} names.
 */
public record RenderContext(boolean firstPersonMod, boolean inventory, boolean paperDoll, boolean immutable) {

    /**
     * The context of a pass that declared nothing: no GUI flag set and mutable - i.e. exactly the behaviour of every
     * animatable before this type existed. It is what
     * {@code software.bernie.geckolib3.core.event.predicate.AnimationEvent} carries until a caller attaches a real
     * context.
     */
    public static final RenderContext NONE = new RenderContext(false, false, false, false);

    /**
     * A model-selection or texture-screen tile, i.e. the inventory pass that
     * {@code com.fox.ysmu.client.animation.FrameRenderQueries#renderingInInventory} describes. Immutability is not
     * part of it: a tile that must not re-pose mid-frame declares it the way it can - through the animatable that
     * draws it (upstream's route, {@code CustomGuiPlayerEntity#determineImmutableContext}) or, while it draws a plain
     * world animatable, through {@code inventoryPass().withImmutable(true)}.
     */
    public static RenderContext inventoryPass() {
        return new RenderContext(false, true, false, false);
    }

    /** The HUD extra-player overlay, upstream's {@code paperDoll()} ({@code util/RenderUtil.java:444-470}). */
    public static RenderContext paperDollPass() {
        return new RenderContext(false, false, true, false);
    }

    /**
     * The first-person pass, upstream's {@code firstPersonMod} flag ({@code FirstPersonCompat.isRenderingPlayer()},
     * consumed by {@code variable/FirstPersonModHideVariable.java}). Like upstream, it is not immutable: the pass
     * follows the live player.
     */
    public static RenderContext firstPersonPass() {
        return new RenderContext(true, false, false, false);
    }

    /**
     * The same pass with a different {@code immutable} bit, or {@code this} when nothing changes - upstream's method
     * of the same name ({@code RenderContext.java:16-21}).
     */
    public RenderContext withImmutable(boolean value) {
        if (immutable == value) {
            return this;
        }
        return new RenderContext(firstPersonMod, inventory, paperDoll, value);
    }
}
