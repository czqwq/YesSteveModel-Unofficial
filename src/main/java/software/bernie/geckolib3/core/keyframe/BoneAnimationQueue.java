/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.keyframe;

import software.bernie.geckolib3.core.processor.IBone;

public class BoneAnimationQueue {

    public final IBone bone;
    /**
     * Whether this queue is already in its controller's {@code activeBoneAnimationQueues} list for the tick being
     * processed. The list used to be maintained with {@code List.contains} per animated bone, which is a linear
     * scan of every bone already marked active (R14 of .agent/perf-audit-frame-cpu.md). The flag makes "already
     * listed" a field test; {@code AnimationController#discardPendingPoints} clears it with the list.
     */
    public boolean activeThisTick;
    public AnimationPointQueue rotationXQueue = new AnimationPointQueue();
    public AnimationPointQueue rotationYQueue = new AnimationPointQueue();
    public AnimationPointQueue rotationZQueue = new AnimationPointQueue();
    public AnimationPointQueue positionXQueue = new AnimationPointQueue();
    public AnimationPointQueue positionYQueue = new AnimationPointQueue();
    public AnimationPointQueue positionZQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleXQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleYQueue = new AnimationPointQueue();
    public AnimationPointQueue scaleZQueue = new AnimationPointQueue();

    public BoneAnimationQueue(IBone bone) {
        this.bone = bone;
    }
}
