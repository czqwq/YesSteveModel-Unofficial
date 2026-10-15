package com.eliotlash.mclib.math.functions.classic;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * Radian cosine - and <b>unreachable from MoLang parsing</b>, so do not read it as the live {@code math.cos}.
 * <p>
 * {@code MolangParser.doCoreRemaps} overwrites the short key with the degrees implementation first
 * ({@code software/bernie/geckolib3/core/molang/MolangParser.java:98}
 * {@code this.functions.put("cos", CosDegrees.class)}) and only afterwards moves it to the Bedrock name
 * ({@code MolangParser.java:153} {@code remap("cos", "math.cos")}, whose {@code remove}/{@code put} pair is
 * {@code :208-213}) - so nothing ever looks this class up, and a pack's {@code math.cos} runs {@code CosDegrees}
 * (degrees), which is what upstream does
 * ({@code tmp/YesSteveModel-dev-1.20 .../builtin/math/Cos.java:11} {@code Mth.cos(arg / 180f * PI)}). Do not convert
 * this class to degrees and do not delete it: {@code MathBuilder.java:51} still names it in the builder's table, and
 * the built-in animations are authored in degrees. See {@code .agent/phase27-mclib-math-defects.md}.
 */
public class Cos extends Function {

    public Cos(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override

    public int getRequiredArguments() {
        return 1;
    }

    @Override

    public double get() {
        return Math.cos(this.getArg(0));
    }
}
