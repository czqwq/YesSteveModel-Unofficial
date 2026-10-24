package com.fox.ysmu.network.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SetPlayAnimation#isPlausibleAnimationName(String)}：旧的"下标必须落在 0..7"被换成了名字的形状检查，
 * 所以这里钉住它到底接受什么、拒绝什么。
 *
 * <p>为什么要这么严：这个名字会进 EEP 并被广播给所有跟踪该玩家的客户端。拒绝里最要紧的两条是
 * {@code '#'} 前缀（子页/return 标记，不是动画，进了 EEP 就成一个永远播不了的"动画名"）和控制字符
 * （{@code \n} 之类会污染日志与 GUI 文本）。长度上限则是给一个畸形/恶意负载的兜底。
 *
 * <p>另一半是**legacy 逐字兼容**：按键绑定与老包用的是 {@code extra0..extra7}，而旧实现是服务端拼
 * {@code "extra" + index}。名字版必须对这批名字一律放行，否则移植会把所有老包的轮盘打哑。
 */
class SetPlayAnimationTest {

    @Test
    void rejectsEmptyMarkerAndMalformedNames() {
        assertFalse(SetPlayAnimation.isPlausibleAnimationName(null), "null 不是名字");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName(""), "空串不是名字");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("#"), "光一个 # 是标记前缀");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("#§5默认动作"), "# 开头的是子页标记，不是动画");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("#return"), "#return 是返回标记，不是动画");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("a\nb"), "换行是控制字符");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("a\tb"), "制表符是控制字符");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("a\u0001b"), "0x01 是控制字符");
        assertFalse(SetPlayAnimation.isPlausibleAnimationName("a\u007Fb"), "0x7F (DEL) 也算控制字符");
        assertFalse(
            SetPlayAnimation.isPlausibleAnimationName(ofLength(SetPlayAnimation.MAX_ANIMATION_NAME_LENGTH + 1)),
            "超过长度上限的名字必须被拒（这是给畸形负载的兜底）");
    }

    @Test
    void acceptsRealPackNamesUpToTheLengthBound() {
        assertTrue(SetPlayAnimation.isPlausibleAnimationName("extra0"), "legacy 槽位名");
        assertTrue(SetPlayAnimation.isPlausibleAnimationName("HI"), "LaPluma 的具名动画");
        assertTrue(SetPlayAnimation.isPlausibleAnimationName("Illustration1"));
        assertTrue(SetPlayAnimation.isPlausibleAnimationName("§5默认动作"), "§ 只是颜色码，名字本身合法");
        assertTrue(SetPlayAnimation.isPlausibleAnimationName("a b"), "名字里的空格不是控制字符");
        assertTrue(
            SetPlayAnimation.isPlausibleAnimationName(ofLength(SetPlayAnimation.MAX_ANIMATION_NAME_LENGTH)),
            "正好等于上限必须接受（边界是 > 而不是 >=）");
    }

    /**
     * legacy 的八个槽位名就是旧的 {@code "extra" + i}；名字版对它们逐字放行，
     * EEP 里最终存的还是同一个串（旧服务端拼什么，现在的发送端就整串送什么）。
     */
    @Test
    void theLegacySlotVocabularyStillSpellsWhatTheOldConcatenationProduced() {
        assertEquals("extra0", "extra" + 0, "旧实现拼出来的就是这个名字");
        assertEquals("extra7", "extra" + 7);

        for (int index = 0; index < 8; index++) {
            String legacy = "extra" + index;
            assertTrue(SetPlayAnimation.isPlausibleAnimationName(legacy), legacy + " 必须仍然被接受");
        }
    }

    /** 长度上限本身也是行为的一部分：128 是上限，且必须与检查里用的常量一致。 */
    @Test
    void theLengthBoundIsOneHundredAndTwentyEight() {
        assertEquals(128, SetPlayAnimation.MAX_ANIMATION_NAME_LENGTH);
        assertTrue(SetPlayAnimation.isPlausibleAnimationName(ofLength(128)));
        assertFalse(SetPlayAnimation.isPlausibleAnimationName(ofLength(129)));
    }

    private static String ofLength(int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, 'x');
        return new String(chars);
    }
}
