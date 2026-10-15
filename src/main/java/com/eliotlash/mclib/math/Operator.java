package com.eliotlash.mclib.math;

public class Operator implements IValue {

    public Operation operation;
    public IValue a;
    public IValue b;

    public Operator(Operation op, IValue a, IValue b) {
        this.operation = op;
        this.a = a;
        this.b = b;
    }

    @Override

    public double get() {
        // 上游每个二元运算符读取操作数时都过 ValueConversions.asFloat（NaN→0），见
        // molang/runtime/ExpressionEvaluatorImpl.java:43-47；本引擎的对应物是 Operation.asDouble。
        return this.operation.calculate(Operation.asDouble(a.get()), Operation.asDouble(b.get()));
    }

    @Override

    public String toString() {
        return a.toString() + " " + this.operation.sign + " " + b.toString();
    }
}
