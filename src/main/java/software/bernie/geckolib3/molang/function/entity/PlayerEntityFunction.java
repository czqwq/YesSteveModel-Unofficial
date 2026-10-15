package software.bernie.geckolib3.molang.function.entity;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.ContextFunction;
import net.minecraft.client.entity.AbstractClientPlayer;

public abstract class PlayerEntityFunction extends ContextFunction<AbstractClientPlayer> {
    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof AbstractClientPlayer;
    }
}
