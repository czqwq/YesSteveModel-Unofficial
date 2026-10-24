package com.fox.ysmu.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.render.ModelFailureNotifier.Outcome;
import com.fox.ysmu.ysmu;

/**
 * {@link ModelFailureNotifier}, i.e. the port's form of upstream's
 * {@code ModelFailureNotificationAggregator} ({@code tmp/YesSteveModel-dev-1.20/.../failure/
 * ModelFailureNotificationAggregator.java:16-101}): which failures are announced, once each, merged by severity, and
 * announced again after the model recovers.
 *
 * <p>A unit JVM has no client, so {@code Minecraft.getMinecraft() == null} and a flush consumes the batch without
 * sending it - which is exactly what makes the "already announced" bookkeeping observable here: after a flush the same
 * outcome is dropped while a different one is still reportable, and a model that
 * {@link ModelFailureNotifier#recovered(ResourceLocation) recovered} is reportable again. The batch clock is supplied
 * by the tests ({@link ModelFailureNotifier#report(ResourceLocation, Outcome, long)},
 * {@link ModelFailureNotifier#tick(long)}) so the 1500 ms window is pinned rather than slept on.
 */
class ModelFailureNotifierTest {

    private static final ResourceLocation MODEL = new ResourceLocation(ysmu.MODID, "wine_fox/01_a/main");
    private static final ResourceLocation OTHER = new ResourceLocation(ysmu.MODID, "wine_fox/02_b/main");
    private static final long START = 1_000_000L;

    @BeforeEach
    @AfterEach
    void resetNotifier() {
        ModelFailureNotifier.clear();
    }

    /** Runs the batch window out: the tick that comes {@code DELAY_MILLIS} after the first report is the flush. */
    private static void flush() {
        ModelFailureNotifier.tick(START + 1500L);
    }

    @Test
    void reportsAreBatchPerModelAndNotDuplicated() {
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        ModelFailureNotifier.report(OTHER, Outcome.PARTIAL, START);

        assertEquals(2, ModelFailureNotifier.pendingCount(), "one pending entry per model");
    }

    /**
     * Upstream merges a model's outcomes by severity, not by arrival order
     * ({@code ModelFailureNotificationAggregator:35-36}), and the chat line names the worst one of the batch.
     */
    @Test
    void aModelsWorstOutcomeWinsWhicheverOrderTheyArriveIn() {
        ModelFailureNotifier.report(MODEL, Outcome.PARTIAL, START);
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        assertEquals(Outcome.FALLBACK, ModelFailureNotifier.pendingOutcome(MODEL));

        ModelFailureNotifier.clear();

        ModelFailureNotifier.report(MODEL, Outcome.STOPPED, START);
        ModelFailureNotifier.report(MODEL, Outcome.PARTIAL, START);
        assertEquals(Outcome.STOPPED, ModelFailureNotifier.pendingOutcome(MODEL));
    }

    /** The batch window is upstream's 1500 ms; a report does not reach the player on the tick it arrives. */
    @Test
    void aReportWaitsForTheBatchWindow() {
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);

        ModelFailureNotifier.tick(START);
        ModelFailureNotifier.tick(START + 1499L);

        assertEquals(1, ModelFailureNotifier.pendingCount(), "the line is not sent inside the window");

        ModelFailureNotifier.tick(START + 1500L);
        assertEquals(0, ModelFailureNotifier.pendingCount(), "and it is sent once the window has passed");
    }

    /**
     * Once a batch was flushed, that model is not announced for the same outcome again - upstream's {@code emitted}
     * set ({@code :32-34}) - while a different outcome for it is still reported, which is what upstream's per
     * (identity, outcome) key does too.
     */
    @Test
    void aFlushedOutcomeIsNotAnnouncedAgainAndADifferentOneStillIs() {
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        flush();

        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        assertEquals(0, ModelFailureNotifier.pendingCount(), "the same outcome is not repeated");

        ModelFailureNotifier.report(MODEL, Outcome.STOPPED, START);
        assertEquals(1, ModelFailureNotifier.pendingCount(), "a different outcome is still news");

        ModelFailureNotifier.report(OTHER, Outcome.FALLBACK, START);
        assertEquals(2, ModelFailureNotifier.pendingCount(), "another model is another report");
    }

    /** Upstream's {@code recovered} ({@code :42-46}): a model that loads again is reported again if it fails later. */
    @Test
    void aRecoveredModelIsAnnounceableAgain() {
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        flush();
        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        assertEquals(0, ModelFailureNotifier.pendingCount());

        ModelFailureNotifier.recovered(MODEL);

        ModelFailureNotifier.report(MODEL, Outcome.FALLBACK, START);
        assertEquals(1, ModelFailureNotifier.pendingCount());
        assertNull(ModelFailureNotifier.pendingOutcome(OTHER));
    }
}
