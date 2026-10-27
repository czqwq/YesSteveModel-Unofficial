package com.fox.ysmu.client.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.fox.ysmu.ysmu;

/**
 * Angelica（1.7.10 的 Iris/光影加载器）在启用 shader pack 时会自己画一遍第一人称手
 * （{@code HandRenderer#renderSolid} → {@code ItemRenderer.renderItemInFirstPerson}）。本 mod 的手臂由
 * {@code ClientEventHandler#onRenderHand} 画，所以这里只提供那一个 pass 的识别与取消它之后的收尾。
 * <p>
 * 全部走反射：Angelica 是可选的，缺失时每个查询都退化为 {@code false}，事件路径照常工作。
 */
public final class AngelicaCompat {

    private static boolean initialized;
    private static boolean available;
    private static Object irisApi;
    private static Method isShaderPackInUse;
    private static Object handRenderer;
    private static Method isHandRendererActive;
    private static Method isRenderingSolid;
    private static Method resetItemId;
    /**
     * 副手半透明信息是否可问（{@code InteractionHand} 枚举 + {@code HandRenderer.isHandTranslucent(InteractionHand)}
     * 在同一次反射查找里解析）。{@code false} 表示"这个 Iris 版本答不出副手是否半透明"，不是"副手不透明"。
     */
    private static boolean translucentInfoAvailable;
    private static Object offHand;
    private static Method isHandTranslucent;
    private static boolean offhandProbeWarningLogged;

    private AngelicaCompat() {}

    /**
     * 是否正处于 Angelica/Iris 的 solid hand pass 里（{@code HandRenderer.renderSolid} 调用
     * {@code renderItemInFirstPerson} 的那一刻）。这个 pass 里画出来的是原版手臂，本 mod 要把它取消掉。
     */
    public static boolean isRenderingSolidHandPass() {
        init();
        return available
            && isShaderPackInUse()
            && invokeBoolean(handRenderer, isHandRendererActive)
            && invokeBoolean(handRenderer, isRenderingSolid);
    }

    /**
     * 光影打开时，事件路径（{@code ClientEventHandler#onRenderHand}）是否要自己画副手。
     * <p>
     * 副手在第一人称只有两个可能的绘制点，必须恰好画一次：
     * <ul>
     *   <li><b>半透明副手</b>：Angelica 的 translucent hand pass 自己画——它的
     *   {@code mixins/early/shaders/MixinItemRendererBackhand.java:22-31} 在
     *   {@code IrisApi#isShaderPackInUse} 且 {@code isRenderingSolid() == isItemTranslucent(itemToRender)} 时取消
     *   主调用并调 {@code BackhandReflectionCompat.renderOffhand(partialTicks)}。这一笔在 shader pipeline 里，
     *   所以事件路径不能再画一次。</li>
     *   <li><b>不透明副手</b>：原本由 solid pass 里 Backhand 的 {@code @Inject(at=@At("RETURN"))} 绘制，而那一整个
     *   调用被 {@code MixinItemRenderer#ysmu$suppressVanillaHandInShaderPass} 取消了，所以要在事件路径补上。</li>
     * </ul>
     * 数据缺失（旧 Angelica 没有 {@code isHandTranslucent(InteractionHand)}）时按"自己画一次"处理并打一条一次性
     * 日志：宁可多画一次也不要静默地把未知当成"不透明"（R-03）。
     */
    public static boolean shouldDrawOffhandInHandEvent() {
        init();
        if (!available || !isShaderPackInUse()) {
            return true;
        }
        if (!translucentInfoAvailable) {
            if (!offhandProbeWarningLogged) {
                offhandProbeWarningLogged = true;
                ysmu.LOG.info(
                    "Angelica/Iris has no offhand translucency info ({}) — the first-person offhand is drawn by the hand event path; a translucent offhand item may also be drawn by the shader hand pass",
                    handRenderer == null ? "no HandRenderer instance" : "no isHandTranslucent(InteractionHand)");
            }
            return true;
        }
        return !invokeBoolean(handRenderer, isHandTranslucent, offHand);
    }

    /**
     * 清掉 Iris 的 {@code currentRenderedItem}。取消 {@code renderItemInFirstPerson} 会跳过宿主 RETURN 注入里的
     * {@code ItemIdManager.resetItemId()}，不清的话这一帧之后的手持物还会带着手部的 item id。
     */
    public static void resetFirstPersonItemId() {
        init();
        if (resetItemId != null) {
            try {
                resetItemId.invoke(null);
            } catch (ReflectiveOperationException ignored) {
                resetItemId = null;
            }
        }
    }

    private static boolean isShaderPackInUse() {
        return invokeBoolean(irisApi, isShaderPackInUse);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = irisApiClass.getMethod("getInstance")
                .invoke(null);
            isShaderPackInUse = irisApiClass.getMethod("isShaderPackInUse");

            Class<?> handRendererClass = Class.forName("net.coderbot.iris.pipeline.HandRenderer");
            Field instance = handRendererClass.getField("INSTANCE");
            handRenderer = instance.get(null);
            isHandRendererActive = handRendererClass.getMethod("isActive");
            isRenderingSolid = handRendererClass.getMethod("isRenderingSolid");

            try {
                Class<?> interactionHand = Class.forName("com.gtnewhorizons.angelica.compat.mojang.InteractionHand");
                offHand = Enum.valueOf((Class<Enum>) interactionHand.asSubclass(Enum.class), "OFF_HAND");
                isHandTranslucent = handRendererClass.getMethod("isHandTranslucent", interactionHand);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                offHand = null;
                isHandTranslucent = null;
            }
            translucentInfoAvailable = handRenderer != null && offHand != null && isHandTranslucent != null;

            try {
                Class<?> itemIdManager = Class.forName("net.coderbot.iris.uniforms.ItemIdManager");
                resetItemId = itemIdManager.getMethod("resetItemId");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                resetItemId = null;
            }
            available = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            available = false;
        }
    }

    private static boolean invokeBoolean(Object owner, Method method, Object... args) {
        if (owner == null || method == null) {
            return false;
        }
        try {
            Object value = method.invoke(owner, args);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
