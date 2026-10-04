package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.entity.projectile.EntityArrow;

public abstract class ArrowEntityFunction extends ContextFunction<EntityArrow> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityArrow;
    }
}
