package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.entity.Entity;

public abstract class EntityFunction extends ContextFunction<Entity> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof Entity;
    }
}
