package software.bernie.geckolib3.core.molang;

import java.util.function.DoubleSupplier;

import com.eliotlash.mclib.math.Variable;

/**
 * 变量延迟计算，在需要时才会进行计算 <br>
 * 这样能优化渲染，只会在需要这个数值时才会进行计算
 */
public class LazyVariable extends Variable {

    /**
     * YSMU: {@code transient}, because it is a capturing lambda and lambdas are not serialisable. Timeline-local
     * variables live in {@code MolangMultiStatement.locals} and are therefore part of a parsed animation graph; a
     * deserialised copy answers its stored literal (or 0) instead of throwing, which is the only sane answer for an
     * object whose supplier belonged to a parser that no longer exists. Nothing changes at runtime - serialisation of
     * this graph failed outright before, so no live object ever travelled through it.
     */
    private transient DoubleSupplier valueSupplier;
    /** Whether {@link #get()} answers a plain stored number instead of calling {@link #valueSupplier}. */
    private boolean literal;
    private double literalValue;

    @Deprecated
    public LazyVariable(String name, double value) {
        super(name, 0);
        setLiteral(value);
    }

    public LazyVariable(String name, DoubleSupplier valueSupplier) {
        super(name, 0);
        this.valueSupplier = valueSupplier;
    }

    public static LazyVariable from(Variable variable) {
        return new LazyVariable(variable.getName(), variable.get());
    }

    /**
     * Stores a plain number. The per-controller clock writes ({@code query.life_time}, {@code query.anim_time},
     * {@code query.delta_time}) and the per-frame query publication use this path: the previous implementation
     * wrapped the value in a capturing {@code () -> value} lambda on every call, which is one allocation per
     * variable per controller per frame (R13 of .agent/perf-audit-frame-cpu.md). Upstream's shape is a stored
     * value on a {@code Variable} too (its {@code constValue}/mutable binding), so this is the same mechanism with
     * this port's lazy table.
     */
    public void setLiteral(double value) {
        this.literal = true;
        this.literalValue = value;
        this.valueSupplier = null;
    }

    @Override

    public void set(double value) {
        setLiteral(value);
    }

    public void set(DoubleSupplier valueSupplier) {
        this.literal = false;
        this.valueSupplier = valueSupplier;
    }

    @Override

    public double get() {
        if (this.literal) {
            return this.literalValue;
        }
        // A deserialised variable has no supplier (see the field's comment); answer the stored value rather than NPE.
        return this.valueSupplier == null ? 0 : this.valueSupplier.getAsDouble();
    }
}
