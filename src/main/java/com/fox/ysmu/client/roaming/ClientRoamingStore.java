package com.fox.ysmu.client.roaming;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.fox.ysmu.model.roaming.ModelRoamingLimits;

/**
 * The client's copy of every player's roaming variables, keyed by player and then by
 * {@link com.fox.ysmu.model.roaming.ModelRoamingKey}.
 * <p>
 * A <em>roaming variable</em> is a named float that belongs to one model of one player and is read by packs as
 * {@code v.roaming.<name>}; the model's own {@code 模型设置} panel writes them. Upstream splits this into a writable
 * local struct for the player this client controls and a read-only remote struct for everybody else
 * ({@code LocalRoamingStruct} / {@code RemoteRoamingStruct} behind {@code ClientRoamingSession}); this class keeps the
 * same two namespaces without the pooled integer variable names upstream uses, because the engine's per-scope store
 * is a plain {@code Map<String, Double>}.
 * <p>
 * The class deliberately holds no Minecraft types: it is a plain data structure so it can be unit tested without a
 * game, and the engine is fed from the outside by pushing the values it returns into
 * {@code RemoteAnimationVariables} (world entities) or through {@code IMolangPhysicsScope#getMolangVariables}
 * (detached previews).
 * <p>
 * No {@code @SideOnly(Side.CLIENT)}: like {@code ModelPreviewAnimationState}, this class is meant to be covered by a
 * plain unit test.
 */
public final class ClientRoamingStore {

    /** Upstream's bound: at most this many variable names per model. */
    public static final int MAX_VARIABLES_PER_MODEL = ModelRoamingLimits.MAX_VARIABLES_PER_MODEL;

    /** Upstream's bound: at most this many models per player. */
    public static final int MAX_MODELS_PER_PLAYER = ModelRoamingLimits.MAX_MODELS_PER_PLAYER;

    /** The bound on a full variable name; see {@link ModelRoamingLimits} for why it is 42 and not 32. */
    public static final int MAX_VARIABLE_NAME_LENGTH = ModelRoamingLimits.MAX_NAME_LENGTH;

    /** Player -> roaming key -> variable name -> value. */
    private static final Map<UUID, Map<Integer, Map<String, Double>>> BY_PLAYER = new ConcurrentHashMap<>();

    /** Variables changed locally and not yet reported to the server, keyed by roaming key. */
    private static final Map<Integer, Map<String, Double>> PENDING = new ConcurrentHashMap<>();

    private ClientRoamingStore() {}

    /**
     * Replaces one player's values for one model, as received from the server.
     * <p>
     * The whole namespace is replaced rather than merged, matching upstream's rule that a full state which omits a
     * variable means the variable is gone.
     */
    public static void acceptState(UUID playerId, int modelKey, @Nullable Map<String, Double> values) {
        if (playerId == null) {
            return;
        }
        Map<String, Double> sanitized = sanitize(values);
        if (sanitized.isEmpty()) {
            Map<Integer, Map<String, Double>> byKey = BY_PLAYER.get(playerId);
            if (byKey != null) {
                byKey.remove(modelKey);
            }
            return;
        }
        Map<Integer, Map<String, Double>> byKey = BY_PLAYER.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        // The values come from the server, so the model count is bounded here as well as in the save data: a peer that
        // sends an endless stream of unknown model keys must not be able to grow this map without limit.
        if (byKey.size() >= MAX_MODELS_PER_PLAYER && !byKey.containsKey(modelKey)) {
            return;
        }
        byKey.put(modelKey, sanitized);
    }

    /** Every variable a player has for one model; never {@code null}, possibly empty. */
    public static Map<String, Double> valuesFor(@Nullable UUID playerId, int modelKey) {
        if (playerId == null) {
            return Collections.emptyMap();
        }
        Map<Integer, Map<String, Double>> byKey = BY_PLAYER.get(playerId);
        if (byKey == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> values = byKey.get(modelKey);
        return values == null ? Collections.emptyMap() : values;
    }

    /**
     * Records a variable the local player just changed, both as the local value and as a change to report. The
     * caller applies it (see {@link #takePending}) and the server is expected to echo it back through
     * {@link #acceptState}, which is what makes the value authoritative rather than merely local.
     */
    public static void setLocal(UUID localPlayerId, int modelKey, String variableName, double value) {
        if (localPlayerId == null || !isAcceptableName(variableName) || !isAcceptableValue(value)) {
            return;
        }
        Map<String, Double> values = BY_PLAYER.computeIfAbsent(localPlayerId, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(modelKey, ignored -> new ConcurrentHashMap<>());
        if (values.size() >= MAX_VARIABLES_PER_MODEL && !values.containsKey(variableName)) {
            return;
        }
        values.put(variableName, value);
        PENDING.computeIfAbsent(modelKey, ignored -> new ConcurrentHashMap<>())
            .put(variableName, value);
    }

    /** The local player's values for one model, for the preview and for the settings panel. */
    public static Map<String, Double> localValuesFor(@Nullable UUID localPlayerId, int modelKey) {
        return valuesFor(localPlayerId, modelKey);
    }

    /**
     * Takes the changes that still have to be reported to the server and clears them, one entry per model.
     * <p>
     * The caller owns the transport; this class never talks to the network.
     */
    public static Map<Integer, Map<String, Double>> takePending() {
        if (PENDING.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, Map<String, Double>> taken = new HashMap<>(PENDING);
        PENDING.clear();
        return taken;
    }

    /**
     * Puts changes taken by {@link #takePending()} back, for a transport that could not send them. The values
     * themselves are left alone: they were already applied locally.
     */
    public static void markPending(int modelKey, @Nullable Map<String, Double> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        Map<String, Double> pending = PENDING.computeIfAbsent(modelKey, ignored -> new ConcurrentHashMap<>());
        for (Map.Entry<String, Double> entry : changes.entrySet()) {
            if (ModelRoamingLimits.isAcceptableName(entry.getKey()) && entry.getValue() != null
                && ModelRoamingLimits.isAcceptableValue(entry.getValue())) {
                pending.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Whether anything changed locally and has not been reported yet. */
    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** Forgets everything, for a resource reload, a server change or a logout. */
    public static void clear() {
        BY_PLAYER.clear();
        PENDING.clear();
    }

    /** Forgets one player, for example when they leave the tracked range. */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            BY_PLAYER.remove(playerId);
        }
    }

    /** Whether a name is one the store accepts: a non-empty {@code v.} name within the length bound. */
    public static boolean isAcceptableName(@Nullable String variableName) {
        return ModelRoamingLimits.isAcceptableName(variableName);
    }

    /** Whether a value is one the store accepts: finite, so a broken expression cannot be persisted. */
    public static boolean isAcceptableValue(double value) {
        return ModelRoamingLimits.isAcceptableValue(value);
    }

    private static Map<String, Double> sanitize(@Nullable Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            String name = entry.getKey();
            Double value = entry.getValue();
            if (!isAcceptableName(name) || value == null || !isAcceptableValue(value)) {
                continue;
            }
            if (out.size() >= MAX_VARIABLES_PER_MODEL) {
                break;
            }
            out.put(name, value);
        }
        return out;
    }
}
