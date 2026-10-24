package com.fox.ysmu.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.ysmu;

/**
 * {@link AppliedModelRegistry}: the per-entity record of what an entity is currently shown with, which is this port's
 * form of upstream's installed ("applied") render target - see the class javadoc for the upstream citations.
 *
 * <p>Nothing here needs a client: the registry is a plain id-keyed table, and the rule the renderer applies is exposed
 * as {@link AppliedModelRegistry#keepsAppliedModel(boolean, boolean, boolean)} precisely so the cases at the bottom of
 * this class can be pinned in a unit JVM (a unit JVM cannot link Minecraft, so {@code CustomPlayerRenderer} cannot be
 * constructed here).
 */
class AppliedModelRegistryTest {

    private static final ResourceLocation MODEL_A = new ResourceLocation(ysmu.MODID, "wine_fox/01_a/main");
    private static final ResourceLocation TEXTURE_A = new ResourceLocation(ysmu.MODID, "wine_fox/01_a/skin.png");
    private static final ResourceLocation MODEL_B = new ResourceLocation(ysmu.MODID, "wine_fox/02_b/main");
    private static final ResourceLocation TEXTURE_B = new ResourceLocation(ysmu.MODID, "wine_fox/02_b/skin.png");

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        AppliedModelRegistry.clear();
    }

    @Test
    void anEntityHasNoAppliedModelUntilOneIsInstalled() {
        assertNull(AppliedModelRegistry.applied(7));

        AppliedModelRegistry.install(7, MODEL_A, TEXTURE_A);

        assertEquals(new AppliedModelRegistry.Applied(MODEL_A, TEXTURE_A), AppliedModelRegistry.applied(7));
        assertNull(AppliedModelRegistry.applied(8), "records are per entity");
    }

    /** Either half missing would let a reader bind a model or texture that is not the pair that was drawn. */
    @Test
    void anIncompleteRecordIsRejected() {
        AppliedModelRegistry.install(7, MODEL_A, null);
        assertNull(AppliedModelRegistry.applied(7));
        AppliedModelRegistry.install(7, null, TEXTURE_A);
        assertNull(AppliedModelRegistry.applied(7));
        assertEquals(0, AppliedModelRegistry.size());
    }

    @Test
    void installingAgainReplacesTheRecordAndForgetDropsOnlyThatEntity() {
        AppliedModelRegistry.install(7, MODEL_A, TEXTURE_A);
        AppliedModelRegistry.install(8, MODEL_A, TEXTURE_A);
        AppliedModelRegistry.install(7, MODEL_B, TEXTURE_B);

        assertSame(MODEL_B, AppliedModelRegistry.applied(7)
            .mainModelId());
        assertSame(TEXTURE_B, AppliedModelRegistry.applied(7)
            .textureId());
        assertEquals(2, AppliedModelRegistry.size());

        AppliedModelRegistry.forget(7);
        assertNull(AppliedModelRegistry.applied(7));
        assertEquals(1, AppliedModelRegistry.size(), "the other entity's record stays");
    }

    /**
     * An entry younger than the grace is kept even when its entity is not found: a chunk can be unloaded for a while
     * and the entity still exists, and losing the record would drop that entity back to the built-in default for no
     * reason. Same grace semantics as {@code NPCData#retainAll}.
     */
    @Test
    void aYoungRecordSurvivesThePrune() {
        AppliedModelRegistry.install(7, MODEL_A, TEXTURE_A);
        long now = System.currentTimeMillis();

        assertEquals(0, AppliedModelRegistry.retainAll(now + 1L, id -> false, 30_000L));
        assertEquals(1, AppliedModelRegistry.size());
    }

    /** Past the grace and gone from the world, the record is dropped; entity ids are never reused. */
    @Test
    void anOldRecordForAVanishedEntityIsPruned() {
        AppliedModelRegistry.install(7, MODEL_A, TEXTURE_A);
        long now = System.currentTimeMillis();

        assertEquals(1, AppliedModelRegistry.retainAll(now + 60_000L, id -> false, 30_000L));
        assertEquals(0, AppliedModelRegistry.size());
    }

    @Test
    void anEntityThatStillExistsIsNeverPruned() {
        AppliedModelRegistry.install(9, MODEL_A, TEXTURE_A);
        long now = System.currentTimeMillis();

        assertEquals(0, AppliedModelRegistry.retainAll(now + 60_000L, id -> id == 9, 0L));
        assertNotNull(AppliedModelRegistry.applied(9));
    }

    /**
     * The applied-model rule, one row per state the renderer can be in. Upstream keeps the installed target while the
     * new request is Pending or Failed and installs the built-in default only when there is nothing installed or the
     * model id has no content at all ({@code EntityModelBinding:106-112,145-167},
     * {@code docs/architecture/model-management/failure-and-recovery.md:36}).
     */
    @Test
    void theRuleKeepsTheAppliedModelOnlyForAPendingOrFailedRequestThatHasContent() {
        // Ready: the swap happens, nothing is kept.
        assertFalse(AppliedModelRegistry.keepsAppliedModel(true, true, true));
        assertFalse(AppliedModelRegistry.keepsAppliedModel(true, true, false));

        // Pending (content registered, geometry still building) or Failed (refused): the applied model is kept.
        assertTrue(AppliedModelRegistry.keepsAppliedModel(false, true, true));

        // Nothing applied yet: the built-in default stands in.
        assertFalse(AppliedModelRegistry.keepsAppliedModel(false, true, false));

        // No content for this id at all: upstream releases the primary and installs its fallback.
        assertFalse(AppliedModelRegistry.keepsAppliedModel(false, false, true));
        assertFalse(AppliedModelRegistry.keepsAppliedModel(false, false, false));
    }
}
