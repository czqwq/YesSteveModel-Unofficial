package com.fox.ysmu.client.compat.tacz;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;


import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * Port of upstream {@code client/compat/tacz/TacCtrlBinding.java}: the nine {@code ctrl.tac_*} Molang values.
 *
 * <p>
 * Upstream binds them as {@code livingEntityVar}s on its context-aware binding table; this port's parser has one flat
 * variable table, so the value is a {@link LazyVariable} supplier reading the frame entity from
 * {@link MolangFrameContext} - the same shape every other {@code livingEntityVar} substitute here uses
 * ({@code YsmEntityQueries}). Strings are {@link MolangStringPool} ids, which is how a pack's
 * {@code ctrl.tac_gun_type == 'rifle'} comparison reaches the same id as the literal.
 */
final class TacCtrlBinding {

    private TacCtrlBinding() {}

    static void addInnerBinding(MolangParser parser) {
        livingEntityVar(parser, "ctrl.tac_hold_gun", entity -> IGun.mainhandHoldGun(entity) ? 1.0D : 0.0D);
        livingEntityVar(parser, "ctrl.tac_gun_type", TacCtrlBinding::getGunType);
        livingEntityVar(parser, "ctrl.tac_gun_id", TacCtrlBinding::getGunId);
        livingEntityVar(
            parser,
            "ctrl.tac_is_fire",
            entity -> IGunOperator.fromLivingEntity(entity)
                .getSynShootCoolDown() > 0 ? 1.0D : 0.0D);
        livingEntityVar(
            parser,
            "ctrl.tac_is_aim",
            entity -> IGunOperator.fromLivingEntity(entity)
                .getSynAimingProgress() > 0 ? 1.0D : 0.0D);
        livingEntityVar(
            parser,
            "ctrl.tac_is_reload",
            entity -> IGunOperator.fromLivingEntity(entity)
                .getSynReloadState()
                .getCountDown() > 0 ? 1.0D : 0.0D);
        livingEntityVar(
            parser,
            "ctrl.tac_is_melee",
            entity -> IGunOperator.fromLivingEntity(entity)
                .getSynMeleeCoolDown() > 0 ? 1.0D : 0.0D);
        livingEntityVar(
            parser,
            "ctrl.tac_is_draw",
            entity -> IGunOperator.fromLivingEntity(entity)
                .getSynDrawCoolDown() > 0 ? 1.0D : 0.0D);
        livingEntityVar(parser, "ctrl.tac_fire_mode", TacCtrlBinding::getFireMode);
    }

    private static void livingEntityVar(MolangParser parser, String name, LivingQuery query) {
        parser.register(new LazyVariable(name, 0));
        parser.setValue(name, () -> {
            EntityLivingBase entity = MolangFrameContext.getEntity();
            return entity == null ? 0.0D : query.query(entity);
        });
    }

    /** {@code IGun.mainhandHoldGun} answers {@code false} for a non-living entity; here only a living one arrives. */
    private static double getGunType(EntityLivingBase entity) {
        ItemStack mainHandItem = entity.getHeldItem();
        IGun gun = IGun.getIGunOrNull(mainHandItem);
        if (gun == null) {
            return MolangStringPool.EMPTY_ID;
        }
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gun.getGunId(mainHandItem))
            .orElse(null);
        return index == null ? MolangStringPool.EMPTY_ID : MolangStringPool.intern(index.getType());
    }

    private static double getGunId(EntityLivingBase entity) {
        ItemStack mainHandItem = entity.getHeldItem();
        IGun gun = IGun.getIGunOrNull(mainHandItem);
        if (gun == null) {
            return MolangStringPool.EMPTY_ID;
        }
        ResourceLocation gunId = gun.getGunId(mainHandItem);
        return gunId == null ? MolangStringPool.EMPTY_ID : MolangStringPool.intern(gunId.toString());
    }

    private static double getFireMode(EntityLivingBase entity) {
        FireMode fireMode = IGun.getMainHandFireMode(entity);
        return fireMode == null || fireMode == FireMode.UNKNOWN ? MolangStringPool.EMPTY_ID
            : MolangStringPool.intern(fireMode.name());
    }

    private interface LivingQuery {

        double query(EntityLivingBase entity);
    }
}
