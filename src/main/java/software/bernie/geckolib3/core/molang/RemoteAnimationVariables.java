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
