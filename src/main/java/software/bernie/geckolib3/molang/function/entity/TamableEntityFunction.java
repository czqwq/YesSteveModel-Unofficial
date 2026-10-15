package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.entity.passive.EntityTameable;

public abstract class TamableEntityFunction extends ContextFunction<EntityTameable> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityTameable;
    }
}
