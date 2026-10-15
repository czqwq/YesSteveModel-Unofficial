package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.entity.EntityLiving;

public abstract class MobEntityFunction extends ContextFunction<EntityLiving> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityLiving;
    }
}
