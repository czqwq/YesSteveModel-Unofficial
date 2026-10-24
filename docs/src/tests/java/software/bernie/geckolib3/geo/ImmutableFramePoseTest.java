package software.bernie.geckolib3.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.render.built.GeoBone;

/**
 * The immutable render context: one frame, two draws of the same animatable, the second must reuse the pose the frame
 * already produced instead of advancing the animation again.
 *
 * <p><b>What this file pins, and what it does not.</b>
 * <ul>
 * <li><b>Pinned behaviourally</b> - {@link ImmutableFramePose}, i.e. the decision "reuse this frame's pose" and the
 *     write-back that performs it: the same frame time reuses and restores every transform, a different frame time
 *     does not, and a bone list the record was not taken from is left untouched so the caller evaluates normally.
 *     This is the half that makes the fix work: the port's renderer draws live bones, and a preview draw restores the
 *     bones it found ({@code com.fox.ysmu.client.render.ModelPoseSnapshot}), so without the write-back the second draw
 *     would render the pre-pass pose.</li>
 * <li><b>Pinned as a source-order guard</b> - that
 *     {@code AnimatedGeoModel#setLivingAnimations} consults this record before it ticks the animation and records the
 *     pose after it, and that immutability is resolved from both declaration routes with {@code false} as the
 *     animatable's default. This cannot be a runtime assertion here: that method's first statement reads
 *     {@code Minecraft.getMinecraft().isGamePaused()} ({@code AnimatedGeoModel.java:62-67}), and this test JVM has no
 *     LWJGL, so {@code net.minecraft.client.Minecraft} cannot even be linked - the same boundary
 *     {@code ParallelAnimationExistenceGuardTest:54} and {@code ModelFormExecutionFrameHookTest:8} record.</li>
 * </ul>
 */
class ImmutableFramePoseTest {

    /** 5.0 is this frame's render tick; 5.5 is the next frame's. */
    private static final double FRAME = 5.0d;
    private static final double NEXT_FRAME = 5.5d;

    private static GeoBone bone(String name, float positionX, float rotationY, float scaleX) {
        GeoBone bone = new GeoBone();
        bone.name = name;
        bone.setPositionX(positionX);
        bone.setRotationY(rotationY);
        bone.setScaleX(scaleX);
        return bone;
    }

    private static List<IBone> bones(GeoBone... bones) {
        List<IBone> list = new ArrayList<>();
        for (GeoBone bone : bones) {
            list.add(bone);
        }
        return list;
    }

    /** Overwrites every animated transform, the way the second writer of a preview's bones would. */
    private static void move(List<IBone> bones, float positionX, float rotationY, float scaleX) {
        for (IBone bone : bones) {
            bone.setPositionX(positionX);
            bone.setPositionY(positionX);
            bone.setPositionZ(positionX);
            bone.setRotationX(rotationY);
            bone.setRotationY(rotationY);
            bone.setRotationZ(rotationY);
            bone.setScaleX(scaleX);
            bone.setScaleY(scaleX);
            bone.setScaleZ(scaleX);
        }
    }

    @Test
    void aSecondDrawInTheSameFrameReusesTheFramePoseInsteadOfTheCurrentBones() {
        GeoBone preview = bone("Preview2", 1.0f, 0.5f, 1.0f);
        preview.setPositionY(2.0f);
        preview.setPositionZ(3.0f);
        preview.setRotationX(0.25f);
        preview.setRotationZ(0.75f);
        preview.setScaleY(1.5f);
        preview.setScaleZ(2.5f);
        List<IBone> modelBones = bones(preview);

        ImmutableFramePose frame = new ImmutableFramePose();
        frame.capture(FRAME, modelBones);
        // The host's ModelPoseSnapshot puts the pose the draw found back after it, and the world pass poses the same
        // shared bones - either way the second draw of this frame starts from something else (upstream's
        // "scale = 0 makes the subtree disappear" is exactly this state).
        move(modelBones, -7.0f, 3.0f, 0.0f);

        assertTrue(frame.reuse(FRAME, modelBones), "the frame that produced the pose must reuse it");
        assertEquals(1.0f, preview.getPositionX(), 0.0f, "the produced pose must be written back");
        assertEquals(2.0f, preview.getPositionY(), 0.0f);
        assertEquals(3.0f, preview.getPositionZ(), 0.0f);
        assertEquals(0.25f, preview.getRotationX(), 0.0f);
        assertEquals(0.5f, preview.getRotationY(), 0.0f);
        assertEquals(0.75f, preview.getRotationZ(), 0.0f);
        assertEquals(1.0f, preview.getScaleX(), 0.0f, "a second draw must not see the other writer's scale");
        assertEquals(1.5f, preview.getScaleY(), 0.0f);
        assertEquals(2.5f, preview.getScaleZ(), 0.0f);
    }

    @Test
    void aNewFrameIsNotReusedSoTheCallerStillAdvances() {
        GeoBone preview = bone("Preview2", 1.0f, 0.5f, 1.0f);
        List<IBone> modelBones = bones(preview);

        ImmutableFramePose frame = new ImmutableFramePose();
        frame.capture(FRAME, modelBones);
        move(modelBones, -7.0f, 3.0f, 0.25f);

        assertFalse(frame.reuse(NEXT_FRAME, modelBones), "a new frame must evaluate instead of reusing");
        assertEquals(-7.0f, preview.getPositionX(), 0.0f, "a refused reuse must not write anything");
        assertEquals(0.25f, preview.getScaleX(), 0.0f);
    }

