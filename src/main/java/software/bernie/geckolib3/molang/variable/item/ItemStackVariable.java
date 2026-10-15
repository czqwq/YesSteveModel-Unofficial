package software.bernie.geckolib3.molang.variable.item;

import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.item.ItemStack;

public class ItemStackVariable extends LambdaVariable<ItemStack> {
    public ItemStackVariable(IValueEvaluator<?, IContext<ItemStack>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof ItemStack;
    }
}
