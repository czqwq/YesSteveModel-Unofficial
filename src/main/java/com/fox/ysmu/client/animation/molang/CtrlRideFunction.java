package com.fox.ysmu.client.animation.molang;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.animation.condition.ConditionalEntityIdMatch;

/**
 * {@code ctrl.ride(type, matcher)} 的 mclib 实现（YSM-wiki: 动画控制器「函数」）。
 *
 * <p>wiki 的用法是 {@code ctrl.ride('vehicle', '$minecraft:pig')} 与
 * {@code ctrl.ride('passenger', '$minecraft:pig')}；上游的真实现是
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/animation/molang/functions/RideCheck.java:28-72}
 * （由 {@code .../molang/CtrlBinding.java:82} 的 {@code function("ride", RideCheck.rideCheck())} 注册），
 * 参数个数校验为 2 或 3（{@code RideCheck.java:74-77}）。</p>
 *
 * <p>本仓此前只有控制器条件路径的一条简化实现（{@code OpenYsmControllerExpressionEvaluator} 里
 * {@code return player.isRiding() ? TRUE : FALSE; }），关键帧/时间轴查找的是 mclib 函数表，表里没有这个名字 ——
 * 于是 {@code ctrl.ride(...)} 在关键帧里是一次解析失败，整条通道被丢弃。
 * 参考分支注册的 {@code CtrlHoldFunction} 是恒 0 桩
 * （{@code tmp/YesSteveModel-Unofficial-perf-previewUI/src/main/java/com/fox/ysmu/client/animation/molang/CtrlHoldFunction.java:11-14}
 * 的 {@code return 0;}），不能照抄，所以这里移植的是上游的真机制。</p>
 *
 * <p><b>1.7.10 缺口</b>：上游的 {@code #<实体 tag>} 那一半靠 {@code ForgeRegistries.ENTITY_TYPES.tags()}
 * （{@code RideCheck.java:61-69}）；1.7.10 的 {@code EntityList} 只有注册名、没有实体标签管理器，
 * 所以 {@code #} 恒 false —— 与上游"标签不匹配时的返回"一致，不是新语义。</p>
 */
public class CtrlRideFunction extends Function {

    public CtrlRideFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    /** 上游 {@code validateArgumentSize} 是 2 或 3；mclib 只校验下限，2 即可同时放行两种写法。 */
    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        try {
            String type = CtrlScriptBinding.stringArgument(this.args, 0, "");
            String matcher = CtrlScriptBinding.stringArgument(this.args, 1, "");
            return ridingMatches(MolangFrameContext.getEntity(), type, matcher) ? 1.0d : 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /**
     * The whole {@code ctrl.ride} answer for one entity, so the animation-file function table and the controller
     * condition path ({@code OpenYsmControllerExpressionEvaluator}) share one rule instead of drifting - the same
     * reason {@code ctrl.armor}/{@code ctrl.hold} delegate to {@code CtrlItemMatcher}/{@code InnerClassify}.
     */
    public static boolean ridingMatches(@Nullable EntityLivingBase entity, String type, String matcher) {
        Entity other = riddenEntity(entity, type);
        if (other == null || !other.isEntityAlive()) {
            return false;
        }
        return matchesId(matcher, EntityList.getEntityString(other));
    }

    /**
     * The one entity upstream looks at for each {@code type}: the vehicle for {@code vehicle}, the first passenger
     * for {@code passenger} - anything else is false ({@code RideCheck.java:37-44}).
     * <p>
     * 1.7.10 has a single {@code ridingEntity}/{@code riddenByEntity} pair, which is that same pair: upstream's
     * {@code getVehicle()}/{@code getFirstPassenger()} for a one-seat 1.7.10 mount.
     */
    @Nullable
    static Entity riddenEntity(@Nullable EntityLivingBase entity, String type) {
        if (entity == null) {
            return null;
        }
        if ("vehicle".equals(type)) {
            return entity.ridingEntity;
        }
        if ("passenger".equals(type)) {
            return entity.riddenByEntity;
        }
        return null;
    }

    /**
     * Whether {@code matcher} names the entity registered as {@code registryName}.
     * <p>
     * 上游的匹配规则（{@code RideCheck.java:50-71}）：先砍掉前缀字符，{@code $} 半段与实体注册名比较；
     * {@code #} 半段走实体 tag；其余一律 false。这里把 id 归一化交给
     * {@link ConditionalEntityIdMatch#normalizedId(String)}——它就是 {@code vehicle$<id>} 动画名用的那条规则
     * （1.7.10 的 {@code EntityList} 字符串 {@code "Pig"} 与包里的 {@code "minecraft:pig"} 归一成同一个形态），
     * 两条路径因此不会各自漂移。
     */
    static boolean matchesId(@Nullable String matcher, @Nullable String registryName) {
        if (matcher == null || matcher.isEmpty()) {
            // 上游 StringUtils.isBlank(input) -> FALSE (RideCheck.java:33-35)
            return false;
        }
        // 1.7.10 没有实体 tag 管理器：'#' 这一半无法回答，恒 false（与上游"标签不匹配"同形）。
        if (matcher.startsWith("#")) {
            return false;
        }
        if (!matcher.startsWith("$") || registryName == null) {
            return false;
        }
        return ConditionalEntityIdMatch.normalizedId(matcher.substring(1))
            .equals(ConditionalEntityIdMatch.normalizedId(registryName));
    }
}
