package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 可选模组依赖的检测模式（{@code ModDependency.matches}）—— 也就是 TacZ 门控的第一环。
 *
 * <p>链路是 {@code ClientProxy} 注册 {@code ModDependency("tacz", "ctrl.tac_")} →
 * {@code OpenYsmAnimationControllerRegistry.parseController} / {@code ClientModelManager} 的
 * 关键帧扫描把命中的 modId 记进 {@code Controller.modDependencies} →
 * {@code OpenYsmPlayerControllerRuntime.tryApplyController} 用
 * {@code ModDependencyRegistry.hasUnmetDependencies} 跳过未加载模组的并行控制器。
 * 这里钉住第一环的纯判定；后两环需要注册表状态与游戏启动（{@code ModDependency.isLoaded()}
 * 直接走 {@code Loader}，单测里不可用），只能在游戏内验证。</p>
 *
 * <p>参考库的同一机制见 {@code client/ClientProxy.java:37-60}、
 * {@code client/ClientModelManager.java:1093-1105}、
 * {@code client/animation/controller/OpenYsmPlayerControllerRuntime.java:378-392}。</p>
 */
class ModDependencyTest {

    @Test
    void theTaczPatternMatchesTheControllerVariablesPacksUse() {
        ModDependency tacz = new ModDependency("tacz", "ctrl.tac_");

        // 参考模型库里 TacZ 并行控制器的真实写法。
        assertTrue(tacz.matches("ctrl.tac_hold_gun"));
        assertTrue(tacz.matches("query.anim_time > 0 && ctrl.tac_reload"));
        // 逐字子串，不做词法分析：这是 detection pattern 的语义。
        assertTrue(tacz.matches("prefix ctrl.tac_"));
    }

    @Test
    void unrelatedTextAndEmptyInputDoNotMatch() {
        ModDependency tacz = new ModDependency("tacz", "ctrl.tac_");

        assertFalse(tacz.matches("ctrl.hold_gun"));
        assertFalse(tacz.matches("tacz:gun"));
        assertFalse(tacz.matches(""));
        assertFalse(tacz.matches(null));
    }

    /** 一个依赖可以带多个模式；任一命中即算引用（例如某个模组同时用变量前缀和命名空间）。 */
    @Test
    void anyOfSeveralPatternsMatches() {
        ModDependency dep = new ModDependency("irons_spellbooks", "irons_spellbooks:", "ctrl.iss_");

        assertTrue(dep.matches("equipped_item_any_tag('mainhand','irons_spellbooks:staff')"));
        assertTrue(dep.matches("ctrl.iss_cast"));
        assertFalse(dep.matches("ctrl.tac_hold_gun"));
    }

    /** 注册表按 modId 去重（{@code ModDependencyRegistry.register} 用 equals），所以它必须按 modId 相等。 */
    @Test
    void equalityIsByModIdSoRegistrationCanDeduplicate() {
        ModDependency a = new ModDependency("tacz", "ctrl.tac_");
        ModDependency b = new ModDependency("tacz", "irons_spellbooks:");
        ModDependency c = new ModDependency("parcool", "ctrl.parcool_");

        assertEquals(a, b, "同一个 modId 的第二次注册应被跳过");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals("tacz", a.getModId());
    }
}
