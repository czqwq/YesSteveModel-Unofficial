package com.fox.ysmu.client.compat.tacz;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.layer.CustomPlayerItemInHandLayer;

import cpw.mods.fml.common.Loader;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * Optional adapter for the 1.7.10 TacZ port ({@code https://github.com/czqwq/TacZ}, mod id {@code tacz}), a port of
 * upstream YSM's {@code client/compat/tacz/TACZCompat.java}. Upstream compiles against the 1.20.1 TacZ API; the 1.7.10
 * port keeps that API's names and signatures (its handoff document,
 * {@code TacZ/docs/YSM_COMPAT_HANDOFF.md}, lists every symbol this class and {@link TacCompatInner} use), so only the
 * platform types differ here.
 *
 * <p>
 * Everything is gated on {@link #isInstalled()}: with TacZ absent this class is inert, the {@code ctrl.tac_*} names
 * keep their registered defaults, and the animation controllers that reference them stay skipped
 * ({@code ModDependencyRegistry}).
 *
 * <p>
 * Port-specific pieces, each with its 1.7.10 reason:
 * <ul>
 * <li>{@link #renderOffsetHand} takes the item-in-hand layer and the {@link GeoModel} instead of upstream's
 * {@code GeoRenderData}/{@code PoseStack}/{@code MultiBufferSource}: the port's layer owns the fixed-pipeline item
 * draw and the locator chains, so the adapter hands the draw back to it (see
 * {@link CustomPlayerItemInHandLayer#renderGunAtLocatorGroup}).</li>
 * <li>{@link #playGunMainAnimation}/{@link #playGunHoldAnimation}/{@link #playGunOnceAnimation} answer {@code null}
 * for a channel the model does not define, where upstream hands the missing name to its engine anyway. This port's
 * engine prints "Could not load animation" for a missing name and leaves the controller without a fallback, so a
 * model without {@code tac:*} clips must fall through to its normal hold/locomotion clips instead.</li>
 * </ul>
 */
public final class TACZCompat {

    private static final String MOD_ID = "tacz";

    private static boolean installed;

    private TACZCompat() {}

    /** Called once from {@code ClientProxy.init}, before {@code AnimationRegister.registerVariables}. */
    public static void init() {
        installed = Loader.isModLoaded(MOD_ID);
        if (installed) {
            TacCompatInner.registerEvent();
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Binds the real values of the nine {@code ctrl.tac_*} names, replacing the defaults
     * {@code AnimationRegister#registerThirdPartyCompatPlaceholders} registered. Upstream shape: its
     * {@code CtrlBinding} calls {@code TACZCompat.addBinding(this)} ({@code client/animation/molang/CtrlBinding.java:86}),
     * which forwards to {@code TacCtrlBinding.addInnerBinding}.
     */
    public static void addBinding(MolangParser parser) {
        if (installed) {
            TacCtrlBinding.addInnerBinding(parser);
        }
    }

    public static boolean isGun(ItemStack stack) {
        return installed && TacCompatInner.isGun(stack);
    }

    public static boolean isMainhandHoldingGun(EntityLivingBase entity) {
        return installed && TacCompatInner.isMainhandHoldingGun(entity);
    }

    /**
     * Upstream {@code TACZCompat#playGunMainAnimation}: the {@code tac:<main animation>} override, or {@code null} when
     * the model has no such clip (upstream checks {@code getAnimation(tacName) != null} itself).
     */
    @Nullable
    public static PlayState playGunMainAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName,
        ILoopType loopType) {
        return installed ? TacCompatInner.playGunMainAnimation(event, animationName, loopType) : null;
    }

    /** Upstream {@code TACZCompat#playGunHoldAnimation}; {@code null} when the item is not a loaded TacZ gun. */
    @Nullable
    public static PlayState playGunHoldAnimation(ItemStack mainHandItem, AnimationEvent<CustomPlayerEntity> event) {
        return installed ? TacCompatInner.playGunHoldAnimation(mainHandItem, event) : null;
    }

    /** Upstream {@code TACZCompat#playGunOnceAnimation}; {@code null} when the item is not a loaded TacZ gun. */
    @Nullable
    public static PlayState playGunOnceAnimation(ItemStack mainHandItem, AnimationEvent<CustomPlayerEntity> event) {
        return installed ? TacCompatInner.playGunOnceAnimation(mainHandItem, event) : null;
    }

    public static void openFlashShellRender(EntityLivingBase livingEntity, ItemStack mainHandItem) {
        if (installed && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.openFlashShellRender(livingEntity);
        }
    }

    public static void stopFlashShellRender(ItemStack mainHandItem) {
        if (installed && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.stopFlashShellRender();
        }
    }

    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        return installed ? TacCompatInner.getGunId(stack) : null;
    }

    /** Upstream {@code TACZCompat#renderOffsetHand}, with the port's fixed-pipeline draw delegated to the layer. */
    public static void renderOffsetHand(CustomPlayerItemInHandLayer<?> layer, ItemStack offhandItem,
        EntityLivingBase livingEntity, GeoModel geoModel) {
        if (installed && TacCompatInner.isGun(offhandItem)) {
            TacCompatInner.renderOffhandGun(layer, offhandItem, livingEntity, geoModel);
        }
    }
}
