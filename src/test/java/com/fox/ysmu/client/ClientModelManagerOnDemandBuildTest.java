package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * End to end proof that a synced model is built off the client thread and published by the tick, using a real model
 * file so the parse being exercised is the real one.
 * <p>
 * This is the only part of the on-demand load path that can be verified without a running game. What it cannot show
 * is the effect on a frame, because there is no frame here - the join-time measurement is in the ExecPlan's
 * acceptance section and needs a client.
 * <p>
 * The failure it guards is the one that would look like "the model never appears": the payload is parked and the
 * build is requested, but the result is never published, so the renderer draws the built-in default forever while
 * every log line looks healthy.
 */
class ClientModelManagerOnDemandBuildTest {

    private static final ResourceLocation MODEL_ID = new ResourceLocation(ysmu.MODID, "on_demand_build_probe");

    private static ResourceLocation mainId() {
        return ModelIdUtil.getMainId(MODEL_ID);
    }

    @AfterEach
    void release() {
        GeckoLibCache.getInstance()
            .getGeoModels()
            .remove(mainId());
        ClientModelManager.SCALE_INFO.remove(mainId());
        ClientModelManager.RENDER_LAYERS_FIRST.remove(mainId());
        ClientModelManager.EXTRA_INFO.remove(mainId());
        ClientModelManager.EXTRA_ANIMATION_NAME.remove(mainId());
        ClientModelManager.MODELS.remove(MODEL_ID);
    }

    @Test
    void aParkedModelIsBuiltOffThreadAndPublishedByTheTick() throws Exception {
        File geometryFile = new File("tmp/\u827e\u83b2\u00b7\u4e541.4.0/models/main.json");
        assumeTrue(geometryFile.isFile(), "the reference pack is not present, so there is nothing to build");
        byte[] geometry = Files.readAllBytes(geometryFile.toPath());

        Map<String, byte[]> models = new LinkedHashMap<>();
        models.put("main", geometry);
        // No textures: uploading them needs a running game, and this test is about the geometry half of the path.
        ModelData data = new ModelData(
            MODEL_ID.getResourcePath(),
            Type.FOLDER,
            models,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());

        ClientModelManager.registerAll(data, false);

        assertTrue(
            ClientModelManager.isModelAvailable(mainId()),
            "registration parks the model, which is what makes the renderer draw it instead of giving up");
        assertFalse(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(mainId()),
            "registration must not have parsed the geometry");

        assertFalse(
            ClientModelManager.ensureGeometry(mainId()),
            "the first request queues the build and must not block the caller to finish it");

        long startedAt = System.nanoTime();
        GeoModel published = null;
        for (int tick = 0; tick < 400 && published == null; tick++) {
            ClientModelManager.tick();
            published = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(mainId());
            if (published == null) {
                Thread.sleep(10L);
            }
        }
        long millis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertNotNull(
            published,
            "the loader must build the model and a tick must publish it; if this fails the model would stay "
                + "substituted with the built-in default forever, with nothing else in the log to show for it");
        System.out.printf(
            "on-demand geometry build: %d KB parsed and published in %d ms%n",
            geometry.length / 1024,
            millis);

        assertTrue(
            ClientModelManager.ensureGeometry(mainId()),
            "once published the model must be available without queueing anything");
        assertTrue(
            ClientModelManager.SCALE_INFO.containsKey(mainId()),
            "publishing must also fill the tables the GUI and the renderer read, not only the engine's cache");
    }
}
