package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.entity.EntityLivingBase;

public abstract class LivingEntityFunction extends ContextFunction<EntityLivingBase> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityLivingBase;
    }
}
