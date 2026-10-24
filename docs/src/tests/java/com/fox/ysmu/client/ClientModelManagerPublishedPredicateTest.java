package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * "Can this geometry be drawn" and "have this connection's textures been uploaded" are different questions, and the
 * renderer needs the second one before it binds a texture.
 * <p>
 * They came apart because the engine's geometry cache deliberately outlives a connection - see the decision in the
 * ExecPlan - while the textures are uploaded by a publish. Reconnecting to a server that sends the same model id means
 * the cache still holds the previous connection's geometry, so a predicate that treats the cache as proof would hand
 * a texture id this connection never uploaded to {@code TextureManager.bindTexture}, and the texture manager answers
 * an unknown id by searching the resource packs: {@code Failed to load texture}, a {@code FileNotFoundException} and
 * the shared missing texture.
 * <p>
 * The stale entry is simulated directly here rather than by reconnecting, because the property under test is the pair
 * of predicates and not the connection lifecycle: geometry is placed in the engine's cache for an id that this
 * connection never published, which is exactly the state a reconnect leaves behind.
 */
class ClientModelManagerPublishedPredicateTest {

    private static final ResourceLocation PUBLISHED_MODEL =
        new ResourceLocation(ysmu.MODID, "published_predicate_probe");
    private static final ResourceLocation STALE_MODEL = new ResourceLocation(ysmu.MODID, "stale_cache_probe");
    // Each test gets its own model id. GEOMETRY_READY has no public reset - it is keyed by model id and is the
    // marker the whole design turns on - so two tests sharing an id would leak readiness into each other and the
    // second would short-circuit past the build it means to exercise.
    private static final ResourceLocation REREGISTER_MODEL =
        new ResourceLocation(ysmu.MODID, "reregister_probe");

    private static final ResourceLocation PUBLISHED_TEXTURE = new ResourceLocation(
        ysmu.MODID,
        "published_predicate_probe/skin.png");
    private static final ResourceLocation STALE_TEXTURE = new ResourceLocation(ysmu.MODID, "stale_cache_probe/skin.png");
    private static final ResourceLocation REREGISTER_TEXTURE = new ResourceLocation(
        ysmu.MODID,
        "reregister_probe/skin.png");

    private static ResourceLocation mainIdOf(ResourceLocation modelId) {
        return ModelIdUtil.getMainId(modelId);
    }

    @AfterEach
    void release() {
        // The bare ids and the main ids both end up in the engine's cache (one per sub-model file), so both are
        // removed; GEOMETRY_READY itself has no public reset, which is why no two tests here share an id.
        for (ResourceLocation modelId : new ResourceLocation[] { PUBLISHED_MODEL, STALE_MODEL, REREGISTER_MODEL }) {
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(mainIdOf(modelId));
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(ModelIdUtil.getArmId(modelId));
            ClientModelManager.MODELS.remove(modelId);
        }
    }

    @Test
    void aModelWhoseGeometrySurvivesInTheCacheIsStillNotBindableUntilThisConnectionPublishesIt() throws Exception {
        // The reference pack when it is present, an inline model when it is not: these tests pin the predicate split,
        // so they have to run on a fresh clone and in CI. A version that skipped itself there executed no assertions.
        byte[] geometry = GeometryFixtures.buildableGeometry("main.json");

        // A model this connection really publishes, exactly as the game does it.
        ClientModelManager.registerAll(payload(PUBLISHED_MODEL, geometry), false);
        ClientModelManager.ensureGeometry(PUBLISHED_MODEL);
        GeoModel built = null;
        for (int tick = 0; tick < 400 && built == null; tick++) {
            ClientModelManager.tick();
            built = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(mainIdOf(PUBLISHED_MODEL));
            if (built == null) {
                Thread.sleep(10L);
            }
        }
        assertTrue(
            built != null,
            "the loader must publish the model; a loader that never publishes is the regression this class exists to "
                + "catch, so it fails here rather than skipping the rest");

        CustomPlayerEntity published = new CustomPlayerEntity();
        published.setMainModel(mainIdOf(PUBLISHED_MODEL));
        published.setTexture(PUBLISHED_TEXTURE);
        CustomPlayerModel provider = new CustomPlayerModel();

        assertTrue(ClientModelManager.isModelPublished(mainIdOf(PUBLISHED_MODEL)));
        // Publishing is what makes the model drawable; it does not by itself make an arbitrary texture id bindable.
        // This payload declares no textures - a test cannot upload one - so the guard still withholds the entity's
        // claimed id, which is the invariant: only an id this client registered may reach TextureManager.bindTexture.
        assertEquals(
            CustomPlayerModel.DEFAULT_TEXTURE,
            provider.getTextureLocation(published),
            "only an id this client uploaded may be bound, even for a model it published");

        // The previous connection's geometry, still in the engine's cache, with nothing published for it here.
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(mainIdOf(STALE_MODEL), built);
        CustomPlayerEntity stale = new CustomPlayerEntity();
        stale.setMainModel(mainIdOf(STALE_MODEL));
        stale.setTexture(STALE_TEXTURE);

        assertFalse(
            ClientModelManager.isModelPublished(mainIdOf(STALE_MODEL)),
            "geometry left behind by a previous connection must not make this model drawable: the renderer would draw "
                + "another server's model and the host-facing isModelLoaded answer would be a lie");
        assertFalse(
            ClientModelManager.isModelAvailable(mainIdOf(STALE_MODEL)),
            "and nothing is on its way for it either, so the entity falls back rather than being drawn as a stranger");
        assertFalse(
            ClientModelManager.isModelPublished(mainIdOf(STALE_MODEL)),
            "this connection never published it, so its textures were never uploaded");

        assertEquals(
            CustomPlayerModel.DEFAULT_TEXTURE,
            provider.getTextureLocation(stale),
            "binding a texture this connection never uploaded is what produces Failed to load texture and the shared "
                + "missing texture, so the built-in default's texture has to be used instead");
    }

