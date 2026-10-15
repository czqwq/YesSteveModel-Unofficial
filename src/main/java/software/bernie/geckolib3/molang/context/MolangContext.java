package software.bernie.geckolib3.molang.context;

import java.util.List;
import java.util.Random;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.IChatComponent;

import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.ExpressionEvaluator;
import software.bernie.geckolib3.molang.runtime.Function;
import software.bernie.geckolib3.molang.storage.IContextVariableStorage;
import software.bernie.geckolib3.molang.storage.IForeignVariableStorage;
import software.bernie.geckolib3.molang.storage.IScopedVariableStorage;
import software.bernie.geckolib3.molang.storage.ITempVariableStorage;
import software.bernie.geckolib3.molang.storage.MolangMemory;
import software.bernie.geckolib3.molang.value.IValue;

public class MolangContext<TEntity> implements IContext<TEntity> {
    protected final TEntity entity;
    protected final IMolangAnimatable animatableEntity;
    protected final IMolangAnimationEvent animationEvent;
    protected final EntityModelData data;
    protected final IMolangSoundManager globalSoundManager;
    protected final Random random;
    protected final MolangMemory memory;
    private final DebugSource debugSource;

    protected AnimationContext animationContext;
    protected ControllerContext controllerContext;
    protected IForeignVariableStorage foreignStorage;
    private boolean allowEmitting;

    public MolangContext(TEntity entity, IMolangAnimationEvent animationEvent,
                         MolangMemory memory, Random random, IMolangSoundManager globalSoundManager) {
        this.entity = entity;
        this.animatableEntity = animationEvent.getAnimatableEntity();
        this.animationEvent = animationEvent;
        this.data = animationEvent.getExtraData();
        this.debugSource = animationEvent.getDebugSource();
        this.memory = memory;
        this.foreignStorage = memory;
        this.random = random;
        this.globalSoundManager = globalSoundManager;
    }

    // TODO
    private MolangContext(TEntity entity, MolangContext<?> context) {
        this.entity = entity;
        this.animatableEntity = context.animatableEntity;
        this.animationEvent = context.animationEvent;
        this.data = context.data;
        this.animationContext = context.animationContext;
        this.random = context.random;
        this.memory = context.memory;
        this.debugSource = context.debugSource;
        this.globalSoundManager = context.globalSoundManager;
        // 1.7.10 has no capabilities, so the host mod decides which entities carry roaming variables.
        IForeignVariableStorage resolved = MolangForeignStorage.resolve(entity);
        if (resolved != null) {
            foreignStorage = resolved;
        }
    }

    @Override
    public IMolangAnimationEvent animationEvent() {
        return animationEvent;
    }

    @Override
    public IMolangAnimatable animatableEntity() {
        return animatableEntity;
    }

    @Override
    public EntityModelData data() {
        return data;
    }

    @Override
    public AnimationContext animationContext() {
        return animationContext;
    }

    @Override
    public ControllerContext controllerContext() {
        return controllerContext;
    }

    @Override
    public Random random() {
        return random;
    }

    @Override
    public TEntity entity() {
        return entity;
    }

    @Override
    public Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    @Override
    public WorldClient level() {
        Minecraft mc = mc();
        if (mc != null) {
            return mc.theWorld;
        } else {
            return null;
        }
    }

    // FIXME: 需要同时更新 animatable 和 entity 两个属性，再加上源属性
    @Override
    public <TChild> IContext<TChild> createChild(TChild child) {
        return new MolangContext<>(child, this);
    }

    @Override
    public ITempVariableStorage tempStorage() {
        return memory.getStackMemory();
    }

    @Override
    public IScopedVariableStorage scopedStorage() {
        return memory;
    }

    @Override
    public IForeignVariableStorage foreignStorage() {
        return foreignStorage;
    }

    @Override
    public @Nullable IContextVariableStorage contextStorage() {
        return animationContext;
    }

    @Override
    public @Nullable IValue getUserFunction(String name) {
        IMolangAnimatable animatable = animatableEntity;
        return animatable == null ? null : animatable.getUserFunction(name);
    }

    @Override
    public Object callUserFunction(ExecutionContext<?> ctx, IValue value, List<?> args) {
        if (this.memory.getStackMemory().push(args)) {
            try {
                return value.eval((ExpressionEvaluator<?>) ctx);
            } finally {
                this.memory.getStackMemory().pop();
            }
        }
        return null;
    }

    @Override
    public Object callUserFunction(ExecutionContext<?> ctx, IValue value, Function.ArgumentCollection args) {
        if (this.memory.getStackMemory().push(ctx, args)) {
            try {
                return value.eval((ExpressionEvaluator<?>) ctx);
            } finally {
                this.memory.getStackMemory().pop();
            }
        }
        return null;
    }

    @Override
    public List<?> userFunctionArgs() {
        return memory.getStackMemory().argsAccessor();
    }

    @Override
    public boolean isDebugEnabled() {
        return debugSource != null;
    }

    @Override
    public boolean allowEmitting() {
        return allowEmitting;
    }

    public void setAllowEmitting(boolean value) {
        allowEmitting = value;
    }

    @Override
    public void debugPrint(String message, Object... args) {
        if (isDebugEnabled()) {
            debugSource.print(message, args);
        }
    }

    @Override
    public void debugPrint(IChatComponent message) {
        if (isDebugEnabled()) {
            debugSource.print(message);
        }
    }

    @Override
    @Nullable
    public IMolangSoundManager getSoundManager(boolean global) {
        if (!global) {
            if (animationContext != null) {
                IMolangSoundManager manager = animationContext.soundManager();
                if (manager != null) {
                    return manager;
                }
            }
            if (controllerContext != null) {
                IMolangSoundManager manager = controllerContext.soundManager();
                if (manager != null) {
                    return manager;
                }
            }
        }
        return this.globalSoundManager;
    }

    public void setAnimationContext(AnimationContext ctx) {
        this.animationContext = ctx;
    }

    public void setControllerContext(ControllerContext ctx) {
        this.controllerContext = ctx;
    }
}
