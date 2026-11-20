package software.bernie.geckolib3.core.molang;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;

import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;

/**
 * Client-side store for remote animation ("roaming") variables, keyed by entity UUID.
 * <p>
 * {@link MolangPhysicsRuntime#begin} merges the stored values into the current Molang scope before the
 * animation is evaluated, so a model can read {@code v.roaming.<name>} directly. Bare names are promoted to
 * the {@code v.roaming.} namespace; names that already carry a {@code v.}/{@code variable.} prefix keep their
 * namespace. Either way the stored name is lower-cased, because that is the form the parser looks up - see
 * {@link #normalize} for why that matters.
 */
public final class RemoteAnimationVariables {

    private static final String ROAMING_PREFIX = "v.roaming.";
    private static final String VARIABLE_PREFIX = "variable.";
    private static final String V_PREFIX = "v.";

    private static final Map<UUID, Map<String, Double>> BY_ENTITY = new ConcurrentHashMap<>();

    private RemoteAnimationVariables() {}

    public static void put(Entity entity, Object2FloatOpenHashMap<String> vars) {
        if (entity == null) {
            return;
        }
        Map<String, Double> converted = new ConcurrentHashMap<>();
        if (vars != null) {
            for (Object2FloatMap.Entry<String> entry : vars.object2FloatEntrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                converted.put(normalize(entry.getKey()), (double) entry.getFloatValue());
            }
        }
        BY_ENTITY.put(entity.getUniqueID(), converted);
    }

    /** Overload used by callers that already carry boxed numbers. */
    public static void putMap(Entity entity, Map<String, ? extends Number> vars) {
        if (entity == null) {
            return;
        }
        Map<String, Double> converted = new ConcurrentHashMap<>();
        if (vars != null) {
            for (Map.Entry<String, ? extends Number> entry : vars.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                converted.put(normalize(entry.getKey()), entry.getValue().doubleValue());
            }
        }
        BY_ENTITY.put(entity.getUniqueID(), converted);
    }

    /**
     * 应用一份服务端权威 roaming 状态：{@code full} 为真时整张表替换（空 map 表示"该实体当前没有 roaming 值"，
     * 留下的是空命名空间而不是删掉条目——{@link #get} 对"收到过"和"从未收到"的回答因此可区分），否则合并。
     * <p>
     * <b>上游对照。</b> 上游 {@code PlayerStateHandler.applyClientUpdate} 对 roaming 段只有两种应用方式：
     * FULL 走 {@code resetRoamingVars}（replace）、DELTA 走 {@code updateRemoteRoamingVars}（merge）
     * （{@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/network/forge/PlayerStateHandler.java:327-338,
     * 353-357}）；并且"FULL 省略 roaming = 明确清空该命名空间"（同处 :331-332）。本方法就是这个规则在客户端
     * 引擎侧的落点：服务端报文处理线程（{@code S2CRoamingState.Handler}，客户端线程）负责把 wire 上的后缀名还原成
     * 完整 {@code v.roaming.<name>} 再交进来。
     * <p>
     * 名字仍过 {@link #normalize}：服务端/网络路径上的名字大小写不受控，而 {@link MolangPhysicsRuntime} 的键是解析器
     * 的 canonical 拼写。
     */
    public static void apply(Entity entity, Map<String, Double> fullNameValues, boolean full) {
        if (entity == null) {
            return;
        }
        Map<String, Double> converted = new ConcurrentHashMap<>();
        if (fullNameValues != null) {
            for (Map.Entry<String, Double> entry : fullNameValues.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                converted.put(normalize(entry.getKey()), entry.getValue());
            }
        }
        if (full) {
            BY_ENTITY.put(entity.getUniqueID(), converted);
            return;
        }
        if (converted.isEmpty()) {
            return;
        }
        BY_ENTITY.computeIfAbsent(entity.getUniqueID(), ignored -> new ConcurrentHashMap<>())
            .putAll(converted);
    }

    public static void put(Entity entity, String name, double value) {
        if (entity == null || name == null) {
            return;
        }
        BY_ENTITY.computeIfAbsent(entity.getUniqueID(), ignored -> new ConcurrentHashMap<>())
            .put(normalize(name), value);
    }

    /** The live map for the entity, or {@code null} when nothing has been received. */
    public static Map<String, Double> get(Entity entity) {
        return entity == null ? null : BY_ENTITY.get(entity.getUniqueID());
    }

    public static void clear(Entity entity) {
        if (entity != null) {
            BY_ENTITY.remove(entity.getUniqueID());
        }
    }

    public static void clear() {
        BY_ENTITY.clear();
    }

    /**
     * Promotes a bare roaming name to the full Molang variable name.
     * <p>
     * The result goes through {@link MolangParser#canonicalVariableName}, the one rule for a Molang name: the
     * parser lower-cases every expression before parsing it, and the engine's newer lexer agrees, so a name stored
     * in any other case can never be found. A pack writing {@code v.roaming.C} had it stored as {@code v.roaming.C}
     * and looked up as {@code v.roaming.c}, read as the neutral 0, and every {@code v.roaming.C==0?...} in that pack
     * took its first branch - the pack's own toggles silently did nothing. Upstream canonicalises identifiers the
     * same way, in {@code com.elfmcys.ysm.api.molang.MolangNames#identifier}.
     */
    public static String normalize(String name) {
        if (name == null || name.isEmpty()) {
            return ROAMING_PREFIX;
        }
        final String canonical;
        if (name.startsWith(VARIABLE_PREFIX)) {
            canonical = V_PREFIX + name.substring(VARIABLE_PREFIX.length());
        } else if (name.startsWith(V_PREFIX)) {
            canonical = name;
        } else {
            canonical = ROAMING_PREFIX + name;
        }
        return MolangParser.canonicalVariableName(canonical);
    }
}
