package com.eliotlash.mclib.math;

public class Ternary implements IValue {

    public IValue condition;
    public IValue ifTrue;
    public IValue ifFalse;

    public Ternary(IValue condition, IValue ifTrue, IValue ifFalse) {
        this.condition = condition;
        this.ifTrue = ifTrue;
        this.ifFalse = ifFalse;
    }

    @Override

    public double get() {
        // 上游三元只访问被选中的那一支，条件过 asBoolean（ExpressionEvaluatorImpl.java:105-111）。
        return Operation.asBoolean(this.condition.get()) ? this.ifTrue.get() : this.ifFalse.get();
    }

    @Override

    public String toString() {
        return this.condition.toString() + " ? " + this.ifTrue.toString() + " : " + this.ifFalse.toString();
    }
}
