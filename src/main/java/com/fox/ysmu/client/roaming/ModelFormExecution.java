package com.fox.ysmu.client.roaming;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.audio.MolangSoundContext;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * 在渲染器已经建立的 {@code (entity, model)} MoLang 作用域里执行一段完整的 MoLang 程序；今天唯一的调用方是
 * {@code /ysm molang execute <targets> <expression>} 的客户端回执（{@code S2CExecuteMolang} →
 * {@code client/animation/molang/RemoteMolangExecution}）。
 *
 * <h2>上游对照</h2>
 * 上游 {@code command/sub/MolangCommand.java:26-50} 把表达式与目标 id 广播给所有客户端，每个观察者的客户端在
 * {@code network/forge/ControlHandler.executeMolang:253-272} 里按 id 找到玩家、解析表达式并交给该玩家的 animatable 执行
 * （{@code capability.executeMolangExp(value, true, false, null)}），即"在该实体自己的表达式运行时里同步执行"。
 * 移植的等价作用域由渲染器在 {@link MolangPhysicsRuntime#begin} 里建立，所以请求必须排队到该实体下一次渲染 pass 才能
 * 落到真实作用域上；这是本类存在的原因（也是 {@code /ysm molang execute} 对尚未渲染的实体要等一帧的特性）。
 *
 * <h2>目标（{@link Target}）：移植特有，但来自一次真实缺陷</h2>
 * 1.7.10 侧的"GUI 预览"是 {@link CustomGuiPlayerEntity}，它与世界角色共用 {@link CustomPlayerEntity} 的渲染入口，也可能
 * 被同一个 owner+model 绘制。设置面板把关屏时的动作排在队列里且不过期，那个请求随后可能被"任何 owner+model 相同的预览"
 * 消费——例如回到模型选择屏后同模型的目录格子，副作用于是在离屏后迟到执行。上游没有这个问题：它的动作执行目标是受试
 * 实体的 capability，不存在"同 owner 的预览替身"。因此给请求加一个目标类别：{@code /ysm molang execute} 只投
 * {@link Target#WORLD}，只有真正的世界 animatable（{@link CustomPlayerEntity#getPlayer()} 非 {@code null}）能消费它；
 * {@link Target#PREVIEW} 留给确实以预览为目标的调用；{@link Target#ANY} 是旧行为（任何 animatable）。
 * <p>
 * <b>模型设置面板不再使用本类。</b>phase78 起它的读写在 {@link ConfigActionRuntime} 里同步执行（照上游 GUI 直接对本地
 * 玩家运行时求值），因此不再有"关屏残留的 GUI 请求"这个来源。
 */
public final class ModelFormExecution {

    /** 一次请求允许被哪种 animatable 消费；见类注释"目标"一节。 */
    public enum Target {
        /** 只允许真实世界 animatable（{@code getPlayer() != null}）。 */
        WORLD,
        /** 只允许 GUI 预览 animatable。 */
        PREVIEW,
        /** 两者皆可（本类旧行为，仅保留给以预览为执行上下文的调用方）。 */
        ANY;

        /** 该类别是否接受当前这次 drain 的 animatable。 */
        private boolean matches(boolean preview, boolean playerWorld) {
            switch (this) {
                case WORLD:
                    // 上游 executeMolangExp 作用在 player 的 capability 上；NPC/其他世界 animatable 不算世界目标。
                    return playerWorld;
                case PREVIEW:
                    return preview;
                default:
                    // 本类旧行为：任何 animatable 都可以消费（包含 NPC 这类既非玩家世界体也非预览的世界实例）。
                    return true;
            }
        }
    }

    private static final List<Request> PENDING = new ArrayList<>();

    private ModelFormExecution() {}

    public static boolean submit(EntityPlayer owner, ResourceLocation modelId, String source,
        Consumer<Double> result, BooleanSupplier stillCurrent) {
        return submit(owner, modelId, source, Target.WORLD, result, stillCurrent);
    }

    public static boolean submit(EntityPlayer owner, ResourceLocation modelId, String source, Target target,
        Consumer<Double> result, BooleanSupplier stillCurrent) {
        RoamingProgram.Compiled program = RoamingProgram.compileCached(source);
        if (program == null) {
            return false;
        }
        return submit(owner, modelId, program, target, result, stillCurrent);
    }

    public static boolean submit(EntityPlayer owner, ResourceLocation modelId, RoamingProgram.Compiled program,
        Consumer<Double> result, BooleanSupplier stillCurrent) {
        return submit(owner, modelId, program, Target.WORLD, result, stillCurrent);
    }

    public static boolean submit(EntityPlayer owner, ResourceLocation modelId, RoamingProgram.Compiled program,
        Target target, Consumer<Double> result, BooleanSupplier stillCurrent) {
        if (program == null || owner == null || modelId == null) {
            return false;
        }
        PENDING.add(new Request(owner, modelId, program, target, result, stillCurrent));
        return true;
    }

    /**
     * Called after both the entity query bindings and the real physics scope have been established. 请求在这里被消费：
     * 按目标类别匹配 animatable（见 {@link Target}），匹配上才在它的作用域里求值。
     */
    public static void drain(CustomPlayerEntity animatable) {
        EntityPlayer owner = animatable instanceof CustomGuiPlayerEntity preview
            ? preview.getExecutionOwner() : animatable.getPlayer();
        if (owner == null) return;
        ResourceLocation modelId = ModelIdUtil.getModelIdFromMainId(animatable.getMolangModelId());
        boolean preview = animatable instanceof CustomGuiPlayerEntity;
        boolean playerWorld = !preview && animatable.getPlayer() != null;
        List<Request> ready = new ArrayList<>();
        for (Iterator<Request> iterator = PENDING.iterator(); iterator.hasNext();) {
            Request request = iterator.next();
            if (!request.stillCurrent.getAsBoolean() || request.ownerEntity.worldObj != Minecraft.getMinecraft().theWorld
                || request.ownerEntity.isDead) {
                iterator.remove();
            } else if (request.owner.equals(owner.getUniqueID()) && request.modelId.equals(modelId)
                && request.target.matches(preview, playerWorld)) {
                    ready.add(request);
                    iterator.remove();
                }
        }
        for (Request request : ready) {
            boolean ui = animatable instanceof CustomGuiPlayerEntity;
            try (MolangSoundContext sound = MolangSoundContext
                .push(ui ? animatable : animatable.getMolangEntity(), ui ? null : animatable.getMolangEntity(),
                    animatable.getMolangModelId(), null, ui, true)) {
                double value = request.program.evaluate();
                if (request.result != null && request.stillCurrent.getAsBoolean()) request.result.accept(value);
            } catch (Exception failure) {
                ysmu.LOG.warn("Failed to execute model form Molang '{}': {}", request.program.source(), failure.getMessage());
            }
        }
    }

    /** Prune cancelled GUI reads immediately rather than retaining closed screens until another render pass. */
    public static void cancelInvalid() {
        PENDING.removeIf(request -> !request.stillCurrent.getAsBoolean());
    }

    public static void clear() { PENDING.clear(); }

    private static final class Request {
        final UUID owner;
        final EntityPlayer ownerEntity;
        final ResourceLocation modelId;
        final RoamingProgram.Compiled program;
        final Target target;
        @Nullable
        final Consumer<Double> result;
        final BooleanSupplier stillCurrent;

        Request(EntityPlayer owner, ResourceLocation modelId, RoamingProgram.Compiled program, Target target,
            @Nullable Consumer<Double> result, BooleanSupplier stillCurrent) {
            this.owner = owner.getUniqueID();
            this.ownerEntity = owner;
            this.modelId = modelId;
            this.program = program;
            this.target = target;
            this.result = result;
            this.stillCurrent = stillCurrent;
        }
    }
}
