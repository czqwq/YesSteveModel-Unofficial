package com.fox.ysmu.client.animation.controller;

/**
 * The {@code math.*} bodies the two controller-expression axes must answer identically.
 *
 * <p>The port has two independent inline {@code math.*} tables - the player controller axis
 * ({@code OpenYsmControllerExpressionEvaluator.mathValue}) and the projectile axis
 * ({@code ProjectileControllerRuntime.evaluateFunction}) - and ten names had drifted apart between them,
 * most of them the projectile axis carrying the completed reference port's shape instead of upstream's.
 * The two axes answering one Molang name differently is a defect on its own (AGENTS.md, "Prefer one
 * implementation to two"), so the diverging bodies live here once and <b>both</b> axes call them. The two
 * axes were rewired onto this class together; a name that reaches only one of them is named in the
 * ExecPlan note {@code .agent/phase36-axis-math-repair.md}.</p>
 *
 * <p>Every body is upstream's, from {@code tmp/YesSteveModel-dev-1.20}:
 * {@code .../geckolib3/core/molang/builtin/math/{Sqrt,Mod,Atan,ATan2,MinAngle,LerpRotate,DieRoll,DieRollInteger,Clamp,Random}.java}.
 * The reference branch ({@code tmp/YesSteveModel-Unofficial-perf-previewUI}) is where the old projectile
 * bodies came from and is <b>not</b> the authority here.</p>
 *
 * <p>Two substitutions and one guard are deliberate and are stated at their call sites: {@link Math#random()}
 * stands in for upstream's {@code context.entity().random()}, because this layer has no entity - the same
 * substitution the neighbouring mclib {@code Random}/{@code RandomInteger} already make; a degenerate
 * {@link #dieRollInteger} span answers its single value instead of upstream's {@code nextInt(0)}, which
 * throws {@code IllegalArgumentException} and would kill the whole expression; and the die-roll family
 * stops after {@link #MAX_ROLLS} rolls, a guard upstream does not have (see its javadoc).</p>
 */
final class ControllerMath {

    private ControllerMath() {}

    /**
     * Upstream {@code .../builtin/math/Sqrt.java:9}: {@code Math.sqrt} with no guard, so a negative input is
     * {@code NaN}. The reference branch clamped it to 0 ({@code OpenYsmControllerExpressionEvaluator.java:678}
     * in that branch), which silently turned an authoring mistake into a plausible value.
     */
    static double sqrt(double value) {
        return Math.sqrt(value);
    }

    /**
     * Upstream {@code .../builtin/math/Mod.java:9}: {@code a % b} - Java's remainder, i.e. the sign follows
     * the dividend ({@code -7 % 3 == -1}). The reference branch folded a negative remainder up by the divisor
     * and answered 0 for a zero divisor.
     */
    static double mod(double dividend, double divisor) {
        return dividend % divisor;
    }

    /**
     * Upstream {@code .../builtin/math/Atan.java:9}: {@code Math.atan}, i.e. a result in <b>radians</b>.
     * The reference branch answered degrees ({@code Math.toDegrees}).
     */
    static double atan(double value) {
        return Math.atan(value);
    }

    /**
     * Upstream {@code .../builtin/math/ATan2.java:10}: {@code Mth.atan2(y, x)} = {@code Math.atan2}, in
     * <b>radians</b>. The reference branch answered degrees.
     */
    static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    /**
     * Upstream {@code .../builtin/math/MinAngle.java:9-16}: a <b>one-argument</b> normalisation of that angle
     * into {@code (-180, 180]}. This is upstream's whole function - it is not a two-argument difference, which
     * is the shape the projectile axis used to carry (see the ExecPlan note
     * {@code .agent/phase29-projectile-axis-upstream-math.md}).
     *
     * <p>The boundary is {@code >= 180}, not the mclib {@code classic/MinAngle.java} {@code > 180}: upstream
     * answers {@code -180} for an input of exactly 180 and mclib leaves 180 alone.</p>
     */
    static double minAngle(double value) {
        double angle = value % 360.0d;
        if (angle >= 180.0d) {
            return angle - 360.0d;
        }
        if (angle < -180.0d) {
            return angle + 360.0d;
        }
        return angle;
    }

    /**
     * Upstream {@code .../builtin/math/LerpRotate.java:10-12} calls
     * {@code Interpolations.lerpYaw(a, b, position)}, which wraps both endpoints into
     * {@code [-180, 180]} and takes the short arc - so the answer also stays inside
     * {@code [-180, 180]}. The port's own vendored mclib copy is the same body
     * ({@code com/eliotlash/mclib/utils/Interpolations.java:129-133}), so it is reused rather than re-derived;
     * the projectile axis used to fold the result into {@code [0, 360)} instead.
     */
    static double lerpRotate(double from, double to, double factor) {
        return com.eliotlash.mclib.utils.Interpolations.lerpYaw((float) from, (float) to, (float) factor);
    }

