package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.projectile.EntityArrow;

public class ArrowVariable extends LambdaVariable<EntityArrow> {
    public ArrowVariable(IValueEvaluator<?, IContext<EntityArrow>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityArrow;
    }
}
