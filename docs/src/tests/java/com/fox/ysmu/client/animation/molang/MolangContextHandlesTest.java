package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 箭头表达式左侧那个"上下文"的表示：0 = 没有上下文（上游的 {@code null}），正句柄换回登记的实体，帧首清空。
 *
 * <p>这里只覆盖不需要 Minecraft 世界的那一半——{@code publish(null)}／{@code resolve(0)}／{@code beginFrame}。
 * 真正的实体由 {@code ArrowProjectileRenderer} 在渲染帧里登记（射击者实体 id 走 datawatcher 槽 20），
 * 那一条只有实机能验；见 {@code .agent/phase50-arrow-expressions.md}。
 */
class MolangContextHandlesTest {

    @Test
    void aMissingContextIsZeroAndResolvesToNothing() {
        assertEquals(0.0D, MolangContextHandles.publish(null), 0.0D);
        assertNull(MolangContextHandles.resolve(0.0D));
        assertNull(MolangContextHandles.resolve(-1.0D), "负数不是句柄");
        assertNull(MolangContextHandles.resolve(12345.0D), "未知句柄不是上下文，不能猜一个实体出来");
    }

    @Test
    void beginFrameEmptiesTheTable() {
        MolangContextHandles.beginFrame();
        assertEquals(0, MolangContextHandles.size());
    }
}
