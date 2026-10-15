package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.passive.EntityTameable;

public class TamableEntityVariable extends LambdaVariable<EntityTameable> {
    public TamableEntityVariable(IValueEvaluator<?, IContext<EntityTameable>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityTameable;
    }
}
