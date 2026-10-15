/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */
package software.bernie.geckolib3.core;

import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.geo.RenderContext;

/**
 * This interface must be applied to any object that wants to be animated
 */
public interface IAnimatable {

    void registerControllers(AnimationData data);

    AnimationFactory getFactory();

    /**
     * Whether this animatable's state is fixed for the whole frame in {@code context}, i.e. whether a second draw of
     * it inside one frame must reuse the pose the frame already produced instead of advancing the animation again.
     *
     * <p>Upstream asks the same question of its animatable ({@code tmp/YesSteveModel-dev-1.20
     * .../geckolib3/model/AnimatableEntity.java:456-461}, resolved into the context at {@code :452-454}), and its GUI
     * preview entity answers {@code true} ({@code .../client/gui/CustomGuiPlayerEntity.java:43-46}) so a preview
     * presents one stable pose per frame. Its own default reads the three flags this port does not model
     * ({@code level() || irisShadow() || offScreen()}, see {@link RenderContext}), so the port's default is a plain
     * {@code false}: only an animatable that deliberately declares immutability - or a pass that attaches a context
     * declaring it - enters that path, and every other animatable keeps behaving exactly as it did before this
     * method existed.
     */
    default boolean determineImmutableContext(RenderContext context) {
        return false;
    }
}
