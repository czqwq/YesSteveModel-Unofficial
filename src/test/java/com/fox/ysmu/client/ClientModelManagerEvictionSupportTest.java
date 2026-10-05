package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.asset.AssetManager;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The three {@link ClientModelManager} members the lazy asset eviction needs, pinned without a client:
 * {@link ClientModelManager#isDefaultModelId} (the default model must never be adopted for eviction),
 * {@link ClientModelManager#isIdleEvictionSuppressed} (the GUI / post-close window) and
 * {@link ClientModelManager#hasReloadableClientCache} (the port-specific safety gate: a geometry the
 * port cannot re-read must never be registered with a cache whose job is to evict it), plus the
 * null-safety of {@link ClientModelManager#parseSingleGeoFromCache}.
 *
 * <p>{@code parseSingleGeoFromCache}'s success path needs a real encrypted client cache
 * ({@code ServerModelManager.CACHE_CLIENT} plus the session password) and is therefore a live-client
 * case; what is pinned here is the decision that protects the safety property — no reload path, no
 * value, so nothing to hand back.
 */
class ClientModelManagerEvictionSupportTest {

    /** Three ids, because a parked payload has no public reset and the markers are per id. */
    private static final ResourceLocation WIRED_OFF = new ResourceLocation(ysmu.MODID, "eviction_wiring_off");
    private static final ResourceLocation WIRED_ON = new ResourceLocation(ysmu.MODID, "eviction_wiring_on");
    private static final ResourceLocation WIRED_UNRELOADABLE =
        new ResourceLocation(ysmu.MODID, "eviction_wiring_unreloadable");

    @AfterEach
    void restoreTheShippedDefaults() {
        // Release whatever the wiring test adopted, while the switch is still on, so the entry count does not
        // leak into another test class in the same JVM.
        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        for (ResourceLocation model : new ResourceLocation[] { WIRED_OFF, WIRED_ON, WIRED_UNRELOADABLE }) {
            ResourceLocation mainId = ModelIdUtil.getMainId(model);
            AssetManager.release(mainId);
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(mainId);
            ClientModelManager.MODELS.remove(model);
        }
        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        Config.GUI_POST_CLOSE_GRACE_SECONDS = 120;
        ClientModelManager.setGuiOpen(false);
    }

    /**
     * Publishes a probe model through the real path and waits for it to settle.
     *
     * @return its {@code main} id
     */
    private static ResourceLocation publishProbeModel(ResourceLocation model) {
        Map<String, byte[]> geometry = new LinkedHashMap<>();
        geometry.put("main", GeometryFixtures.buildableGeometry("main.json"));
        ModelData data = new ModelData(
            model.getResourcePath(),
            Type.FOLDER,
            geometry,
            new LinkedHashMap<>(),
            new LinkedHashMap<>());
        ClientModelManager.registerAll(data, false);

        ResourceLocation mainId = ModelIdUtil.getMainId(model);
        boolean settled = false;
        for (int tick = 0; tick < 400 && !settled; tick++) {
            ClientModelManager.tick();
            settled = ClientModelManager.ensureGeometry(mainId);
            if (!settled) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    break;
                }
            }
        }
        assertTrue(settled, "the build must settle within a few seconds");
        assertTrue(ClientModelManager.isModelPublished(mainId), "the probe model must be published");
        return mainId;
    }

    @Test
    void isDefaultModelIdAnswersForEveryShapeOfTheDefaultModelsIds() {
        assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, Config.DEFAULT_MODEL_ID)));
        assertTrue(
            ClientModelManager
                .isDefaultModelId(new ResourceLocation(ysmu.MODID, Config.DEFAULT_MODEL_ID + "/main")));
        assertTrue(
            ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, Config.DEFAULT_MODEL_ID + "/arm")));

        assertFalse(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "wine_fox/main")));
        assertFalse(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "wine_fox")));
        assertFalse(
            ClientModelManager.isDefaultModelId(new ResourceLocation("minecraft", Config.DEFAULT_MODEL_ID + "/main")),
            "the domain is part of the test: only this mod's own default model is pinned");
        assertFalse(ClientModelManager.isDefaultModelId(null));
    }

    /**
     * The port-specific gate. Only a model whose payload the port can re-read from the encrypted
     * client cache may be adopted by the eviction — the reference has one reload path for both sync
     * formats ({@code ClientModelManager.java:1484-1508}), this port only has the OpenYSM half.
     */
    @Test
    void hasReloadableClientCacheNeedsARegisteredOpenYsmCacheEntry() {
        ResourceLocation model = new ResourceLocation("ysmu", "reloadable_probe");
        assertFalse(ClientModelManager.hasReloadableClientCache(new ResourceLocation("ysmu", "reloadable_probe/main")));

        ClientModelManager.rememberOpenYsmModelCache(model, "0123456789abcdef0123456789abcdef");

        assertTrue(ClientModelManager.hasReloadableClientCache(new ResourceLocation("ysmu", "reloadable_probe")));
        assertTrue(ClientModelManager.hasReloadableClientCache(new ResourceLocation("ysmu", "reloadable_probe/main")));
        assertTrue(ClientModelManager.hasReloadableClientCache(new ResourceLocation("ysmu", "reloadable_probe/arm")));
        assertFalse(ClientModelManager.hasReloadableClientCache(null));
    }

    /**
     * A sub-model id whose model has no reload path yields no geometry. This is the branch that keeps
     * the whole design safe: a geometry the eviction cannot restore is never adopted (see
     * {@code GeoModelProvider}/{@code AssetManager.registerGeo} in this port).
     */
    @Test
    void parseSingleGeoFromCacheYieldsNothingWithoutAReloadPath() {
        assertNull(ClientModelManager.parseSingleGeoFromCache(null));
        assertNull(
            ClientModelManager.parseSingleGeoFromCache(new ResourceLocation("ysmu", "never_synced/main")),
            "a model with no registered encrypted cache entry has no geometry to reload");
    }

    @Test
    void idleEvictionSuppressionTracksTheGuiAndTheGraceWindow() {
        Config.GUI_POST_CLOSE_GRACE_SECONDS = 0;
        ClientModelManager.setGuiOpen(false);
        assertFalse(ClientModelManager.isIdleEvictionSuppressed(), "0 seconds means the window is disabled");

        ClientModelManager.setGuiOpen(true);
        assertTrue(ClientModelManager.isIdleEvictionSuppressed(), "an open preview GUI always suppresses eviction");

        Config.GUI_POST_CLOSE_GRACE_SECONDS = 120;
        ClientModelManager.setGuiOpen(false);
        assertTrue(
            ClientModelManager.isIdleEvictionSuppressed(),
            "closing the preview starts the grace window (reference ClientModelManager.java:198-214)");
    }

    /**
     * The wiring itself, through the real publish path: a geometry is adopted only when the switch is on
     * <em>and</em> the model's payload can be re-read, and never otherwise.
     * <p>
     * The mid case is the port-specific safety gate: with the switch on but no encrypted-cache entry for
     * the model, adopting its geometry would mean evicting it into a permanent fallback to the built-in
     * default, because this port has no legacy reload path. The first case is the switch's contract at the
     * call site: off means the publish registers nothing.
     */
    @Test
    void aPublishedGeometryIsAdoptedOnlyWhenTheSwitchIsOnAndThePayloadIsReloadable() {
        int before = AssetManager.geoSize();

        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        publishProbeModel(WIRED_OFF);
        assertEquals(before, AssetManager.geoSize(), "with the switch off the publish must not adopt anything");

        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        publishProbeModel(WIRED_UNRELOADABLE);
        assertEquals(
            before,
            AssetManager.geoSize(),
            "a model with no reloadable cache entry must not be adopted - the eviction could not restore it");

        ClientModelManager.rememberOpenYsmModelCache(WIRED_ON, "0123456789abcdef0123456789abcdef");
        ResourceLocation wiredMain = publishProbeModel(WIRED_ON);
        assertEquals(before + 1, AssetManager.geoSize(), "a published, reloadable geometry is adopted");
        assertFalse(
            AssetManager.geo(wiredMain)
                .isPending(),
            "and the adopted entry is READY, so a later get() returns it instead of reloading");
    }
}
