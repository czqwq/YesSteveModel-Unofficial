package software.bernie.geckolib3.molang.builtin.math;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;

public class DieRoll extends ContextFunction<Object> {
    @Override
    public boolean validateArgumentSize(int size) {
        return size == 3;
    }

    @Override
    protected Object eval(ExecutionContext<IContext<Object>> context, ArgumentCollection arguments) {
        int i = arguments.getAsInt(context, 0);
        float min = arguments.getAsFloat(context, 1);
        float range = arguments.getAsFloat(context, 2);
        if(min > range) {
            float temp = min;
            min = range;
            range = temp - range;
        } else {
            range -= min;
        }
        float total = 0;
        var rnd = context.entity().random();
        while (i-- > 0) {
            total += min + rnd.nextFloat() * range;
        }
        return total;
    }
}
