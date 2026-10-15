package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.projectile.EntityFishHook;

public class FishingHookVariable extends LambdaVariable<EntityFishHook> {
    public FishingHookVariable(IValueEvaluator<?, IContext<EntityFishHook>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityFishHook;
    }
}