    /**
     * A model re-registered with different bytes is a fresh attempt: the previous publish is not allowed to keep
     * answering for the new payload, or the new payload would never be built and the engine's cache would keep
     * serving the old geometry - and if the two payloads name their textures differently, the texture the entity asks
     * for would never have been uploaded.
     */
    @Test
    void reRegisteringAModelDropsThePreviousPublishSoTheNewPayloadIsBuilt() throws Exception {
        // The reference pack when it is present, an inline model when it is not: these tests pin the predicate split,
        // so they have to run on a fresh clone and in CI. A version that skipped itself there executed no assertions.
        byte[] geometry = GeometryFixtures.buildableGeometry("main.json");

        ClientModelManager.registerAll(payload(REREGISTER_MODEL, geometry), false);
        ClientModelManager.ensureGeometry(REREGISTER_MODEL);
        GeoModel built = null;
        for (int tick = 0; tick < 400 && built == null; tick++) {
            ClientModelManager.tick();
            built = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(mainIdOf(REREGISTER_MODEL));
            if (built == null) {
                Thread.sleep(10L);
            }
        }
        assertTrue(
            built != null,
            "the loader must publish the model; a loader that never publishes is the regression this class exists to "
                + "catch, so it fails here rather than skipping the rest");

        CustomPlayerEntity entity = new CustomPlayerEntity();
        entity.setMainModel(mainIdOf(REREGISTER_MODEL));
        entity.setTexture(REREGISTER_TEXTURE);
        CustomPlayerModel provider = new CustomPlayerModel();
        assertTrue(ClientModelManager.isModelPublished(mainIdOf(REREGISTER_MODEL)));

        // The same id with different bytes. The content signature does not stop this: it only skips identical payloads.
        Map<String, byte[]> changed = new LinkedHashMap<>();
        changed.put("main", geometry);
        changed.put("arm", geometry);
        ClientModelManager.registerAll(
            new ModelData(
                REREGISTER_MODEL.getResourcePath(),
                Type.FOLDER,
                changed,
                new LinkedHashMap<>(),
                new LinkedHashMap<>()),
            false);

        assertFalse(
            ClientModelManager.isModelPublished(mainIdOf(REREGISTER_MODEL)),
            "the previous publish described the previous bytes, so it may not answer for these");
        assertFalse(
            ClientModelManager.isModelPublished(mainIdOf(REREGISTER_MODEL)),
            "and the geometry in the cache is the previous payload's, so nothing here is drawable yet");
        assertTrue(
            ClientModelManager.isModelAvailable(mainIdOf(REREGISTER_MODEL)),
            "the new payload is on its way, so the entity stays with this renderer");
        assertEquals(
            CustomPlayerModel.DEFAULT_TEXTURE,
            provider.getTextureLocation(entity),
            "and until it is published the built-in default's texture is the only bindable one");

        // The fresh payload must now be able to build at all, which is what the cleared request marker is for.
        assertFalse(
            ClientModelManager.ensureGeometry(REREGISTER_MODEL),
            "the new payload must be requestable - if the previous request marker were still set, it never would be");
        GeoModel rebuilt = null;
        for (int tick = 0; tick < 400 && rebuilt == null; tick++) {
            ClientModelManager.tick();
            rebuilt = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(ModelIdUtil.getArmId(REREGISTER_MODEL));
            if (rebuilt == null) {
                Thread.sleep(10L);
            }
        }
        assertTrue(rebuilt != null, "the new payload must publish, including the sub-model only it declares");
        assertTrue(ClientModelManager.isModelPublished(mainIdOf(REREGISTER_MODEL)));
    }

    /** A payload with one real geometry file and no textures; uploading textures needs a running game. */
    private static ModelData payload(ResourceLocation modelId, byte[] geometry) {
        Map<String, byte[]> models = new LinkedHashMap<>();
        models.put("main", geometry);
        return new ModelData(
            modelId.getResourcePath(),
            Type.FOLDER,
            models,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());
    }
}
