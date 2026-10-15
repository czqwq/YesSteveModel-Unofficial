package software.bernie.geckolib3.molang.variable.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.client.entity.AbstractClientPlayer;

public class ClientPlayerVariable extends LambdaVariable<AbstractClientPlayer> {
    public ClientPlayerVariable(IValueEvaluator<?, IContext<AbstractClientPlayer>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof AbstractClientPlayer;
    }
}
