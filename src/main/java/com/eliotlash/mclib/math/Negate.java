package com.eliotlash.mclib.math;

import java.io.Serializable;

public class Negate implements IValue, Serializable {

    private static final long serialVersionUID = 42L;

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
