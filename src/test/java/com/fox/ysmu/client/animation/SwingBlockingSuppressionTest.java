package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 格挡时不得触发挥动动画（owner 报告的缺陷：举盾格挡时仍播挥动）。
 *
 * <p>
 * 规则来自 SOURCE 的抑制分支（{@code OpenYsmPlayerControllerRuntime.java:1557-1566}）：格挡期间把
 * {@code swing} / {@code swing_sword} 清零，不让控制器播放挥动动画；YSMU 内置模型没有 OpenYSM
 * 控制器，挥动只由 {@code AnimationManager.predicateSwing} 的 legacy 选择路径产出，所以那里取
 * 同一结论（见 {@link AnimationManager#swingMustYieldToBlocking(boolean)}）。
 * </p>
 *
 * <p>
 * <b>为什么这里锁的是纯谓词而不是整条选择路径</b>：{@code predicateSwing} 需要完整的
 * {@code AnimationEvent} / {@code CustomPlayerEntity} / {@code EntityPlayer} 客户端上下文；
 * 而 {@link com.fox.ysmu.compat.BlockingCompat#isBlocking} 自己也没法在测试 JVM 里跑 —— 它的
 * 第三条兜底路径（右键按住 + 副手盾）直接解引用 {@code Minecraft.getMinecraft()}，测试里没有
 * 客户端实例（null），"未格挡"那一半必然 NPE。所以单测钉住规则本身：格挡 → 抑制，未格挡 → 放行。
 * </p>
 */
class SwingBlockingSuppressionTest {

    /** 格挡中：swing_controller 必须放弃挥动动画，否则就是 owner 报的那个缺陷。 */
    @Test
    void blockingSuppressesTheSwingAnimation() {
        assertTrue(AnimationManager.swingMustYieldToBlocking(true),
            "格挡中必须放弃挥动动画（SOURCE 的抑制分支）");
    }

    /** 未格挡：挥动动画照常选择，抑制不能泄漏到普通攻击。 */
    @Test
    void notBlockingKeepsTheSwingAnimation() {
        assertFalse(AnimationManager.swingMustYieldToBlocking(false),
            "未格挡时挥动动画必须照常播放");
    }
}
