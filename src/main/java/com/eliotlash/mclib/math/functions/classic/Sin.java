package com.eliotlash.mclib.math.functions.classic;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * Radian sine - and <b>unreachable from MoLang parsing</b>, so do not read it as the live {@code math.sin}.
 * <p>
 * {@code MolangParser.doCoreRemaps} overwrites the short key with the degrees implementation first
 * ({@code software/bernie/geckolib3/core/molang/MolangParser.java:99}
 * {@code this.functions.put("sin", SinDegrees.class)}) and only afterwards moves it to the Bedrock name
 * ({@code MolangParser.java:181} {@code remap("sin", "math.sin")}, whose {@code remove}/{@code put} pair is
 * {@code :208-213}) - so nothing ever looks this class up, and a pack's {@code math.sin} runs {@code SinDegrees}
 * (degrees), which is what upstream does
 * ({@code tmp/YesSteveModel-dev-1.20 .../builtin/math/Sin.java:11} {@code Mth.sin(arg / 180f * PI)}). Do not convert
 * this class to degrees and do not delete it: {@code MathBuilder.java:52} still names it in the builder's table, and
 * the built-in animations are authored in degrees. See {@code .agent/phase27-mclib-math-defects.md}.
 */
public class Sin extends Function {

    public Sin(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override

    public int getRequiredArguments() {
        return 1;
    }

    @Override

    public double get() {
        return Math.sin(this.getArg(0));
    }
}
