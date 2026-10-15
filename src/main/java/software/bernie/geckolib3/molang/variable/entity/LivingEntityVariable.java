package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.EntityLivingBase;

public class LivingEntityVariable extends LambdaVariable<EntityLivingBase> {
    public LivingEntityVariable(IValueEvaluator<?, IContext<EntityLivingBase>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityLivingBase;
    }
}
