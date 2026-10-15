package com.eliotlash.mclib.math.functions.utility;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class DieRollInteger extends Function {

    public java.util.Random random;

    public DieRollInteger(IValue[] values, String name) throws Exception {
        super(values, name);
        this.random = new java.util.Random();
    }

    @Override

    public int getRequiredArguments() {
        return 3;
    }

    /**
     * Ported from upstream {@code MathBinding}'s {@code math.die_roll_integer}
     * (tmp/YesSteveModel-dev-1.20 .../geckolib3/core/molang/builtin/math/DieRollInteger.java:14-31). The vendored
     * snapshot had {@code while (i < getArg(0))} with no increment - an infinite loop for any {@code rolls > 0}.
     * <p>
     * It is <b>not</b> the same shape as {@link DieRoll}: upstream rounds the roll count with
     * {@code Math.round} there, truncates the two bounds with {@code getAsInt}, and swaps the bounds with a plain
     * three-line swap (not the {@code temp - range} trick {@code DieRoll} uses for its float bounds). Two
     * substitutions are unavoidable here and are stated rather than hidden:
     * <ul>
     * <li>{@code min + (int) (Math.random() * (max - min))} replaces upstream's {@code min + rnd.nextInt(max - min)}.
     * They answer the same on the integer interval {@code [min, max-1]}, and this layer has no entity to take a
     * {@code RandomSource} from.</li>
     * <li>A degenerate span makes it answer {@code min} instead of upstream's {@code nextInt(0)}, which throws
     * {@code IllegalArgumentException} - the same non-inherited guard {@code OpenYsmControllerExpressionEvaluator}'s
     * {@code randomInteger} already records (that file's {@code :1322-1343}), because an exception here would kill the
     * whole animation channel.</li>
     * </ul>
     * See .agent/phase27-mclib-math-defects.md.
     */
    @Override

    public double get() {
        int rolls = (int) Math.round(this.getArg(0));
        int min = (int) this.getArg(1);
        int max = (int) this.getArg(2);
        if (min > max) {
            int temp = min;
            min = max;
            max = temp;
        }
        int span = max - min;
        double total = 0;
        for (int i = 0; i < rolls; i++) {
            total += min + (span > 0 ? (int) (Math.random() * span) : 0);
        }
        return total;
    }
}
