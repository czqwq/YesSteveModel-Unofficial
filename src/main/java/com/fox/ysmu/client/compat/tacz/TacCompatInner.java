package com.fox.ysmu.client.compat.tacz;

import java.lang.reflect.Field;
import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.YsmBuiltinAnimations;
import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.condition.ConditionTAC;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * The TacZ-dependent half of the adapter, the port of upstream {@code client/compat/tacz/TacCompatInner.java}. Kept in
 * its own class so nothing here is loaded unless TacZ is installed: {@link TACZCompat} only touches it behind
 * {@link TACZCompat#isInstalled()}.
 *
 * <p>
 * The animation channels are upstream's, name for name ({@code tac:hold:pistol}, {@code tac:aim:rifle},
 * {@code tac:reload:pistol}, {@code tac:hold:fire:...}, {@code tac:climbing:...}, ...), and the decision order is
 * upstream's: reload &gt; melee &gt; fire for the one-shot channel, climbing &gt; aim &gt; run &gt; hold for the loop
 * channel. Two 1.7.10 substitutions:
 * <ul>
 * <li>Upstream's "climbing pose" is {@code !isSwimming() && getPose() == Pose.SWIMMING}; 1.7.10 has neither swimming
 * nor poses, and the TacZ handoff document leaves the equivalent to this side (§3: "1.7.10 对应为「正在爬梯/贴墙攀爬」").
 * The port answers {@code isOnLadder()} - the same condition the legacy {@code climb}/{@code climbing} states use -
 * and the moving/stationary split stays upstream's limb-swing threshold.</li>
 * <li>The client-side shoot cool-down is read through {@link IClientPlayerGunOperator#fromLocalPlayer} only for the
 * local player ({@code EntityPlayerSP}); upstream asks {@code livingEntity instanceof IClientPlayerGunOperator}, which
 * in 1.7.10 is the same object for that one entity.</li>
 * </ul>
 *
 * <p>
 * Missing channels are answered with {@code null} instead of being handed to the engine: this port's
 * {@code AnimationController#setAnimation} logs "Could not load animation" and leaves the controller without a
 * fallback, so a model that ships no {@code tac:*} clips must keep its ordinary hold/locomotion animations.
 */
final class TacCompatInner {

    private static final String MUZZLE_FLASH_CLASS = "com.tacz.guns.client.model.functional.MuzzleFlashRender";
    private static final String SHELL_CLASS = "com.tacz.guns.client.model.functional.ShellRender";
    /** TacZ's public static {@code isSelf} flags ({@code TacZ/docs/YSM_COMPAT_HANDOFF.md} §2.8); reflected to keep client classes out of the compile surface. */
    private static Field muzzleFlashSelf;
    private static Field shellSelf;
    private static boolean flashFieldsResolved;

    private TacCompatInner() {}

    static boolean isMainhandHoldingGun(EntityLivingBase entity) {
        return entity != null && IGun.mainhandHoldGun(entity);
    }

    static boolean isGun(ItemStack itemStack) {
        return itemStack != null && itemStack.getItem() != null && IGun.getIGunOrNull(itemStack) != null;
    }

    static void registerEvent() {
        MinecraftForge.EVENT_BUS.register(new TacEvent());
    }

    /** Upstream {@code tac:idle}/{@code tac:run}/{@code tac:walk} override for the main controller. */
    @Nullable
    static PlayState playGunMainAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName,
        ILoopType loopType) {
        EntityLivingBase entity = event.getAnimatable()
            .getEntity();
        if (entity == null || !isGun(entity.getHeldItem())) {
            return null;
        }
        String tacName = "tac:" + animationName;
        if (!hasAnimation(event, tacName)) {
            return null;
        }
        return playAnimation(event, tacName, loopType);
    }

    /** Upstream {@code TacCompatInner#playGunHoldAnimation}: the loop channel's held/aim/run/climbing clip. */
    @Nullable
    static PlayState playGunHoldAnimation(ItemStack heldItem, AnimationEvent<CustomPlayerEntity> event) {
        CommonGunIndex gunIndex = gunIndex(heldItem);
        EntityLivingBase entity = event.getAnimatable()
            .getEntity();
        if (gunIndex == null || entity == null) {
            return null;
        }
        String weaponType = gunIndex.getType();
        IGunOperator operator = IGunOperator.fromLivingEntity(entity);

        if (entity.isOnLadder()) {
            return getGunTypeAnimation(
                event,
                entity,
                weaponType,
                isMoving(event) ? "tac:climb:" : "tac:climbing:",
                ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (operator.getSynAimingProgress() > 0) {
            return getGunTypeAnimation(
                event,
                entity,
                weaponType,
                "tac:aim:",
                ILoopType.EDefaultLoopTypes.LOOP);
        }
        if (entity.onGround && entity.isSprinting()) {
            return getGunTypeAnimation(
                event,
                entity,
                weaponType,
                "tac:run:",
                ILoopType.EDefaultLoopTypes.LOOP);
        }
        return getGunTypeAnimation(
            event,
            entity,
            weaponType,
            "tac:hold:",
            ILoopType.EDefaultLoopTypes.LOOP);
    }

    /**
     * Upstream {@code TacCompatInner#playGunOnceAnimation}: the one-shot channel, so a reload/melee/fire clip with a
     * recovery tail can run past the transition (upstream comment at {@code TacCompatInner.java:146-148}).
     */
    @Nullable
    static PlayState playGunOnceAnimation(ItemStack heldItem, AnimationEvent<CustomPlayerEntity> event) {
        CommonGunIndex gunIndex = gunIndex(heldItem);
        EntityLivingBase entity = event.getAnimatable()
            .getEntity();
        if (gunIndex == null || entity == null) {
            return null;
        }
        String weaponType = gunIndex.getType();
        IGunOperator operator = IGunOperator.fromLivingEntity(entity);
        long fireTick = operator.getSynShootCoolDown();
        if (entity instanceof EntityPlayerSP) {
            IClientPlayerGunOperator clientOperator = IClientPlayerGunOperator
                .fromLocalPlayer((EntityPlayerSP) entity);
            fireTick = Math.max(fireTick, clientOperator.getClientShootCoolDown());
        }

        // The "clicked with an empty magazine" hint: play the empty clip once, then let the real channel win if it has
        // one (upstream TacCompatInner.java:169-172).
        if (TacGunAnimationState.consumeNeedReload(entity)) {
            playAnimation(event, YsmBuiltinAnimations.EMPTY_ANIMATION_NAME, ILoopType.EDefaultLoopTypes.LOOP);
        }

        float reloadProgress = operator.getSynReloadState()
            .getCountDown();
        if (reloadProgress > 0) {
            return getGunTypeAnimation(
                event,
                entity,
                weaponType,
                "tac:reload:",
                ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        if (operator.getSynMeleeCoolDown() > 0) {
            return getGunTypeAnimation(
                event,
                entity,
                weaponType,
                "tac:melee:",
                ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        if (fireTick > 0) {
            boolean climbing = entity.isOnLadder() && !isMoving(event);
            if (climbing) {
                return getGunTypeAnimation(
                    event,
                    entity,
                    weaponType,
                    "tac:climbing:fire:",
                    ILoopType.EDefaultLoopTypes.PLAY_ONCE);
            }
            if (operator.getSynAimingProgress() > 0) {
                return getGunTypeAnimation(
                    event,
                    entity,
                    weaponType,
                    "tac:aim:fire:",
                    ILoopType.EDefaultLoopTypes.PLAY_ONCE);
            }
            return getGunTypeAnimation(
                event,
                entity,
                weaponType,
                "tac:hold:fire:",
                ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        return PlayState.CONTINUE;
    }

    static void openFlashShellRender(EntityLivingBase livingEntity) {
        if (livingEntity == Minecraft.getMinecraft().thePlayer) {
            setFlashShellSelf(true);
        }
    }

    static void stopFlashShellRender() {
        setFlashShellSelf(false);
    }

    @Nullable
    static ResourceLocation getGunId(ItemStack itemInHand) {
        IGun gun = IGun.getIGunOrNull(itemInHand);
        return gun == null ? null : gun.getGunId(itemInHand);
    }

    /** Upstream {@code TacCompatInner#renderOffhandGun}, drawn by the port's item-in-hand layer at the same locators. */
    static void renderOffhandGun(CustomPlayerItemInHandLayer<?> layer, ItemStack heldItem, EntityLivingBase entity,
        GeoModel geoModel) {
        CommonGunIndex gunIndex = gunIndex(heldItem);
        if (gunIndex == null) {
            return;
        }
        String weaponType = gunIndex.getType();
        if (isType(weaponType, GunTabType.PISTOL)) {
            layer.renderGunAtLocatorGroup(entity, heldItem, geoModel, "PistolLocator", true);
        } else {
            layer.renderGunAtLocatorGroup(entity, heldItem, geoModel, "RifleLocator", false);
        }
    }

    /**
     * Upstream {@code TacCompatInner#getGunTypeAnimation}: a model's per-gun override name
     * ({@code tac:hold$tacz:ak47}) wins over the weapon-type name ({@code tac:hold:pistol} / {@code :rpg} /
     * {@code :rifle}).
     */
    @Nullable
    private static PlayState getGunTypeAnimation(AnimationEvent<CustomPlayerEntity> event, EntityLivingBase entity,
        String weaponType, String prefix, ILoopType loopType) {
        ConditionTAC conditionTAC = ConditionManager.tacFor(
            event.getAnimatable()
                .getAnimation());
        if (conditionTAC != null) {
            String name = conditionTAC.doTest(entity.getHeldItem(), prefix);
            if (StringUtils.isNotBlank(name) && hasAnimation(event, name)) {
                return playAnimation(event, name, loopType);
            }
        }
        String name;
        if (isType(weaponType, GunTabType.PISTOL)) {
            name = prefix + "pistol";
        } else if (isType(weaponType, GunTabType.RPG)) {
            name = prefix + "rpg";
        } else {
            name = prefix + "rifle";
        }
        return hasAnimation(event, name) ? playAnimation(event, name, loopType) : null;
    }

    @Nullable
    private static CommonGunIndex gunIndex(ItemStack heldItem) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return null;
        }
        ResourceLocation gunId = gun.getGunId(heldItem);
        return gunId == null ? null : TimelessAPI.getCommonGunIndex(gunId)
            .orElse(null);
    }

    private static boolean hasAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        CustomPlayerEntity animatable = event.getAnimatable();
        ClientModelManager.ensureAnimations(animatable.getMainModel());
        AnimationFile file = ClientModelManager.animationFileFor(animatable.getAnimation());
        return file != null && file.animations.containsKey(animationName);
    }

    private static PlayState playAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName,
        ILoopType loopType) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, loopType));
        return PlayState.CONTINUE;
    }

    private static boolean isMoving(AnimationEvent<CustomPlayerEntity> event) {
        return Math.abs(event.getLimbSwingAmount()) > 0.05F;
    }

    private static boolean isType(String type, GunTabType tabType) {
        return type != null && type.equals(tabType.name().toLowerCase(Locale.ENGLISH));
    }

    /**
     * Writes TacZ's two {@code public static boolean isSelf} flags ({@code TacZ/docs/YSM_COMPAT_HANDOFF.md} §2.8).
     * Reflected rather than referenced so the compile surface stays the TacZ API/index classes; the fields are part of
     * the handoff contract and may be assigned directly by outside mods.
     */
    private static void setFlashShellSelf(boolean value) {
        if (!flashFieldsResolved) {
            muzzleFlashSelf = resolveSelfField(MUZZLE_FLASH_CLASS);
            shellSelf = resolveSelfField(SHELL_CLASS);
            flashFieldsResolved = true;
        }
        setBoolean(muzzleFlashSelf, value);
        setBoolean(shellSelf, value);
    }

    @Nullable
    private static Field resolveSelfField(String className) {
        try {
            return Class.forName(className)
                .getField("isSelf");
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void setBoolean(@Nullable Field field, boolean value) {
        if (field == null) {
            return;
        }
        try {
            field.setBoolean(null, value);
        } catch (IllegalAccessException ignored) {
            // Not reachable for a public field; leaving the flag untouched only costs a muzzle flash.
        }
    }
}
