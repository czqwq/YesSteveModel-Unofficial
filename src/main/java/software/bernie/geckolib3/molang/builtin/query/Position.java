package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.entity.Entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.entity.EntityFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.util.MolangUtils;

public class Position extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        int axis = arguments.getAsInt(context, 0);
        float partialTicks = context.entity()
            .animationEvent()
            .getPartialTick();
        Entity entity = context.entity()
            .entity();
        switch (axis) {
            case 0:
                return MolangUtils.lerp(partialTicks, (float) entity.prevPosX, (float) entity.posX);
            case 1:
                return MolangUtils.lerp(partialTicks, (float) entity.prevPosY, (float) entity.posY);
            case 2:
                return MolangUtils.lerp(partialTicks, (float) entity.prevPosZ, (float) entity.posZ);
            default:
                return null;
        }
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
