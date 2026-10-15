package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.projectile.EntityThrowable;

public class ThrowableItemProjectileVariable extends LambdaVariable<EntityThrowable> {
    public ThrowableItemProjectileVariable(IValueEvaluator<?, IContext<EntityThrowable>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityThrowable;
    }
}