    @Test
    void nothingIsReusedBeforeAFrameProducedAPose() {
        GeoBone preview = bone("Preview2", 1.0f, 0.5f, 1.0f);
        List<IBone> modelBones = bones(preview);

        assertFalse(new ImmutableFramePose().reuse(FRAME, modelBones), "an empty record has no pose to reuse");
        assertEquals(1.0f, preview.getPositionX(), 0.0f);
    }

    @Test
    void aBoneListTheRecordWasNotTakenFromIsLeftUntouched() {
        List<IBone> twoBones = bones(bone("Root", 1.0f, 0.0f, 1.0f), bone("Head", 2.0f, 0.0f, 1.0f));
        ImmutableFramePose frame = new ImmutableFramePose();
        frame.capture(FRAME, twoBones);

        // A model switch inside one frame: the same frame time, but these are not the bones the pose was taken from.
        GeoBone other = bone("SomethingElse", 9.0f, 9.0f, 9.0f);
        List<IBone> otherBones = bones(other);
        assertFalse(frame.reuse(FRAME, otherBones), "a different bone list must not be written");
        assertEquals(9.0f, other.getPositionX(), 0.0f);

        List<IBone> moreBones = bones(bone("A", 1.0f, 0.0f, 1.0f), bone("B", 2.0f, 0.0f, 1.0f), bone("C", 3.0f, 0.0f, 1.0f));
        assertFalse(frame.reuse(FRAME, moreBones), "a longer bone list must not be written either");
        assertEquals(3.0f, moreBones.get(2)
            .getPositionX(), 0.0f);
    }

    /**
     * A frame time that does not advance - a paused game freezes {@code timer.renderPartialTicks} while the animation
     * tick still advances ({@code Minecraft.runGameLoop:1017-1026}) - is the same frame, which is upstream's answer
     * too ({@code AnimatableEntity.java:354-357} clamps a non-advancing frame time instead of starting a new frame).
     */
    @Test
    void aFrameTimeThatDoesNotAdvanceKeepsReusingItsPose() {
        GeoBone preview = bone("Preview2", 1.0f, 0.5f, 1.0f);
        List<IBone> modelBones = bones(preview);

        ImmutableFramePose frame = new ImmutableFramePose();
        frame.capture(FRAME, modelBones);

        for (int draw = 0; draw < 3; draw++) {
            move(modelBones, draw, draw, 0.0f);
            assertTrue(frame.reuse(FRAME, modelBones), "draw " + draw + " is still the same frame");
            assertEquals(1.0f, preview.getScaleX(), 0.0f);
        }

        // ... and a frame that does advance stops reusing, even if this one is drawn again afterwards.
        frame.capture(NEXT_FRAME, modelBones);
        assertFalse(frame.reuse(FRAME, modelBones), "the record now belongs to the newer frame");
        assertTrue(frame.reuse(NEXT_FRAME, modelBones));
    }

    /**
     * Source-order guard for the wiring this record is consumed by; see the class comment for why it cannot be a
     * runtime assertion in this JVM.
     */
    @Test
    void theGateConsultsTheFramePoseBeforeTickingAndRecordsItAfter() throws Exception {
        String source = Files.readString(Path.of("src/main/java/software/bernie/geckolib3/model/AnimatedGeoModel.java"));

        int context = source.indexOf("RenderContext renderContext = predicate.getRenderContext();");
        int immutability = source.indexOf("determineImmutableContext(renderContext)", context);
        int reuse = source.indexOf("immutableFrame.reuse(seekTime,", immutability);
        int tick = source.indexOf("animationProcessor.tickAnimation(", reuse);
        int capture = source.indexOf("immutableFrame.capture(seekTime,", tick);
        assertTrue(
            context >= 0 && immutability > context && reuse > immutability && tick > reuse && capture > tick,
            "setLivingAnimations must resolve the context, then reuse the frame's pose, then tick, then record it");

        assertTrue(
            source.indexOf("renderContext.immutable()", context) > 0,
            "a caller-declared immutable context must count, not only the animatable's own answer");
        // Searched from `context`, not from `reuse`: the gate's `if` is written *before* the `reuse` call and the
        // declaration of `immutable` sits between them, so a forward search from `reuse` can never find it.
        assertTrue(
            source.indexOf("if (immutable && immutableFrame != null", context) > 0,
            "the gate must be conditioned on the resolved immutability");
    }

    @Test
    void theAnimatableDefaultIsFalseSoOnlyADeclaringAnimatableEntersThePath() throws Exception {
        String source = Files.readString(Path.of("src/main/java/software/bernie/geckolib3/core/IAnimatable.java"));
        int method = source.indexOf("default boolean determineImmutableContext(RenderContext context)");
        int fallback = source.indexOf("return false;", method);
        assertTrue(method > 0 && fallback > method, "IAnimatable's default answer must be false");
    }

    @Test
    void anEventNobodyAttachedAContextToStaysMutable() throws Exception {
        String source = Files.readString(
            Path.of("src/main/java/software/bernie/geckolib3/core/event/predicate/AnimationEvent.java"));
        assertTrue(
            source.indexOf("private RenderContext renderContext = RenderContext.NONE;") > 0,
            "an engine-built event must carry the mutable context, i.e. today's behaviour");
        assertTrue(
            source.indexOf("this.renderContext = renderContext == null ? RenderContext.NONE : renderContext;") > 0,
            "attaching no context must mean the mutable context, never null");
    }
}
