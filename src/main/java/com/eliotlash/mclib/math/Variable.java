package com.eliotlash.mclib.math;

import java.io.Serializable;

public class Variable implements IValue, Serializable {

    private static final long serialVersionUID = 42L;

    private String name;
    private double value;

    public Variable(String name, double value) {
        this.name = name;
        this.value = value;
    }

    public void set(double value) {
        this.value = value;
    }

    @Override

    public double get() {
        return this.value;
    }

    public String getName() {
        return name;
    }

    @Override

    public String toString() {
        return this.name;
    }
}
