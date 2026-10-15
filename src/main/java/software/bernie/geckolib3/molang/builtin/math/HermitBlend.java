package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;
import net.minecraft.util.MathHelper;

public class HermitBlend implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double min = MathHelper.ceiling_float_int(arguments.getAsFloat(context, 0));
        return MathHelper.floor_double(3.0d * Math.pow(min, 2.0d) - 2.0d * Math.pow(min, 3.0d));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
