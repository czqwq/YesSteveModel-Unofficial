package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;

public class Mod implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return arguments.getAsFloat(context, 0) % arguments.getAsFloat(context, 1);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2;
    }
}
