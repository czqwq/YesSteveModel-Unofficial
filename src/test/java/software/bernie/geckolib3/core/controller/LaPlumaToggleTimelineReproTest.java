package software.bernie.geckolib3.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import rip.ysm.security.YsmCrypt;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

/**
 * REPRODUCTION (temporary): LaPluma2.5.3's clothing flicker, reproduced on the real engine with the real decoded
 * clips and no client.
 *
 * <p>The pack has exactly two writers of its costume toggles, both as a tick-0 timeline:
 * {@code pre_parallel0} sets {@code v.is_feet/is_chest/is_legs = 1} ("everything equipped") and {@code parallel4}
 * sets them from {@code v.roaming.*} (the user's setting). {@code pre_parallel0} declares no
 * {@code animation_length}, and its 390 bone channels all sit at tick 0, so upstream's rule
 * ({@code format/parser/pojo/animation/Animation.java:199-207}) derives {@code Float.MAX_VALUE}: the clip never
 * ends and its tick-0 timeline runs <b>once</b>. {@code parallel4} declares {@code 0.5}s and loops, so its timeline
 * re-runs every 10 ticks and re-seeds the toggles from {@code v.roaming.*} - the pack's designed mechanism for
 * keeping the switches in sync.
 *
 * <p>Both clips are played by {@code AnimationManager.playParallelIfPresent -> playLoopAnimation}, which calls
 * {@code setAnimation} with a fresh equivalent builder on <b>every frame</b>. This test drives the real
 * {@link AnimationController} exactly that way, in the runtime's registration order ({@code pre_parallel_0} before
 * {@code parallel_4}), and requires the observable outcome the pack is authored for: {@code pre_parallel0}'s
 * one-shot assignment must not keep overwriting {@code parallel4}'s re-seed, i.e. after the first frames
 * {@code v.is_chest} must equal the user's roaming value (0 = clothing shown).
 *
 * <p>Skipped when the pack is absent (it is test data, not a dependency).
 */
class LaPlumaToggleTimelineReproTest {

    private static final String PRE_PARALLEL_0 = "pre_parallel0";
    private static final String PARALLEL_4 = "parallel4";

    private final StubModel model = new StubModel();
    private final List<AnimationController.ModelFetcher<StubAnimatable>> fetchers = new ArrayList<>();

    private static final class StubAnimatable implements IAnimatable {

        @Override
        public void registerControllers(AnimationData data) {}

        @Override
        public AnimationFactory getFactory() {
            return null;
        }
    }

    private static final class StubModel implements IAnimatableModel<StubAnimatable> {

        final Map<String, Animation> animations = new HashMap<>();

        @Override
        public void setLivingAnimations(StubAnimatable entity, Integer uniqueID, AnimationEvent customPredicate) {}

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            return animations.get(name);
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {}
    }

    @AfterEach
    void removeFetchers() {
        for (AnimationController.ModelFetcher<StubAnimatable> fetcher : fetchers) {
            while (AnimationController.modelFetchers.remove(fetcher)) {
                // remove every registration this test added
            }
        }
        fetchers.clear();
    }

