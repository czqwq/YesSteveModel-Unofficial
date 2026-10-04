package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * {@code bone_glow(bone, level)} - the per-bone emissive level, -1 (not emissive) or 0..15.
 * <p>
 * The port of upstream's {@code BoneRenderFunction.Glow} ({@code client/animation/molang/functions/
 * BoneRenderFunction.java:82-94}). Upstream stores -1 as {@code 0xFF} in the high byte of the packed
 * transparency+glow attribute and leaves the value at that default, so a model that never calls this is lit exactly
 * as before. The level itself is the light value the bone is drawn at.
 */
public class BoneGlow extends Function {

    public BoneGlow(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        MolangPhysicsRuntime.boneGlow((int) getArg(0), getArg(1));
        return 0.0D;
    }
}
