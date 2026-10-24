package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationDomain;
import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.condition.FPArmConditionManager;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Pins the first-person arm's domain decisions: which animation-file id is a first-person one, which controller map
 * an arm reaches, and which condition table its conditional clip names are classified in.
 *
 * <p>
 * Upstream keeps two condition managers and feeds each from its own animation store
 * ({@code model/resource/client/render/ModelRenderTargetAssembler.java:53-60}), and the arm's armour predicate reads
 * the first-person one ({@code client/animation/predicate/FPArmArmorPredicate.java:36}). The port keys both tables by
 * the animation-file id the asking animatable reads, so "which table" is decided by the same id rule as everything
 * else in {@link AnimationDomain} - and this test is what keeps a name the {@code fp_arm} set declares out of the
 * body's table, and the body's names out of the arm's.
 */
class FirstPersonArmDomainRoutingTest {

    private static final ResourceLocation MODEL = new ResourceLocation(ysmu.MODID, "first_person_arm_routing");
    /** A condition name only the first-person set declares (armour slot + item id). */
    private static final String FIRST_PERSON_CONDITION = "head$ysmu:first_person_helmet";
    /** A condition name only the model proper declares. */
    private static final String MAIN_CONDITION = "chest$ysmu:body_chestplate";

    @AfterEach
    void release() {
        for (String domain : AnimationDomain.ALL) {
            GeckoLibCache.getInstance()
                .getAnimations()
                .remove(AnimationDomain.animationFileId(MODEL, domain));
        }
        ClientModelManager.MODELS.remove(MODEL);
        ConditionManager.clear();
    }

    @Test
    void theArmIdIsTheFirstPersonOneAndTheMainIdIsNot() {
        ResourceLocation modelId = MODEL;
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        ResourceLocation armId = ModelIdUtil.getArmId(modelId);

        assertTrue(AnimationDomain.isFirstPersonId(armId), "the arm id is the first-person animation-file id");
        assertFalse(AnimationDomain.isFirstPersonId(mainId), "the main id is the model proper's");
        assertFalse(AnimationDomain.isFirstPersonId(modelId), "the model id is neither");
        assertFalse(AnimationDomain.isFirstPersonId(null), "a null id is not a first-person one");
    }

    @Test
    void anArmReachesTheModelsControllerMapAndABodyReachesItsOwn() {
        // Upstream keeps ONE controller map per render target and both entities read it
        // (FPArmControllerCollection.java:74-76 through the same ResourceAdapter). The port's registry is keyed by
        // animation id, so the arm's id has to resolve to the model's `main` id and an id that is already the model's
        // own must be left alone.
        assertEquals(ModelIdUtil.getMainId(MODEL), AnimationDomain.controllerSetId(ModelIdUtil.getArmId(MODEL)));
        assertEquals(ModelIdUtil.getMainId(MODEL), AnimationDomain.controllerSetId(ModelIdUtil.getMainId(MODEL)));
    }

    @Test
    void theArmReadsTheFirstPersonConditionTableAndTheBodyItsOwn() {
        ResourceLocation mainId = ModelIdUtil.getMainId(MODEL);
        ResourceLocation armId = ModelIdUtil.getArmId(MODEL);
        // `true`: both names are declared by the model's own files, which is the case this routing question is about.
        ConditionManager.addTest(mainId, MAIN_CONDITION, true);
        FPArmConditionManager.addTest(armId, FIRST_PERSON_CONDITION, true);

        // `addTest` creates the table for the id it was given and for nothing else, so the presence of a table is
        // exactly the question "was this model's own/other domain classified under this id".
        assertNotNull(ConditionManager.getArmor(mainId));
        assertNull(ConditionManager.getArmor(armId), "the first-person set's names must not land in the body's table");
        assertNotNull(FPArmConditionManager.getArmor(armId));
        assertNull(FPArmConditionManager.getArmor(mainId), "the body's names must not land in the arm's table");

        // The one lookup every caller uses, which is the decision this test exists for.
        assertSame(ConditionManager.getArmor(mainId), ConditionManager.armorFor(mainId));
        assertSame(FPArmConditionManager.getArmor(armId), ConditionManager.armorFor(armId));
        assertNull(ConditionManager.armorFor(new ResourceLocation(ysmu.MODID, "no_such_model/arm")));
    }

    @Test
    void theInstallPathSplitsTheTwoSetsConditionNamesByDomain() {
        ClientModelManager.registerAll(payload(), false);
        // The install is lazy: asking for the model's animations is what puts both domains and both condition tables
        // in place (CustomPlayerModel#getAnimation installs through ClientModelManager.ensureAnimations).
        assertNotNull(ClientModelManager.animationFileFor(ModelIdUtil.getMainId(MODEL)));

        ResourceLocation mainId = ModelIdUtil.getMainId(MODEL);
        ResourceLocation armId = ModelIdUtil.getArmId(MODEL);

        assertNotNull(ConditionManager.getArmor(mainId), "the model proper's names are classified under the main id");
        assertNull(
            ConditionManager.getArmor(armId),
            "a name the fp_arm set declares must not be classified for the body");
        assertNotNull(
            FPArmConditionManager.getArmor(armId),
            "the fp_arm set's names are classified under the arm id");
        assertNull(
            FPArmConditionManager.getArmor(mainId),
            "the model proper's names must not be classified for the arm");
    }

    /**
     * A payload with one condition name per domain: the model proper declares {@code chest$…} and the first-person set
     * declares {@code head$…}, so a table that ended up holding both is visible from either id.
     */
    private static ModelData payload() {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main.animation.json", document(MAIN_CONDITION));
        animations.put("fp_arm", document(FIRST_PERSON_CONDITION));
        return new ModelData(MODEL.getResourcePath(), Type.FOLDER, new LinkedHashMap<>(), new LinkedHashMap<>(), animations);
    }

    /** A minimal animation document with one clip; the clip's name is the condition name being classified. */
    private static byte[] document(String clipName) {
        return ("{\"format_version\":\"1.8.0\",\"animations\":{\"" + clipName
            + "\":{\"loop\":true,\"animation_length\":0.2,\"bones\":{}}}}").getBytes(StandardCharsets.UTF_8);
    }
}
