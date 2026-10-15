package software.bernie.geckolib3.molang.variable.item;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.item.Item;

public class ItemVariable extends LambdaVariable<Item> {
    public ItemVariable(IValueEvaluator<?, IContext<Item>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof Item;
    }
}
