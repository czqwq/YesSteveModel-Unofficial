package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.MathBuilder;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 箭头表达式（{@code a -> b}）的宿主实现：把右边放进左边指出的上下文里求值。
 *
 * <p>上游的语义（{@code tmp/YesSteveModel-dev-1.20}）：{@code BinaryExpression.Op.ARROW} 是优先级最高的二元运算符
 * （{@code .../molang/parser/BinaryExpression.java:127}，解析见 {@code MolangParserImpl.java:313}），求值器先算左边，
 * 左边为 null 时整体为 null，否则 {@code createChild(左边)} 造一个子求值器再跑右边
 * （{@code .../molang/runtime/ExpressionEvaluatorImpl.java:58-69}）。wiki 记的用例就是它：
 * {@code ysm.projectile_owner->ysm.equipped_enchantment_level('Mainhand', 'minecraft:flame')}
 * （{@code tmp/wiki/molang_misc.md:148}）。
 *
 * <p>"换掉 subject"在本引擎里要换三处，都是这条关键帧路径上已有的"当前主体"：
 * <ul>
 *   <li>{@link ParticleEffectUtil#getCurrentEntity()} —— mclib 函数（{@code ysm.equipped_enchantment_level}、
 *       {@code ctrl.hold}、{@code query.item_*} 等）读的当前实体；</li>
 *   <li>{@link MolangFrameContext} —— {@code query.position_delta}、{@code ysm.texture_name}、
 *       {@code ctrl.ride}/{@code ctrl.hold} 这类读的帧主体；</li>
 *   <li>{@link EntityQueryScope#publishForRead} —— 61 个 {@code query.*} 名字的值本身就是每帧发布进解析器表的，
 *       换主体就得重新发布它们，这正是那个方法存在的用途（它会在关闭时把表还原）。</li>
 * </ul>
 * 三处都还原（{@code finally}），所以一次箭头求值不会把主体留给后面的表达式——与
 * {@code EntityQueryScope} 自己的"泄漏与修复都限定在这次读取内"同一约定。
 *
 * <p>左侧不是句柄（0、未知句柄）时返回 0，对应上游的"左侧为 null 则整体为 null"：在只有 double 的引擎里
 * null 读作假，{@code ??} 因此会照常取右侧——wiki 的用例正是 {@code v.flame_level ?? (v.flame_level = ...)}。
 *
 * <p>句柄由 {@link MolangContextHandles} 表示，帧末随表清空（上游用活对象，且 wiki 自记有内存泄漏；不复制）。
 */
public final class MolangStructContextAccess implements MathBuilder.StructAccessHost {

    /** 装进 {@code MathBuilder.structAccessHost} 的唯一实例（宿主钩子是静态的，见该字段的说明）。 */
    public static final MolangStructContextAccess INSTANCE = new MolangStructContextAccess();

    private MolangStructContextAccess() {}

    @Override
    public IValue access(final MathBuilder builder, final IValue left, final IValue right) {
        return new IValue() {

            @Override
            public double get() {
                Entity subject = MolangContextHandles.resolve(left.get());
                if (subject == null) {
                    return 0.0D;
                }
                // 只有关键帧路径的解析器能把 query 绑定发布进那张共享表；别的构建器（用例里的桩）只是缺这一层，
                // 不该因此让整个箭头求值失败。
                EntityQueryScope.Publication publication = builder instanceof MolangParser
                    ? EntityQueryScope.publishForRead((MolangParser) builder, subject)
                    : null;
                Entity previousEntity = ParticleEffectUtil.getCurrentEntity();
                Entity previousSubject = MolangFrameContext.getFrameEntity();
                ResourceLocation previousModelId = MolangFrameContext.getMainModelId();
                try {
                    ParticleEffectUtil.setCurrentEntity(subject);
                    // 子上下文不带模型 id：上游那个子上下文是按实体造出来的，本渲染路径并不知道**它**正被哪个模型
                    // 画着。传 null 而不是沿用抛射物自己的 id——后者会让按模型 id 取值的名字拿到错误的答案。
                    MolangFrameContext.begin(subject, null);
                    return right.get();
                } finally {
                    ParticleEffectUtil.setCurrentEntity(previousEntity);
                    if (previousSubject != null) {
                        MolangFrameContext.begin(previousSubject, previousModelId);
                    } else {
                        MolangFrameContext.end();
                    }
                    if (publication != null) {
                        publication.close();
                    }
                }
            }
        };
    }
}
