package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.fox.ysmu.client.animation.molang.CtrlScriptBinding;
import com.fox.ysmu.client.animation.molang.PackFunctionScript;
import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.gui.ModelPreviewRegistry;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Pins the two remaining unguarded animation-name hand-overs of {@code tmp/unguarded-animation-names.md}: its C-1
 * ({@code CtrlScriptBinding.java:161}, a pack script's {@code ctrl.set_animation('<literal>')}) and its C-2
 * ({@code AnimationManager.java:203} handing over at {@code :246}, the GUI preview channel's {@code preview_animation}).
 *
 * <p>
 * Both drive {@code AnimationController#setAnimation} into its "Could not load animation: &lt;name&gt;. Is it missing?"
 * branch ({@code software/bernie/geckolib3/core/controller/AnimationController.java:345-358}), which prints to stdout
 * <em>and</em> returns before filling {@code animationQueue} ({@code :360}) and before updating
 * {@code currentAnimationBuilder} ({@code :362}) - so the same builder is re-evaluated as "changed" every frame, the
 * message repeats once per frame and the controller is left with no fallback.
 *
 * <p>
 * C-1 is tested through the real route - {@link CtrlScriptBinding#evaluate} with a parsed pack script and a real
 * {@link AnimationController} - because the route is cheap and the assertion is then the one that matters: the name
 * never reached {@code setAnimation} (nothing was queued, and the controller's builder is untouched). The engine only
 * looks at a model in the <em>accepted</em> case, where it asks the animatable's model for the clip, so a stub model is
 * registered for the controller here (the same pattern {@code AnimationControllerTimelineClockTest} uses); the guard
 * itself reads the model's real, installed animation file, which is what the test's payload builds. C-2 goes through
 * {@code AnimationManager.predicateCap}, a plain predicate: a preview entity has no player, so that method's first
 * Minecraft-touching statements are never reached.
 *
 * <p>
 * Both fixture ids are shared by every test, so each install carries a marker clip ({@link #installMarker()}) that
 * makes its content signature unique: {@link ClientModelManager#registerAll} skips a payload whose content signature
 * is already registered for that id, and a skipped registration leaves the animations unparked, which the install
 * assertion would then report as a fixture failure rather than as the behaviour under test. Every test installs the
 * fixtures in {@code @BeforeEach}, so the marker is what keeps the second and later tests honest - the per-test-id
 * alternative {@code ClientModelManagerAnimationDomainTest} uses is not available here, because the ids are named by
 * the assertions. The two built-in default animation files are emptied for the duration so the existence answers come
 * from this test's payload alone ({@code ClientModelManager#registerAnimations} completes every model from the
 * default's same domain, and the domain split is what the cross-domain cases below turn on).
 */
class GuardedAnimationHandoverTest {

    /** Body payload: {@code body_body_clip} in the main domain only. */
    private static final ResourceLocation BODY_MODEL = new ResourceLocation(ysmu.MODID, "guarded_handover_body");
    /** Arm payload: {@code arm_arm_clip} in the {@code fp_arm} domain only. */
    private static final ResourceLocation ARM_MODEL = new ResourceLocation(ysmu.MODID, "guarded_handover_arm");

    private static final ResourceLocation[] MODEL_IDS = { BODY_MODEL, ARM_MODEL };

    /** Makes every install's payload content unique; see {@link #installMarker()}. */
    private static final java.util.concurrent.atomic.AtomicInteger INSTALLS =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Declared by the body's main set only. */
    private static final String BODY_CLIP = "body_body_clip";
    /** Declared by the arm's {@code fp_arm} set only. */
    private static final String ARM_CLIP = "arm_arm_clip";
    /** Declared by neither. */
    private static final String MISSING_CLIP = "not_declared_anywhere";

    private AnimationFile savedMainDefault;
    private AnimationFile savedFirstPersonDefault;

    /** The engine's animation lookup for the accepted case; the guard's own lookup is the real one. */
    private final AnimationController.ModelFetcher<CustomPlayerEntity> fetcher =
        new AnimationController.ModelFetcher<CustomPlayerEntity>() {

            @Override
            @SuppressWarnings("unchecked")
            public IAnimatableModel<CustomPlayerEntity> apply(IAnimatable animatable) {
                return STUB_MODEL;
            }
        };

    /** Answers every name with a clip, because the guard has already decided the name exists. */
    private static final IAnimatableModel<CustomPlayerEntity> STUB_MODEL = new IAnimatableModel<CustomPlayerEntity>() {

        @Override
        public void setLivingAnimations(CustomPlayerEntity entity, Integer uniqueID, AnimationEvent customPredicate) {
            // no-op
        }

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            Animation animation = new Animation();
            animation.animationName = name;
            animation.animationLength = 0.2d;
            return animation;
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {
            // no-op
        }
    };

    @BeforeEach
    void isolateDefaultFallbacks() {
        savedMainDefault = ClientModelManager.DEFAULT_ANIMATION_FILE;
        savedFirstPersonDefault = ClientModelManager.DEFAULT_FIRST_PERSON_ANIMATION_FILE;
        ClientModelManager.DEFAULT_ANIMATION_FILE = new AnimationFile();
        ClientModelManager.DEFAULT_FIRST_PERSON_ANIMATION_FILE = new AnimationFile();
        install(BODY_MODEL);
        install(ARM_MODEL);
        AnimationController.addModelFetcher(fetcher);
    }

    @AfterEach
    void release() {
        AnimationController.removeModelFetcher(fetcher);
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

    // --- C-1: a pack script's ctrl.set_animation('<literal>') -------------------------------------------------------

    @Test
    void aScriptCannotAskABodyControllerForAClipTheModelDoesNotDeclare() {
        AnimationController<CustomPlayerEntity> controller = controllerFor(body());

        assertNull(
            runScript(controller, body().getAnimation(), "return ctrl.set_animation('" + MISSING_CLIP + "');"),
            "the script has no opinion about the controller state, so the built-in predicate still decides the frame");
        assertEquals(
            0,
            controller.animationQueue.size(),
            "a name the model does not declare must not reach AnimationController#setAnimation");
        assertTrue(
            controller.currentAnimationBuilder.getRawAnimationList()
                .isEmpty(),
            "the controller's builder must be untouched, or the same builder would be re-evaluated as changed every "
                + "frame");
    }

    @Test
    void aScriptCannotAskTheArmForAClipThatOnlyExistsInTheBodyDomain() {
        CustomFirstPersonArmEntity arm = arm();
        AnimationController<CustomPlayerEntity> controller = controllerFor(arm);

        assertNull(runScript(controller, arm.getAnimation(), "return ctrl.set_animation('" + BODY_CLIP + "');"));
        assertEquals(
            0,
            controller.animationQueue.size(),
            "the body's clip is not in the arm's own animation file and must not be asked for");

        // And the sibling direction: the body's controller reads the main file, which does not declare the arm's clip.
        AnimationController<CustomPlayerEntity> bodyController = controllerFor(body());
        assertNull(runScript(bodyController, body().getAnimation(), "return ctrl.set_animation('" + ARM_CLIP + "');"));
        assertEquals(
            0,
            bodyController.animationQueue.size(),
            "the fp_arm set's clip is not in the main file and must not be asked for by a body controller");
    }

    @Test
    void aScriptMayStillAskForAClipItsOwnDomainDeclares() {
        AnimationController<CustomPlayerEntity> controller = controllerFor(body());

        assertNull(runScript(controller, body().getAnimation(), "return ctrl.set_animation('" + BODY_CLIP + "');"));
        assertEquals(
            1,
            controller.animationQueue.size(),
            "a clip the model declares is handed over exactly as before");
        assertNotNull(controller.animationQueue.peek(), "the queued entry is the clip the script asked for");
        assertEquals(
            BODY_CLIP,
            controller.currentAnimationBuilder.getRawAnimationList()
                .get(0).animationName,
            "the declared name is what the controller was asked to play");
    }

    @Test
    void aScriptMayAskTheArmForItsOwnDomainsClip() {
        CustomFirstPersonArmEntity arm = arm();
        AnimationController<CustomPlayerEntity> controller = controllerFor(arm);

        assertNull(runScript(controller, arm.getAnimation(), "return ctrl.set_animation('" + ARM_CLIP + "');"));
        assertEquals(
            1,
            controller.animationQueue.size(),
            "the arm's own clip is handed over, which is the control for the cross-domain case");
    }

    @Test
    void withoutAnAnimationIdTheCheckIsSkipped() {
        // A null animation id carries no file to check against, so the hand-over keeps the pre-fix behaviour; the
        // production path (AnimationManager.predicateWithPackScript) always passes the animatable's own id.
        AnimationController<CustomPlayerEntity> controller = controllerFor(body());

        assertNull(runScript(controller, null, "return ctrl.set_animation('" + MISSING_CLIP + "');"));
        assertEquals(
            1,
            controller.animationQueue.size(),
            "with no file id to check against, the hand-over is unchanged");
    }

    // --- C-2: the GUI preview channel ------------------------------------------------------------------------------

    @Test
    void aPreviewNameTheModelDoesNotDeclareStopsTheCapController() {
        CustomPlayerEntity preview = previewEntity(BODY_MODEL);
        preview.getPreviewInfo()
            .setPreview(MISSING_CLIP);

        assertEquals(
            PlayState.STOP,
            AnimationManager.getInstance()
                .predicateCap(event(preview, "cap_controller")),
            "a preview_animation the model does not declare must stop the controller instead of reaching "
                + "AnimationController#setAnimation");
    }

    @Test
    void aDeclaredPreviewNameIsStillPlayed() {
        CustomPlayerEntity preview = previewEntity(BODY_MODEL);
        preview.getPreviewInfo()
            .setPreview(BODY_CLIP);

        assertEquals(
            PlayState.CONTINUE,
            AnimationManager.getInstance()
                .predicateCap(event(preview, "cap_controller")),
            "a preview_animation the model does declare is played");
    }

    @Test
    void aPreviewEntityAskingFromTheWrongDomainIsRefused() {
        // The GUI preview is a body animatable and reads the main domain, so the fp_arm set's clip is not a name it can
        // play even though the same model declares it in the other domain.
        CustomPlayerEntity preview = previewEntity(BODY_MODEL);
        preview.getPreviewInfo()
            .setPreview(ARM_CLIP);

        assertEquals(
            PlayState.STOP,
            AnimationManager.getInstance()
                .predicateCap(event(preview, "cap_controller")),
            "the preview channel reads the main file, so an fp_arm-only clip is not playable there");
    }

    // --- the pure decision the preview channel is guarded with --------------------------------------------------------

    @Test
    void thePreviewsExistenceDecisionAsksTheModelsOwnMainFile() {
        // The guard is ModelPreviewRegistry's own lookup, the one the hover/focus channels already use, so this pins
        // that lookup's answers for the preview channel's ids.
        assertFalse(
            ModelPreviewRegistry.hasAnimation(BODY_MODEL, MISSING_CLIP),
            "a name the model does not declare is not defined");
        assertTrue(ModelPreviewRegistry.hasAnimation(BODY_MODEL, BODY_CLIP), "the main file's own clip is");
        assertFalse(
            ModelPreviewRegistry.hasAnimation(BODY_MODEL, ARM_CLIP),
            "a clip that exists only in the other domain is not defined for the body's controllers");
        assertFalse(ModelPreviewRegistry.hasAnimation(null, BODY_CLIP));
        assertFalse(ModelPreviewRegistry.hasAnimation(BODY_MODEL, ""));
        assertFalse(ModelPreviewRegistry.hasAnimation(BODY_MODEL, null));
    }

    @Test
    void theFixtureClipIsWhatTheEngineWouldLoad() {
        AnimationFile main = ClientModelManager.animationFileFor(ModelIdUtil.getMainId(BODY_MODEL));
        assertNotNull(main);
        Animation clip = main.getAnimation(BODY_CLIP);
        assertNotNull(clip, "the fixture's clip must be loadable by name, or the accepted-route tests prove nothing");
        assertEquals(BODY_CLIP, clip.animationName);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    /** Registers the payload; the install itself is lazy, so the first lookup is what installs it. */
    private static void install(ResourceLocation modelId) {
        ClientModelManager.registerAll(payload(modelId, installMarker()), false);
        assertNotNull(
            ClientModelManager.animationFileFor(ModelIdUtil.getMainId(modelId)),
            "the main domain must be installed for this test's lookups");
    }

    /**
     * A clip name no assertion names, so that every install is a payload {@link ClientModelManager#registerAll} has
     * not seen before.
     * <p>
     * The two fixture ids are shared by every test here, and the payloads differ only in the marker, so without it the
     * second test's registration would be skipped as "identical content is already registered" - the animations would
     * never be re-parked, and the install assertion above would fail for a reason that has nothing to do with the
     * behaviour under test. It stays inside each model's own domain file, so it changes no existence answer.
     */
    private static String installMarker() {
        return "install_marker_" + INSTALLS.incrementAndGet();
    }

    private static ModelData payload(ResourceLocation modelId, String marker) {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        if (modelId.equals(ARM_MODEL)) {
            animations.put("fp_arm", document(ARM_CLIP, marker));
        } else {
            animations.put("main.animation.json", document(BODY_CLIP, marker));
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

    private static CustomPlayerEntity body() {
        CustomPlayerEntity body = new CustomPlayerEntity();
        body.setMainModel(ModelIdUtil.getMainId(BODY_MODEL));
        return body;
    }

    private static CustomFirstPersonArmEntity arm() {
        CustomFirstPersonArmEntity arm = new CustomFirstPersonArmEntity();
        // The arm resolves its own first-person domain from the parent of the main id it is handed, so it has to be the
        // arm fixture's own id - the body's would make it read an `arm` file nobody installed.
        arm.setMainModel(ModelIdUtil.getMainId(ARM_MODEL));
        return arm;
    }

    /** A GUI preview animatable: no player, so the preview predicates take their preview branch. */
    private static CustomPlayerEntity previewEntity(ResourceLocation modelId) {
        CustomPlayerEntity preview = new CustomPlayerEntity();
        preview.setMainModel(ModelIdUtil.getMainId(modelId));
        return preview;
    }

    private static AnimationController<CustomPlayerEntity> controllerFor(CustomPlayerEntity animatable) {
        return new AnimationController<>(animatable, "guarded_handover_test_controller", 0.0F, e -> PlayState.CONTINUE);
    }

    private static AnimationEvent<CustomPlayerEntity> event(CustomPlayerEntity animatable, String controllerName) {
        AnimationEvent<CustomPlayerEntity> event = new AnimationEvent<>(
            animatable,
            0.0F,
            0.0F,
            0.0F,
            false,
            new ArrayList<>());
        event.setController(
            new AnimationController<>(animatable, controllerName, 0.0F, e -> PlayState.CONTINUE));
        return event;
    }

    /**
     * Runs one script against {@code controller} the way {@code AnimationManager.predicateWithPackScript} does, with
     * {@code animationId} as the file id the controller's animatable resolves to. A {@code null} id skips the check,
     * which is the shape every caller had before the check existed.
     */
    private static PlayState runScript(AnimationController<CustomPlayerEntity> controller,
        ResourceLocation animationId, String source) {
        MolangParser parser = new MolangParser();
        CtrlScriptBinding.register(parser, java.util.Collections.emptyList());
        PackFunctionScript script = PackFunctionScript.parse(source);
        return CtrlScriptBinding.evaluate(script, parser, null, controller, null, animationId);
    }
}