    /**
     * Upstream {@code .../builtin/math/DieRoll.java:15-30}: the roll count is truncated ({@code getAsInt}), the
     * two bounds are put in order, and each roll is {@code min + nextFloat() * range} - a float sum over
     * {@code [min, max)}. Both axes run this one body, so they cannot drift for the {@code math.roll} aliases
     * either.
     */
    static double dieRoll(double count, double min, double max) {
        int rolls = Math.min((int) count, MAX_ROLLS);
        double low = Math.min(min, max);
        double span = Math.abs(max - min);
        double total = 0.0d;
        for (int i = 0; i < rolls; i++) {
            total += low + Math.random() * span;
        }
        return total;
    }

    /**
     * The die-roll family's roll count is capped here. Upstream loops {@code while (i-- > 0)} with
     * {@code i = getAsInt(getAsFloat(0))} and has <b>no</b> cap ({@code DieRoll.java:15,27} /
     * {@code DieRollInteger.java:15,27}), so a pack that writes a wrong count loops for the whole render
     * frame and hangs the client thread - the failure mode AGENTS.md records for the reference branch's
     * {@code DieRoll.java:26-28}. The cap was carried by <em>both</em> axes before the axes were merged
     * (player axis {@code OpenYsmControllerExpressionEvaluator.dieRoll}, projectile axis
     * {@code Math.min(rolls, 100)}), so it is a port guard, not a new invention; it is kept in the one
     * shared body so the two axes cannot disagree about it. It is the only place this class answers
     * differently from upstream, and it only differs for a count above it.
     */
    private static final int MAX_ROLLS = 100;

    /**
     * Upstream {@code .../builtin/math/DieRollInteger.java:15-31}: the roll count is rounded
     * ({@code Math.round(getAsFloat(0))}), both bounds are truncated ({@code getAsInt}), and each roll is
     * {@code min + nextInt(range)} over {@code [min, max-1]} - the upper bound is <b>exclusive</b>. The
     * projectile axis used to answer {@code (max - min + 1)}, i.e. {@code [min, max]}, one value wider, and
     * its call site truncated the count before the body could round it.
     *
     * <p>Substitutions: {@link Math#random()} for upstream's {@code context.entity().random()} (no entity at
     * this layer), a degenerate span ({@code min == max}) answers {@code min} instead of upstream's
     * {@code nextInt(0)} exception, and {@link #MAX_ROLLS} caps the loop.</p>
     */
    static double dieRollInteger(double count, double min, double max) {
        int rolls = Math.min((int) Math.round(count), MAX_ROLLS);
        int low = (int) min;
        int high = (int) max;
        if (low > high) {
            int swap = low;
            low = high;
            high = swap;
        }
        int span = high - low;
        double total = 0.0d;
        for (int i = 0; i < rolls; i++) {
            total += span <= 0 ? low : low + (int) (Math.random() * span);
        }
        return total;
    }

    /**
     * Upstream {@code .../builtin/math/Clamp.java:10-12} calls {@code Mth.clamp(value, min, max)}, whose body
     * is {@code value < min ? min : Math.min(value, max)}. The projectile axis used to carry
     * {@code Math.max(min, Math.min(max, value))}, which is the other clamp convention and answers the lower
     * bound where upstream answers {@code Math.min(value, max)} whenever a pack inverts the two bounds
     * ({@code math.clamp(7,5,1)}: upstream {@code 1}, that body {@code 5}). The two forms agree for
     * {@code min <= max}, so only the inverted-bounds case changes.
     */
    static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * Upstream {@code .../builtin/math/Random.java:14-24}: the two bounds are put in order and the answer is
     * {@code min + nextFloat() * (max - min)}, i.e. {@code [min, max)}, with {@code min} for a degenerate
     * span. The projectile axis used to answer the <em>first</em> argument whenever it was not below the
     * second, so a pack that wrote its bounds the other way round got a constant instead of a random value;
     * upstream and the player axis both sort. {@link Math#random()} stands in for
     * {@code context.entity().random()}, as in {@link #dieRoll}.
     */
    static double randomRange(double first, double second) {
        double low = Math.min(first, second);
        double high = Math.max(first, second);
        return low + Math.random() * (high - low);
    }
}
