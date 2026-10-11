package software.bernie.geckolib3.model;

import java.util.Collections;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.common.FMLCommonHandler;
import software.bernie.geckolib3.animation.AnimationTicker;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.ImmutableFramePose;
import software.bernie.geckolib3.geo.RenderContext;
import software.bernie.geckolib3.geo.exception.GeoModelException;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.model.provider.IAnimatableModelProvider;
import software.bernie.geckolib3.resource.GeckoLibCache;

@SuppressWarnings({ "rawtypes", "unchecked" })
public abstract class AnimatedGeoModel<T extends IAnimatable> extends GeoModelProvider<T>
    implements IAnimatableModel<T>, IAnimatableModelProvider<T> {

    private final AnimationProcessor animationProcessor;
    private GeoModel currentModel;

    protected AnimatedGeoModel() {
        this.animationProcessor = new AnimationProcessor(this);
    }

    public void registerBone(GeoBone bone) {
        registerModelRenderer(bone);

        for (GeoBone childBone : bone.childBones) {
            registerBone(childBone);
        }
    }

    @Override
    public void setLivingAnimations(T entity, Integer uniqueID, @Nullable AnimationEvent customPredicate) {
        // Each animation has it's own collection of animations (called the
        // EntityAnimationManager), which allows for multiple independent animations
        AnimationData manager = entity.getFactory()
            .getOrCreateAnimationData(uniqueID);
        manager.bindAnimationFile(getAnimationFileLocation(entity));
        if (manager.ticker == null) {
            AnimationTicker ticker = new AnimationTicker(manager);
            manager.ticker = ticker;
            FMLCommonHandler.instance()
                .bus()
                .register(ticker);
        }
        if (!Minecraft.getMinecraft()
            .isGamePaused() || manager.shouldPlayWhilePaused) {
            seekTime = manager.tick + Minecraft.getMinecraft().timer.renderPartialTicks;
        } else {
            seekTime = manager.tick;
        }

        AnimationEvent<T> predicate;
        if (customPredicate == null) {
            predicate = new AnimationEvent<T>(
                entity,
                0,
                0,
                (float) (manager.tick - lastGameTickTime),
                false,
                Collections.emptyList());
        } else {
            predicate = customPredicate;
        }

        predicate.animationTick = seekTime;
        animationProcessor.preAnimationSetup(predicate.getAnimatable(), seekTime);
        // 上游把"这一帧是不是不可变"解析在 animatable 身上（AnimatableEntity:452-454 的 resolveRenderContext →
        // :456-461 的白名单），但它有两条声明路径，本仓两条都留：animatable 自己声明（本仓的
        // CustomGuiPlayerEntity 按上游 client/gui/CustomGuiPlayerEntity.java:43-46 答 true），或者调用方直接
        // 交来一个已经不可变的 context（上游 client/entity/CustomEntity.java:247 把
        // RenderContext.levelImmutable() 直接交给 update(float,RenderContext)，根本不过 resolveRenderContext）。
        // 两条都不声明就是 false，也就是这个类型出现之前的老路径。
        RenderContext renderContext = predicate.getRenderContext();
        boolean immutable = renderContext.immutable()
            || predicate.getAnimatable()
                .determineImmutableContext(renderContext);
        ImmutableFramePose immutableFrame = manager.immutableFrame;
        if (!this.animationProcessor.getModelRendererList()
            .isEmpty()) {
            if (immutable && immutableFrame != null
                && immutableFrame.reuse(seekTime, this.animationProcessor.getModelRendererList())) {
                // 上游 AnimatableEntity:344（mutableRender = !ctx.immutable()）与 :373-374（shouldUpdate /
                // shouldTick 都在不可变且本帧已产出时为 false）+ :265-277（不可变时只有一份 mainRenderData）：
                // 同一个渲染帧里的第二次绘制不再推进动画，直接复用这一帧已经产出的姿势。上面那些步骤——时钟、
                // 事件、MoLang 作用域——都已经按第一次绘制的方式跑过，所以这一帧自身的状态没有被改变；被跳过的
                // 只有"再改写一次骨骼"的那次控制器求值。这正是让一个预览的两个写入者无法在同一帧内交换姿势的
                // 那一条。AnimationController.process 在这条路径上不会被调用，所以 AGENTS.md 记的"HUD/world
                // 两次 process 不得丢掉 transition 要去的动画"不受影响：那条不变式走的是可变语境（默认 false），
                // 而且引擎自建事件都是 RenderContext.NONE。
                return;
            }
            animationProcessor.tickAnimation(
                entity,
                uniqueID,
                seekTime,
                predicate,
                GeckoLibCache.getInstance().parser,
                shouldCrashOnMissing);
            if (immutable) {
                if (immutableFrame == null) {
                    immutableFrame = new ImmutableFramePose();
                    manager.immutableFrame = immutableFrame;
                }
                // 上游在这一步之后才 extractRenderData（AnimatableEntity:238-246），本仓的渲染数据就是活的骨骼，
                // 所以这里把这一帧的姿势记下来，供同一帧里的后续绘制写回。
                immutableFrame.capture(seekTime, this.animationProcessor.getModelRendererList());
            }
        }
    }

    @Override
    public AnimationProcessor getAnimationProcessor() {
        return this.animationProcessor;
    }

    public void registerModelRenderer(IBone modelRenderer) {
        animationProcessor.registerModelRenderer(modelRenderer);
    }

    @Override
    public Animation getAnimation(String name, IAnimatable animatable) {
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(this.getAnimationFileLocation((T) animatable));
        if (file != null) {
            return file.getAnimation(name);
        }
        return null;
    }

    @Override
    public GeoModel getModel(ResourceLocation location) {
        GeoModel model = super.getModel(location);
        if (model == null) {
            throw new GeoModelException(location, "Could not find model.");
        }
        if (model != currentModel) {
            // 命中骨骼登记缓存时只换引用：预览页十几个模型轮流用同一个模型实例，
            // 旧实现每次切换都要清空 + 递归重走整棵骨骼树 + 重存初始快照。
            if (!this.animationProcessor.selectModel(model)) {
                for (GeoBone bone : model.topLevelBones) {
                    registerBone(bone);
                }
            }
            this.currentModel = model;
        }
        return model;
    }

    public GeoModel getCurrentModel() {
        return currentModel;
    }

    /**
     * 资源框架释放这份几何（{@code ReleaseMode.DROP_HEAP}，见 {@code GeoModelProvider}）时调用。
     *
     * <p>两件事：让处理器丢掉骨骼登记；清掉 {@link #currentModel} 对它的强引用 ——
     * 后者只被赋新值、从不置空，于是"最后渲染过的那份几何"即使被淘汰也永远释放不掉
     * （骨骼表引用的 cube 几何跟着一起留）。释放后若再次渲染到该模型，取到的是重新解析出的
     * 新对象，会正常重建。
     */
    public void onModelReleased(GeoModel model) {
        if (model == null) {
            return;
        }
        this.animationProcessor.forgetModel(model);
        if (this.currentModel == model) {
            this.currentModel = null;
        }
    }

    @Override
    public double getCurrentTick() {
        return (Minecraft.getSystemTime() / 50d);
    }
}
