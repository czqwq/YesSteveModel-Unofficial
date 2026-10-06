package com.fox.ysmu.util;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

/**
 * 预览缩略图的**姿态签名缓存**：记住每个模型上一次"真正画出去"时的姿态签名，
 * 连续姿态采用 1/64 量化以减少微小变化带来的重画；这是近似缓存，非逐像素相等。
 * 显隐（包括精确零缩放）必须单独编码，不能被量化吞掉。
 *
 * <p><b>本仓当前没有调用方</b>，而这是有意的决定，不是漏接。参考分支用它跳过"姿态与上一帧相同"
 * 时的几何提交（参考 {@code util/RenderUtil.java:371-378} 的 {@code invalidatePreviewPose} 与
 * {@code :665-675}），本仓的 {@code RenderUtil.renderEntityInInventory} 没有这条链：模型网格的
 * 重绘率现在由 {@code client/renderer/PreviewRefreshPolicy} + {@code FboCache} 限住
 * （{@code GUI_MODEL_PREVIEW_REFRESH}，每格最多 90 Hz 且整页共享预算），再叠一层"姿态没变就不画"
 * 的收益只对静止模型存在，代价是每个可能重建 FBO 的路径（翻页/换分类/换模型/悬停与 GUI 动画切换/
 * FBO 尺寸变化）都必须记得 {@link #invalidate}，漏一个就是空白或过期缩略图 ——
 * 正是 {@code AGENTS.md} 警告的"为时序差新增上游没有的状态"。
 * 所以判定逻辑留在本类、可单测，接线等有了实机依据再定。
 *
 * <p>单独一个类而不是塞进 {@code RenderUtil}：{@code RenderUtil} 的静态初始化会加载
 * GL/LWJGL（`org.lwjgl.LWJGLException`），单测 JVM 里没有，所以判定逻辑放在这里才可测。
 */
public final class PreviewPoseCache {

    /** 只在渲染线程访问，普通 HashMap 即可。 */
    private static final Map<ResourceLocation, Long> POSES = new HashMap<>();

    private PreviewPoseCache() {}

    /** Pure signature component, kept independent of RenderUtil/GL for regression tests. */
    public static long appendBone(long h, software.bernie.geckolib3.core.processor.IBone bone) {
        h = h * 31 + Math.round(bone.getRotationX() * 64f);
        h = h * 31 + Math.round(bone.getRotationY() * 64f);
        h = h * 31 + Math.round(bone.getRotationZ() * 64f);
        h = h * 31 + Math.round(bone.getPositionX() * 64f);
        h = h * 31 + Math.round(bone.getPositionY() * 64f);
        h = h * 31 + Math.round(bone.getPositionZ() * 64f);
        h = h * 31 + Math.round(bone.getScaleX() * 64f);
        h = h * 31 + Math.round(bone.getScaleY() * 64f);
        h = h * 31 + Math.round(bone.getScaleZ() * 64f);
        int visibility = bone.isHidden() ? 1 : 0;
        if (bone.getScaleX() == 0f || bone.getScaleY() == 0f || bone.getScaleZ() == 0f) visibility |= 2;
        if (bone instanceof software.bernie.geckolib3.geo.render.built.GeoBone
            && ((software.bernie.geckolib3.geo.render.built.GeoBone) bone).childBonesAreHiddenToo()) visibility |= 4;
        return h * 31 + visibility;
    }

    /**
     * @return {@code true} = 与上次真正画出去时相同 → 这一帧不必提交几何；
     *         {@code false} = 需要画，并记下新签名。缓存里没有该模型的条目时一律返回
     *         {@code false}（第一次烘焙 / FBO 刚被重建 / 刚作废，都必须画）。
     */
    public static boolean shouldSkip(ResourceLocation modelId, long signature) {
        Long previous = POSES.get(modelId);
        if (previous != null && previous.longValue() == signature) {
            return true;
        }
        POSES.put(modelId, Long.valueOf(signature));
        return false;
    }

    /** 作废某模型的签名：FBO 被重建、翻页/换模型、悬停或 gui 动画切换后调用，
     *  下一次渲染必然执行（否则会留下空白或过期的缩略图）。 */
    public static void invalidate(ResourceLocation modelId) {
        if (modelId != null) {
            POSES.remove(modelId);
        }
    }

    /** 全部作废（模型重载 / 断线时用）。 */
    public static void clear() {
        POSES.clear();
    }

    /** 当前记录的模型数（诊断用）。 */
    public static int size() {
        return POSES.size();
    }
}
