package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;

/**
 * 模型 / 贴图预览平铺页的整页预算换算。
 *
 * <p>这一页和 HUD 纸娃娃完全不同：一页同时显示十几个预览，每个都是一次完整的 GeckoLib
 * 渲染。所以刷新率不是"每个预览各自一个上限"，而是**整页共享一份每秒预算再摊到可见数量上**：
 * 忘记除可见数量（照抄 HUD 那个 15 % 预算）会让 13 个预览一起把帧预算吃光；除错方向
 * （乘以可见数量）则会变成几乎每帧重烘焙。这里钉住算式与两边夹取。</p>
 *
 * <p>移植说明：本类与参考分支 {@code PreviewRefreshPolicyTest} 一一对应，只去掉了一条本仓无法成立的断言：
 * 参考用 {@code OpenYsmPlayerControllerRuntime.reEntryFrameWindow()} 证明 {@code MAX_SKIP_FRAMES}
 * 落在控制器的再入窗口内，而本仓的 {@code OpenYsmPlayerControllerRuntime} 没有窗口可推导——本仓
 * {@code PreviewRefreshPolicy} 的类注释把 {@code MAX_SKIP_FRAMES = 8} 记作**固定下界**（照
 * {@code HudPreviewCache.HUD_SOLE_DRIVER_MAX_SKIP_FRAMES} 的做法）。所以这里保留"预算再低也只由
 * 每 N 帧一次兜底"那一半，改由 {@link #theTrackerNeverLeavesAPreviewSkippedLongerThanTheFloor()}
 * 直接钉住真正的保证（帧计数下界）。</p>
 */
class PreviewRefreshPolicyTest {

    private int savedRefreshMode;

    @BeforeEach
    void saveConfig() {
        this.savedRefreshMode = Config.GUI_MODEL_PREVIEW_REFRESH;
    }

    @AfterEach
    void restoreConfig() {
        Config.GUI_MODEL_PREVIEW_REFRESH = this.savedRefreshMode;
    }

