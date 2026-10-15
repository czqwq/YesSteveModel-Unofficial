package com.eliotlash.mclib.math.functions.utility;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class DieRoll extends Function {

    public java.util.Random random;

    public DieRoll(IValue[] values, String name) throws Exception {
        super(values, name);
        this.random = new java.util.Random();
    }

    @Override

    public int getRequiredArguments() {
        return 3;
    }

    /**
     * Ported from upstream {@code MathBinding}'s {@code math.die_roll}
     * (tmp/YesSteveModel-dev-1.20 .../geckolib3/core/molang/builtin/math/DieRoll.java:14-30), which sums
     * {@code rolls} samples of {@code [min, max)}. The vendored snapshot had {@code while (i < getArg(0))} with no
     * increment and a spread of {@code getArg(2) - getArg(2)} - an infinite loop for any {@code rolls > 0}, not a
     * wrong value. The only substitution is this file's own {@code Math.random()} for upstream's per-entity
     * {@code RandomSource}: this layer has no entity, and the neighbouring {@code Random}/{@code RandomInteger}
     * answer the same way. See .agent/phase27-mclib-math-defects.md.
     */
    @Override

    public double get() {
        int rolls = (int) this.getArg(0);
        double min = this.getArg(1);
        double range = this.getArg(2);
        if (min > range) {
            double temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        double total = 0;
        for (int i = 0; i < rolls; i++) {
            total += min + Math.random() * range;
        }
        return total;
    }
}
