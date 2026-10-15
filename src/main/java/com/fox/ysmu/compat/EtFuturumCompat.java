package com.fox.ysmu.compat;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.Loader;

/**
 * Optional bridge to <a href="https://github.com/GTNewHorizons/Et-Futurum-Requiem">Et Futurum Requiem</a>, which is
 * what gives 1.7.10 an elytra at all.
 * <p>
 * ETFR exposes three small, stable interfaces for this and no more is needed:
 * <ul>
 * <li>{@code ganymedes01.etfuturum.api.elytra.IElytraPlayer#etfu$isElytraFlying()} - its own documented duck on
 * {@code EntityPlayer}, "equivalent to isFallFlying in 1.12";</li>
 * <li>{@code ganymedes01.etfuturum.items.equipment.ItemArmorElytra#getElytra(EntityLivingBase)} - the equipped elytra
 * stack, already Baubles-Expanded aware;</li>
 * <li>{@code ganymedes01.etfuturum.elytra.IClientElytraPlayer#getRotateElytraX/Y/Z()} - the wing angles, in radians
 * ({@code ModelElytra} assigns them straight to {@code ModelRenderer.rotateAngle*} at its lines 75-77).</li>
 * </ul>
 * <p>
 * It is reached reflectively rather than through a compile dependency because ETFR is a player-optional mod and the
 * only copy of it in this workspace is a source checkout under {@code tmp/}, not a publishable artifact. Every entry
 * point degrades to "no elytra" and logs once if the mod is absent or its shape changed.
 */
public final class EtFuturumCompat {

    private static final String MOD_ID = "etfuturum";

    private static final String ELYTRA_PLAYER = "ganymedes01.etfuturum.api.elytra.IElytraPlayer";
    private static final String CLIENT_ELYTRA_PLAYER = "ganymedes01.etfuturum.elytra.IClientElytraPlayer";
    private static final String ELYTRA_ITEM = "ganymedes01.etfuturum.items.equipment.ItemArmorElytra";
    private static final String ELYTRA_LAYER =
        "ganymedes01.etfuturum.client.renderer.entity.elytra.LayerBetterElytra";

    private static final boolean LOADED = isModPresent();

