package software.bernie.geckolib3.molang.function.item;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.item.ItemStack;

public abstract class ItemStackFunction extends ContextFunction<ItemStack> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof ItemStack;
    }
}
