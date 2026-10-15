package software.bernie.geckolib3.molang.context;

import org.jetbrains.annotations.Nullable;

import software.bernie.geckolib3.model.provider.data.EntityModelData;

/**
 * The host's per-frame animation event, as seen by the MoLang runtime.
 */
public interface IMolangAnimationEvent {

    /** Partial tick the model asked for when it requested this frame. */
    float getRequestedPartialTick();

    /** Partial tick actually used for this frame. */
    float getPartialTick();

    /** Entity tick count plus the partial tick. */
    float getRenderTicks();

    /** Limb swing / head rotation data gathered for this frame. */
    EntityModelData getExtraData();

    /** Debug sink for {@code debug_output}, or {@code null} when debugging is off. */
    @Nullable
    DebugSource getDebugSource();

    /** The animatable this event belongs to. */
    IMolangAnimatable getAnimatableEntity();

    /**
     * True when this frame is being drawn inside the world rather than in a GUI/preview pass.
     * <p>
     * The {@code is_first_person} query only consults the camera for the local player while the world is rendering.
     */
    boolean isRenderingInLevel();
}
