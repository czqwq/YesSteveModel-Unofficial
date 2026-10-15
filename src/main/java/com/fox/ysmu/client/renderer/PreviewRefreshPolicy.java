package com.fox.ysmu.client.renderer;

import java.util.Locale;

import com.fox.ysmu.Config;

/**
 * 模型 / 贴图预览平铺页(模型选择页、贴图页)的**共享**刷新预算。
 *
 * <p>和 {@link HudPreviewCache} 的思路一样(预算换算成目标 Hz,用墙钟累加器而不是
 * "每 N 帧"),但这里的乘数完全不同:一页最多同时显示十几个预览,每个都是一次完整的
 * GeckoLib 渲染。所以预算按**整页**算,再按当前可见数量摊到每个预览上:</p>
 *
 * <pre>
 *   visible      = 上一帧调用过 due() 的预览数量(翻页/切页自动跟随)
 *   costPerBake  = 实测单次烘焙耗时(EMA)
 *   targetHz     = 帧率 × GRID_FRAME_RATIO,上限 MAX_HZ
 *                  再被"整页每帧最多 OVERHEAD_MS_PER_FRAME ms"压住:
 *                  min(targetHz, OVERHEAD_MS_PER_FRAME × 帧率 / (visible × costPerBake))
 *                  最后抬到"每 MAX_SKIP_FRAMES 帧至少一次":max(targetHz, 帧率 / MAX_SKIP_FRAMES)
 * </pre>
 *
 * <p>参考分支 {@code client/renderer/PreviewRefreshPolicy.java:1-233} 是这一整块的唯一来源:
 * <b>upstream(tmp/YesSteveModel-dev-1.20)完全没有它</b>,也没有 {@code FboCache} / {@code HudPreviewCache}
 * (grep 该树的 {@code PreviewRefreshPolicy} / {@code FboCache} / {@code GUI_MODEL_PREVIEW_REFRESH} 皆为 0)。
 * 所以这里没有 upstream 行为可对照,逐处核对的是参考的每个除数/提前返回与 1.7.10 的 GL 约定。三处与参考不同:</p>
 * <ul>
 *   <li><b>类注释里的"BUDGET_MS_PER_SEC / 200 ms 每秒"已经删掉。</b>参考的注释描述的是被它自己替换掉的旧算法:
 *       类里并不存在 {@code BUDGET_MS_PER_SEC},真正生效的是 {@code OVERHEAD_MS_PER_FRAME}(每帧毫秒),
 *       而类注释后半段还专门解释了"为什么兜底写成每帧开销而不是每秒预算"。照抄那句注释等于把一条过期的公式
 *       当成规格,所以改成上面这个与代码逐项对应的算式。</li>
 *   <li><b>{@link #MAX_SKIP_FRAMES} 是固定值 8,不是从控制器再入窗口推导的。</b>参考写作
 *       {@code OpenYsmPlayerControllerRuntime.safePassWindowFrames()}(其 {@code :68})。本仓的
 *       {@code OpenYsmPlayerControllerRuntime} 没有 {@code RE_ENTRY_FRAMES}/{@code lastActiveFrame}/
 *       {@code isReEntry}(grep 为 0),没有窗口可推导;{@link HudPreviewCache} 遇到同一处境时保留了参考的
 *       数值 8(其 {@code HUD_SOLE_DRIVER_MAX_SKIP_FRAMES}),这里照做。</li>
 *   <li>注释里的调用点名字改成这个仓库真实存在的调用点(两个平铺页按钮的 {@code drawButton} 与两个屏幕的
 *       {@code drawScreen});参考的 {@code beginFrame()} 挂在 {@code ClientEventHandler.onRenderTick}。</li>
 * </ul>
 *
 * <p>两个细节不是可选的:</p>
 * <ul>
 *   <li><b>错峰</b>:所有预览如果用同一个相位累加,就会在同一帧集体重烘焙 —— 十几个烘焙撞在一起是一帧的尖峰
 *       (会看见整页规律性卡顿)。{@link Tracker} 用按钮 id 给每个预览一个不同的初始相位,之后各走各的墙钟累加器,
 *       长期看仍然等间隔。</li>
 *   <li><b>焦点放宽</b>:正在悬停或选中的那个预览在播 {@code hover}/{@code focus} 动画,用户正盯着它看,
 *       按整页平均速率会明显发顿。所以焦点预览单独按 {@link #FOCUS_BOOST} 倍放宽(略超总预算,换"看着的东西不卡")。</li>
 *   <li><b>间隔硬下界(帧数)</b>:预算压得再低,相邻两次烘焙的间隔也不能超过 {@link #MAX_SKIP_FRAMES} 帧。
 *       这一页的每个预览都是那个模型唯一一次动画推进,间隔太长会让预览肉眼可见地停顿。所以下界同时写进
 *       {@link #targetHzFor}(让显示的目标说实话)和 {@link Tracker} 的帧计数(真正的保证,不受帧时间抖动影响)。</li>
 * </ul>
 */
public final class PreviewRefreshPolicy {

