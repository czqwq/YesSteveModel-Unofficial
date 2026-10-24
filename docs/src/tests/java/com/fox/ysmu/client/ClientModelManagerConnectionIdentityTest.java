package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The two guards that decide whether a finished build may be installed at all: the connection it was queued on, and
 * the payload it was made from.
 * <p>
 * Neither had any coverage. They are the part of the design that keeps an old connection's bytes from becoming the new
 * connection's model - and that failure is invisible: the model simply never appears while the log looks healthy,
 * because the id counts as ready and is therefore never rebuilt.
 * <p>
 * The geometry here is a minimal Bedrock document written into the test, not a borrowed pack file, so these tests run
 * on a fresh clone and on CI. The pack-based tests next door still cover the real parser on a real model; these cover
 * the guards.
 */
class ClientModelManagerConnectionIdentityTest {

    /** One bone, one cube: enough for the engine to build a model, small enough to inline. */
    private static final String GEOMETRY = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.probe\",\"texture_width\":64,\"texture_height\":64,"
        + "\"visible_bounds_width\":1,\"visible_bounds_height\":1,\"visible_bounds_offset\":[0,0,0]},"
        + "\"bones\":[{\"name\":\"body\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static final ResourceLocation STALE_CONNECTION_MODEL =
        new ResourceLocation(ysmu.MODID, "stale_connection_probe");
    private static final ResourceLocation SUPERSEDED_MODEL = new ResourceLocation(ysmu.MODID, "superseded_probe");
    private static final ResourceLocation REFUSED_MODEL = new ResourceLocation(ysmu.MODID, "refused_probe");
    private static final ResourceLocation BUDGET_MODEL = new ResourceLocation(ysmu.MODID, "budget_probe");

    /**
     * The per-tick publish budget, written out here on purpose: the point of the test is to notice when the value
     * changes, because every tick installs textures and fills the tables the renderer reads.
     */
    private static final int EXPECTED_MAX_PUBLISH_PER_TICK = 4;

    private static ResourceLocation[] allIds() {
        return new ResourceLocation[] { STALE_CONNECTION_MODEL, SUPERSEDED_MODEL, REFUSED_MODEL, BUDGET_MODEL };
    }

    @AfterEach
    void release() {
        for (ResourceLocation modelId : allIds()) {
            releaseModel(modelId);
        }
        // The two multi-model tests register siblings under their own id spaces.
        for (int index = 0; index < 24; index++) {
            releaseModel(budgetModel(index));
            releaseModel(retryModel(index));
        }
    }

    /**
     * Removes everything a publish writes for one model. The engine's geometry is only part of it: {@code publishGeo}
     * also fills SCALE_INFO, RENDER_LAYERS_FIRST, EXTRA_INFO and EXTRA_ANIMATION_NAME, and since the marker sets have
     * no public reset, a test that leaves them behind makes a later test's result depend on this one.
     */
    private static void releaseModel(ResourceLocation modelId) {
        for (ResourceLocation id : new ResourceLocation[] { modelId, ModelIdUtil.getMainId(modelId),
            ModelIdUtil.getArmId(modelId) }) {
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(id);
            ClientModelManager.SCALE_INFO.remove(id);
            ClientModelManager.RENDER_LAYERS_FIRST.remove(id);
            ClientModelManager.EXTRA_INFO.remove(id);
            ClientModelManager.EXTRA_ANIMATION_NAME.remove(id);
        }
        ClientModelManager.MODELS.remove(modelId);
    }

    /** A separate id space for the retry test, so it cannot leave published markers on the budget test's ids. */
    private static ResourceLocation retryModel(int index) {
        return new ResourceLocation(ysmu.MODID, "retry_probe_" + index);
    }

    private static ResourceLocation budgetModel(int index) {
        return new ResourceLocation(ysmu.MODID, "budget_probe_" + index);
    }

