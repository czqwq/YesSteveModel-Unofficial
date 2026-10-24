package com.fox.ysmu.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.util.ModAvailability;

/**
 * {@code Config.HIDDEN_OFFHAND_ITEMS}（副手隐藏名单）的匹配规则 —— 也就是
 * {@code FirstPersonHandRenderer} 与 {@code CustomPlayerItemInHandLayer} 现在真正调用的那个谓词。
 *
 * <p>默认名单是 {@code ExtraUtilities:defoliageAxe}（Extra Utilities 的治愈之斧）。匹配忽略大小写与
 * 首尾空白；条目所属 mod 没装时该条目永远匹配不到，应告警一次后跳过（"正确跳过，不误伤"）——
 * 存在性判断走 {@link ModAvailability}，不看版本号。</p>
 *
 * <p>真实渲染（GL/ItemRenderer）不在单测范围内：这里只钉住纯匹配规则与两个开关的短路顺序。
 * 对应参考库 {@code src/test/java/com/fox/ysmu/compat/BackhandCompatTest.java}。</p>
 */
class BackhandCompatTest {

    @AfterEach
    void restoreProbe() {
        ModAvailability.clearLoadProbe();
        Config.HIDE_OFFHAND_DEFOLIAGE_AXE = true;
    }

    private static ItemStack stack() {
        // 不用 net.minecraft.init.Items：没有游戏 bootstrap 时那些静态字段是 null。
        return new ItemStack(new Item());
    }

    @Test
    void defaultEntryMatchesItsItem() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);

        assertTrue(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxe"));
        // 忽略大小写
        assertTrue(BackhandCompat.matchesHiddenList("extrautilities:defoliageaxe"));
        assertTrue(BackhandCompat.matchesHiddenList("EXTRAUTILITIES:DEFOLIAGEAXE"));
    }

    @Test
    void unrelatedItemsDoNotMatch() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);

        assertFalse(BackhandCompat.matchesHiddenList("minecraft:diamond_sword"));
        assertFalse(BackhandCompat.matchesHiddenList(null));
        assertFalse(BackhandCompat.matchesHiddenList(""));
    }

    /** 条目所属 mod 没装 → 命中不了（并会告警一次），不能因为"名字对上了"就返回 true。 */
    @Test
    void entriesFromMissingModsNeverMatch() {
        ModAvailability.setLoadProbe(modId -> false);

        assertFalse(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxe"));
    }

    /** 名字必须整体相等，不能是前缀/子串匹配（避免误伤同名物品）。 */
    @Test
    void matchingIsExactNotSubstring() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);

        assertFalse(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxeReinforced"));
        assertFalse(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliage"));
    }

    /** 功能总开关关掉时，任何物品都不隐藏 —— 这是两个绘图点现在唯一的总闸。 */
    @Test
    void theConfigSwitchShortCircuitsEverything() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);
        Config.HIDE_OFFHAND_DEFOLIAGE_AXE = false;

        assertFalse(BackhandCompat.isHiddenOffhandItem(stack()));
        // 名单本身仍然命中（matchesHiddenList 不看开关），是调用方的开关先短路。
        assertTrue(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxe"));
    }

    /**
     * 没装 Backhand 时谓词恒 false（1.7.10 原版没有副手槽，隐藏无从生效）—— 关键是它必须回答而不是抛。
     * 单测 JVM 里 {@code Loader} 不可用，{@code BACKHAND_LOADED} 正是这种情况。
     */
    @Test
    void withoutBackhandNothingIsHidden() {
        assumeFalse(BackhandCompat.isBackhandLoaded(), "Backhand is installed; this covers its absence");

        assertFalse(BackhandCompat.isHiddenOffhandItem(stack()));
        assertFalse(BackhandCompat.isHiddenOffhandItem(null));
        // 副手查询本身也必须安全返回 null，而不是把 Loader/Backhand 的异常带进渲染帧。
        assertFalse(BackhandCompat.isRenderingOffhand(null));
        assertTrue(BackhandCompat.swingingArm(null), "回退到主手");
    }
}
