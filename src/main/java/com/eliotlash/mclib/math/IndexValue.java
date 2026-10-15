package com.eliotlash.mclib.math;

import java.util.List;

import org.apache.logging.log4j.Logger;

import com.eliotlash.mclib.math.functions.UserFunctionArguments;
import software.bernie.geckolib3.GeckoLib;

/**
 * {@code args[index]} - the argument of the user function call currently being evaluated.
 * <p>
 * OpenYSM pack scripts ({@code functions/*.molang}) read their parameters this way, for example
 * {@code t.halo_no=args[0];}. The values come from {@link UserFunctionArguments}, a per-thread stack of call frames
 * that whoever evaluates a user function pushes before evaluating its body: {@code fn.halo_battery_indicator(4)} makes
 * {@code args[0]} answer 4 inside that body.
 * <p>
 * Only {@code args} is indexed here. Molang has no other array in this runtime, so an unknown name answers 0 and is
 * reported once instead of silently pretending to work.
 */
public class IndexValue implements IValue {

    private static final Logger LOG = GeckoLib.LOG;
    private static final java.util.Set<String> WARNED_NAMES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final String name;
    private final IValue index;

    public IndexValue(String name, IValue index) {
        this.name = name == null ? "" : name;
        this.index = index;
    }

    @Override
    public double get() {
        int slot = (int) this.index.get();
        if (UserFunctionArguments.ARRAY_NAME.equals(this.name)) {
            return UserFunctionArguments.get(slot);
        }
        if (WARNED_NAMES.add(this.name)) {
            LOG.warn("Molang indexed value '{}[{}]' is not supported; only '{}' is", this.name, slot,
                UserFunctionArguments.ARRAY_NAME);
        }
        return 0;
    }

    @Override
    public String toString() {
        return this.name + "[" + this.index + "]";
    }

    /** Parsed {@code name[index]} before the index is turned into an {@link IValue}. */
    public static final class Access {

        public final List<Object> symbols;

        public Access(List<Object> symbols) {
            this.symbols = symbols;
        }
    }
}
