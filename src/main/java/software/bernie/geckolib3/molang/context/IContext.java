package software.bernie.geckolib3.molang.context;

import java.util.List;
import java.util.Random;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.IChatComponent;

import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Function;
import software.bernie.geckolib3.molang.storage.IContextVariableStorage;
import software.bernie.geckolib3.molang.storage.IForeignVariableStorage;
import software.bernie.geckolib3.molang.storage.IScopedVariableStorage;
import software.bernie.geckolib3.molang.storage.ITempVariableStorage;
import software.bernie.geckolib3.molang.value.IValue;

/**
 * Everything a MoLang expression can reach while it is being evaluated for one entity.
 * <p>
 * The host mod owns the animatable and the animation event; this interface is the boundary between them and the
 * engine, which is why it refers to the {@code IMolang*} interfaces instead of any concrete host class.
 */
public interface IContext<TEntity> {

    TEntity entity();

    IMolangAnimatable animatableEntity();

    Minecraft mc();

    WorldClient level();

    IMolangAnimationEvent animationEvent();

    EntityModelData data();

    @Nullable
    AnimationContext animationContext();

    @Nullable
    ControllerContext controllerContext();

    Random random();

    <TChild> IContext<TChild> createChild(TChild child);

    ITempVariableStorage tempStorage();

    IScopedVariableStorage scopedStorage();

    @Nullable
    IContextVariableStorage contextStorage();

    IForeignVariableStorage foreignStorage();

    @Nullable
    IValue getUserFunction(String name);

    Object callUserFunction(ExecutionContext<?> context, IValue value, List<?> args);

    Object callUserFunction(ExecutionContext<?> ctx, IValue value, Function.ArgumentCollection args);

    List<?> userFunctionArgs();

    boolean isDebugEnabled();

    /**
     * 是否允许生成行为（粒子、音效、骨骼变色、骨骼发光、相机变换等）
     */
    boolean allowEmitting();

    void debugPrint(String message, Object... args);

    void debugPrint(IChatComponent message);

    IMolangSoundManager getSoundManager(boolean global);
}