    /** Decodes the reported pack exactly as {@code OpenYsmFormat#cacheBinaryModel} does, and returns its main JSON. */
    private static JsonObject mainAnimationJson() throws Exception {
        File file = new File("run/client/config/ysmu/custom/LaPluma2.5.3.ysm");
        assumeTrue(file.isFile(), "the reported pack is not present, so there is nothing to reproduce");
        byte[] raw = YsmCrypt.decryptYsmFile(Files.readAllBytes(file.toPath()));
        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(raw)) {
            decoded = deserializer.deserializeKeepOpen();
        }
        ModelData data = RawYsmModelAdapter.toLegacyModelData(decoded, "ysm_repro");
        byte[] main = data.getAnimation()
            .get("main");
        assumeTrue(main != null, "the pack has no main animation file");
        return new JsonParser().parse(new String(main, StandardCharsets.UTF_8))
            .getAsJsonObject();
    }

    /** One runtime-shaped controller: its predicate resubmits the same builder every frame, like playLoopAnimation. */
    private final class Writer {

        final String clipName;
        final MolangParser parser;
        final AnimationController<StubAnimatable> controller;
        int executions;
        final List<Double> executedAtTicks = new ArrayList<>();

        Writer(JsonObject root, MolangParser parser, String clipName, String controllerName) throws Exception {
            this.clipName = clipName;
            this.parser = parser;
            Animation animation = JsonAnimationUtils
                .deserializeJsonToAnimation(JsonAnimationUtils.getAnimation(root, clipName), parser);
            model.animations.put(clipName, animation);
            AnimationController.ModelFetcher<StubAnimatable> fetcher = animatable -> model;
            fetchers.add(fetcher);
            AnimationController.addModelFetcher(fetcher);
            this.controller = new AnimationController<>(new StubAnimatable(), controllerName, 0.0f, event -> {
                // Verbatim AnimationManager#playLoopAnimation.
                event.getController()
                    .setAnimation(new AnimationBuilder().addAnimation(clipName, ILoopType.EDefaultLoopTypes.LOOP));
                return PlayState.CONTINUE;
            });
            this.controller.registerCustomInstructionListener(event -> {
                executions++;
                executedAtTicks.add(event.getController()
                    .getSyncTick());
                // Apply the timeline's own statements to the shared parser, so the variable effect is observable.
                try {
                    for (String statement : MolangParser.splitStatements(event.instructions)) {
                        try {
                            parser.parseExpression(statement)
                                .get();
                        } catch (Exception ignored) {
                            // A statement this legacy parser cannot tokenize (the pack has "v.Tswing??0;").
                        }
                    }
                } catch (Exception ignored) {
                    // Same.
                }
            });
        }

        void tick(double seekTime) {
            AnimationEvent<StubAnimatable> event = new AnimationEvent<>(new StubAnimatable(), 0.0f, 0.0f, 0.0f, false,
                new ArrayList<>());
            event.setController(controller);
            controller.process(seekTime, event, new HashMap<>(), new HashMap<>(), parser, false);
        }
    }

    @Test
    void preParallel0TimelineIsOneShotWhileItsBuilderIsResubmittedEveryTick() throws Exception {
        JsonObject root = mainAnimationJson();
        MolangParser parser = new MolangParser();
        Writer pre0 = new Writer(root, parser, PRE_PARALLEL_0, "pre_parallel_0_controller");
        for (int tick = 1; tick <= 60; tick++) {
            pre0.tick(tick);
        }
        assertEquals(
            1,
            pre0.executions,
            "a clip whose derived length is MAX_VALUE must run its tick-0 timeline exactly once, "
                + "but it ran " + pre0.executions + " times over 60 ticks");
    }

    @Test
    void thePackOutputsTheUsersToggleSettingOnceTheOneShotHasPassed() throws Exception {
        JsonObject root = mainAnimationJson();
        MolangParser parser = new MolangParser();
        Writer pre0 = new Writer(root, parser, PRE_PARALLEL_0, "pre_parallel_0_controller");
        Writer par4 = new Writer(root, parser, PARALLEL_4, "parallel_4_controller");
        for (int tick = 1; tick <= 60; tick++) {
            double isChestBefore = value(parser, "v.is_chest");
            pre0.tick(tick);
            double afterPre0 = value(parser, "v.is_chest");
            par4.tick(tick);
            double afterPar4 = value(parser, "v.is_chest");

        }
        // v.roaming.is_chest is unset in this bare parser, so the pack's intended steady state is 0 = clothing shown.
        assertEquals(
            0.0d,
            value(parser, "v.is_chest"),
            1.0e-9d,
            "the user's setting (v.roaming.is_chest = 0 -> clothing shown) must be what the pack outputs; "
                + "a 1 here is the flicker: pre_parallel0's one-shot assignment is being replayed");
    }

    private static double value(MolangParser parser, String expression) {
        try {
            return parser.parseExpression(expression)
                .get();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

}
