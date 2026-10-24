package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * {@link AnimationRouletteScreen#wheelEntriesFor(ResourceLocation)}：轮盘的"这一级有哪些条目"。
 *
 * <p>两条路：模型自己声明过 {@code extra_animation}（根表在 {@link ModelConfigRegistry} 里）就用它，
 * 否则用发布时烘焙好的扁平标签表 {@code ClientModelManager.EXTRA_ANIMATION_NAME} 映射到 legacy 槽位键。
 *
 * <p>第二条路的**逐下标**语义是这次要钉的重点：键是 {@code extra<i>}，而按键绑定与扇区按下标认人，
 * 所以空槽必须保留它自己的下标（空标签）而不是被压掉 —— 一个 sparse 的 legacy 包（例如只有
 * {@code extra0} 和 {@code extra2}）如果被压缩，{@code extra2} 会滑到 {@code extra1} 的扇区/按键上，
 * 玩家按第二个键播的是第三个动画。
 */
class AnimationRouletteWheelEntriesTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "wheel_probe");
    private static final ResourceLocation MAIN = ModelIdUtil.getMainId(MODEL);

    @BeforeEach
    void reset() {
        ModelConfigRegistry.clear();
        ClientModelManager.EXTRA_ANIMATION_NAME.remove(MAIN);
    }

    @AfterEach
    void cleanup() {
        ModelConfigRegistry.clear();
        ClientModelManager.EXTRA_ANIMATION_NAME.remove(MAIN);
    }

    /** 有声明过的根表就用它：保序、保内容，而且不混进烘焙的 {@code extra<i>}。 */
    @Test
    void aDeclaredRootTableWinsOverTheBakedLabels() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("HI", "打招呼");
        declared.put("#page", "更多");
        declared.put("#return", "返回");
        ModelConfigRegistry.accept(MODEL, null, null, declared);
        ClientModelManager.EXTRA_ANIMATION_NAME.put(MAIN, new String[] { "legacy0", "legacy1" });

        Map<String, String> entries = AnimationRouletteScreen.wheelEntriesFor(MODEL);

        assertEquals(Arrays.asList("HI", "#page", "#return"), new ArrayList<>(entries.keySet()), "必须保持声明序");
        assertEquals("打招呼", entries.get("HI"));
        assertFalse(entries.containsKey("extra0"), "有根表时不得再混入 baked 的槽位键");
    }

    /** 没有根表时，扁平标签按**每个下标**给一个 {@code extra<i>}：中间的空槽保留为一位空标签。 */
    @Test
    void withoutADeclaredTableTheBakedLabelsBecomeOneSlotPerIndexWithGapsKept() {
        ClientModelManager.EXTRA_ANIMATION_NAME.put(MAIN, new String[] { "A", null, "C" });

        Map<String, String> entries = AnimationRouletteScreen.wheelEntriesFor(MODEL);

        assertEquals(Arrays.asList("extra0", "extra1", "extra2"), new ArrayList<>(entries.keySet()),
            "每个下标一个键：sparse 表不能被压缩到前面去");
        assertEquals("A", entries.get("extra0"));
        assertEquals("", entries.get("extra1"), "空槽的标签是空串（由 labelFor 回落成键名），键本身必须还在");
        assertEquals("C", entries.get("extra2"), "C 必须留在它自己的下标上");
    }

    /** 尾部空槽同样保留；完全没有标签表时轮盘是空的（不是 null）。 */
    @Test
    void trailingGapsKeepTheirSlotAndNoLabelsMeansAnEmptyWheel() {
        ClientModelManager.EXTRA_ANIMATION_NAME.put(MAIN, new String[] { "A", null, null });

        Map<String, String> entries = AnimationRouletteScreen.wheelEntriesFor(MODEL);
        assertEquals(Arrays.asList("extra0", "extra1", "extra2"), new ArrayList<>(entries.keySet()));
        assertEquals("", entries.get("extra2"));

        ClientModelManager.EXTRA_ANIMATION_NAME.remove(MAIN);
        assertTrue(AnimationRouletteScreen.wheelEntriesFor(MODEL).isEmpty());
        assertTrue(AnimationRouletteScreen.wheelEntriesFor(null).isEmpty(), "null 模型不能抛");
    }

    /** 空数组是"声明过但一条都没有"：结果是空表，不是 8 个空槽。 */
    @Test
    void anEmptyLabelArrayProducesNoSlots() {
        ClientModelManager.EXTRA_ANIMATION_NAME.put(MAIN, new String[0]);
        assertTrue(AnimationRouletteScreen.wheelEntriesFor(MODEL).isEmpty());
    }

    /** 只填了第一个槽的 legacy 包：键只有 extra0，不能凭空补出 extra1..7。 */
    @Test
    void aSingleFilledSlotProducesExactlyOneEntry() {
        ClientModelManager.EXTRA_ANIMATION_NAME.put(MAIN, new String[] { "走" });

        Map<String, String> entries = AnimationRouletteScreen.wheelEntriesFor(MODEL);
        assertEquals(Arrays.asList("extra0"), new ArrayList<>(entries.keySet()));
        assertEquals("走", entries.get("extra0"));
    }
}
