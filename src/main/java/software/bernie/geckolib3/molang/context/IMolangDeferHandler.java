package software.bernie.geckolib3.molang.context;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import software.bernie.geckolib3.molang.value.IValue;

/**
 * Implemented by an animatable that defers MoLang behaviour to the end of the frame.
 * <p>
 * Deferred functions are collected while the animation is evaluated and executed once the frame is done, which is
 * what lets a model emit particles/sounds from a controller that has already finished.
 */
public interface IMolangDeferHandler {

    /** Functions to run at the end of the frame, or {@code null} when the model registers none. */
    @Nullable
    List<IValue> getMolangDeferHandler();
}
