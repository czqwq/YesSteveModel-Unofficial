package com.fox.ysmu.util;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

/**
 * 预览缩略图的**姿态签名缓存**：记住每个模型上一次"真正画出去"时的姿态签名，
 * 连续姿态采用 1/64 量化以减少微小变化带来的重画；这是近似缓存，非逐像素相等。
 * 显隐（包括精确零缩放）必须单独编码，不能被量化吞掉。
 *
 * <p><b>谁在用它。</b>{@code util/RenderUtil.java} 的缩略图烘焙路径
 * （{@code renderEntityInInventory(..., clearBeforeDraw, allowPoseSkip)}）：签名由
 * {@code previewPoseSignature}（几何 identity + 贴图 + 逐骨骼 {@link #appendBone}）算，命中时跳过几何
 * 提交、只跑动画 tick，并把"这一帧到底画没画"作为布尔值返回给 {@code ModelButton}，由它决定要不要把耗时
 * 喂给 {@code PreviewRefreshPolicy}。参考分支的同一条链：{@code util/RenderUtil.java:371-378}
 * （{@code invalidatePreviewPose}）、{@code :380-397}（签名）、{@code :662-672}（命中即 return）、
 * {@code :351-356}（跳过时不喂预算）。
 *
 * <h3>失效点就是这套机制的失败模式</h3>
 * <p>命中时几何不提交、FBO 里的旧画面直接复用，所以任何"画面已经不该是这个"的时刻都必须
 * {@link #invalidate}，漏一个就是**空白或过期**缩略图。已接的点（{@code ModelButton#bakePreviewIfDue}）：
 * <ol>
 * <li>{@code FboCache#checkAndResize} 返回 true —— FBO 被（重）建、画面物理上已经没了：首次烘焙、
 * 窗口/GUI scale 变化、{@code dispose()} 删掉之后。这是唯一"内容消失但姿态可能没变"的情况。</li>
 * <li>悬停/选中变化 —— 切的是预览动画通道，骨骼姿态可能这一帧还没变。</li>
 * <li>{@code modelCacheDirty} —— 构造后的第一次、{@code dispose()} 之后、模型重新发布。</li>
 * <li>{@link #clear()} —— 断线/模型重载（{@code ClientEventHandler#clearClientRuntimeState}）：签名按
 * 模型 id 存，换服务器后同一个 id 可能指向另一份几何。</li>
 * </ol>
 * <p><b>贴图页不接</b>：同一个主模型有 N 张贴图、共用同一个签名 key，打开只会互相顶掉（参考
 * {@code util/RenderUtil.java:532-533} 的同一句说明），所以 {@code TextureButton} 仍传
 * {@code allowPoseSkip=false}。
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
