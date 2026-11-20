package com.fox.ysmu.client.roaming;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
 * <b>Keys are full MoLang names</b> ({@code v.roaming.x}), exactly as the engine scope keys them; the wire and the
 * server save data use the bare suffix, and the packet handlers convert at that boundary
 * ({@code ModelRoamingLimits#fullName}). No store accepts both spellings.
 * <p>
 * <b>Who writes what (phase78).</b> Only the server's authoritative state is written here, through
 * {@link #acceptState}: a FULL replaces the namespace, a DELTA merges into it. The local player's own values are not
 * written here first any more: a config action executes in the engine's real {@code (entity, model)} scope, and
 * {@link ClientRoamingSync} samples that scope every client tick and reports it. That is upstream's shape - the
 * detected change is reported, the server echoes the authority back ({@code network/forge/PlayerStateHandler.java:
 * 214-239,288-338}) - and it is what makes a {@code v.roaming.*} write visible to the world on the same frame instead
 * of one server round-trip later.
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

    /**
     * Player -> the model keys for which an authoritative state has been received on this connection.
     * <p>
     * 上游"客户端只有收到 self 的权威 FULL 之后才有上报资格"的移植落点：planner 在 baseline 未知时
     * 不消耗本次上报机会（{@code network/forge/LocalPlayerStateReporter.java:70-88} 拿不到 roaming 快照就
     * {@code RoamingSample.unavailable()}，planner 直接放弃），{@code ClientRoamingSession} 的 retained 快照/服务端 FULL
     * 才是本地运行时之外那份"已知状态"（{@code capability/ClientRoamingSession.java:32-62,76-99}）。
     * {@link ClientRoamingSync} 用它回答"当前模型的权威状态到了没有"：没到就等，避免把"本机还没收到"误当成"服务端状态为空"
     * 用一条空 FULL 覆盖掉服务端存档。本表随连接边界（{@link #clear()}）和模型重载一起清空。
     */
    private static final Map<UUID, Set<Integer>> AUTHORITATIVE_KEYS = new ConcurrentHashMap<>();

    private ClientRoamingStore() {}

    /**
     * Applies one authoritative roaming state for one player's model namespace.
     * <p>
     * 上游规则（{@code network/forge/PlayerStateHandler.java:327-338,353-357}）：FULL 是整命名空间替换
     * （{@code resetRoamingVars}），DELTA 是合并（{@code updateRemoteRoamingVars}）；FULL 里省略一个变量就是"它没了"。
     * 这里同样：{@code full} 为真时整段替换、空 map 等于删掉这个命名空间；否则只覆盖出现的键。
     *
     * @param playerId        the player the state belongs to
     * @param modelKey        the model's roaming key ({@link ClientRoamingKeys#keyFor})
     * @param fullNameValues  full {@code v.roaming.*} names to values; {@code null} is an empty map
     * @param full            {@code true} = FULL/replace, {@code false} = DELTA/merge
     */
    public static void acceptState(UUID playerId, int modelKey, @Nullable Map<String, Double> fullNameValues,
        boolean full) {
        if (playerId == null) {
            return;
        }
        // Receiving any authoritative state for this key is what makes the client eligible to report it back (see
        // AUTHORITATIVE_KEYS); a FULL with no values is such a state too ("this namespace is empty").
        markAuthoritative(playerId, modelKey);
        Map<String, Double> values = sanitize(fullNameValues);
        if (full) {
            if (values.isEmpty()) {
                Map<Integer, Map<String, Double>> byKey = BY_PLAYER.get(playerId);
                if (byKey != null) {
                    byKey.remove(modelKey);
                }
                return;
            }
            Map<Integer, Map<String, Double>> byKey = BY_PLAYER
                .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
            // The values come from the server, so the model count is bounded here as well as in the save data: a peer
            // that sends an endless stream of unknown model keys must not be able to grow this map without limit.
            if (byKey.size() >= MAX_MODELS_PER_PLAYER && !byKey.containsKey(modelKey)) {
                return;
            }
            byKey.put(modelKey, values);
            return;
        }
        if (values.isEmpty()) {
            return;
        }
        Map<Integer, Map<String, Double>> byKey = BY_PLAYER
            .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
        if (byKey.size() >= MAX_MODELS_PER_PLAYER && !byKey.containsKey(modelKey)) {
            return;
        }
        Map<String, Double> current = byKey.computeIfAbsent(modelKey, ignored -> new ConcurrentHashMap<>());
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            // Same bound as the full path: a delta may not grow a namespace past upstream's variable limit.
            if (current.size() >= MAX_VARIABLES_PER_MODEL && !current.containsKey(entry.getKey())) {
                continue;
            }
            current.put(entry.getKey(), entry.getValue());
        }
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

    /** The local player's values for one model, for the preview and for the settings panel. */
    public static Map<String, Double> localValuesFor(@Nullable UUID localPlayerId, int modelKey) {
        return valuesFor(localPlayerId, modelKey);
    }

    /**
     * Whether an authoritative state for this player's model key has been received since the last connection change.
     * See {@link #AUTHORITATIVE_KEYS} for why the reporter has to ask this before its first report.
     */
    public static boolean isAuthoritative(@Nullable UUID playerId, int modelKey) {
        if (playerId == null) {
            return false;
        }
        Set<Integer> keys = AUTHORITATIVE_KEYS.get(playerId);
        return keys != null && keys.contains(modelKey);
    }

    private static void markAuthoritative(UUID playerId, int modelKey) {
        Set<Integer> keys = AUTHORITATIVE_KEYS.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
        if (keys.size() >= MAX_MODELS_PER_PLAYER && !keys.contains(modelKey)) {
            return;
        }
        keys.add(modelKey);
    }

    /** Forgets everything, for a resource reload, a server change or a logout. */
    public static void clear() {
        BY_PLAYER.clear();
        AUTHORITATIVE_KEYS.clear();
    }

    /** Forgets one player, for example when they leave the tracked range. */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            BY_PLAYER.remove(playerId);
            AUTHORITATIVE_KEYS.remove(playerId);
        }
    }

    /**
     * Forgets one player's values for one model namespace, for a user-requested reset
     * ({@code MolangReset.resetSelf} → {@code OpenYsmPlayerControllerRuntime#resetUserRoamingVars}).
     * <p>
     * 这张表只是服务端权威状态的客户端副本（phase78 起本地写入不再落地这里），所以本方法只清掉本地这一份，让面板/预览
     * 立刻回到模型自身 {@code ysm.json} 的默认值；真正的 reset 由调用方接着清掉引擎作用域里的
     * {@code v.roaming.*}，再由 {@link ClientRoamingSync} 的下一个 FULL 把清空后的全量上报给服务端——服务端是权威，它
     * 会回一份新的权威状态（{@code network/forge/PlayerStateHandler.java:331-332,353-357} 的"FULL 省略=清空"）。
     */
    public static void forget(UUID playerId, int modelKey) {
        if (playerId != null) {
            Map<Integer, Map<String, Double>> byKey = BY_PLAYER.get(playerId);
            if (byKey != null) {
                byKey.remove(modelKey);
            }
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
