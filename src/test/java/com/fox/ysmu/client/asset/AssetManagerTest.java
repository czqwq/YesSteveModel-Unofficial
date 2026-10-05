package com.fox.ysmu.client.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.provider.GeoModelProvider;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The {@link AssetManager} facade on a synthetic clock: no Minecraft, no GL.
 *
 * <p>What is pinned here is the whole point of the subsystem's port-specific switch — with
 * {@link Config#ENABLE_IDLE_ASSET_EVICTION} off, nothing is registered, nothing is released and the
 * engine's cache is untouched; with it on, an entry idle past the window is released through
 * {@link GeoModelProvider}, removed from {@code GeckoLibCache}, the built-in default is never adopted
 * for eviction, and an open preview GUI suppresses the scan.
 *
 * <p>Not pinned here, because it needs a live client: the reload half
 * ({@link ClientModelManager#parseSingleGeoFromCache} against a real encrypted client cache and the
 * {@code Minecraft.func_152344_a} hop in {@link AssetCache#get}). See
 * {@code .agent/phase32-asset-eviction-subsystem.md}.
 */
class AssetManagerTest {

    /** Mirrors {@code AssetManager.IDLE_UNLOAD_MS}, which is private. */
    private static final long IDLE_UNLOAD_MS = 30_000L;

    @BeforeEach
    void enableTheSubsystemWithoutTheGraceWindow() {
        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        // Drop anything another test class left registered, so this class's entry counts are absolute.
        AssetManager.clearAll();
        // The grace window is wall-clock driven and has no injectable clock (the reference has none
        // either), so it is disabled here and its semantics are pinned deterministically in
        // ClientModelManagerEvictionSupportTest.
        Config.GUI_POST_CLOSE_GRACE_SECONDS = 0;
    }

    @AfterEach
    void restoreTheShippedDefaults() {
        // Release whatever this test adopted, while the switch is still on, so no entry leaks into
        // another test class in the same JVM.
        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        AssetManager.clearAll();
        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        Config.GUI_POST_CLOSE_GRACE_SECONDS = 120;
        ClientModelManager.setGuiOpen(false);
    }

    /**
     * The switch's contract, and the reason it exists: off means the subsystem is completely inert.
     * Note that this is not merely "no eviction" — {@link AssetManager#registerGeo} must not adopt
     * anything either, because an adopted entry is what {@link AssetManager#geo} would later start a
     * background load for.
     */
    @Test
    void switchOffRegistersAndReleasesNothing() {
        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        ResourceLocation id = new ResourceLocation("ysmu", "switchoff/main");
        GeoModel geo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(id, geo);

        AssetManager.registerGeo(id, geo);
        assertEquals(0, AssetManager.geoSize(), "with the switch off nothing may be registered");

        AssetManager.tick();
        AssetManager.tick(System.currentTimeMillis() + 10L * IDLE_UNLOAD_MS);
        assertSame(
            geo,
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "with the switch off no asset may be released");

        AssetManager.clearAll();
        assertSame(
            geo,
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "with the switch off clearAll must not release anything");

        AssetManager.release(id);
        assertSame(
            geo,
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "with the switch off release() must not release anything");
    }

    /** An entry idle past the window is released, and the engine's cache no longer hands it out. */
    @Test
    void idlePastTheWindowIsReleasedAndLeavesTheEngineCache() {
        ResourceLocation id = new ResourceLocation("ysmu", "idle/main");
        GeoModel geo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(id, geo);
        AssetManager.registerGeo(id, geo);
        assertEquals(1, AssetManager.geoSize());

        AssetManager.tick(System.currentTimeMillis() + IDLE_UNLOAD_MS + 1L);

        assertEquals(0, AssetManager.geoSize(), "an entry idle past the window is released");
        assertNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "publishGeo's counterpart, release(), must remove the geometry from GeckoLibCache");
        assertTrue(
            AssetManager.geo(id)
                .isPending(),
            "after the release the handle is no longer READY - a later get() would reload it");
    }

    /**
     * A model touched within the window is not collected: this is the anti-eviction half of the safety
     * property, driven through the same {@link AssetHandle#touch()} the render path uses. The window is
     * 1 s here so the assertion does not depend on a long wall-clock wait.
     */
    @Test
    void anAssetTouchedWithinTheWindowIsNotCollected() {
        ResourceLocation id = new ResourceLocation("ysmu", "touched/main");
        GeoModel geo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(id, geo);
        AssetManager.registerGeo(id, geo);

        AssetManager.geo(id)
            .touch();
        // Same instant as the touch's wall-clock stamp: 0 ms idle, so the entry survives.
        AssetManager.tick(System.currentTimeMillis());

        assertEquals(1, AssetManager.geoSize(), "a touched entry stays resident");
        assertNotNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "a touched entry must still be in the engine cache");
    }

    /**
     * The built-in fallback model must never be collected: it has no encrypted client cache to
     * reload from, so releasing its geometry would leave every fallback draw without a model
     * (reference {@code AssetManager.java:81-82}).
     */
    @Test
    void theDefaultModelIsPinnedAgainstIdleEviction() {
        ResourceLocation defaultMain = ModelIdUtil.getMainId(new ResourceLocation(ysmu.MODID, Config.DEFAULT_MODEL_ID));
        ResourceLocation other = new ResourceLocation("ysmu", "pinned_other/main");
        GeoModel defaultGeo = new GeoModel();
        GeoModel otherGeo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(defaultMain, defaultGeo);
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(other, otherGeo);
        AssetManager.registerGeo(defaultMain, defaultGeo);
        AssetManager.registerGeo(other, otherGeo);
        assertEquals(2, AssetManager.geoSize());

        AssetManager.tick(System.currentTimeMillis() + IDLE_UNLOAD_MS + 1L);

        assertNotNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(defaultMain),
            "the built-in default model must survive the idle scan");
        assertNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(other),
            "and the scan must still collect an ordinary idle model in the same pass");
    }

    /** An open preview GUI suppresses the whole scan, and closing it (grace disabled) lets the scan run. */
    @Test
    void anOpenPreviewGuiSuppressesTheEvictionScan() {
        ResourceLocation id = new ResourceLocation("ysmu", "gui/main");
        GeoModel geo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(id, geo);
        AssetManager.registerGeo(id, geo);

        ClientModelManager.setGuiOpen(true);
        AssetManager.tick(System.currentTimeMillis() + 2L * IDLE_UNLOAD_MS);
        assertNotNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "while the preview GUI is open nothing may be released");

        ClientModelManager.setGuiOpen(false);
        AssetManager.tick(System.currentTimeMillis() + 3L * IDLE_UNLOAD_MS);
        assertNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "once the GUI is closed and the grace window is disabled the scan collects it");
    }

    /**
     * The provider half: {@code DROP_HEAP} removes the geometry from the engine's cache and hands the
     * released object to the model instance (through
     * {@link CustomPlayerModel#onGeoModelReleased}, whose client-renderer lookup is empty in a unit
     * JVM and must simply do nothing), while {@code GPU_ONLY} leaves the heap entry alone.
     */
    @Test
    void heapReleaseRemovesTheEngineCacheEntryAndGpuOnlyDoesNot() {
        GeoModelProvider provider = new GeoModelProvider();
        ResourceLocation id = new ResourceLocation("ysmu", "provider/main");
        GeoModel geo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(id, geo);

        provider.release(id, geo, ReleaseMode.GPU_ONLY);
        assertSame(
            geo,
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "GPU_ONLY must keep the heap entry");

        provider.release(id, geo, ReleaseMode.DROP_HEAP);
        assertNull(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .get(id),
            "DROP_HEAP must remove the heap entry");
    }

    /**
     * The engine hook itself: {@code AnimatedGeoModel#onModelReleased} is what
     * {@link CustomPlayerModel#onGeoModelReleased} forwards to, and dropping {@code currentModel} is
     * the part that actually lets the released geometry be collected.
     */
    @Test
    void theEngineReleaseHookDropsTheModelInstanceReference() {
        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        CustomPlayerModel model = new CustomPlayerModel();
        ResourceLocation id = new ResourceLocation("ysmu", "hook/main");
        GeoModel geo = new GeoModel();
        GeckoLibCache.getInstance()
            .getGeoModels()
            .put(id, geo);

        assertSame(geo, model.getModel(id), "the model instance picks up the geometry it is given");
        assertSame(geo, model.getCurrentModel());

        model.onModelReleased(geo);

        assertNull(model.getCurrentModel(), "onModelReleased must drop the strong reference to the released geometry");
    }
}
