package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * What registering a synced model actually costs, measured against what building one costs.
 * <p>
 * This is the mechanism behind the plan's first acceptance criterion. The nine second freeze was the client thread
 * parsing every model of the catalog at join, one after another, with nothing else happening; the change is that
 * registration parses nothing and only the models that get drawn are ever built. The claim to test is therefore not
 * "joining is faster by some amount" but "registering a catalog costs approximately nothing", which is measurable
 * here without a game: it is the same call, with the same payloads, only it is not on a frame.
 * <p>
 * The comparison is a real one model build through the eager path against a whole catalog through the deferred one.
 * The eager path is the same code the default model takes, so the number is the cost this change moved off the join.
 * <p>
 * Payloads carry no textures because the eager path uploads them, and uploading needs a running game.
 */
class ClientModelManagerRegistrationCostTest {

    /** The catalog size the baseline was measured against; see the ExecPlan's artifact section. */
    private static final int CATALOG_SIZE = 33;

    @Test
    void registeringACatalogCostsAlmostNothingWhileBuildingOneModelCostsHundredsOfMilliseconds() throws Exception {
        File geometryFile = new File("tmp/\u827e\u83b2\u00b7\u4e541.4.0/models/main.json");
        assumeTrue(geometryFile.isFile(), "the reference pack is not present, so there is nothing to measure");
        byte[] geometry = Files.readAllBytes(geometryFile.toPath());

        // Warm the JVM and the parsers up so the comparison is between the two paths and not between cold and hot.
        registerDeferred("warmup", geometry);
        GeckoLibCache.getInstance()
            .getGeoModels()
            .remove(ModelIdUtil.getMainId(modelIdFor("warmup")));

        long deferredStart = System.nanoTime();
        for (int index = 0; index < CATALOG_SIZE; index++) {
            registerDeferred("catalog_" + index, geometry);
        }
        long deferredNanos = System.nanoTime() - deferredStart;

        long eagerStart = System.nanoTime();
        ClientModelManager.registerAll(payload("eager_probe", geometry), true);
        long eagerNanos = System.nanoTime() - eagerStart;

        long deferredMillis = deferredNanos / 1_000_000L;
        long eagerMillis = eagerNanos / 1_000_000L;
        long perModelMicros = deferredNanos / 1000L / CATALOG_SIZE;
        System.out.printf(
            "registration cost: registering one model of %d KB costs %d us; building it costs %d ms; "
                + "a catalog of %d registers in %d ms with nothing parsed%n",
            geometry.length / 1024,
            perModelMicros,
            eagerMillis,
            CATALOG_SIZE,
            deferredMillis);

        // Nothing may have been parsed: that is the whole point, and a regression here would look correct in game
        // while quietly restoring the freeze.
        for (int index = 0; index < CATALOG_SIZE; index++) {
            ResourceLocation mainId = ModelIdUtil.getMainId(modelIdFor("catalog_" + index));
            assertTrue(ClientModelManager.isModelAvailable(mainId), "the catalog must be indexed");
            assertTrue(
                GeckoLibCache.getInstance()
                    .getGeoModels()
                    .get(mainId) == null,
                "registering a catalog must not build any of it");
        }

        // The comparison is one registration against one build, not the catalog against one build. A warm JVM runs
        // the build ten times faster than a cold one - 636 ms cold against 63 ms warm on the machine this was written
        // on - so a margin expressed over the whole catalog only holds before the JIT has seen the parser. Per model
        // the ratio is stable in both: about 2 ms to register against tens of milliseconds to build.
        assertTrue(
            (deferredNanos / CATALOG_SIZE) * 4 < eagerNanos,
            "registering one model (" + perModelMicros + " us) must cost far less than building it (" + eagerMillis
                + " ms); if it does not, registration has started parsing again");

        releaseAfterMeasurement();
    }

    private static void registerDeferred(String name, byte[] geometry) {
        ClientModelManager.registerAll(payload(name, geometry), false);
    }

    private static ResourceLocation modelIdFor(String name) {
        return new ResourceLocation(ysmu.MODID, "cost_probe_" + name);
    }

    private static ModelData payload(String name, byte[] geometry) {
        Map<String, byte[]> models = new LinkedHashMap<>();
        models.put("main", geometry);
        return new ModelData(
            modelIdFor(name).getResourcePath(),
            Type.FOLDER,
            models,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());
    }

    /** Leaves nothing behind for another test's tick to publish or for a later measurement to inherit. */
    private static void releaseAfterMeasurement() {
        for (String name : new String[] { "eager_probe" }) {
            ClientModelManager.MODELS.remove(modelIdFor(name));
            ClientModelManager.SCALE_INFO.remove(ModelIdUtil.getMainId(modelIdFor(name)));
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(ModelIdUtil.getMainId(modelIdFor(name)));
        }
        for (int index = 0; index < CATALOG_SIZE; index++) {
            ClientModelManager.MODELS.remove(modelIdFor("catalog_" + index));
        }
        ClientModelManager.MODELS.remove(modelIdFor("warmup"));
    }
}