    /** 目标频率取帧率的这个比例 —— 和 HUD 纸娃娃同一条曲线:60 fps -> 45 Hz、120 fps -> 90 Hz。
     *  这一页打开时用户的注意力就在预览模型上,周边背景顺不顺看不出来,所以宁可把帧预算花在预览上。 */
    private static final float GRID_FRAME_RATIO = 0.75F;
    /** 单个预览的上限(Hz)。比 HUD 的 120 低:缩略图 90 Hz 已远超肉眼需求。 */
    private static final float MAX_HZ = 90.0F;
    /** 单个预览的下限:再低就看着像静止图了。 */
    private static final float MIN_HZ = 5.0F;
    /** 整页预览允许给**每一个渲染帧**加的最大毫秒数(兜底)。
     *
     *  <p>为什么兜底写成"每帧开销"而不是"每秒预算":比例式的目标意味着每帧的烘焙次数是
     *  {@code 可见数量 × 比例}(与帧率无关),所以预览一多、烘焙一贵,帧时间就会被顶上去,
     *  帧率随之下降 —— 而"每秒预算"是按帧率换算的,会在帧率高时严重超支、帧率低时又太紧。
     *  直接按每帧开销封顶才是这一页真正要守的约束。</p> */
    private static final float OVERHEAD_MS_PER_FRAME = 8.0F;
    /** 只用来保证除法有限。 */
    private static final float MIN_COST_MS = 0.05F;
    /** 忽略超过这个的帧间隔(切页、卡顿、alt-tab)。 */
    private static final float MAX_FRAME_DELTA_MS = 200.0F;
    /** 焦点预览(悬停 / 选中)的速率倍数。 */
    private static final float FOCUS_BOOST = 3.0F;
    /** 相邻两次烘焙允许的最大渲染帧间隔。**不是可调项**:参考分支由它的控制器再入窗口推导
     *  ({@code RE_ENTRY_FRAMES - 2} = 8),本仓没有那个窗口(见类注释),所以按 {@link HudPreviewCache}
     *  的同一做法保留参考的数值作为固定下界 —— 它在这里的作用是"预览的动画不被饿死"。 */
    static final int MAX_SKIP_FRAMES = 8;

    private static long lastFrameNanos;
    private static float frameDeltaMs = 16.0F;
    private static int visibleThisFrame;
    private static int visibleLastFrame;
    private static float smoothCostMs = 0.0F;
    /** 观测用的间隔统计:窗口内实际出现的最大烘焙间隔(帧)。用来验证
     *  {@link #MAX_SKIP_FRAMES} 真的生效 —— 这是"预览停顿"唯一能证伪的数据。 */
    private static int maxGapThisFrame;
    private static int maxGapSincePublish;
    private static int maxGapLastSecond = -1;
    private static long gapWindowStartNanos;

    private PreviewRefreshPolicy() {}

    /** 每个渲染帧调一次(本仓的两个平铺页在 {@code drawScreen} 开头调;参考挂在
     *  {@code ClientEventHandler.onRenderTick})。可见数量按"这一帧调用了几次 {@link #noteVisible()}"
     *  统计,所以翻页/切页自动跟随。 */
    public static void beginFrame() {
        long now = System.nanoTime();
        float dtMs = lastFrameNanos == 0L ? 16.0F : (now - lastFrameNanos) / 1.0e6F;
        lastFrameNanos = now;
        if (dtMs > 0.0F && dtMs < MAX_FRAME_DELTA_MS) {
            frameDeltaMs = frameDeltaMs * 0.9F + dtMs * 0.1F;
        }
        visibleLastFrame = visibleThisFrame;
        visibleThisFrame = 0;

        maxGapSincePublish = Math.max(maxGapSincePublish, maxGapThisFrame);
        maxGapThisFrame = 0;
        if (gapWindowStartNanos == 0L) {
            gapWindowStartNanos = now;
        } else if (now - gapWindowStartNanos >= 1_000_000_000L) {
            maxGapLastSecond = maxGapSincePublish;
            maxGapSincePublish = 0;
            gapWindowStartNanos = now;
        }
    }

    /** 某次烘焙距上一次隔了几个渲染帧({@link Tracker} 每次真的烘焙时上报)。 */
    static void noteBakeGap(int gapFrames) {
        maxGapThisFrame = Math.max(maxGapThisFrame, gapFrames);
    }

    /** 最近一秒内观测到的最大烘焙间隔(帧);-1 = 还没满一秒。要一直 &le;
     *  {@link #MAX_SKIP_FRAMES};超过就说明有预览被跳过了。参考由它的 F3 读数消费,
     *  本仓没有那段读数(见 {@link #describeMode()}),所以这个值目前只发布不读。 */
    public static int maxGapFrames() {
        return maxGapLastSecond;
    }

    /** 当前帧里又有一个预览参与刷新(按钮每次绘制时调)。 */
    public static void noteVisible() {
        visibleThisFrame++;
    }

