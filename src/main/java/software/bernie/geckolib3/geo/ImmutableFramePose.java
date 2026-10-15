package software.bernie.geckolib3.geo;

import java.util.List;

import software.bernie.geckolib3.core.processor.IBone;

/**
 * The pose one immutable frame already produced, so a second draw inside that frame can reuse it instead of advancing
 * the animation again.
 *
 * <p>This is the port's counterpart of upstream's extracted render data. Upstream keeps a posed model as data -
 * {@code GeoRenderData.modelState}, filled by {@code extractRenderData}
 * ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/geckolib3/model/AnimatableEntity.java:255-263}) and
 * reused for an immutable context through the single {@code mainRenderData} ({@code :265-277}) - and its renderer
 * draws from that data. This port's renderer draws from the live {@code GeoBone} instances, and every preview draw
 * brackets itself with a bone snapshot ({@code com.fox.ysmu.client.render.ModelPoseSnapshot}, needed because the
 * world, the HUD overlay and the GUI all mutate the same {@code GeoModel}), so "reuse the frame's pose" has to be a
 * write-back instead of a second draw from stored data. That reason is the only port-specific part of the mechanism;
 * the decision it serves is upstream's.
 *
 * <p>The frame is identified by the animation time it was evaluated at - the render tick that
 * {@code AnimatedGeoModel#setLivingAnimations} computes, which is upstream's {@code renderTicks}
 * ({@code AnimatableEntity.java:342} reads it, {@code :346-357} compares it with {@code lastFrameTime}). Two draws of
 * one animatable inside one rendered frame see the same value, and a value that repeats later is reused rather than
 * re-evaluated, which is upstream's answer for a frame time that does not advance ({@code :354-357} clamps
 * {@code frameTime} to {@code lastFrameTime}).
 *
 * <p>Nothing here holds a bone reference: the record is nine floats per bone plus the frame it belongs to, so it
 * cannot keep geometry alive after the resource framework releases it.
 */
public final class ImmutableFramePose {

    /** position X/Y/Z, rotation X/Y/Z, scale X/Y/Z - {@link IBone}'s animated transform. */
    private static final int STRIDE = 9;

    private double frameTime = Double.NaN;
    private float[] pose = new float[0];
    private int boneCount;

    /**
     * Records the pose of {@code bones} as the pose of the frame identified by {@code frameTime}. Called after the
     * frame's animation has been evaluated, which is where upstream extracts the model state it later reuses
     * ({@code AnimatableEntity.java:239-246}).
     */
    public void capture(double frameTime, List<IBone> bones) {
        int count = bones.size();
        float[] values = this.pose;
        if (values.length < count * STRIDE) {
            values = new float[count * STRIDE];
            this.pose = values;
        }
        int i = 0;
        for (int b = 0; b < count; b++) {
            IBone bone = bones.get(b);
            values[i++] = bone.getPositionX();
            values[i++] = bone.getPositionY();
            values[i++] = bone.getPositionZ();
            values[i++] = bone.getRotationX();
            values[i++] = bone.getRotationY();
            values[i++] = bone.getRotationZ();
            values[i++] = bone.getScaleX();
            values[i++] = bone.getScaleY();
            values[i++] = bone.getScaleZ();
        }
        this.boneCount = count;
        this.frameTime = frameTime;
    }

    /**
     * Writes the recorded pose back onto {@code bones} when {@code frameTime} is the frame that produced it, and
     * answers {@code true} - the caller must then not evaluate the animation again. Answers {@code false}, and writes
     * nothing, for a different frame and for a bone list this record was not taken from (a model switch between two
     * draws of one frame), in which case the caller evaluates normally.
     */
    public boolean reuse(double frameTime, List<IBone> bones) {
        int count = this.boneCount;
        if (count == 0 || bones.size() != count || Double.compare(this.frameTime, frameTime) != 0) {
            return false;
        }
        float[] values = this.pose;
        int i = 0;
        for (int b = 0; b < count; b++) {
            IBone bone = bones.get(b);
            bone.setPositionX(values[i++]);
            bone.setPositionY(values[i++]);
            bone.setPositionZ(values[i++]);
            bone.setRotationX(values[i++]);
            bone.setRotationY(values[i++]);
            bone.setRotationZ(values[i++]);
            bone.setScaleX(values[i++]);
            bone.setScaleY(values[i++]);
            bone.setScaleZ(values[i++]);
        }
        return true;
    }
}
