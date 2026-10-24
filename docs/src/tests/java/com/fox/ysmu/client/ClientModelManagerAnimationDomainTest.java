package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationDomain;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Guards the animation-domain split through the real install path.
 *
 * <p>
 * A model's animation sets used to be flattened into one namespace, later file wins, so the first-person set could
 * redefine a clip the model proper had already defined. That is not hypothetical: the built-in default model ships
 * {@code main.animation.json} with {@code parallel0} (its eyes and chest) and {@code parallel1} (its ribbon) and
 * {@code fp.arm.animation.json} with {@code parallel0..7} as empty stubs, and {@code fp_arm} is last in its
 * {@code files.player.animation} map - so the stubs replaced the body's clips for every model that does not define
 * its own. This test is that shape in miniature: one clip defined twice, plus one clip that belongs to each domain
 * only.
 *
 * <p>
 * Upstream's shape is the two-store split this pins: {@code DefaultAnimationKey.java:36-38} maps a {@code player}
 * target's {@code fp_arm} set to {@code player/first-person} and everything else to {@code player/main}, and
 * {@code ModelRenderTargetLoader.java:243-253} bakes one store per group.
 *
 * <p>
 * Each test installs under its own model id: {@code ClientModelManager.registerAll} skips a payload whose content
 * signature it has already registered, and the signature is keyed by model id, so two tests sharing one id would
 * silently test nothing.
 */
class ClientModelManagerAnimationDomainTest {

    private static final ResourceLocation MERGE_MODEL = new ResourceLocation(ysmu.MODID, "animation_domain_merge");
    private static final ResourceLocation FIRST_PERSON_MODEL = new ResourceLocation(
        ysmu.MODID,
        "animation_domain_first_person");
    private static final ResourceLocation RULE_MODEL = new ResourceLocation(ysmu.MODID, "animation_domain_rule");

    /** The clip both sets define, with different content - the shape the flattening used to lose. */
    private static final String SHARED_CLIP = "parallel0";
    /** Declared by the model proper only. */
    private static final String MAIN_ONLY_CLIP = "idle";
    /** Declared by the first-person set only. */
    private static final String FIRST_PERSON_ONLY_CLIP = "fp_only";

    /**
     * Both lengths are seconds in the file; the parser converts them to ticks
     * ({@code JsonAnimationUtils:364-366} -> {@code AnimationUtils.convertSecondsToTicks}, i.e. x20).
     */
    private static final double MAIN_SECONDS = 1.0;
    private static final double FIRST_PERSON_SECONDS = 0.2;
    private static final double MAIN_TICKS = 20.0;
    private static final double FIRST_PERSON_TICKS = 4.0;

    private static final ResourceLocation[] MODEL_IDS = { MERGE_MODEL, FIRST_PERSON_MODEL, RULE_MODEL };

    @AfterEach
    void release() {
        for (ResourceLocation modelId : MODEL_IDS) {
            for (String domain : AnimationDomain.ALL) {
                GeckoLibCache.getInstance()
                    .getAnimations()
                    .remove(AnimationDomain.animationFileId(modelId, domain));
            }
            ClientModelManager.MODELS.remove(modelId);
        }
    }

    /**
     * The payload every install test registers: the model proper's set, then the first-person set, in the order a
     * pack's {@code files.player.animation} map puts them. No geometry and no textures - this test is about the
     * animation install, and an empty geometry map also keeps the model out of the geometry queue.
     */
    private static ModelData payload(ResourceLocation modelId) {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main.animation.json", document(MAIN_SECONDS, SHARED_CLIP, MAIN_ONLY_CLIP));
        animations.put("fp_arm", document(FIRST_PERSON_SECONDS, SHARED_CLIP, FIRST_PERSON_ONLY_CLIP));
        return new ModelData(
            modelId.getResourcePath(),
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            animations);
    }

