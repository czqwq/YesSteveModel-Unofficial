package com.fox.ysmu.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ModAvailability;

/**
 * 无 TConstruct 时十字弩检测必须安全地回答 false。
 *
 * <p>{@code ysm.mainhand_charged_crossbow} / {@code ysm.offhand_charged_crossbow} 现在读的是
 * {@code TinkersCrossbowCompat.isCrossbowLoaded(...)}（在 {@code AnimationRegister} 里主手/副手各一次、
 * 在 {@code OpenYsmControllerExpressionEvaluator} 里同样两处）；此前这两处是硬编码的 0。
 * TiCon 在 1.7.10 上不一定是必装项，所以这条路径的"模组不在 → false 且不抛"必须被钉住：
 * 它会出现在每帧的 Molang 求值路径上。</p>
 *
 * <p>真正装填状态来自物品 NBT（{@code InfiTool.Loaded}），需要 TiCon 注册的物品与
 * {@code GameRegistry}，只能在游戏内验证。</p>
 */
class TinkersCrossbowCompatTest {

    @AfterEach
    void restoreProbe() {
        ModAvailability.clearLoadProbe();
    }

    private static ItemStack stack() {
        // 不用 net.minecraft.init.Items：没有游戏 bootstrap 时那些静态字段是 null。
        return new ItemStack(new Item());
    }

    @Test
    void withoutTinkersNothingIsACrossbowAndNothingThrows() {
        assumeFalse(TinkersCrossbowCompat.isCrossbowLoaded(stack()), "TConstruct is present; this covers its absence");

        assertFalse(TinkersCrossbowCompat.isTinkersCrossbow(stack()));
        assertFalse(TinkersCrossbowCompat.isCrossbowLoaded(stack()));
        assertFalse(TinkersCrossbowCompat.isCrossbowReloading(stack()));
    }

    /** 空物品/空栈同样只能回答 false：副手在没有 Backhand 时就是 null 走进来。 */
    @Test
    void nullStacksAnswerFalse() {
        assertFalse(TinkersCrossbowCompat.isTinkersCrossbow(null));
        assertFalse(TinkersCrossbowCompat.isCrossbowLoaded(null));
        assertFalse(TinkersCrossbowCompat.isCrossbowReloading(null));
    }
}
