package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.Entity;

public class ProjectileVariable extends LambdaVariable<Entity> {
    public ProjectileVariable(IValueEvaluator<?, IContext<Entity>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof Entity;
    }
}
