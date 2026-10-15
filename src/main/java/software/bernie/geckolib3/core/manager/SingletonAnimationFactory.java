package software.bernie.geckolib3.core.manager;

import software.bernie.geckolib3.core.IAnimatable;

/**
 * The factory for an animatable that gets one {@link AnimationData} rather than one per entity instance.
 * <p>
 * It deliberately carries no state of its own: it used to keep a private {@code animationDataMap} and override
 * {@link AnimationFactory#getOrCreateAnimationData}, which made the base class's {@code dispose()} iterate an
 * always-empty map - so a singleton factory's {@code AnimationData} (and the geometry and animation-file references it
 * holds) was never released. The base class already keys by uniqueID with exactly the same
 * create-and-register logic, so the override was pure duplication; the advanced branch deletes it for the same
 * reason.
 */
public class SingletonAnimationFactory extends AnimationFactory {

    public SingletonAnimationFactory(IAnimatable animatable) {
        super(animatable);
    }
}
