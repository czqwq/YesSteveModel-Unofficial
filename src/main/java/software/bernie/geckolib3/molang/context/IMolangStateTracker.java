package software.bernie.geckolib3.molang.context;

import net.minecraft.util.Vec3;

/**
 * The subset of the host's per-entity state tracking that MoLang queries read.
 */
public interface IMolangStateTracker {

    /** Render ticks elapsed since the previous rendered frame. */
    float getRenderTickDelta();

    /** Interpolated position change between the previous and the current rendered frame. */
    Vec3 getPositionDelta();
}
