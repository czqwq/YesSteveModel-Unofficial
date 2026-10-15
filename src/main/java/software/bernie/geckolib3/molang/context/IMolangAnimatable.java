package software.bernie.geckolib3.molang.context;

import org.jetbrains.annotations.Nullable;

import software.bernie.geckolib3.molang.value.IValue;

/**
 * The host's animatable object, as seen by the MoLang runtime.
 * <p>
 * On 1.20.1 this was the concrete {@code AnimatableEntity} of the mod that owned the engine. The library only
 * needs these three members, so the host mod implements this interface on whatever object drives its model.
 */
public interface IMolangAnimatable {

    /** Seconds (or ticks/20) elapsed in the currently playing animation. */
    float getSeekTime();

    /** Per-frame movement/delta bookkeeping used by the {@code query.position_delta} and {@code delta_time} queries. */
    IMolangStateTracker getStateTracker();

    /** A model supplied (user defined) MoLang function, or {@code null} when the model has none by that name. */
    @Nullable
    IValue getUserFunction(String name);
}
