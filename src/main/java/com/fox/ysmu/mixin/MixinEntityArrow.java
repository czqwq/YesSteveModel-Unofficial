package com.fox.ysmu.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.Config;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.IProjectileModelArrow;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Adds two datawatcher entries to {@code EntityArrow} so the client can draw the shooter's projectile sub-model.
 * <p>
 * 1.7.10 syncs nothing about who fired an arrow: {@code shootingEntity} is a plain field that the server assigns and
 * never sends, so the client cannot ask "whose model should this arrow use". The model id therefore travels in a
 * datawatcher slot, exactly as the sibling port does.
 * <p>
 * The second slot carries the firing item's registry name, because the client also cannot see what the shooter was
 * holding - and {@code ysm.shoot_item_id} is what a pack uses to choose between its bow and crossbow sub-models.
 * Taking it once, at construction, is deliberate: that is the instant the arrow was loosed, and the weapon it should
 * be drawn with is the one held then, not whatever the shooter picks up later.
 */
@Mixin(value = EntityArrow.class, priority = 900)
public abstract class MixinEntityArrow implements IProjectileModelArrow {

    /** The first free datawatcher id after vanilla's own 16 and 17. */
    @Unique
    private static final int ysmu$DW_MODEL_ID = 18;

    /** The next free id, used for {@code ysm.shoot_item_id}. */
    @Unique
    private static final int ysmu$DW_SHOOT_ITEM = 19;

    /**
     * 射击者的实体 id，供 {@code ysm.projectile_owner} 解析出那个实体（箭头表达式的左侧）。
     * <p>
     * 与上面两个槽的理由相同：1.7.10 的 {@code shootingEntity} 只在服务端赋值、从不发送，而
     * {@code World#getEntityByID} 需要的就是实体 id。写成 {@code Integer} 而不是字符串：id 是整数，且
     * {@code updateObject} 的类型必须与 {@code addObject} 一致。
     */
    @Unique
    private static final int ysmu$DW_OWNER = 20;

    @Shadow
    public Entity shootingEntity;

    @Shadow
    protected abstract void entityInit();

    @Inject(method = "entityInit", at = @At("TAIL"))
    private void ysmu$onEntityInit(CallbackInfo ci) {
        ((Entity) (Object) this).getDataWatcher()
            .addObject(ysmu$DW_MODEL_ID, "");
        ((Entity) (Object) this).getDataWatcher()
            .addObject(ysmu$DW_SHOOT_ITEM, "");
        ((Entity) (Object) this).getDataWatcher()
            .addObject(ysmu$DW_OWNER, 0);
    }

    /**
     * Captures the shooter's model on the server side for a player-fired arrow
     * ({@code EntityArrow(World, EntityLivingBase, float)}).
     */
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;F)V", at = @At("TAIL"))
    private void ysmu$onConstruct(World world, EntityLivingBase shooter, float velocity, CallbackInfo ci) {
        if (world.isRemote) {
            return;
        }
        if (shooter instanceof EntityPlayer player) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null && eep.getModelId() != null) {
                ((Entity) (Object) this).getDataWatcher()
                    .updateObject(ysmu$DW_MODEL_ID, eep.getModelId()
                        .toString());
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_RENDER) {
                    ysmu.LOG.info("[YSMU-ARROW] set model id {} on the arrow entity", eep.getModelId());
                }
            }
        }
        ysmu$captureShootItem(shooter);
        ysmu$captureOwner(shooter);
    }

    /**
     * The other construction path - {@code (World, EntityLivingBase shooter, EntityLivingBase target, float, float)} -
     * which is what a skeleton aiming at an entity uses, and what a mod may use directly. Only the firing item is
     * captured: an arrow from a mob carries no player model.
     */
    @Inject(
        method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/entity/EntityLivingBase;FF)V",
        at = @At("TAIL"))
    private void ysmu$onConstructTargeted(World world, EntityLivingBase shooter, EntityLivingBase target, float velocity,
        float inaccuracy, CallbackInfo ci) {
        ysmu$captureShootItem(shooter);
        ysmu$captureOwner(shooter);
    }

    /**
     * Writes the shooter's held-item registry name into the datawatcher, on the server.
     * <p>
     * An empty string means "no weapon to name" - a dispenser-fired arrow has no shooter at all.
     */
    @Unique
    private void ysmu$captureShootItem(EntityLivingBase shooter) {
        if (shooter == null || ((Entity) (Object) this).worldObj.isRemote) {
            return;
        }
        String itemId = "";
        ItemStack held = shooter.getHeldItem();
        if (held != null && held.getItem() != null) {
            GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(held.getItem());
            if (uid != null) {
                itemId = uid.toString();
            }
        }
        ((Entity) (Object) this).getDataWatcher()
            .updateObject(ysmu$DW_SHOOT_ITEM, itemId);
    }

    /** The model id stored on this arrow, or an empty string when no custom model is associated with it. */
    @Unique
    public String ysmu$getProjectileModelId() {
        return ((Entity) (Object) this).getDataWatcher()
            .getWatchableObjectString(ysmu$DW_MODEL_ID);
    }

    /** The registry name of the item that fired this arrow, or an empty string. */
    @Unique
    public String ysmu$getShootItemId() {
        return ((Entity) (Object) this).getDataWatcher()
            .getWatchableObjectString(ysmu$DW_SHOOT_ITEM);
    }

    /**
     * 把射击者的实体 id 写进 datawatcher，在服务端。
     * <p>
     * 与开火物品不同，这里**不限于玩家**：上游的 {@code projectile_owner} 是 {@code ctx.entity().getOwner()}，
     * 骷髅射出的箭同样有 owner。客户端换不回那个实体时（怪物不在已加载实体里）调用方会发布"没有上下文"，
     * 所以多写一个 id 不会造成错误上下文。
     */
    @Unique
    private void ysmu$captureOwner(EntityLivingBase shooter) {
        if (shooter == null || ((Entity) (Object) this).worldObj.isRemote) {
            return;
        }
        ((Entity) (Object) this).getDataWatcher()
            .updateObject(ysmu$DW_OWNER, shooter.getEntityId());
    }

    /**
     * 射击者的实体 id，没有则为 0。客户端用它 {@code World#getEntityByID} 换回实体，供箭头表达式
     * {@code ysm.projectile_owner->…} 使用（只对已加载的实体有效）。
     */
    @Unique
    public int ysmu$getProjectileOwnerId() {
        return ((Entity) (Object) this).getDataWatcher()
            .getWatchableObjectInt(ysmu$DW_OWNER);
    }
}