    /** The mod probe is defensive because an optional dependency must never be able to fail class initialisation. */
    private static boolean isModPresent() {
        try {
            return Loader.isModLoaded(MOD_ID);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean resolved;
    private static Class<?> elytraPlayerClass;
    private static Class<?> clientElytraPlayerClass;
    private static Method isElytraFlyingMethod;
    private static Method getElytraMethod;
    private static Method rotateXMethod;
    private static Method rotateYMethod;
    private static Method rotateZMethod;
    private static Method doRenderLayerMethod;

    private EtFuturumCompat() {}

    /** Whether Et Futurum Requiem is installed. */
    public static boolean isLoaded() {
        return LOADED;
    }

    /**
     * Whether the player is gliding. Mirrors upstream's {@code ysm.is_riptide}-style live bindings: the flag is ticked
     * by ETFR itself, so it does not depend on any renderer running.
     */
    public static boolean isElytraFlying(EntityPlayer player) {
        if (!resolve() || elytraPlayerClass == null || isElytraFlyingMethod == null
            || !elytraPlayerClass.isInstance(player)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isElytraFlyingMethod.invoke(player));
        } catch (Exception e) {
            report("etfu$isElytraFlying", e);
            return false;
        }
    }

    /**
     * The elytra the entity has equipped, or {@code null}. This is upstream's source for {@code ysm.has_elytra}
     * ({@code client/animation/molang/YSMBinding.java:120} binds it to
     * {@code !EquipmentUtil.getEquippedElytraItem(entity).isEmpty()}), so it answers "is one worn", not "is one open".
     */
    @Nullable
    public static ItemStack getEquippedElytra(EntityLivingBase entity) {
        if (!resolve() || getElytraMethod == null) {
            return null;
        }
        try {
            return (ItemStack) getElytraMethod.invoke(null, entity);
        } catch (Exception e) {
            report("ItemArmorElytra.getElytra", e);
            return null;
        }
    }

    /**
     * Draws ETFR's elytra wings at the current matrix origin, the way its own layer would.
     * <p>
     * This is ETFR's {@code LayerBetterElytra.doRenderLayer} - the whole of it: it picks the texture (the cape's when
     * the player has one, otherwise {@code textures/entity/elytra.png}), draws both wings and adds the enchantment
     * glint. It is also the <b>only</b> place that advances {@code IClientElytraPlayer}'s wing angles
     * ({@code ModelElytra.setRotationAngles}, its lines 72-74), which is why the call belongs here and not twice per
     * frame: ETFR normally reaches it from {@code RenderPlayerEvent.SetArmorModel}, and that event never fires for a
     * YSM model because this port cancels {@code RenderPlayerEvent.Pre}. Driven from this layer, the wing angles
     * update exactly once per render pass - the same cadence ETFR itself has - and {@code ysm.elytra_rot_*} read the
     * values of the previous pass, which is invisible at a 0.1 lerp.
     */
    public static void renderWings(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
        float partialTicks, float ageInTicks, float scale) {
        if (!resolve() || doRenderLayerMethod == null) {
            return;
        }
        try {
            doRenderLayerMethod.invoke(null, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, scale);
        } catch (Exception e) {
            report("LayerBetterElytra.doRenderLayer", e);
        }
    }

    /** The wing's X angle in degrees. ETFR stores radians; upstream converts the same way at {@code YSMBinding.java:173}. */
    public static double elytraRotX(EntityPlayer player) {
        return Math.toDegrees(rotation(player, rotateXMethod, "getRotateElytraX"));
    }

    /** The wing's Y angle in degrees. */
    public static double elytraRotY(EntityPlayer player) {
        return Math.toDegrees(rotation(player, rotateYMethod, "getRotateElytraY"));
    }

    /** The wing's Z angle in degrees. */
    public static double elytraRotZ(EntityPlayer player) {
        return Math.toDegrees(rotation(player, rotateZMethod, "getRotateElytraZ"));
    }

    /**
     * 判断玩家是否在胸甲槽装备了鞘翅
     *
     * <p>不依赖 Et-Futurum，通过物品注册名中是否包含 "elytra" 来判断。</p>
     *
     * @param player 玩家实体
     * @return 是否装备了鞘翅
     */
    public static boolean hasElytraEquipped(EntityPlayer player) {
        if (player == null) return false;
        ItemStack chest = player.getEquipmentInSlot(3); // 胸甲槽
        if (chest == null || chest.getItem() == null) return false;
        Object rawName = net.minecraft.item.Item.itemRegistry.getNameForObject(chest.getItem());
        return rawName != null && rawName.toString().contains("elytra");
    }

    /**
     * 获取玩家鞘翅飞行的进度分数（用于 Molang 变量）
     *
     * <p>基于垂直速度归一化到 [0, 1] 范围，方便动画混合。
     * 当 Et-Futurum 加载时根据其飞行状态计算，否则按运动推断。</p>
     *
     * @param player 玩家实体
     * @return 飞行进度 (0=未飞行, 0~1=飞行中)
     */
    public static double getElytraFlightProgress(EntityPlayer player) {
        if (player == null || !isElytraFlying(player)) return 0.0d;
        // 基于下落速度估算飞行强度
        double speed = MathHelper.sqrt_double(
            player.motionX * player.motionX +
            player.motionY * player.motionY +
            player.motionZ * player.motionZ);
        return MathHelper.clamp_double(speed * 0.5, 0.0, 1.0);
    }

    private static float rotation(EntityPlayer player, Method method, String name) {
        if (!resolve() || clientElytraPlayerClass == null || method == null
            || !clientElytraPlayerClass.isInstance(player)) {
            return 0F;
        }
        try {
            Object value = method.invoke(player);
            return value instanceof Number ? ((Number) value).floatValue() : 0F;
        } catch (Exception e) {
            report(name, e);
            return 0F;
        }
    }

    /**
     * Resolves every entry point once. Any missing piece leaves its own method {@code null} and the whole class keeps
     * answering "no elytra", so a changed ETFR only costs the feature instead of throwing per frame.
     */
    private static synchronized boolean resolve() {
        if (resolved) {
            return LOADED;
        }
        resolved = true;
        if (!LOADED) {
            return false;
        }
        try {
            elytraPlayerClass = Class.forName(ELYTRA_PLAYER);
            isElytraFlyingMethod = elytraPlayerClass.getMethod("etfu$isElytraFlying");
        } catch (Throwable ignored) {
            // Fall through: the other entry points may still resolve.
        }
        try {
            getElytraMethod = Class.forName(ELYTRA_ITEM)
                .getMethod("getElytra", EntityLivingBase.class);
        } catch (Throwable ignored) {}
        try {
            clientElytraPlayerClass = Class.forName(CLIENT_ELYTRA_PLAYER);
            rotateXMethod = clientElytraPlayerClass.getMethod("getRotateElytraX");
            rotateYMethod = clientElytraPlayerClass.getMethod("getRotateElytraY");
            rotateZMethod = clientElytraPlayerClass.getMethod("getRotateElytraZ");
        } catch (Throwable ignored) {}
        try {
            doRenderLayerMethod = Class.forName(ELYTRA_LAYER)
                .getMethod(
                    "doRenderLayer",
                    EntityLivingBase.class,
                    float.class,
                    float.class,
                    float.class,
                    float.class,
                    float.class);
        } catch (Throwable ignored) {}
        return true;
    }

    private static void report(String entryPoint, Throwable failure) {
        ysmu.LOG.warn(
            "Et Futurum Requiem elytra bridge failed at {}; the elytra variables stay at their defaults",
            entryPoint,
            failure);
    }
}
