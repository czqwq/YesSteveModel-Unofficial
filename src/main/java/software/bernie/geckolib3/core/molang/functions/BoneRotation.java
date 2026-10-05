package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

public class BoneRotation extends Function {

    public BoneRotation(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        // 本拓扑下引擎直接调用才是活路径：MolangPhysicsRuntime 在引擎里，桥只服务于
        // SOURCE 把引擎内嵌进 mod 时的布局（源侧由 AnimationRegister:249 接线）。
        // 桥未接线时回落到直接调用，绝不短路成 0。
        // 桥前置判断取自 SOURCE software/bernie/geckolib3/core/molang/functions/BoneRotation.java:19-20。
        MolangPhysicsBridge.Physics physics = MolangPhysicsBridge.physics;
        return physics == null ? MolangPhysicsRuntime.boneRotation((int) getArg(0), axis())
            : physics.boneRotation((int) getArg(0), axis());
    }

    /**
     * The axis this call site names: the last character of the registered name ({@code ysm.bone_rot_z} → {@code z}),
     * or {@code x} when the name carries no axis character - the unsuffixed vector form
     * {@code ysm.bone_rot('Arm')}, which a pack uses in a position the {@code ).x} rewrite cannot reach (upstream
     * answers a struct there and this parser has none). Falling back rather than rejecting keeps that position
     * working; see {@code MolangParser.VECTOR_FUNCTIONS}.
     */
    private char axis() {
        if (this.name == null || this.name.isEmpty()) {
            return 'x';
        }
        char axis = this.name.charAt(this.name.length() - 1);
        return axis == 'x' || axis == 'y' || axis == 'z' ? axis : 'x';
    }
}
