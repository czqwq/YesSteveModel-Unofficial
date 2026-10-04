package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;

/**
 * Converts a model rotation into the camera's rotation, so a bone can follow the view.
 * <p>
 * 1.20.1 read the render camera; 1.7.10 has no camera object, so the view entity's interpolated rotation is used
 * instead, which is what the camera is derived from.
 */
public class Rot2Camera extends ContextFunction<Object> {
    @Override
    protected Object eval(ExecutionContext<IContext<Object>> ctx, ArgumentCollection arguments) {
        int axis = arguments.getAsInt(ctx, 0);
        if (axis < 0 || axis > 1) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc == null ? null : mc.renderViewEntity;
        if (view == null) {
            return axis == 0 ? 0.0f : 180.0f;
        }
        float partial = ctx.entity()
            .animationEvent()
            .getPartialTick();
        float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partial;
        float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partial;
        if (axis == 0) {
            return -pitch;
        } else {
            return 180 + yaw;
        }
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
