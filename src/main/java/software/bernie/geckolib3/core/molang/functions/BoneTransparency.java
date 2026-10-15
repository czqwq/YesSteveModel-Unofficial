package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * {@code bone_transparency(bone, alpha)} - the per-bone alpha, 0..255.
 * <p>
 * The port of upstream's {@code BoneRenderFunction.Transparency} ({@code client/animation/molang/functions/
 * BoneRenderFunction.java:68-80}). The initial value is 255, i.e. opaque, which is what the renderer already assumed
 * before this existed.
 */
public class BoneTransparency extends Function {

    public BoneTransparency(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        MolangPhysicsRuntime.boneTransparency((int) getArg(0), getArg(1));
        return 0.0D;
    }
}
