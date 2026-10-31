package com.eliotlash.mclib.math;

import java.io.Serializable;

public class Constant implements IValue, Serializable {

    private static final long serialVersionUID = 42L;

    private double value;

    public Constant(double value) {
        this.value = value;
    }

    @Override

    public double get() {
        return this.value;
    }

    public void set(double value) {
        this.value = value;
    }

    @Override

    public String toString() {
        return String.valueOf(this.value);
    }
}
