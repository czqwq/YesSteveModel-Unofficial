package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

    /** The animation half's own ids; see {@link #installAnimationProbe}. */
    private static final ResourceLocation ANIMATIONS_OFF = new ResourceLocation(ysmu.MODID, "eviction_anim_off");
    private static final ResourceLocation ANIMATIONS_ON = new ResourceLocation(ysmu.MODID, "eviction_anim_on");
    private static final ResourceLocation ANIMATIONS_UNRELOADABLE =
        new ResourceLocation(ysmu.MODID, "eviction_anim_unreloadable");
    private static final ResourceLocation ANIMATIONS_NEVER_ADOPTED =
        new ResourceLocation(ysmu.MODID, "eviction_anim_never_adopted");

    /**
     * One animation set with no keyframes, which is enough for the payload to count as non-empty and be parked; the
     * parse of it is `ClientModelManagerAnimationInstallTest`'s subject, not this class's.
     */
    private static final String ANIMATION_JSON = "{\"format_version\":\"1.8.0\",\"animations\":"
        + "{\"probe_idle\":{\"loop\":true,\"animation_length\":1.0,\"bones\":{}}}}";

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
     * The animation probes' own teardown.
     * <p>
     * {@link AssetManager#clearAll} while the switch is on is what releases their adopted entries (and clears the
     * adoption record with them); the engine's animation cache is dropped by hand as well, because an install writes
     * one file per domain under the model's {@code main} and {@code arm} ids and neither may be left behind for
     * another test class in this JVM.
     */
    @AfterEach
    void releaseAdoptedAnimationProbes() {
        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        AssetManager.clearAll();
        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        for (ResourceLocation model : new ResourceLocation[] { ANIMATIONS_OFF, ANIMATIONS_ON, ANIMATIONS_UNRELOADABLE,
            ANIMATIONS_NEVER_ADOPTED }) {
            ClientModelManager.MODELS.remove(model);
            GeckoLibCache.getInstance()
                .getAnimations()
                .remove(ModelIdUtil.getMainId(model));
            GeckoLibCache.getInstance()
                .getAnimations()
                .remove(ModelIdUtil.getArmId(model));
        }
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

    /**
     * The pinned model is the <b>built-in fallback</b>, by its literal id - not whatever
     * {@code Config.DEFAULT_MODEL_ID} says. Upstream's rule is the same literal check
     * ({@code ClientModelRenderTargetManager#isDefault:620-624}) and pins exactly those leases for the process;
     * its configurable default is the <i>selection</i> default, an ordinary catalog entry. The two coincide only
     * while nobody changes the config, which is why the case below moves the config out of the way.
     */
    @Test
    void isDefaultModelIdAnswersForEveryShapeOfTheDefaultModelsIds() {
        String configured = Config.DEFAULT_MODEL_ID;
        try {
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, "default")));
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, "default/main")));
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, "default/arm")));

            assertFalse(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "wine_fox/main")));
            assertFalse(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "wine_fox")));
            assertFalse(
                ClientModelManager.isDefaultModelId(new ResourceLocation("minecraft", "default/main")),
                "the domain is part of the test: only this mod's own default model is pinned");
            assertFalse(ClientModelManager.isDefaultModelId(null));

            // A renamed selection default is a different model; the fallback the renderer draws
            // (CustomPlayerModel.DEFAULT_MAIN_MODEL) must not lose its pin because of it.
            Config.DEFAULT_MODEL_ID = "wine_fox";
            assertTrue(
                ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, "default/main")),
                "the built-in fallback stays pinned");
            assertFalse(
                ClientModelManager.isDefaultModelId(new ResourceLocation(ysmu.MODID, "wine_fox/main")),
                "the configured selection default is an ordinary model, as it is upstream");
        } finally {
            Config.DEFAULT_MODEL_ID = configured;
        }
    }

    /**
     * {@link ClientModelManager#hasModelContent} answers "did this connection receive a payload for this model",
     * which is the question that decides between keeping the model an entity is already shown with and falling back
     * to the built-in default - upstream's catalog lookup ({@code ClientCatalogSnapshot#find}: empty for an id the
     * client never received). A parked payload has content but is not published; a model this client never got has
     * neither. Both id shapes are accepted, because the renderer passes a main id while the first-person arm passes
     * the model's own.
     */
    @Test
    void hasModelContentAnswersForRegisteredPayloadsWhateverTheirBuildState() {
        ResourceLocation model = new ResourceLocation("ysmu", "content_probe");
        ResourceLocation mainId = ModelIdUtil.getMainId(model);
        assertFalse(ClientModelManager.hasModelContent(null));
        assertFalse(ClientModelManager.hasModelContent(model));
        assertFalse(ClientModelManager.hasModelContent(mainId));

        // The registration index is the texture list, so indexing the model is what "has content" means; writing it
        // directly keeps this a predicate test with no parked payload left behind for another test class in this JVM.
        ClientModelManager.MODELS.put(model, new java.util.ArrayList<>());
        try {
            assertTrue(ClientModelManager.hasModelContent(model), "the registered payload is content");
            assertTrue(ClientModelManager.hasModelContent(mainId), "and a sub-model id answers for its model");
            assertFalse(
                ClientModelManager.isModelPublished(mainId),
                "content is what the connection received, not what it has built");
        } finally {
            ClientModelManager.MODELS.remove(model);
        }
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

    /** A payload with one animation set and no geometry; see {@link #ANIMATION_JSON}. */
    private static ModelData animationPayload(ResourceLocation model) {
        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main.animation.json", ANIMATION_JSON.getBytes(StandardCharsets.UTF_8));
        return new ModelData(
            model.getResourcePath(),
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            animations);
    }

    /**
     * Registers a probe payload through the real path and installs its animations the way the engine does.
     * <p>
     * The install is the choke point {@code CustomPlayerModel.getAnimation} drives
     * ({@code ClientModelManager.ensureAnimations}), so calling it here is the engine's own first request for one of
     * this model's animations - which is when the port parses the parked payload and installs the per-domain files.
     *
     * @return its {@code main} id
     */
    private static ResourceLocation installAnimationProbe(ResourceLocation model) {
        ClientModelManager.registerAll(animationPayload(model), false);
        ResourceLocation mainId = ModelIdUtil.getMainId(model);
        ClientModelManager.ensureAnimations(mainId);
        return mainId;
    }

    /**
     * The animation half of the wiring test above, through the same real path: the install adopts the payload for
     * eviction only when the switch is on <em>and</em> the payload can be re-read, and the install really did put the
     * model's own per-domain files where the engine reads them.
     * <p>
     * The mid case is the port-specific safety gate again: this port has no legacy reload path, so a model whose
     * payload cannot be re-read must not be adopted - the eviction could never put its clips back.
     */
    @Test
    void aModelsAnimationsAreAdoptedOnlyWhenTheSwitchIsOnAndThePayloadIsReloadable() {
        int before = AssetManager.animSize();

        Config.ENABLE_IDLE_ASSET_EVICTION = false;
        installAnimationProbe(ANIMATIONS_OFF);
        assertEquals(before, AssetManager.animSize(), "with the switch off the install must not adopt anything");
        assertFalse(AssetManager.managesAnimations(ModelIdUtil.getMainId(ANIMATIONS_OFF)));

        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        ClientModelManager.rememberOpenYsmModelCache(ANIMATIONS_ON, "0123456789abcdef0123456789abcdef");
        ResourceLocation wiredMain = installAnimationProbe(ANIMATIONS_ON);
        assertEquals(before + 1, AssetManager.animSize(), "an installed, reloadable animation payload is adopted");
        assertTrue(AssetManager.managesAnimations(wiredMain));
        assertNotNull(
            GeckoLibCache.getInstance()
                .getAnimations()
                .get(wiredMain),
            "the install put the model's own file under its main id");
        assertNotNull(
            GeckoLibCache.getInstance()
                .getAnimations()
                .get(ModelIdUtil.getArmId(ANIMATIONS_ON)),
            "and the first-person domain's file under its arm id");

        installAnimationProbe(ANIMATIONS_UNRELOADABLE);
        assertEquals(
            before + 1,
            AssetManager.animSize(),
            "a model with no reloadable cache entry must not be adopted - the eviction could not restore it");
    }

    /**
     * The adoption record is what keeps the animation seam from starting a reload for a model this subsystem never
     * took over.
     * <p>
     * This is the case the geometry seam does not have: a model that declares no animations of its own has no file in
     * the engine cache, which is exactly the state an eviction leaves behind, so "the cache has no file" alone would
     * make every such model decrypt its whole payload to install clips it already falls back to. The assertion that
     * proves nothing was asked for is the entry count: {@code AssetCache.get} on an absent key registers an entry
     * (through {@code computeIfAbsent}) before it submits the load, so an unchanged count means no load was started -
     * and no background thread was handed {@code Minecraft.func_152344_a}, which a unit JVM does not have.
     */
    @Test
    void anAnimationLookupForAModelTheSubsystemNeverAdoptedStartsNoReload() {
        Config.ENABLE_IDLE_ASSET_EVICTION = true;
        ClientModelManager.rememberOpenYsmModelCache(ANIMATIONS_NEVER_ADOPTED, "0123456789abcdef0123456789abcdef");
        ResourceLocation mainId = ModelIdUtil.getMainId(ANIMATIONS_NEVER_ADOPTED);
        int before = AssetManager.animSize();
        assertTrue(ClientModelManager.hasReloadableClientCache(mainId), "the model is reloadable...");

        // The model declares no animations of its own, so its normal state is the built-in default fallback and the
        // engine's cache holds no file for it. Nothing was ever installed, so nothing was ever adopted.
        ClientModelManager.ensureAnimations(mainId);

        assertEquals(before, AssetManager.animSize(), "nothing may be registered for a model this subsystem never adopted");
        assertFalse(AssetManager.managesAnimations(mainId));
        assertNull(
            GeckoLibCache.getInstance()
                .getAnimations()
                .get(mainId),
            "... and no reload may have been started for it");
    }
}
