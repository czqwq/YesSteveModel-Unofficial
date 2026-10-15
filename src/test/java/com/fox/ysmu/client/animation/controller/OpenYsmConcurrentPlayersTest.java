package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib3.core.*;
import software.bernie.geckolib3.core.builder.*;
import software.bernie.geckolib3.core.controller.*;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.*;
import software.bernie.geckolib3.core.manager.*;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.*;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.geo.render.built.GeoBone;

class OpenYsmConcurrentPlayersTest {
    final OpenYsmAnimationFinishedTest.Model model = new OpenYsmAnimationFinishedTest.Model();
    final OpenYsmAnimationFinishedTest.Entity entity = new OpenYsmAnimationFinishedTest.Entity();
    final AnimationController.ModelFetcher<OpenYsmAnimationFinishedTest.Entity> fetcher = a -> model;
    final Map<String, IBone> bones = new HashMap<>();
    final HashMap<String, Pair<IBone, BoneSnapshot>> snapshots = new HashMap<>();
    final List<String> events = new ArrayList<>();
    final Set<Object> tokens = Collections.newSetFromMap(new IdentityHashMap<>());
    List<AnimationController.ConcurrentAnimation> entries;
    Object state = new Object();
    BlendTransition blend = BlendTransition.linear(0);

    @AfterEach void cleanup() { AnimationController.removeModelFetcher(fetcher); }
    List<KeyFrame<IValue>> frames(double value) {
        return new ArrayList<>(List.of(new KeyFrame<>(20d, new ConstantValue(value), new ConstantValue(value))));
    }
    Animation animation(String name, double position, double scale, String weight, double length) {
        Animation a = new Animation();
        a.animationName = name;
        a.animationLength = length;
        a.boneAnimations = new ArrayList<>();
        a.blendWeight = weight;
        BoneAnimation b = new BoneAnimation();
        b.boneName = "root";
        b.rotationKeyFrames = new VectorKeyFrameList<>();
        b.positionKeyFrames = new VectorKeyFrameList<>(frames(position), frames(0), frames(0));
        b.scaleKeyFrames = new VectorKeyFrameList<>(frames(scale), frames(1), frames(1));
        a.boneAnimations.add(b);
        a.customInstructionKeyframes.add(new EventKeyFrame<>(1d, name));
        model.animations.put(name, a);
        return a;
    }
    AnimationController<OpenYsmAnimationFinishedTest.Entity> controller() {
        GeoBone bone = new GeoBone();
        bone.name = "root";
        bone.saveInitialSnapshot();
        bones.put("root", bone);
        snapshots.put("root", Pair.of(bone, new BoneSnapshot(bone.getInitialSnapshot())));
        AnimationController.addModelFetcher(fetcher);
        var c = new AnimationController<>(entity, "outer", 1, event -> {
            event.getController().setConcurrentAnimations(state, entries, blend, event.getAnimationTick());
            return PlayState.CONTINUE;
        });
        c.registerCustomInstructionListener(event -> {
            events.add(event.instructions);
            tokens.add(event.getController().getPlaybackOwnerToken());
        });
        return c;
    }
    void process(AnimationController<OpenYsmAnimationFinishedTest.Entity> c, double tick) {
        AnimationEvent<OpenYsmAnimationFinishedTest.Entity> e = new AnimationEvent<>(entity, 0, 0, 0, false, new ArrayList<>());
        e.animationTick = tick;
        e.setController(c);
        c.process(tick, e, bones, snapshots, new MolangParser(), false);
    }
    @Test void everyPlayerRunsAndPositionSumsWhileScaleMultipliesWeightedContributions() {
        animation("a", 2, 2, "0.5", 2);
        animation("b", 4, 3, "0.5", 5);
        entries = List.of(new AnimationController.ConcurrentAnimation("a", true), new AnimationController.ConcurrentAnimation("b", true));
        var c = controller();
        process(c, 0);
        var q = c.getActiveBoneAnimationQueues().get(0);
        assertEquals(3, q.positionXQueue.poll().animationEndValue, 1e-6);
        assertEquals(3, q.scaleXQueue.poll().animationEndValue, 1e-6);
        process(c, 1);
        assertEquals(List.of("a", "b"), events);
        assertEquals(2, tokens.size(), "same outer controller name must not share local sound scope");
        process(c, 1);
        assertEquals(2, events.size(), "second world/HUD draw must not replay timeline");
        process(c, 3);
        assertTrue(c.concurrentAnimationFinished(0));
        assertFalse(c.concurrentAnimationFinished(1));
    }
    @Test void disabledPlayerAdvancesAndDoesNotReplaySuppressedEventsWhenEnabled() {
        animation("a", 2, 2, "1", 2);
        entries = List.of(new AnimationController.ConcurrentAnimation("a", false));
        var c = controller();
        process(c, 0);
        process(c, 1);
        assertTrue(events.isEmpty());
        entries = List.of(new AnimationController.ConcurrentAnimation("a", true));
        process(c, 1.5);
        assertTrue(events.isEmpty());
        process(c, 3);
        assertTrue(c.concurrentAnimationFinished(0));
    }
    @Test void hierarchyExitRunsDeepestFirstAndReleasesRealChildPlayersBeforeReentry() {
        animation("a", 2, 2, "1", 20);
        entries = List.of(new AnimationController.ConcurrentAnimation("a", true));
        var c = controller();
        List<Object> stopped = new ArrayList<>();
        c.registerPlaybackStoppedListener(stopped::add);
        process(c, 0);
        process(c, 1);
        Object oldChildToken = tokens.iterator().next();
        var child = new OpenYsmPlayerControllerRuntime.RuntimeState();
        child.activeState = new OpenYsmControllerDefinitions.State();
        child.activeState.name = "child";
        child.currentState = "child";
        child.engineController = c;
        var grandchild = new OpenYsmPlayerControllerRuntime.RuntimeState();
        grandchild.activeState = new OpenYsmControllerDefinitions.State();
        grandchild.activeState.name = "grandchild";
        grandchild.currentState = "grandchild";
        grandchild.engineController = c;
        child.subState = grandchild;
        List<String> exits = new ArrayList<>();
        OpenYsmPlayerControllerRuntime.exitHierarchy(child, exited -> exits.add(exited.currentState));
        assertEquals(List.of("grandchild", "child"), exits);
        assertNull(child.subState);
        assertNull(child.activeState);
        assertEquals("", grandchild.currentState);
        assertTrue(stopped.contains(oldChildToken));
        tokens.clear();
        process(c, 2);
        process(c, 3);
        assertFalse(tokens.contains(oldChildToken), "reentry creates new child playback context");
        assertEquals(1, tokens.size());
    }
    @Test void missingClipRetainsIdleCompletionWithoutBlockingOtherPlayers() {
        animation("present", 2, 2, "1", 20);
        entries = List.of(new AnimationController.ConcurrentAnimation("missing", true),
            new AnimationController.ConcurrentAnimation("present", true));
        var c = controller();
        process(c, 0);
        assertTrue(c.concurrentAnimationFinished(0));
        assertFalse(c.concurrentAnimationFinished(1));
        assertEquals(1, c.getActiveBoneAnimationQueues().size());
    }
    @Test void authoredCodedCurveChangesActualTransitionPointsOnlyForOptedInPlayer() {
        animation("coded", 8, 1, "1", 20);
        entries = List.of();
        var registration = controller();
        registration.releaseAnimationResources();
        BlendTransition curve = new BlendTransition(new double[] {0, 5, 10}, new double[] {0, 0.8, 1});
        var coded = new AnimationController<>(entity, "coded", 10, event -> {
            event.getController().setAuthoredCodedTransition(curve);
            return PlayState.CONTINUE;
        });
        coded.setAnimation(new AnimationBuilder().addAnimation("coded"));
        process(coded, 0);
        process(coded, 5);
        var point = coded.getActiveBoneAnimationQueues().get(0).positionXQueue.poll();
        assertEquals(8, point.currentTick, 1e-6, "curve at half duration is 80%, not linear 50%");
        assertEquals(6.4, software.bernie.geckolib3.core.util.MathUtil.lerpValues(point,
            coded.easingType, coded.customEasingMethod), 1e-6);
    }
    @Test void perClipSpeedsDoNotLeakFromFirstToSecondPlayer() {
        animation("a", 2, 2, "1", 20).animSpeed = "2";
        animation("b", 2, 2, "1", 20).animSpeed = "0.5";
        entries = List.of(new AnimationController.ConcurrentAnimation("a", true), new AnimationController.ConcurrentAnimation("b", true));
        var c = controller();
        process(c, 0);
        process(c, 2);
        assertEquals(4, c.concurrentAnimationTick(0), 1e-6);
        assertEquals(1, c.concurrentAnimationTick(1), 1e-6);
    }
    @Test void segmentedCurveKeepsValuesIncludingReverseProgress() {
        BlendTransition curve = new BlendTransition(new double[] {0, 10, 20}, new double[] {0, 1, 0.25});
        assertEquals(0.5, curve.progress(5), 1e-6);
        assertEquals(0.625, curve.progress(15), 1e-6);
        assertEquals(20, curve.length());
    }
    @Test void onceSignalsCompletionAtEndingBoundaryWithoutChangingLegacyQueueRules() {
        animation("a", 2, 2, "1", 2).loop = ILoopType.EDefaultLoopTypes.PLAY_ONCE;
        entries = List.of(new AnimationController.ConcurrentAnimation("a", true));
        var c = controller();
        process(c, 0);
        process(c, 1);
        process(c, 2);
        assertTrue(c.concurrentAnimationFinished(0));
        state = new Object();
        process(c, 3);
        assertFalse(c.concurrentAnimationFinished(0), "same clip in new state owns fresh completion");
    }
}
