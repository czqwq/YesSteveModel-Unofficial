package software.bernie.geckolib3.core.controller;

/** Upstream SegmentedBlendTransition:12-36, with immutable seconds-to-tick points.
 * Values are the amount of the new target (upstream stores 1-value then its point inverts it). */
public final class BlendTransition {
    private final double[] ticks;
    private final double[] values;

    public BlendTransition(double[] ticks, double[] values) {
        if (ticks.length != values.length || ticks.length == 0) throw new IllegalArgumentException("transition points");
        this.ticks = ticks.clone();
        this.values = values.clone();
    }
    public static BlendTransition linear(double lengthTicks) {
        return new BlendTransition(new double[] { 0, Math.max(0, lengthTicks) }, new double[] { 0, 1 });
    }
    public double length() { return ticks[ticks.length - 1]; }
    public double progress(double tick) {
        if (tick <= ticks[0]) return values[0];
        for (int i = 1; i < ticks.length; i++) {
            if (tick <= ticks[i]) {
                double width = ticks[i] - ticks[i - 1];
                return width <= 0 ? values[i] : values[i - 1]
                    + (values[i] - values[i - 1]) * (tick - ticks[i - 1]) / width;
            }
        }
        return values[values.length - 1];
    }
}
