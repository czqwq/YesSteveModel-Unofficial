package com.fox.ysmu.client.animation.molang;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;

/**
 * 箭头表达式（{@code a -> b}）左侧那个"上下文"在本引擎里的表示：一张句柄表。
 *
 * <p>上游 Molang 的值是对象，{@code ysm.projectile_owner} 直接返回一个 context
 * （{@code tmp/YesSteveModel-dev-1.20 .../client/animation/molang/YSMBinding.java:193}
 * {@code projectileVar("projectile_owner", ctx -> ctx.createChild(ctx.entity().getOwner()))}），箭头运算符把右边放进
 * 这个子上下文里求值。本引擎的关键帧路径值只有 {@code double}（{@code IValue} 就是 {@code double}），所以上下文
 * 只能以**句柄**（一个正整数）表示——与字符串走 {@link software.bernie.geckolib3.core.molang.MolangStringPool}
 * 是同一套办法，同一个理由。
 *
 * <p>句柄只在**一个求值周期内**有效：{@link #beginFrame()} 在每帧开头清空，所以表不会随会话增长。上游的
 * {@code projectile_owner} 是活对象且 wiki 自己标了"目前存在内存泄漏问题，不推荐使用"
 * （{@code tmp/wiki/molang_ref.md:229}）——那是上游的缺陷，这里不复制：句柄在帧末随表一起消失，不会把实体留在
 * 静态表里。
 *
 * <p>渲染线程专用，和这条路径上其他帧作用域的东西（{@code MolangFrameContext}、{@link EntityQueryScope}）一样。
 */
public final class MolangContextHandles {

    private static final Map<Integer, Entity> HANDLES = new HashMap<>();

    /** 递增的句柄。0 不使用，见 {@link #resolve(double)}。 */
    private static int nextHandle = 0;

    private MolangContextHandles() {}

    /**
     * 把一个实体登记成句柄。
     *
     * @return 正整数句柄；{@code entity == null} 时返回 0，也就是上游"没有上下文"（读作假、{@code ??} 会取右侧）
     */
    public static double publish(Entity entity) {
        if (entity == null) {
            return 0.0D;
        }
        int handle = ++nextHandle;
        if (handle <= 0) {
            // int 溢出：连同表一起从头开始。只可能发生在超长会话里，重置是安全的——句柄只在一个求值周期内有效，
            // 任何还活着的句柄都只可能属于当前帧。
            HANDLES.clear();
            nextHandle = 1;
            handle = 1;
        }
        HANDLES.put(handle, entity);
        return handle;
    }

    /**
     * @return 句柄指向的实体，或 {@code null}（0、负数、未知句柄、以及没有宿主认得的上下文时）
     */
    public static Entity resolve(double handle) {
        int id = (int) handle;
        if (id <= 0) {
            return null;
        }
        return HANDLES.get(id);
    }

    /** 每帧开头清空：句柄的生命周期就是一个求值周期。 */
    public static void beginFrame() {
        HANDLES.clear();
    }

    /** 当前登记的句柄数（诊断与用例）。 */
    public static int size() {
        return HANDLES.size();
    }
}
