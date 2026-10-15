package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.EntityLiving;

public class MobEntityVariable extends LambdaVariable<EntityLiving> {
    public MobEntityVariable(IValueEvaluator<?, IContext<EntityLiving>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityLiving;
    }
}
