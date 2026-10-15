package software.bernie.geckolib3.core;

public enum PlayState {
    CONTINUE,
    /**
     * Hold the pose the controller is already showing: the clip stays where it is instead of advancing or being
     * stopped. Upstream has the same third state and uses it where a pose must survive an interruption rather than
     * restart - the held-item channels answer {@code PAUSE} while that hand is swinging
     * ({@code client/animation/predicate/MainhandPredicate.java:31-33}).
     */
    PAUSE,
    STOP
}
