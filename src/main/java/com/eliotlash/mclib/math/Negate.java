package com.eliotlash.mclib.math;

public class Negate implements IValue {

    public IValue value;

    public Negate(IValue value) {
        this.value = value;
    }

    @Override

    public double get() {
        // 上游取非是 `!asBoolean(value)`（ExpressionEvaluatorImpl.java:359-360）：NaN 判假，取非为真。
        return Operation.asBoolean(this.value.get()) ? 0 : 1;
    }

    @Override

    public String toString() {
        return "!" + this.value.toString();
    }
}
