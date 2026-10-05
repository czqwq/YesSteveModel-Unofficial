package com.fox.ysmu.client.asset;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.provider.AnimationProvider;
import com.fox.ysmu.client.asset.provider.GeoModelProvider;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.file.AnimationFile;
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

    // ── The animation half ────────────────────────────────────────────────
    // The reference's ANIM cache is keyed by the model's mainId and holds the merged AnimationFile its provider
    // rebuilds (reference AssetManager.java:43-44, :54-56). This port's install produces one file per domain, so the
    // cached value is the animation map that install consumes and the provider replays the install rule - see
    // .agent/phase40-eviction-members.md. What the facade's own tests can pin is therefore the same set of properties
    // the geometry half is pinned on: switch-off inertness, the idle rule, the default pin, and the provider's
    // release/weight contract.

    /** Mirrors the geometry tests' helper: one animation file is enough for the map to count as non-empty. */
    private static Map<String, byte[]> animationMap(String name) {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main.animation.json", ("{\"animations\":{\"" + name + "\":{}}}").getBytes(StandardCharsets.UTF_8));
        return animations;
    }

    private static ResourceLocation armIdOf(ResourceLocation mainId) {
        return ModelIdUtil.getArmId(ModelIdUtil.getParentModelId(mainId));
    }

    private static Map<ResourceLocation, AnimationFile> engineAnimations() {
        return GeckoLibCache.getInstance()
            .getAnimations();
    }

    /**
     * The animation half of {@link #switchOffRegistersAndReleasesNothing}: off means the ANIM cache is as inert as
     * the geometry one, including {@link AssetManager#registerAnim}, which must not adopt an entry a later
     * {@code get()} would start a load for.
     */
    @Test
    void switchOffRegistersAndReleasesNoAnimations() {
        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        ResourceLocation mainId = new ResourceLocation("ysmu", "anim_switchoff/main");
        ResourceLocation armId = armIdOf(mainId);
        engineAnimations().put(mainId, new AnimationFile());
        engineAnimations().put(armId, new AnimationFile());
        try {
            AssetManager.registerAnim(mainId, animationMap("anim_switchoff"));
            assertEquals(0, AssetManager.animSize(), "with the switch off nothing may be registered");
            assertFalse(AssetManager.managesAnimations(mainId), "and nothing may be adopted for eviction");

            AssetManager.tick(System.currentTimeMillis() + 10L * IDLE_UNLOAD_MS);
            assertNotNull(
                engineAnimations().get(mainId),
                "with the switch off no animation may be released");

            AssetManager.clearAll();
            assertNotNull(engineAnimations().get(mainId), "with the switch off clearAll must not release anything");

            AssetManager.release(mainId);
            assertNotNull(engineAnimations().get(mainId), "with the switch off release() must not release anything");
        } finally {
            engineAnimations().remove(mainId);
            engineAnimations().remove(armId);
        }
    }

    /**
     * An animation payload idle past the window is released, both per-domain files leave the engine cache (only
     * releasing the main one would leave the first-person arm's file behind and make this half of the release a
     * no-op), and the facade still remembers the model so the seam can ask for it back.
     */
    @Test
    void idleAnimationPastTheWindowIsReleasedFromBothDomainsAndLeavesTheEngineCache() {
        ResourceLocation mainId = new ResourceLocation("ysmu", "anim_idle/main");
        ResourceLocation armId = armIdOf(mainId);
        AnimationFile mainFile = new AnimationFile();
        AnimationFile armFile = new AnimationFile();
        engineAnimations().put(mainId, mainFile);
        engineAnimations().put(armId, armFile);
        AssetManager.registerAnim(mainId, animationMap("anim_idle"));
        assertEquals(1, AssetManager.animSize());

        AssetManager.tick(System.currentTimeMillis() + IDLE_UNLOAD_MS + 1L);

        assertEquals(0, AssetManager.animSize(), "an animation payload idle past the window is released");
        assertNull(
            engineAnimations().get(mainId),
            "AnimationProvider.release must remove the model's own domain file from GeckoLibCache");
        assertNull(
            engineAnimations().get(armId),
            "and the first-person domain file with it - the two are one cache entry here");
        assertTrue(
            AssetManager.anim(mainId)
                .isPending(),
            "after the release the handle is no longer READY - a later get() would reload it");
        assertTrue(
            AssetManager.managesAnimations(mainId),
            "and the adoption record survives the eviction: that is what lets the seam tell an evicted model apart "
                + "from one that never had animations of its own");
    }

    /**
     * The built-in fallback model's animations must never be collected either - its payload is read from
     * {@code builtin/}, never from the encrypted client cache, so a release could not be undone (reference
     * {@code AssetManager.java:81-82}). This is the animation half of
     * {@link #theDefaultModelIsPinnedAgainstIdleEviction}.
     */
    @Test
    void theDefaultModelsAnimationsArePinnedAgainstIdleEviction() {
        ResourceLocation defaultMain = ModelIdUtil.getMainId(new ResourceLocation(ysmu.MODID, Config.DEFAULT_MODEL_ID));
        ResourceLocation otherMain = new ResourceLocation("ysmu", "anim_pinned_other/main");
        engineAnimations().put(defaultMain, new AnimationFile());
        engineAnimations().put(otherMain, new AnimationFile());
        AssetManager.registerAnim(defaultMain, animationMap("default"));
        AssetManager.registerAnim(otherMain, animationMap("anim_pinned_other"));
        assertEquals(2, AssetManager.animSize());

        AssetManager.tick(System.currentTimeMillis() + IDLE_UNLOAD_MS + 1L);

        assertNotNull(
            engineAnimations().get(defaultMain),
            "the built-in default model's animations must survive the idle scan");
        assertNull(
            engineAnimations().get(otherMain),
            "and the scan must still collect an ordinary idle model in the same pass");
    }

    /**
     * The provider half of the animation cache: {@code DROP_HEAP} removes both of the model's per-domain files (the
     * reference has one merged file under the mainId, so its provider removes one - reference
     * {@code provider/AnimationProvider.java:37-43}), while {@code GPU_ONLY} leaves the heap entry alone; and the
     * weight is the cached animation map's own byte count, which is the port's substitute for the reference's clip
     * count because the cached value is the install rule's input (see {@link AnimationProvider}).
     */
    @Test
    void animationProviderReleaseRemovesBothDomainsAndHonoursGpuOnly() {
        AnimationProvider provider = new AnimationProvider();
        ResourceLocation mainId = new ResourceLocation("ysmu", "anim_provider/main");
        ResourceLocation armId = armIdOf(mainId);
        AnimationFile mainFile = new AnimationFile();
        AnimationFile armFile = new AnimationFile();
        engineAnimations().put(mainId, mainFile);
        engineAnimations().put(armId, armFile);
        Map<String, byte[]> animations = animationMap("anim_provider");

        provider.release(mainId, animations, ReleaseMode.GPU_ONLY);
        assertSame(mainFile, engineAnimations().get(mainId), "GPU_ONLY must keep the heap entry");
        assertSame(armFile, engineAnimations().get(armId), "GPU_ONLY must keep the first-person domain entry as well");

        provider.release(mainId, animations, ReleaseMode.DROP_HEAP);
        assertNull(engineAnimations().get(mainId), "DROP_HEAP must remove the model's own domain file");
        assertNull(engineAnimations().get(armId), "DROP_HEAP must remove the first-person domain file too");

        assertEquals(ReleaseMode.DROP_HEAP, provider.defaultReleaseMode(), "animation eviction drops the heap");
        assertEquals(0L, provider.weight(mainId, null), "a missing animation map weighs nothing");
        assertEquals(
            animations.values()
                .iterator()
                .next().length,
            provider.weight(mainId, animations),
            "the animation map's own bytes are its weight");
    }
}
