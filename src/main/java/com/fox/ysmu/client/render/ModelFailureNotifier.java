package com.fox.ysmu.client.render;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.ysmu;

/**
 * Tells the player which models failed, aggregated the way upstream's
 * {@code ModelFailureNotificationAggregator} does it
 * ({@code tmp/YesSteveModel-dev-1.20/.../model/resource/client/failure/ModelFailureNotificationAggregator.java:16-101}).
 *
 * <p>Upstream's shape, kept here: reports are collected for {@code DELAY_MILLIS} (1500 ms) after the first one, a
 * given model is announced <b>once</b> per outcome, several failures collapse into a single chat line carrying the
 * count, the line uses the worst outcome of the batch, and a model that is later loaded successfully is forgotten on
 * both sides ({@code recovered}, {@code :42-46}). What differs is only the plumbing: upstream schedules the flush on a
 * {@code ScheduledExecutorService} and hops to the render thread, while this port has a client tick it already runs
 * timers from and 1.7.10's chat has to be touched there anyway, so {@link #tick()} drains it.
 *
 * <p>The message keys are upstream's ({@code :84-88}), and the count is passed as a string: 1.7.10's
 * {@code ChatComponentTranslation} rejects every conversion but {@code %s} (AGENTS.md, "ChatComponentTranslation
 * 消费的翻译字符串只能使用 %s").
 *
 * <p>The fourth entry point, {@link #reportFallbackUnavailable()}, has no upstream counterpart because upstream's
 * built-in default is a startup requirement whose failure is exceptional
 * ({@code docs/product-decisions/decisions/model-fallback.md:29-39,49-53}). This port keeps running in that state and
 * hands the entities back to the vanilla renderer, so it says so once - loudly and to the player - instead of leaving
 * them to wonder why their model is gone.
 */
public final class ModelFailureNotifier {

    /** Upstream's aggregation window ({@code ModelFailureNotificationAggregator:18}). */
    private static final long DELAY_MILLIS = 1500L;

    /** The outcome a failed model is reported as; the order is the severity upstream compares by ordinal. */
    public enum Outcome {

        /** Some of the model's resources failed while the model still renders (upstream {@code PARTIAL}). */
        PARTIAL("message.yes_steve_model.model.failure.partial"),
        /** The model failed and is drawn as the built-in default (upstream {@code FALLBACK}). */
        FALLBACK("message.yes_steve_model.model.failure.fallback"),
        /** Nothing could be drawn for it, so the entity keeps its own renderer (upstream {@code STOPPED}). */
        STOPPED("message.yes_steve_model.model.failure.stopped");

        private final String translationKey;

        Outcome(String translationKey) {
            this.translationKey = translationKey;
        }

        /** The chat key for this outcome, upstream's own ({@code ModelFailureNotificationAggregator:85-87}). */
        public String translationKey() {
            return translationKey;
        }
    }

    /** One model's worst pending outcome; upstream merges by severity the same way ({@code :35-36}). */
    private static final Map<ResourceLocation, Outcome> PENDING = new ConcurrentHashMap<>();
    /** Model ids already announced, so a model that keeps failing does not keep talking ({@code :57-58}). */
    private static final Set<String> EMITTED = ConcurrentHashMap.newKeySet();
    private static volatile long batchStartedAt;
    private static volatile boolean fallbackUnavailableReported;

    private ModelFailureNotifier() {}

    /**
     * Records a model failure; it reaches the player through {@link #tick()} once the batch window has passed.
     * Client thread only (every caller is a render or publish path).
     */
    public static void report(ResourceLocation modelId, Outcome outcome) {
        report(modelId, outcome, System.currentTimeMillis());
    }

    /** {@link #report(ResourceLocation, Outcome)} with the batch clock supplied, so the window can be pinned; tests. */
    static void report(ResourceLocation modelId, Outcome outcome, long now) {
        if (modelId == null || EMITTED.contains(key(modelId, outcome))) {
            return;
        }
        PENDING.merge(modelId, outcome, (left, right) -> left.ordinal() >= right.ordinal() ? left : right);
        if (PENDING.size() == 1) {
            batchStartedAt = now;
        }
    }

    /**
     * Forgets a model that is now loaded, so a failure of a later payload is announced again - upstream's
     * {@code recovered} ({@code :42-46}).
     */
    public static void recovered(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return;
        }
        PENDING.remove(modelId);
        for (Outcome outcome : Outcome.values()) {
            EMITTED.remove(key(modelId, outcome));
        }
    }

    /**
     * Reports that the built-in fallback itself is unusable, i.e. that YSM cannot replace player models on this
     * client at all: one ERROR line for the log and one chat line for the player, once per session - the state cannot
     * change while the client runs (only a restart or a reinstall of the built-in model changes it), so repeating it
     * every frame would bury both.
     */
    public static void reportFallbackUnavailable() {
        if (fallbackUnavailableReported) {
            return;
        }
        fallbackUnavailableReported = true;
        ysmu.LOG.error(
            "YSM cannot draw player models on this client: the built-in default model is not usable (see the earlier "
                + "'Failed to load default model' / 'cannot draw the built-in default model' lines). Entities are left "
                + "to their vanilla renderers until the client restarts or the built-in model is reinstalled.");
        sendChat("message.yes_steve_model.model.failure.default_unavailable", null);
    }

    /** Client tick: flushes the batch once its window has passed. */
    public static void tick() {
        tick(System.currentTimeMillis());
    }

    /** {@link #tick()} with the clock supplied, so the window can be pinned; tests only. */
    static void tick(long now) {
        if (PENDING.isEmpty() || now - batchStartedAt < DELAY_MILLIS) {
            return;
        }
        Map<ResourceLocation, Outcome> batch = new LinkedHashMap<>(PENDING);
        PENDING.clear();
        for (Map.Entry<ResourceLocation, Outcome> entry : batch.entrySet()) {
            EMITTED.add(key(entry.getKey(), entry.getValue()));
        }
        if (batch.isEmpty()) {
            return;
        }
        Outcome worst = batch.values()
            .stream()
            .max(Comparator.comparingInt(Enum::ordinal))
            .orElse(Outcome.FALLBACK);
        sendChat(worst.translationKey(), String.valueOf(batch.size()));
    }

    /** Drops everything, including the "already reported" markers: a new session reports again. */
    public static void clear() {
        PENDING.clear();
        EMITTED.clear();
        batchStartedAt = 0L;
        fallbackUnavailableReported = false;
    }

    /** Pending reports; tests only. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** The outcome a model would be announced as right now; tests only, so the severity merge can be pinned. */
    @Nullable
    static Outcome pendingOutcome(ResourceLocation modelId) {
        return PENDING.get(modelId);
    }

    private static String key(ResourceLocation modelId, Outcome outcome) {
        return modelId + "|" + outcome;
    }

    /**
     * Sends the line to the local player, if there is one. {@code count} is {@code null} for the notice that has no
     * model count.
     */
    private static void sendChat(String translationKey, @Nullable String count) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.thePlayer == null) {
            return;
        }
        minecraft.thePlayer.addChatMessage(
            count == null ? new ChatComponentTranslation(translationKey) : new ChatComponentTranslation(
                translationKey,
                count));
    }
}
