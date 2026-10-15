package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

public class SecondOrder extends Function {

    public SecondOrder(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        int nameId = (int) this.getArg(0);
        double input = this.getArg(1);
        double frequency = this.args.length >= 3 ? this.getArg(2) : 1.0D;
        double coefficient = this.args.length >= 4 ? this.getArg(3) : 1.0D;
        double response = this.args.length >= 5 ? this.getArg(4) : 1.0D;
        // 本拓扑下引擎直接调用才是活路径：MolangPhysicsRuntime 在引擎里，桥只服务于
        // SOURCE 把引擎内嵌进 mod 时的布局（源侧由 AnimationRegister:249 接线）。
        // 桥未接线时回落到直接调用，绝不短路成 0。
        // 桥前置判断取自 SOURCE software/bernie/geckolib3/core/molang/functions/SecondOrder.java:24-25。
        MolangPhysicsBridge.Physics physics = MolangPhysicsBridge.physics;
        return physics == null
            ? MolangPhysicsRuntime.secondOrder(nameId, input, frequency, coefficient, response)
            : physics.secondOrder(nameId, input, frequency, coefficient, response);
    }
}
