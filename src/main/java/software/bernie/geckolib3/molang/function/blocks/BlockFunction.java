package software.bernie.geckolib3.molang.function.blocks;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.block.Block;

public abstract class BlockFunction extends ContextFunction<Block> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof Block;
    }
}
