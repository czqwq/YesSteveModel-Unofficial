package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Pins the parallel family's existence check: a model that does not declare {@code parallelN} must never be handed
 * {@code parallelN}, because that is what drives {@code AnimationController#setAnimation} into its
 * "Could not load animation: parallelN. Is it missing?" branch - which returns <em>before</em> filling
 * {@code animationQueue} and before updating {@code currentAnimationBuilder}, so the same builder is re-evaluated as
 * "changed" on the next frame and the message prints every frame while the controller stays without an animation.
 *
 * <p>
 * This is SOURCE's own ending for the predicate - {@code animationExistsInFile(animId, animationName)} then
 * {@code playLoopAnimation} else {@code PlayState.STOP}
 * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/AnimationManager.java:852-855}, judged at
 * {@code :740-746}, with the reason written at {@code :822-823}). The port kept the registration of the fixed
 * {@code pre_parallel0..7} / {@code parallel0..7} controller set but dropped that guard, so the test asserts the port's
 * equivalent: {@link AnimationManager#playParallelIfPresent} answers {@link PlayState#STOP} for a name the model's file
 * does not declare, and {@link PlayState#CONTINUE} for one it does.
 *
 * <p>
 * It also pins the domain split that goes with it: the body is asked from the main domain and the first-person arm
 * from its own ({@link AnimationDomain}), so a {@code parallel2} only the {@code fp_arm} set declares is playable for
 * the arm and not for the body, and the body's {@code parallel0} is not playable for the arm.
 *
 * <p>
 * The predicate itself cannot be called here - its first statement is {@code Minecraft.getMinecraft().isGamePaused()},
 * which a plain JUnit JVM cannot serve - so {@code predicateParallel} delegates this decision to the package-private
 * helper this test drives, the same way {@code AnimationManager.swingMustYieldToBlocking} exposes the swing/blocking
 * decision.
 *
 * <p>
 * Each test installs under its own model id, because {@link ClientModelManager#registerAll} skips a payload whose
 * content signature is already registered for that id, and identical content is exactly what three tests with the same
 * fixture would have (the same reason {@code ClientModelManagerAnimationDomainTest} gives each test its own id).
 */
class ParallelAnimationExistenceGuardTest {

    /** The body's payload: {@code parallel0} in the main domain. */
    private static final ResourceLocation BODY_MODEL = new ResourceLocation(ysmu.MODID, "parallel_guard_body");
    /** The arm's payload: {@code parallel2} in the {@code fp_arm} domain. */
    private static final ResourceLocation ARM_MODEL = new ResourceLocation(ysmu.MODID, "parallel_guard_arm");
    /** A payload neither domain declares {@code parallelN} for. */
    private static final ResourceLocation EMPTY_MODEL = new ResourceLocation(ysmu.MODID, "parallel_guard_empty");

    private static final ResourceLocation[] MODEL_IDS = { BODY_MODEL, ARM_MODEL, EMPTY_MODEL };

    /** Declared by the body's own file only. */
    private static final String BODY_PARALLEL = "parallel0";
    /** Declared by the {@code fp_arm} set only. */
    private static final String ARM_PARALLEL = "parallel2";

    private AnimationFile savedMainDefault;
    private AnimationFile savedFirstPersonDefault;

    @BeforeEach
    void isolateDefaultFallbacks() {
        // ClientModelManager#registerAnimations completes every model's own clips from the built-in default's same
        // domain. On a client that file is filled by loadDefaultModel(); a unit JVM never runs
        // that, but another test could, so both are emptied here and restored afterwards to make the existence answers
        // depend on this test's payload alone.
        savedMainDefault = ClientModelManager.DEFAULT_ANIMATION_FILE;
        savedFirstPersonDefault = ClientModelManager.DEFAULT_FIRST_PERSON_ANIMATION_FILE;
        ClientModelManager.DEFAULT_ANIMATION_FILE = new AnimationFile();
        ClientModelManager.DEFAULT_FIRST_PERSON_ANIMATION_FILE = new AnimationFile();
    }

    @AfterEach
    void release() {
        ClientModelManager.DEFAULT_ANIMATION_FILE = savedMainDefault;
        ClientModelManager.DEFAULT_FIRST_PERSON_ANIMATION_FILE = savedFirstPersonDefault;
        for (ResourceLocation modelId : MODEL_IDS) {
            for (String domain : AnimationDomain.ALL) {
                GeckoLibCache.getInstance()
                    .getAnimations()
                    .remove(AnimationDomain.animationFileId(modelId, domain));
            }
            ClientModelManager.MODELS.remove(modelId);
        }
        ConditionManager.clear();
    }

    @Test
    void aModelWithoutParallel7IsNotAskedForIt() {
        install(BODY_MODEL);

        assertEquals(
            PlayState.STOP,
            AnimationManager.playParallelIfPresent(bodyEvent(BODY_MODEL), "parallel7"),
            "a clip the model does not declare must not reach AnimationController#setAnimation");
        assertEquals(
            PlayState.CONTINUE,
            AnimationManager.playParallelIfPresent(bodyEvent(BODY_MODEL), BODY_PARALLEL),
            "a clip the model does declare is played");
    }

    @Test
    void theArmIsAskedOnlyForItsOwnDomainsParallelClips() {
        install(ARM_MODEL);

        assertEquals(
            PlayState.CONTINUE,
            AnimationManager.playParallelIfPresent(armEvent(ARM_MODEL), ARM_PARALLEL),
            "the fp_arm set's parallel2 is what the arm's parallel_2 controller may play");
        assertEquals(
            PlayState.STOP,
            AnimationManager.playParallelIfPresent(armEvent(ARM_MODEL), BODY_PARALLEL),
            "the body's parallel0 is not in the arm's domain and must not be asked for");
        assertEquals(
            PlayState.STOP,
            AnimationManager.playParallelIfPresent(armEvent(ARM_MODEL), "parallel7"),
            "a name neither domain declares must not be asked for");
    }

    @Test
    void anEmptyOrMissingNameStopsInsteadOfAsking() {
        install(EMPTY_MODEL);

        assertEquals(PlayState.STOP, AnimationManager.playParallelIfPresent(bodyEvent(EMPTY_MODEL), null));
        assertEquals(PlayState.STOP, AnimationManager.playParallelIfPresent(bodyEvent(EMPTY_MODEL), ""));
        assertEquals(
            PlayState.STOP,
            AnimationManager.playParallelIfPresent(bodyEvent(EMPTY_MODEL), "parallel7"),
            "an empty domain must answer STOP rather than hand the name to the engine");
    }

    /** Registers the payload and installs both domains; the install is lazy, so asking is what installs it. */
    private static void install(ResourceLocation modelId) {
        ClientModelManager.registerAll(payload(modelId), false);
        assertNotNull(
            ClientModelManager.animationFileFor(ModelIdUtil.getMainId(modelId)),
            "the main domain must be installed for this test's lookups");
    }

    private static AnimationEvent<CustomPlayerEntity> bodyEvent(ResourceLocation modelId) {
        CustomPlayerEntity body = new CustomPlayerEntity();
        body.setMainModel(ModelIdUtil.getMainId(modelId));
        return eventOf(body);
    }

    private static AnimationEvent<CustomPlayerEntity> armEvent(ResourceLocation modelId) {
        CustomFirstPersonArmEntity arm = new CustomFirstPersonArmEntity();
        arm.setMainModel(ModelIdUtil.getMainId(modelId));
        return eventOf(arm);
    }

    /**
     * The predicate hands the name to {@code event.getController()}, so the event needs one - the engine builds it
     * from the controller set it is evaluating, which a test has to stand in for. The controller's own predicate is
     * never run here: this test calls the predicate helper directly.
     */
    private static AnimationEvent<CustomPlayerEntity> eventOf(CustomPlayerEntity animatable) {
        AnimationEvent<CustomPlayerEntity> event = new AnimationEvent<>(
            animatable,
            0.0F,
            0.0F,
            0.0F,
            false,
            new ArrayList<>());
        event.setController(
            new AnimationController<>(animatable, "parallel_guard_test_controller", 0.0F, e -> PlayState.CONTINUE));
        return event;
    }

    private static ModelData payload(ResourceLocation modelId) {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        if (modelId.equals(BODY_MODEL)) {
            animations.put("main.animation.json", document(BODY_PARALLEL));
        } else if (modelId.equals(ARM_MODEL)) {
            animations.put("fp_arm", document(ARM_PARALLEL));
        } else {
            // A clip in each domain, but never one of the parallel family.
            animations.put("main.animation.json", document("idle"));
            animations.put("fp_arm", document("idle"));
        }
        return new ModelData(
            modelId.getResourcePath(),
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            animations);
    }

    /** A minimal animation document whose clips are the names given. */
    private static byte[] document(String... clipNames) {
        StringBuilder json = new StringBuilder("{\"format_version\":\"1.8.0\",\"animations\":{");
        for (int i = 0; i < clipNames.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"')
                .append(clipNames[i])
                .append("\":{\"loop\":true,\"animation_length\":0.2,\"bones\":{}}");
        }
        return json.append("}}")
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }
}
