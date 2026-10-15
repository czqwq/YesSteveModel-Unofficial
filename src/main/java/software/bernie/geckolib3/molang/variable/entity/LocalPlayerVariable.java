package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.client.entity.EntityPlayerSP;

public class LocalPlayerVariable extends LambdaVariable<EntityPlayerSP> {
    public LocalPlayerVariable(IValueEvaluator<?, IContext<EntityPlayerSP>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityPlayerSP;
    }
}
