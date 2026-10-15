package software.bernie.geckolib3.molang.variable.block;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.variable.IValueEvaluator;
import software.bernie.geckolib3.molang.variable.LambdaVariable;
import net.minecraft.block.Block;

/**
 * 1.20.1 keyed this off {@code BlockState}; 1.7.10 splits a block from its metadata, so the carrier here is the
 * {@link Block} itself.
 */
public class BlockStateVariable extends LambdaVariable<Block> {
    public BlockStateVariable(IValueEvaluator<?, IContext<Block>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof Block;
    }
}