    /** 实测一次烘焙的耗时,供预算换算。 */
    public static void noteBake(float costMs) {
        if (!(costMs > 0.0F) || Float.isInfinite(costMs)) {
            return;
        }
        if (smoothCostMs <= 0.0F) {
            smoothCostMs = costMs;
            return;
        }
        // 尖峰只允许把估计往上推这么多:每页/每个模型第一次烘焙会把几何与动画一起加载进来
        // (几十毫秒量级),那是加载成本而不是渲染成本。原样吃进 EMA 会让整页的频率被压到底
        // 好几个周期。夹住而不是丢弃:真实的成本上升(更重的模型)仍然能把估计推上去,只是每采样最多 ×4。
        float sample = Math.min(costMs, smoothCostMs * 4.0F);
        smoothCostMs = smoothCostMs * 0.7F + sample * 0.3F;
    }

    /** 每个预览当前的目标刷新率(Hz),供 {@link #describeMode()} 与 {@link Tracker} 使用。 */
    public static float targetHz() {
        return targetHzFor(visibleLastFrame, smoothCostMs, 1000.0F / Math.max(frameDeltaMs, 0.5F));
    }

    /** {@link #targetHz()} 的纯计算部分(拆出来便于单测)。算式就是全部策略:
     *  帧率 × 比例(上限 {@link #MAX_HZ}),再被"每帧最多加 {@link #OVERHEAD_MS_PER_FRAME} ms"
     *  的整页兜底压住,最后抬到"每 {@link #MAX_SKIP_FRAMES} 帧至少一次"。 */
    static float targetHzFor(int visible, float costMs, float frameHz) {
        float perBakeCost = Math.max(costMs, MIN_COST_MS);
        float hz = Math.min(frameHz * GRID_FRAME_RATIO, MAX_HZ);
        hz = Math.min(hz, OVERHEAD_MS_PER_FRAME * frameHz / (Math.max(1, visible) * perBakeCost));
        hz = Math.max(hz, frameHz / MAX_SKIP_FRAMES);
        return Math.max(hz, MIN_HZ);
    }

    /** 上一帧的可见预览数量(0 = 当前没在预览页上)。 */
    public static int visibleCount() {
        return visibleLastFrame;
    }

    /** 实测的单次烘焙耗时(ms)。参考由它的 F3 读数消费,本仓目前只发布不读。 */
    public static float bakeCostMs() {
        return smoothCostMs;
    }

    /** 版本号后面那截状态文本(模型选择页左下角):自动模式直接给出每个预览的当前 Hz
     *  与可见数量,不用开 F3 就能看到限频在做什么。
     *  <p>三串文本与参考分支逐字一致({@code :169-179}),包括没有语言键这一点 —— 它是运行读数而不是
     *  配置项标签,参考也没有为它提供 {@code gui.yes_steve_model.*} 键(相邻的版本号字符串同理)。 */
    public static String describeMode() {
        int mode = Config.GUI_MODEL_PREVIEW_REFRESH;
        if (mode < 0) {
            return "preview FBO " + String.format(Locale.ROOT, "%.1f", targetHz())
                + " Hz x" + visibleCount();
        }
        if (mode == 0) {
            return "preview FBO static";
        }
        return "preview FBO 1/" + mode + " frames";
    }

    /** 单个预览的墙钟累加器。每个预览按钮持有一个。 */
    public static final class Tracker {

        private double accumMs;
        private boolean started;
        /** 距上一次烘焙过了几个渲染帧(真正的硬保证,与帧时间无关)。 */
        private int framesSinceBake;

        /** true = 这一帧该重烘焙了。
         *
         *  @param slot    预览的稳定编号(按钮 id),用来错峰
         *  @param focused 正在悬停 / 选中:按 {@link #FOCUS_BOOST} 倍放宽 */
        public boolean due(int slot, boolean focused) {
            float hz = targetHz();
            if (focused) {
                hz = Math.min(MAX_HZ, hz * FOCUS_BOOST);
            }
            double intervalMs = 1000.0D / hz;
            if (!started) {
                started = true;
                // 7 与 16 互质:连续 10 个按钮 id 会落在 10 个不同相位上,
                // 一批预览不会挤在同一个渲染帧里集体烘焙。
                accumMs = intervalMs * ((slot * 7 % 16) / 16.0D);
            }
            boolean frameDeadline = ++framesSinceBake >= MAX_SKIP_FRAMES;
            accumMs += frameDeltaMs;
            if (!frameDeadline && accumMs < intervalMs) {
                return false;
            }
            noteBakeGap(framesSinceBake);
            framesSinceBake = 0;
            if (frameDeadline) {
                // 落后于墙钟了(帧时间抖动,或预算算出来的间隔本来就接近窗口)。
                // 丢掉多余额度,不做补帧。
                accumMs = 0.0D;
            } else {
                accumMs -= intervalMs;
                if (accumMs >= intervalMs) {
                    // 单帧跨过多个间隔时丢掉多余额度,不做补帧(同 HudPreviewCache)。
                    accumMs %= intervalMs;
                }
            }
            return true;
        }

        /** 预览被销毁 / 换页时调用,下次绘制重新错峰。 */
        public void reset() {
            started = false;
            accumMs = 0.0D;
            framesSinceBake = 0;
        }
    }
}
