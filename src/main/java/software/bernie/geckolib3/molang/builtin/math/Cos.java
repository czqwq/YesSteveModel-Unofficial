package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;
import net.minecraft.util.MathHelper;

public class Cos implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return MathHelper.cos(arguments.getAsFloat(context, 0) / 180f * (float) Math.PI);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
