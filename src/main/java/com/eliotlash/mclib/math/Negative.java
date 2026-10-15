package com.eliotlash.mclib.math;

public class Negative implements IValue {

    public IValue value;

    public Negative(IValue value) {
        this.value = value;
    }

    @Override

    public double get() {
        // 上游一元负号是 `-asFloat(value)`（ExpressionEvaluatorImpl.java:361-362），NaN 先归 0。
        return -Operation.asDouble(this.value.get());
    }

    @Override

    public String toString() {
        return "-" + this.value.toString();
    }
}
