package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;
import net.minecraft.util.MathHelper;

public class Clamp implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return MathHelper.clamp_float(arguments.getAsFloat(context, 0),
                arguments.getAsFloat(context, 1),
                arguments.getAsFloat(context, 2));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 3;
    }
}