    private static ModelData payload(String name, Map<String, byte[]> geometry) {
        return new ModelData(name, Type.FOLDER, geometry, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private static Map<String, byte[]> geometryOf(String... files) {
        Map<String, byte[]> geometry = new LinkedHashMap<>();
        for (String file : files) {
            geometry.put(file, GEOMETRY.getBytes(StandardCharsets.UTF_8));
        }
        return geometry;
    }

    /** Pumps the publish loop for a while; returns whether the model became published, rather than skipping. */
    private static boolean waitUntilPublished(ResourceLocation modelId, int ticks) throws InterruptedException {
        for (int tick = 0; tick < ticks; tick++) {
            ClientModelManager.tick();
            if (ClientModelManager.isModelPublished(ModelIdUtil.getMainId(modelId))) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    /**
     * A build queued on a connection that has since ended must not be installed on the next one, even when the next
     * connection re-sends the same model id - which is the only situation in which the parking check cannot tell the
     * two apart, and the reason the build carries a generation at all.
     */
    @Test
    void aBuildQueuedBeforeTheConnectionEndedIsNotPublishedOntoTheNextOne() throws Exception {
        ClientModelManager.registerAll(payload(STALE_CONNECTION_MODEL.getResourcePath(), geometryOf("main")), false);
        assertFalse(
            ClientModelManager.ensureGeometry(STALE_CONNECTION_MODEL),
            "the first request queues the build rather than doing it");

        // The connection ends while the build is queued or in flight.
        ClientModelManager.clearConnectionState();

        // A later frame publishes whatever has finished. The payload is still parked and the request marker is still
        // set, so without the generation check this is exactly where the old connection's geometry would be installed.
        for (int tick = 0; tick < 40; tick++) {
            ClientModelManager.tick();
            Thread.sleep(5L);
        }

        assertFalse(
            ClientModelManager.isModelPublished(ModelIdUtil.getMainId(STALE_CONNECTION_MODEL)),
            "a build from the connection that just ended must not be published");
        assertFalse(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(ModelIdUtil.getMainId(STALE_CONNECTION_MODEL)),
            "and must leave the engine's cache untouched");
    }

    /**
     * A finished build whose payload has since been replaced must be dropped, and the replacement must still publish.
     * <p>
     * The two are told apart by which sub-model each payload declares: the first declares only {@code main}, the
     * replacement also declares {@code arm}. If the older build were installed it would mark the id ready and consume
     * the replacement's parking entry, so the replacement would never build and its arm would never appear.
     */
    @Test
    void aFinishedBuildWhosePayloadWasReplacedIsDroppedAndTheReplacementStillPublishes() throws Exception {
        ClientModelManager.registerAll(payload(SUPERSEDED_MODEL.getResourcePath(), geometryOf("main")), false);
        ClientModelManager.ensureGeometry(SUPERSEDED_MODEL);
        // Give the first build time to finish without publishing it, so it is ahead of the replacement in the queue.
        Thread.sleep(1500L);

        ClientModelManager.registerAll(payload(SUPERSEDED_MODEL.getResourcePath(), geometryOf("main", "arm")), false);
        ClientModelManager.ensureGeometry(SUPERSEDED_MODEL);

        assertTrue(
            waitUntilPublished(SUPERSEDED_MODEL, 400),
            "the replacement payload must publish; if the older build won, it consumed the replacement's entry");
        assertTrue(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(ModelIdUtil.getArmId(SUPERSEDED_MODEL)),
            "the arm is declared only by the replacement, so its presence is what proves the replacement published");
    }

    /**
     * A model refused for having no usable main geometry must be reported as not drawable even though the engine's
     * cache still holds the previous connection's geometry for it - and must recover when a working payload arrives
     * for the same id.
     */
    @Test
    void aRefusalOverridesGeometryLeftInTheCacheAndIsLiftedByANewPayload() throws Exception {
        // Published first, so the cache holds geometry for this id before the refusal happens.
        ClientModelManager.registerAll(payload(REFUSED_MODEL.getResourcePath(), geometryOf("main")), false);
        ClientModelManager.ensureGeometry(REFUSED_MODEL);
        assertTrue(waitUntilPublished(REFUSED_MODEL, 400), "the first payload must publish");

        // The replacement declares no usable layout, so its main geometry cannot be built.
        Map<String, byte[]> unusable = new LinkedHashMap<>();
        unusable.put("main", "{}".getBytes(StandardCharsets.UTF_8));
        ClientModelManager.registerAll(payload(REFUSED_MODEL.getResourcePath(), unusable), false);
        ClientModelManager.ensureGeometry(REFUSED_MODEL);

        boolean refused = false;
        for (int tick = 0; tick < 400 && !refused; tick++) {
            ClientModelManager.tick();
            refused = ClientModelManager.isGeometryFailed(ModelIdUtil.getMainId(REFUSED_MODEL));
            if (!refused) {
                Thread.sleep(5L);
            }
        }
        assertTrue(refused, "a payload whose main geometry cannot be built must be refused");

        assertTrue(
            GeckoLibCache.getInstance()
                .getGeoModels()
                .containsKey(ModelIdUtil.getMainId(REFUSED_MODEL)),
            "the previous geometry is still in the engine's cache; this is why the predicates must not consult it");

        // The predicates are marker-only, so "refused", "not published" and "not drawable" are deliberately the same
        // answer here. What is worth asserting is that the refusal is terminal and that nothing draws it - not that
        // three spellings of one bit agree, which they trivially do.
        assertFalse(
            ClientModelManager.isModelPublished(ModelIdUtil.getMainId(REFUSED_MODEL)),
            "a refused model must not report itself published, so nothing binds its texture or draws it");
        assertTrue(
            ClientModelManager.ensureGeometry(ModelIdUtil.getMainId(REFUSED_MODEL)),
            "and no later frame may ask for it again: a refusal is terminal for the connection");
        for (int tick = 0; tick < 20; tick++) {
            ClientModelManager.tick();
            Thread.sleep(5L);
        }
        assertFalse(
            ClientModelManager.isModelPublished(ModelIdUtil.getMainId(REFUSED_MODEL)),
            "so it must still not publish after further ticks");

        CustomPlayerEntity entity = new CustomPlayerEntity();
        entity.setMainModel(ModelIdUtil.getMainId(REFUSED_MODEL));
        entity.setTexture(new ResourceLocation(ysmu.MODID, "refused_probe/skin.png"));
        assertTrue(
            CustomPlayerModel.DEFAULT_TEXTURE.equals(new CustomPlayerModel().getTextureLocation(entity)),
            "and no texture of a refused model may be bound");

        // A fresh payload for the same id must be able to recover: parking drops the refusal.
        ClientModelManager.registerAll(payload(REFUSED_MODEL.getResourcePath(), geometryOf("main")), false);
        assertFalse(
            ClientModelManager.isGeometryFailed(ModelIdUtil.getMainId(REFUSED_MODEL)),
            "a new payload is a fresh attempt, so the refusal must not outlive the bytes it was about");
        ClientModelManager.ensureGeometry(REFUSED_MODEL);
        assertTrue(waitUntilPublished(REFUSED_MODEL, 400), "and it must be buildable again");
    }

    /**
     * Publishing is budgeted per tick, because installing a model uploads its textures and fills the tables the
     * renderer reads - doing the whole backlog in one tick is the join hitch this design exists to remove. Nothing
     * pinned the budget before, so reverting the loop to a full drain would have kept the suite green.
     */
    @Test
    void thePerTickBudgetInstallsExactlyFourAndDelaysTheRest() throws Exception {
        // Drain anything another test left in the publish queue first. That queue is process-wide and a sibling test
        // deliberately queues a build it never publishes, so without this the single tick below could spend a slot on
        // a leftover and the count would be short for a reason that has nothing to do with the budget.
        for (int drain = 0; drain < 8; drain++) {
            ClientModelManager.tick();
        }
        // Eight models, so that more than the budget is certainly ready when the single tick runs. Six was not enough
        // to make the assertion mean anything: with six, "at most four" can hold because fewer than four were ready,
        // and the test passes whether or not the budget exists.
        for (int index = 0; index < 8; index++) {
            ResourceLocation modelId = budgetModel(index);
            ClientModelManager.registerAll(payload(modelId.getResourcePath(), geometryOf("main")), false);
            ClientModelManager.ensureGeometry(modelId);
        }
        // These are the inline models, so a generous wait is more than every build needs. If the loader is slow the
        // assertion below fails rather than passing vacuously, which is the direction that matters.
        Thread.sleep(3000L);

        ClientModelManager.tick();
        int publishedAfterOneTick = 0;
        for (int index = 0; index < 8; index++) {
            if (ClientModelManager.isModelPublished(ModelIdUtil.getMainId(budgetModel(index)))) {
                publishedAfterOneTick++;
            }
        }
        assertEquals(
            EXPECTED_MAX_PUBLISH_PER_TICK,
            publishedAfterOneTick,
            "one tick must install exactly the budget: fewer means the loader was not ready, more means the loop "
                + "drains the whole queue and the join hitch this design exists to remove comes back");

        // And the rest follow on later ticks, so the budget delays rather than drops.
        for (int tick = 0; tick < 60; tick++) {
            ClientModelManager.tick();
            Thread.sleep(5L);
        }
        for (int index = 0; index < 8; index++) {
            assertTrue(
                ClientModelManager.isModelPublished(ModelIdUtil.getMainId(budgetModel(index))),
                "every finished build must be installed eventually");
        }
    }

    /**
     * More models than the loader may keep outstanding at once must still all arrive.
     * <p>
     * {@code requestBuild} refuses transiently when too many finished builds are waiting to be published, and that is
     * only safe because a refused request does <em>not</em> set the request marker - the next frame's
     * {@code ensureGeometry} queues it again. If that marker were set on the refusal instead, every model past the
     * limit would be parked for the life of the connection: built never, drawn as the built-in default forever, and
     * nothing in the log to say so. This asks for more than the limit and then keeps asking, exactly as the render
     * path does.
     */
    @Test
    void modelsBeyondTheOutstandingLimitStillArriveBecauseTheRefusalIsRetried() throws Exception {
        int models = 20;
        for (int index = 0; index < models; index++) {
            ResourceLocation modelId = retryModel(index);
            ClientModelManager.registerAll(payload(modelId.getResourcePath(), geometryOf("main")), false);
        }
        // Ask the way the render path and the GUI do: every tick, for everything still missing.
        for (int tick = 0; tick < 120; tick++) {
            int missing = 0;
            for (int index = 0; index < models; index++) {
                ResourceLocation modelId = retryModel(index);
                if (!ClientModelManager.isModelPublished(ModelIdUtil.getMainId(modelId))) {
                    missing++;
                    ClientModelManager.ensureGeometry(modelId);
                }
            }
            ClientModelManager.tick();
            if (missing == 0) {
                break;
            }
            Thread.sleep(5L);
        }
        for (int index = 0; index < models; index++) {
            assertTrue(
                ClientModelManager.isModelPublished(ModelIdUtil.getMainId(retryModel(index))),
                "model " + index + " never arrived; a refusal that is not retried loses it for the connection");
        }
    }
}
