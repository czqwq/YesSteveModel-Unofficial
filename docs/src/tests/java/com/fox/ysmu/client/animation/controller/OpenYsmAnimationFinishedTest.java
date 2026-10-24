package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.bernie.geckolib3.core.*;
import software.bernie.geckolib3.core.builder.*;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.*;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;

class OpenYsmAnimationFinishedTest {
    static final class Entity implements IAnimatable {
        public void registerControllers(AnimationData data) {}
        public AnimationFactory getFactory() { return null; }
    }
    static final class Model implements IAnimatableModel<Entity> {
        final HashMap<String, Animation> animations = new HashMap<>();
        public void setLivingAnimations(Entity entity, Integer id, AnimationEvent event) {}
        public AnimationProcessor getAnimationProcessor() { return null; }
        public Animation getAnimation(String name, IAnimatable entity) { return animations.get(name); }
        public void setMolangQueries(IAnimatable entity, double tick) {}
    }
    final Model model = new Model();
    final AnimationController.ModelFetcher<Entity> fetcher = entity -> model;
    boolean bedrock = true;
    PlayState playState = PlayState.CONTINUE;
    final Entity entity = new Entity();

    @AfterEach
    void cleanup() { AnimationController.removeModelFetcher(fetcher); }

    AnimationController<Entity> controller(ILoopType loop) {
        Animation a = new Animation();
        a.animationName = "clip";
        a.animationLength = 4d;
        a.loop = loop;
        a.boneAnimations = new ArrayList<>();
        a.soundKeyFrames = new ArrayList<>();
        a.particleKeyFrames = new ArrayList<>();
        a.customInstructionKeyframes = new ArrayList<>();
        model.animations.put("clip", a);
        AnimationController.addModelFetcher(fetcher);
        AnimationController<Entity> c = new AnimationController<>(entity, "test", 0, event -> {
            event.getController().useBedrockCompletionBoundary(bedrock);
            return playState;
        });
        c.setAnimation(new AnimationBuilder().addAnimation("clip", loop));
        return c;
    }
    void process(AnimationController<Entity> c, double tick) {
        AnimationEvent<Entity> event = new AnimationEvent<>(entity, 0, 0, 0, false, new ArrayList<>());
        event.setController(c);
        c.process(tick, event, new HashMap<>(), new HashMap<>(), new MolangParser(), false);
    }

    @Test
    void strictBoundaryAndCompletionPersistForEachLoopMode() {
        for (ILoopType loop : new ILoopType[] { ILoopType.EDefaultLoopTypes.LOOP,
            ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, ILoopType.EDefaultLoopTypes.PLAY_ONCE }) {
            AnimationController<Entity> c = controller(loop);
            process(c, 0);
            process(c, 4);
            assertFalse(c.hasCurrentAnimationFinished(), "exact end is not upstream completion");
            assertEquals(AnimationState.Running, c.getAnimationState());
            process(c, 4.1);
            assertTrue(c.hasCurrentAnimationFinished());
            if (loop != ILoopType.EDefaultLoopTypes.PLAY_ONCE) {
                process(c, 4.2);
                assertTrue(c.hasCurrentAnimationFinished(), "loop wrap/HOLD must preserve first completion");
            }
            c.releaseAnimationResources();
            assertFalse(c.hasCurrentAnimationFinished());
            AnimationController.removeModelFetcher(fetcher);
        }
    }

    @Test
    void legacyOnceStillStopsAtExactEnd() {
        bedrock = false;
        var c = controller(ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        process(c, 0);
        process(c, 4);
        assertEquals(AnimationState.Stopped, c.getAnimationState());
    }

    @Test
    void speedAndAnimationTimeUpdateDriveCompletionNotWallClock() {
        var c = controller(ILoopType.EDefaultLoopTypes.LOOP);
        c.setAnimationSpeed(0);
        process(c, 0);
        process(c, 100);
        assertFalse(c.hasCurrentAnimationFinished());
        c.setAnimationSpeed(2);
        process(c, 3);
        assertTrue(c.hasCurrentAnimationFinished());
        c.releaseAnimationResources();
        model.animations.get("clip").animTimeUpdate = "0";
        c.setAnimation(new AnimationBuilder().addAnimation("clip", ILoopType.EDefaultLoopTypes.LOOP));
        process(c, 200);
        process(c, 300);
        assertFalse(c.hasCurrentAnimationFinished());
    }

    @Test
    void pauseDoesNotCompleteAndClipReloadClearsLatch() {
        var c = controller(ILoopType.EDefaultLoopTypes.LOOP);
        process(c, 0);
        process(c, 2);
        playState = PlayState.PAUSE;
        process(c, 3);
        process(c, 100);
        assertFalse(c.hasCurrentAnimationFinished());
        playState = PlayState.CONTINUE;
        process(c, 101);
        process(c, 105);
        assertTrue(c.hasCurrentAnimationFinished());
        c.markNeedsReload();
        c.setAnimation(new AnimationBuilder().addAnimation("clip", ILoopType.EDefaultLoopTypes.LOOP));
        assertFalse(c.hasCurrentAnimationFinished());
    }
}
