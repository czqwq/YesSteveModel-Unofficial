package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;
import net.minecraft.util.MathHelper;

public class Ceil implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return MathHelper.ceiling_float_int(arguments.getAsFloat(context, 0));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
