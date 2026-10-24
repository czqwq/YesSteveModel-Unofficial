package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Animations are parked at registration and installed on first use, so that joining a world does not parse every
 * model's animation files.
 * <p>
 * The failure mode this guards is silent in both directions. If the install never runs, every model plays no
 * animation and nothing logs an error; if the pending entry is not cleared, the model is re-parsed on every frame
 * that asks for an animation, because the engine looks the animation up again for whatever is already playing.
 * <p>
 * The payload used here is deliberately tiny: what matters is the bookkeeping, and a real payload would only make the
 * test slower. Nothing in this path needs a running game - {@code ModelData} is a plain holder of byte arrays.
 */
class ClientModelManagerDeferralTest {

    private static final ResourceLocation MODEL_ID = new ResourceLocation("ysmu", "deferral_probe");

    /** One animation with no keyframes, which is enough for the file to count as non-empty. */
    private static final String ANIMATION_JSON = "{\"format_version\":\"1.8.0\",\"animations\":"
        + "{\"idle\":{\"loop\":true,\"animation_length\":1.0,\"bones\":{}}}}";

    private static ModelData payload() {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main.animation.json", ANIMATION_JSON.getBytes(StandardCharsets.UTF_8));
        return new ModelData(
            MODEL_ID.getResourcePath(),
            Type.FOLDER,
            new HashMap<>(),
            new HashMap<>(),
            animations);
    }

    private static ResourceLocation mainId() {
        return ModelIdUtil.getMainId(MODEL_ID);
    }

    /**
     * A payload with one geometry file, one texture and no animations, which is all {@code registerAll} needs to park
     * a model. The geometry is deliberately not valid JSON: the point of the test is that registration never parses
     * it, and an unparseable file makes that visible - if registration did parse it, the build would fail loudly in
     * the log rather than the test passing on a technicality.
     */
    /**
     * A payload with one geometry file and no textures, which is all {@code registerAll} needs to park a model. The
     * geometry is deliberately not valid JSON: the point of the test is that registration never parses it, and an
     * unparseable file makes that visible - if registration did parse it, the build would fail loudly in the log
     * rather than the test passing on a technicality.
     * <p>
     * The texture map is empty on purpose. A parked model is published by whichever test next drives
     * {@code ClientModelManager.tick()}, and publishing uploads textures through {@code TextureManager}, which needs a
     * running game; an empty map turns a stray publish into a no-op instead of a {@code NoClassDefFoundError} in an
     * unrelated test.
     */
    private static ModelData geometryPayload() {
        Map<String, byte[]> geometry = new LinkedHashMap<>();
        geometry.put("main", "not parsed at registration".getBytes(StandardCharsets.UTF_8));
        return new ModelData(
            MODEL_ID.getResourcePath(),
            Type.FOLDER,
            geometry,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());
    }

    @AfterEach
    void release() {
        // Bounded, and the install runs before the final removal so the drain cannot re-install what was just
        // removed. The loop must not be `while (animationsPending(...))`: ensureAnimations returns early when it is not
        // on the recorded client thread, before it takes the payload, so a guard that ever failed closed would spin
        // here forever and turn the failure this suite is meant to report into a hung build.
        for (int attempt = 0; attempt < 4 && ClientModelManager.animationsPending(mainId()); attempt++) {
            ClientModelManager.ensureAnimations(mainId());
        }
        GeckoLibCache.getInstance()
            .getAnimations()
            .remove(mainId());
    }

    @Test
    void aParkedPayloadIsInstalledOnceAndOnlyOnDemand() {
        ClientModelManager.deferAnimations(MODEL_ID, payload());

        assertTrue(
            ClientModelManager.animationsPending(mainId()),
            "registration must park the animation payload instead of installing it - that parked payload is the whole "
                + "point of the change, and joining a world must not parse it");
        assertFalse(
            GeckoLibCache.getInstance()
                .getAnimations()
                .containsKey(mainId()),
            "the animation file must not reach the engine's cache before anything asks for it");

        ClientModelManager.ensureAnimations(mainId());

        assertFalse(
            ClientModelManager.animationsPending(mainId()),
            "the first request installs the payload and clears it, so later requests cost one lookup");
        assertTrue(
            GeckoLibCache.getInstance()
                .getAnimations()
                .containsKey(mainId()),
            "the first request must leave the model's animations in the engine's cache");

        // A second call must be a no-op rather than a re-parse: the engine asks for the playing animation every frame.
        ClientModelManager.ensureAnimations(mainId());
        assertFalse(ClientModelManager.animationsPending(mainId()));
    }

    @Test
    void aModelThatWasNeverParkedIsUntouched() {
        ResourceLocation unknown = new ResourceLocation("ysmu", "never_registered");
        assertFalse(ClientModelManager.animationsPending(ModelIdUtil.getMainId(unknown)));
        // Must not throw, must not install anything, and must stay a miss.
        ClientModelManager.ensureAnimations(ModelIdUtil.getMainId(unknown));
        assertFalse(
            GeckoLibCache.getInstance()
                .getAnimations()
                .containsKey(ModelIdUtil.getMainId(unknown)),
            "an id nobody registered must not conjure an entry");
    }

    /**
     * Registration must index a model without building it.
     * <p>
     * This is the whole of the join-time saving: the model-selection screen needs to know the model exists and which
     * textures it has, which is a couple of map writes, while the geometry parse - the expensive half - waits until
     * something actually draws the model. The failure this guards is a silent regression back to eager building,
     * which would look correct in game and only show up as the old freeze.
     */
    @Test
    void aSyncedModelIsIndexedButNotBuilt() {
        ModelData data = geometryPayload();
        ClientModelManager.registerAll(data, false);
        ResourceLocation mainId = ModelIdUtil.getMainId(MODEL_ID);

        assertTrue(
            ClientModelManager.MODELS.containsKey(MODEL_ID),
            "the model must be indexed at registration, because the selection screen lists models from MODELS");
        assertTrue(
            ClientModelManager.isModelAvailable(mainId),
            "the model must count as available so the renderer draws it rather than falling back to vanilla");
        assertFalse(
            ClientModelManager.ensureGeometry(mainId),
            "asking for a model that has not been built must report 'not ready yet' so the caller draws the default, "
                + "instead of blocking the frame to build it");
        assertFalse(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(mainId),
            "registration must not have built the geometry - that is the work this change moved off the join");
    }

    /** An id nobody registered has nothing to wait for, so asking must never hold a caller back. */
    @Test
    void anUnknownModelIsReportedAsReady() {
        assertTrue(ClientModelManager.ensureGeometry(new ResourceLocation("ysmu", "nobody_registered_this")));
    }
}
