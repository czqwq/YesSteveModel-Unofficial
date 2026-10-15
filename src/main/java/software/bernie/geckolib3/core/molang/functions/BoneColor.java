package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * {@code bone_color(bone, red, green, blue)} - the per-bone colour multiplier.
 * <p>
 * The port of upstream's {@code BoneRenderFunction.Color} ({@code client/animation/molang/functions/
 * BoneRenderFunction.java:52-66}): each channel is rounded and clamped to 0..255, the bone is named by the first
 * argument, and a bone this model does not have is ignored rather than raising. The initial value is white, so a
 * model that never calls it is unaffected.
 */
public class BoneColor extends Function {

    public BoneColor(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 4;
    }

    @Override
    public double get() {
        MolangPhysicsRuntime.boneColor((int) getArg(0), getArg(1), getArg(2), getArg(3));
        return 0.0D;
    }
}
