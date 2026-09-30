package com.fox.ysmu.client.animation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

/**
 * A controller can be driven more than once at the same {@code seekTime} inside one rendered frame.
 * <p>
 * YSMU renders the local player twice: the world render, and the HUD "extra player" render
 * ({@code ClientEventHandler#renderSelfGuiPlayer}, which passes {@code partialTicks = 1.0}). Both go through the same
 * shared {@code CustomPlayerEntity}, so both reach {@code AnimationController.process} with the same animation data
 * and the same {@code seekTime}, and the HUD render runs <em>after</em> the world render has already drawn.
 * <p>
 * The second pass is destructive in one place: the transition branch polls {@code animationQueue} to pick the
 * animation being transitioned to, and the first pass has already drained that one-element queue, so the second pass
 * assigns {@code null} over an animation that is mid-transition. {@code setAnimation}'s loop guard reads exactly that
 * field and answers {@code needsAnimationReload} on the next frame, which restarts the transition; the transition then
 * needs {@code transitionLengthTicks} of a clock that is reset every frame, so it never ends. The controller stays in
 * {@link AnimationState#Transitioning} forever, {@code query.anim_time} stays 0, and the model is frozen on the
 * transition's first frame - which is what "walking animates, sprinting does not" is.
 * <p>
 * This drives the controller directly and asserts only that a transition finishes; it needs no Minecraft classes.
 */
class AnimationControllerTransitionTest {

    /** A twenty-tick clip requested every frame, like a locomotion state. */
    private static final double CLIP_LENGTH_SECONDS = 1.1667;

    @Test
    void aTransitionCompletesWhenTheControllerIsProcessedOncePerFrame() {
        assertEquals(AnimationState.Running, runFrames(60, 1), "a single process per frame must leave the transition");
    }

    @Test
    void aTransitionCompletesWhenTheControllerIsProcessedTwicePerFrame() {
        assertEquals(
            AnimationState.Running,
            runFrames(60, 2),
            "the world render and the HUD render share one controller and one seekTime; the second pass must not "
                + "strand the transition, or the animation stays frozen in AnimationState.Transitioning");
    }

    /**
     * Runs {@code frames} render frames, each advancing the clock by a 60 fps frame of a 20 tick a second game, and
     * processing the controller {@code processesPerFrame} times at that same clock reading.
     */
    private static AnimationState runFrames(int frames, int processesPerFrame) {
        Animatable animatable = new Animatable();
        FakeModel model = new FakeModel();
        AnimationController.ModelFetcher<Animatable> fetcher = ignored -> model;
        AnimationController.addModelFetcher(fetcher);
        try {
            AnimationController<Animatable> controller = new AnimationController<>(
                animatable,
                "main_controller",
                2,
                event -> {
                    event.getController()
                        .setAnimation(
                            new AnimationBuilder()
                                .addAnimation("run", ILoopType.EDefaultLoopTypes.LOOP));
                    return PlayState.CONTINUE;
                });
            MolangParser parser = new MolangParser();
            List<IBone> bones = new ArrayList<>();
            HashMap<String, Pair<IBone, BoneSnapshot>> snapshots = new HashMap<>();

            double seek = 100.0D;
            for (int frame = 0; frame < frames; frame++) {
                for (int pass = 0; pass < processesPerFrame; pass++) {
                    // The HUD pass differs only in partialTicks, which is what makes it a second pass rather than a
                    // repeat the engine could skip.
                    float partialTick = pass == 0 ? 0.5F : 1.0F;
                    AnimationEvent<Animatable> event = new AnimationEvent<>(
                        animatable,
                        0.0F,
                        0.0F,
                        partialTick,
                        true,
                        new ArrayList<>());
                    event.setController(controller);
                    controller.process(seek, event, bones, snapshots, parser, false);
                }
                seek += 1.0D / 3.0D;
            }
            assertNotNull(controller.getCurrentAnimation(), "the transition must have kept an animation");
            return controller.getAnimationState();
        } finally {
            AnimationController.removeModelFetcher(fetcher);
        }
    }

    /** Minimal animatable: this test only cares about the controller the model hands back. */
    private static final class Animatable implements IAnimatable {

        private final AnimationFactory factory = new AnimationFactory(this);

        @Override
        public void registerControllers(AnimationData data) {
            // The controller under test is built by hand.
        }

        @Override
        public AnimationFactory getFactory() {
            return this.factory;
        }
    }

    /** Answers the one animation the predicate asks for, so nothing depends on a real resource pack. */
    private static final class FakeModel implements IAnimatableModel<Animatable> {

        private final Animation run = new Animation();

        private FakeModel() {
            this.run.animationName = "run";
            this.run.animationLength = CLIP_LENGTH_SECONDS;
            this.run.loop = ILoopType.EDefaultLoopTypes.LOOP;
            this.run.boneAnimations = new ArrayList<>();
        }

        @Override
        public void setLivingAnimations(Animatable entity, Integer uniqueID, AnimationEvent customPredicate) {}

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            return "run".equals(name) ? this.run : null;
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {}
    }
}