    /** A minimal animation document: every named clip the same length, no bones. */
    private static byte[] document(double seconds, String... clipNames) {
        StringBuilder animations = new StringBuilder();
        for (String name : clipNames) {
            if (animations.length() > 0) {
                animations.append(',');
            }
            animations.append('"')
                .append(name)
                .append("\":{\"loop\":true,\"animation_length\":")
                .append(seconds)
                .append(",\"bones\":{}}");
        }
        return ("{\"format_version\":\"1.8.0\",\"animations\":{" + animations + "}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aLaterAnimationSetCannotRedefineAnEarlierOneAcrossDomains() {
        ClientModelManager.registerAll(payload(MERGE_MODEL), false);

        AnimationFile model = ClientModelManager.animationFileFor(ModelIdUtil.getMainId(MERGE_MODEL));
        assertNotNull(model, "asking for the model's animations must install them");
        Animation shared = model.animations.get(SHARED_CLIP);
        assertNotNull(shared, "the model's own " + SHARED_CLIP + " must survive the later first-person set");
        assertEquals(
            MAIN_TICKS,
            shared.animationLength,
            1.0e-6,
            "the model proper must keep its own version of a clip the first-person set also defines");
        assertTrue(
            model.animations.containsKey(MAIN_ONLY_CLIP),
            "the model proper's own clips must still be installed");
        assertFalse(
            model.animations.containsKey(FIRST_PERSON_ONLY_CLIP),
            "a first-person-only clip must not appear in the model proper's namespace");
    }

    @Test
    void theFirstPersonSetIsInstalledUnderItsOwnId() {
        ClientModelManager.registerAll(payload(FIRST_PERSON_MODEL), false);

        // Both domains are installed by the one request the engine makes, which is keyed by the model's `main` id
        // (CustomPlayerModel#getAnimation installs through ClientModelManager.ensureAnimations(getMainModel())).
        assertNotNull(
            ClientModelManager.animationFileFor(ModelIdUtil.getMainId(FIRST_PERSON_MODEL)),
            "asking for the model's animations must install them");

        AnimationFile firstPerson = GeckoLibCache.getInstance()
            .getAnimations()
            .get(AnimationDomain.animationFileId(FIRST_PERSON_MODEL, AnimationDomain.FIRST_PERSON));
        assertNotNull(firstPerson, "the first-person domain must be installed, not dropped");
        assertTrue(
            firstPerson.animations.containsKey(FIRST_PERSON_ONLY_CLIP),
            "every clip of the first-person set must be reachable in its own domain");
        Animation shared = firstPerson.animations.get(SHARED_CLIP);
        assertNotNull(shared, "the first-person set defines this clip too, so its domain must keep a copy");
        assertEquals(
            FIRST_PERSON_TICKS,
            shared.animationLength,
            1.0e-6,
            "the first-person domain must keep the first-person set's version of a shared clip name");
        assertFalse(
            firstPerson.animations.containsKey(MAIN_ONLY_CLIP),
            "the model proper's clips must not leak into the first-person domain");
    }

    @Test
    void onlyTheFpArmSetIsTheFirstPersonDomain() {
        assertEquals(AnimationDomain.FIRST_PERSON, AnimationDomain.ofAnimationSet("fp_arm"));
        assertTrue(AnimationDomain.isFirstPerson(AnimationDomain.FIRST_PERSON));
        // Every other set a pack may declare belongs to the model proper, including the legacy trio and the
        // compatibility sets. Upstream's rule is exactly this one comparison (ModelRenderTargetLoader.java:246).
        for (String set : new String[] { "main", "arm", "extra", "tac", "carryon", "slashblade", "tlm", "parcool" }) {
            assertEquals(AnimationDomain.MAIN, AnimationDomain.ofAnimationSet(set), set + " is not the fp_arm set");
            assertFalse(AnimationDomain.isFirstPerson(AnimationDomain.ofAnimationSet(set)));
        }
        // The two domains are two engine cache entries, so neither can overwrite the other.
        assertEquals(
            ModelIdUtil.getMainId(RULE_MODEL),
            AnimationDomain.animationFileId(RULE_MODEL, AnimationDomain.MAIN));
        assertEquals(
            ModelIdUtil.getArmId(RULE_MODEL),
            AnimationDomain.animationFileId(RULE_MODEL, AnimationDomain.FIRST_PERSON));
    }
}
