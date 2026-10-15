package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;
import net.minecraft.util.MathHelper;

public class Trunc implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double value = arguments.getAsFloat(context, 0);
        return value < 0 ? MathHelper.ceiling_double_int(value) : MathHelper.floor_double(value);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
