package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.entity.EntityFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.util.MolangUtils;

/**
 * 1.7.10 has no block tags; names are matched against the OreDictionary instead.
 */
public class RelativeBlockHasAnyTag extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> ctx, ArgumentCollection arguments) {
        Block block = MolangUtils.getRelativeBlock(ctx, arguments);
        if (block == null) {
            return null;
        }
        for (int i = 3; i < arguments.size(); i++) {
            ResourceLocation tagId = arguments.getAsResourceLocation(ctx, i);
            if (tagId == null) {
                return null;
            }
            if (MolangUtils.blockMatchesTag(block, tagId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 4;
    }
}
