package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.entity.Entity;
import net.minecraft.util.Vec3;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.entity.EntityFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;

public class PositionDelta extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        int axis = arguments.getAsInt(context, 0);
        Vec3 delta = context.entity()
            .animatableEntity()
            .getStateTracker()
            .getPositionDelta();
        if (delta == null) {
            return 0.0f;
        }
        switch (axis) {
            case 0:
                return (float) delta.xCoord;
            case 1:
                return (float) delta.yCoord;
            case 2:
                return (float) delta.zCoord;
            default:
                return null;
        }
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