    /** 用户给的曲线：60 fps → 45 Hz、120 fps 及以上 → 90 Hz（同 HUD 纸娃娃的 3/4 + 上限）。 */
    @Test
    void targetFollowsTheFrameRateCurve() {
        assertEquals(45.0F, PreviewRefreshPolicy.targetHzFor(1, 0.5F, 60.0F), 0.01F, "60 fps -> 45 Hz");
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(1, 0.5F, 120.0F), 0.01F, "120 fps -> 90 Hz");
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(1, 0.5F, 300.0F), 0.01F,
            ">120 fps 不该继续涨：缩略图 90 Hz 已经过头了");
    }

    /** 整页兜底按"每帧开销"摊到可见数量上：8 ms/帧 ÷ 13 个 ÷ 1 ms = 55.4 Hz。 */
    @Test
    void perFrameOverheadIsSplitAcrossVisiblePreviews() {
        assertEquals(8.0F * 90.0F / 13.0F, PreviewRefreshPolicy.targetHzFor(13, 1.0F, 90.0F), 0.01F,
            "预览极多/极贵时要按每帧开销摊分，否则十几个预览会把帧时间顶爆");
        // 可见数量少时兜底更宽松，于是比例项（67.5 Hz）接管；要用更贵的烘焙才看得到兜底。
        assertEquals(8.0F * 90.0F / (5.0F * 3.0F), PreviewRefreshPolicy.targetHzFor(5, 3.0F, 90.0F), 0.01F,
            "可见数量越少，单个预览分到的每帧开销越多");
    }

    /** 便宜的预览不因此无限提频：90 Hz 是单个预览的上限。 */
    @Test
    void cheapPreviewIsCapped() {
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(1, 0.01F, 300.0F), 0.001F);
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(13, 0.01F, 300.0F), 0.001F);
    }

    /** 再贵也不能低于 5 Hz，否则缩略图看起来就是一张静止图（帧率低到 8 帧下界 < 5 时才轮到它）。 */
    @Test
    void expensivePreviewFloorsAtMinimum() {
        assertEquals(5.0F, PreviewRefreshPolicy.targetHzFor(13, 20.0F, 20.0F), 0.001F);
    }

    /** 同一帧烘两次没有意义：目标不超过帧率（比例 3/4 让它在低帧率下也留了一档余量）。 */
    @Test
    void targetNeverExceedsFrameRate() {
        float hz = PreviewRefreshPolicy.targetHzFor(1, 0.1F, 20.0F);
        assertTrue(hz <= 20.0F, "目标不能超过帧率: " + hz);
        assertEquals(15.0F, hz, 0.001F, "20 fps 下应是 3/4 的 15 Hz");
    }

    /**
     * 预算再低也不能让间隔无限拉长：这一页每个预览都是那个模型唯一一次动画推进，间隔太长就会肉眼可见地停顿。
     * 本仓没有控制器的再入窗口可对照（见类注释），所以这里钉住算式里的"每 {@link
     * PreviewRefreshPolicy#MAX_SKIP_FRAMES} 帧至少一次"下界本身。
     */
    @Test
    void expensivePreviewNeverFallsBelowTheFrameFloor() {
        assertEquals(90.0F / PreviewRefreshPolicy.MAX_SKIP_FRAMES,
            PreviewRefreshPolicy.targetHzFor(13, 500.0F, 90.0F), 0.01F,
            "预算低到离谱时也该由'每 N 帧至少一次'兜底");
    }

    /** 可见数量为 0（预览页没开）时按 1 处理，不能除以 0。 */
    @Test
    void zeroVisibleIsTreatedAsOne() {
        float hz = PreviewRefreshPolicy.targetHzFor(0, 1.0F, 1000.0F);
        assertTrue(Float.isFinite(hz) && hz > 0.0F, "可见数量 0 不能产生 NaN/Inf: " + hz);
        assertEquals(PreviewRefreshPolicy.targetHzFor(1, 1.0F, 1000.0F), hz, 0.001F);
    }

    /**
     * 真正的保证在 {@link PreviewRefreshPolicy.Tracker} 的帧计数里，而不是在 Hz 换算里：帧时间抖动
     * （墙钟累加器落后）时也必须每 {@code MAX_SKIP_FRAMES} 帧烘一次。这个断言与全局静态状态无关 ——
     * 无论当前预算算出多长的间隔，相邻两次烘焙的帧间隔都不能超过下界。
     */
    @Test
    void theTrackerNeverLeavesAPreviewSkippedLongerThanTheFloor() {
        PreviewRefreshPolicy.Tracker tracker = new PreviewRefreshPolicy.Tracker();
        int lastBakeFrame = -1;
        int bakes = 0;
        for (int frame = 1; frame <= 200; frame++) {
            if (!tracker.due(3, false)) {
                continue;
            }
            bakes++;
            if (lastBakeFrame >= 0) {
                assertTrue(frame - lastBakeFrame <= PreviewRefreshPolicy.MAX_SKIP_FRAMES,
                    "相邻两次烘焙隔了 " + (frame - lastBakeFrame) + " 帧,超过硬下界 " + PreviewRefreshPolicy.MAX_SKIP_FRAMES);
            } else {
                assertTrue(frame <= PreviewRefreshPolicy.MAX_SKIP_FRAMES,
                    "第一次烘焙被推迟到第 " + frame + " 帧,超过硬下界");
            }
            lastBakeFrame = frame;
        }
        assertTrue(bakes >= 200 / PreviewRefreshPolicy.MAX_SKIP_FRAMES,
            "200 帧里至少应有 " + (200 / PreviewRefreshPolicy.MAX_SKIP_FRAMES) + " 次烘焙,实际 " + bakes);
    }

    /** 悬停/选中的那个预览不能比别的更卡：焦点倍率只会让它烘得更勤，不会更少。 */
    @Test
    void aFocusedPreviewIsBakedAtLeastAsOften() {
        PreviewRefreshPolicy.Tracker unfocused = new PreviewRefreshPolicy.Tracker();
        PreviewRefreshPolicy.Tracker focused = new PreviewRefreshPolicy.Tracker();
        int unfocusedBakes = 0;
        int focusedBakes = 0;
        for (int frame = 0; frame < 200; frame++) {
            if (unfocused.due(5, false)) {
                unfocusedBakes++;
            }
            if (focused.due(5, true)) {
                focusedBakes++;
            }
        }
        assertTrue(focusedBakes >= unfocusedBakes,
            "焦点预览烘得比非焦点少: focused=" + focusedBakes + ", unfocused=" + unfocusedBakes);
        assertTrue(focusedBakes > 0, "焦点预览必须至少烘一次");
    }

    /** 版本号后面那截状态文本：自动模式给读数，固定模式给"1/N frames"。 */
    @Test
    void describeModeFollowsTheConfiguredMode() {
        Config.GUI_MODEL_PREVIEW_REFRESH = 0;
        assertEquals("preview FBO static", PreviewRefreshPolicy.describeMode());

        Config.GUI_MODEL_PREVIEW_REFRESH = 3;
        assertEquals("preview FBO 1/3 frames", PreviewRefreshPolicy.describeMode());

        Config.GUI_MODEL_PREVIEW_REFRESH = -1;
        String automatic = PreviewRefreshPolicy.describeMode();
        assertTrue(automatic.startsWith("preview FBO "), "自动模式应给出读数: " + automatic);
        assertTrue(automatic.contains(" Hz x"), "自动模式应给出当前 Hz 与可见数量: " + automatic);
    }
}
