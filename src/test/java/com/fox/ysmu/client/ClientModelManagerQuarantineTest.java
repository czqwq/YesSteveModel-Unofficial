package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.GeometryFixtures;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * A model whose geometry cannot be built is quarantined once, not retried on every frame.
 * <p>
 * The failure this guards is silent and expensive: the render path asks {@code ensureGeometry} on every frame that
 * draws a model, so a build that is allowed to fail repeatedly is re-parsed every frame for as long as the model is
 * on screen - with the entity meanwhile drawn as the built-in default, so the only visible symptom is a warning that
 * keeps repeating. Upstream distinguishes a deterministic failure from a transient one and quarantines the first.
 * <p>
 * The signature of a quarantined model is the pair asserted at the end: {@code ensureGeometry} answers "nothing to
 * wait for" (so no frame asks again) while {@code isModelPublished} still answers "not drawable" (so the renderer
 * keeps substituting the default instead of binding a texture that was never uploaded).
 * <p>
 * The payload's geometry is the empty JSON object, which parses but declares no usable layout - the same path a model
 * with an unsupported {@code format_version} takes.
 */
class ClientModelManagerQuarantineTest {

    private static final ResourceLocation MODEL_ID = new ResourceLocation(ysmu.MODID, "quarantine_probe");
    // A second id for the arm-only case. Parking a payload does drop the markers for its id, so sharing one id would
    // still work - but each test would then depend on the other's leftovers, and the quarantine markers have no
    // public reset to fall back on.
    private static final ResourceLocation ARM_ONLY_MODEL_ID = new ResourceLocation(ysmu.MODID, "arm_only_probe");

    private static ResourceLocation mainId() {
        return ModelIdUtil.getMainId(MODEL_ID);
    }

    private static ResourceLocation armOnlyMainId() {
        return ModelIdUtil.getMainId(ARM_ONLY_MODEL_ID);
    }

    @AfterEach
    void release() {
        GeckoLibCache.getInstance()
            .getGeoModels()
            .remove(mainId());
        GeckoLibCache.getInstance()
            .getGeoModels()
            .remove(armOnlyMainId());
        ClientModelManager.MODELS.remove(MODEL_ID);
        ClientModelManager.MODELS.remove(ARM_ONLY_MODEL_ID);
    }

    @Test
    void aBuildThatProducedOnlyTheArmIsQuarantinedRatherThanReportedReady() throws Exception {
        // The reference pack's arm when it is present, an inline model when it is not: this test pins "a build that
        // produced only the arm must not be reported ready", which has to hold on a fresh clone too.
        byte[] armGeometry = GeometryFixtures.buildableGeometry("arm.json");

        // The arm builds and the main does not. Reporting this model ready would advertise geometry the player
        // renderer cannot look up - it asks for the *main* file - and the player would be drawn as nothing at all,
        // because the vanilla renderer was already cancelled by the time the renderer bails out.
        Map<String, byte[]> geometry = new LinkedHashMap<>();
        geometry.put("main", "{}".getBytes(StandardCharsets.UTF_8));
        geometry.put("arm", armGeometry);
        ModelData data = new ModelData(
            ARM_ONLY_MODEL_ID.getResourcePath(),
            Type.FOLDER,
            geometry,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());

        ClientModelManager.registerAll(data, false);
        ClientModelManager.ensureGeometry(ARM_ONLY_MODEL_ID);

        boolean settled = false;
        for (int tick = 0; tick < 400 && !settled; tick++) {
            ClientModelManager.tick();
            settled = ClientModelManager.ensureGeometry(armOnlyMainId());
            if (!settled) {
                Thread.sleep(10L);
            }
        }
        assertTrue(settled, "the build must settle within a few seconds");

        assertFalse(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(armOnlyMainId()),
            "no main geometry was produced, so none may be published");
        assertFalse(ClientModelManager.isModelPublished(armOnlyMainId()), "so the model must not report itself drawable");
        assertFalse(
            ClientModelManager.isModelPublished(armOnlyMainId()),
            "and it must not report itself published either, which is what gates the texture bind");
        assertFalse(
            ClientModelManager.isModelAvailable(armOnlyMainId()),
            "a quarantined model is neither installed nor pending, so the entity falls back rather than drawing "
                + "nothing");
    }

    @Test
    void aModelThatCannotBeBuiltIsQuarantinedInsteadOfRetriedEveryFrame() throws Exception {
        Map<String, byte[]> geometry = new LinkedHashMap<>();
        geometry.put("main", "{}".getBytes(StandardCharsets.UTF_8));
        ModelData data = new ModelData(
            MODEL_ID.getResourcePath(),
            Type.FOLDER,
            geometry,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());

        ClientModelManager.registerAll(data, false);

        assertTrue(
            ClientModelManager.isModelAvailable(mainId()),
            "the model must start out parked, which is what makes the first request build it");
        assertFalse(
            ClientModelManager.ensureGeometry(mainId()),
            "the first request queues the build rather than doing it");

        // Wait for the build to finish and be published. Either outcome makes ensureGeometry answer true - installed,
        // or quarantined - so the wait is the same and the assertion afterwards is what distinguishes them.
        boolean settled = false;
        for (int tick = 0; tick < 400 && !settled; tick++) {
            ClientModelManager.tick();
            settled = ClientModelManager.ensureGeometry(mainId());
            if (!settled) {
                Thread.sleep(10L);
            }
        }
        assertTrue(settled, "the build must settle within a few seconds");

        assertFalse(
            ClientModelManager.isModelPublished(mainId()),
            "this model's geometry could not be built, so it must not claim to be drawable");
        assertFalse(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(mainId()),
            "nothing may be published for a model whose every geometry file was refused");
        assertFalse(
            ClientModelManager.isModelAvailable(mainId()),
            "a quarantined model is neither installed nor pending, so it behaves like one this client never had");

        // The point of the quarantine: further frames ask, get "nothing to wait for", and do no work at all.
        assertTrue(
            ClientModelManager.ensureGeometry(mainId()),
            "a later frame must not be told to wait again, which is what would make it re-request the build");
        assertTrue(ClientModelManager.ensureGeometry(mainId()));
    }
}
