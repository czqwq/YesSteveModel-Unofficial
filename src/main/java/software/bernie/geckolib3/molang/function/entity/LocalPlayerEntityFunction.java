package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.client.entity.EntityPlayerSP;

public abstract class LocalPlayerEntityFunction extends ContextFunction<EntityPlayerSP> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityPlayerSP;
    }
}
