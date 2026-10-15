package com.fox.ysmu.client.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.fox.ysmu.ysmu;

public final class AngelicaCompat {

    private static boolean initialized;
    private static boolean available;
    private static Object irisApi;
    private static Method isShaderPackInUse;
    private static Object handRenderer;
    private static Method isHandRendererActive;
    private static Method isRenderingSolid;
    private static Method isHandTranslucent;
    private static Object offHand;
    private static Method resetItemId;
    /**
     * 该 Angelica/Iris 版本是否真的提供了副手半透明信息（R-03）。
     * {@code InteractionHand} 枚举与 {@code HandRenderer.isHandTranslucent(InteractionHand)} 在同一次反射查找里
     * 解析，所以 {@code false} 等价于"该版本的 Iris 没有半透明手部分 pass"，而不是"副手不透明"。
     */
    private static boolean translucentInfoAvailable;
    private static boolean offhandInfoWarningLogged;

    private AngelicaCompat() {}

    public static boolean usesShaderHandRenderer() {
        init();
        return available && isShaderPackInUse();
    }

    public static boolean isRenderingSolidHandPass() {
        init();
        return available
            && isShaderPackInUse()
            && invokeBoolean(handRenderer, isHandRendererActive)
            && invokeBoolean(handRenderer, isRenderingSolid);
    }

    /**
     * Angelica/Iris 版本是否提供了副手半透明信息
     * ({@code HandRenderer.isHandTranslucent(InteractionHand)})。
     * <p>
     * R-03：把"数据缺失"与"副手不透明"区分开——两者都会让本类只能给出保守结论，但含义不同。
     * 数据缺失时该版本只会调用一次 {@code ItemRenderer.renderItemInFirstPerson}，所以在当前（唯一）
     * pass 渲染副手不可能重复绘制。
     */
    public static boolean hasOffhandTranslucencyInfo() {
        init();
        return available && translucentInfoAvailable;
    }

    /**
     * 当前 Iris hand pass 是否应该渲染副手物品。
     * <p>
     * 数据可用时按"副手半透明性是否与本 pass 匹配"判定（与 Iris 的两趟 hand pass 约定一致）；
     * 数据缺失时退化为"只在当前 pass 渲染一次"，并打一条一次性日志说明 Angelica 版本不支持，
     * 而不是静默地把未知当成"不透明"（R-03）。
     */
    public static boolean shouldRenderOffhandInCurrentHandPass() {
        init();
        if (!available) {
            return true;
        }
        if (!hasOffhandTranslucencyInfo()) {
            if (!offhandInfoWarningLogged) {
                offhandInfoWarningLogged = true;
                ysmu.LOG.info(
                    "Angelica/Iris has no offhand translucency info ({}); rendering the offhand once, in the active hand pass only",
                    handRenderer == null
                        ? "no HandRenderer instance"
                        : "no isHandTranslucent(InteractionHand)");
            }
            return true;
        }
        boolean offhandTranslucent = invokeBoolean(handRenderer, isHandTranslucent, offHand);
        return invokeBoolean(handRenderer, isRenderingSolid) != offhandTranslucent;
    }

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

            try {
                Class<?> itemIdManager = Class.forName("net.coderbot.iris.uniforms.ItemIdManager");
                resetItemId = itemIdManager.getMethod("resetItemId");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                resetItemId = null;
            }
            // R-03: record whether this Iris version can answer "is this offhand item translucent" at all.
            translucentInfoAvailable = handRenderer != null && offHand != null && isHandTranslucent != null;
            available = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            available = false;
            translucentInfoAvailable = false;
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
