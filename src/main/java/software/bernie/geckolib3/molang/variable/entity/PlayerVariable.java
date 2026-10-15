package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.entity.player.EntityPlayer;

public class PlayerVariable extends LambdaVariable<EntityPlayer> {
    public PlayerVariable(IValueEvaluator<?, IContext<EntityPlayer>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityPlayer;
    }
}
