package com.eliotlash.mclib.math;

import java.io.Serializable;

public class Group implements IValue, Serializable {

    private static final long serialVersionUID = 42L;

    private IValue value;

    public Group(IValue value) {
        this.value = value;
    }

    @Override

    public double get() {
        return this.value.get();
    }

    @Override

    public String toString() {
        return "(" + this.value.toString() + ")";
    }
}
