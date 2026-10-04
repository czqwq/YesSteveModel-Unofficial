package software.bernie.geckolib3.molang.builtin;

import java.util.Optional;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import org.jetbrains.annotations.Nullable;

import software.bernie.geckolib3.molang.binding.ContextBinding;
import software.bernie.geckolib3.molang.builtin.query.BiomeHasAllTags;
import software.bernie.geckolib3.molang.builtin.query.BiomeHasAnyTag;
import software.bernie.geckolib3.molang.builtin.query.DebugOutput;
import software.bernie.geckolib3.molang.builtin.query.EquippedItemAllTags;
import software.bernie.geckolib3.molang.builtin.query.EquippedItemAnyTags;
import software.bernie.geckolib3.molang.builtin.query.ItemMaxDurability;
import software.bernie.geckolib3.molang.builtin.query.ItemNameAny;
import software.bernie.geckolib3.molang.builtin.query.ItemRemainingDurability;
import software.bernie.geckolib3.molang.builtin.query.Position;
import software.bernie.geckolib3.molang.builtin.query.PositionDelta;
import software.bernie.geckolib3.molang.builtin.query.RelativeBlockHasAllTags;
import software.bernie.geckolib3.molang.builtin.query.RelativeBlockHasAnyTag;
import software.bernie.geckolib3.molang.builtin.query.Rot2Camera;
import software.bernie.geckolib3.molang.context.AnimationContext;
import software.bernie.geckolib3.molang.context.ControllerContext;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.context.IMolangPlayerState;
import software.bernie.geckolib3.molang.context.IMolangStateTracker;
import software.bernie.geckolib3.molang.util.MolangEquipmentSlot;
import software.bernie.geckolib3.molang.util.MolangEquipmentUtil;
import software.bernie.geckolib3.molang.util.MolangUtils;
import software.bernie.geckolib3.molang.util.PersonView;

/**
 * The MoLang query surface: the {@code query.*} names a model may read.
 * <p>
 * Ported from the 1.20.1 fork. Everything the modern game exposes and 1.7.10 does not (tags, poses, spectator mode,
 * model parts) degrades to the closest 1.7.10 fact or to a constant, and each such spot says so in a comment.
 */
public class QueryBinding extends ContextBinding {
    public static final QueryBinding INSTANCE = new QueryBinding();

    private static final float DEG_TO_RAD = (float) Math.PI / 180F;

    @SuppressWarnings("resource")
    private QueryBinding() {
        function("debug_output", new DebugOutput());

        function("biome_has_all_tags", new BiomeHasAllTags());
        function("biome_has_any_tag", new BiomeHasAnyTag());
        function("relative_block_has_all_tags", new RelativeBlockHasAllTags());
        function("relative_block_has_any_tag", new RelativeBlockHasAnyTag());
        function("is_item_name_any", new ItemNameAny());
        function("equipped_item_all_tags", new EquippedItemAllTags());
        function("equipped_item_any_tag", new EquippedItemAnyTags());
        function("position", new Position());
        function("position_delta", new PositionDelta());
        function("rotation_to_camera", new Rot2Camera());

        function("max_durability", new ItemMaxDurability());
        function("remaining_durability", new ItemRemainingDurability());

        var("actor_count", ctx -> ctx.level() == null ? 0 : ctx.level().loadedEntityList.size());
        var("anim_time", ctx -> getAnimationContext(ctx).map(AnimationContext::animTime).orElse(0f));
        // 目前控制器只能同时播放单一动画，所以两个 molang 都是一样的结果
        var("all_animations_finished", ctx -> getControllerContext(ctx).map(ControllerContext::isAllAnimationsFinished).orElse(false));
        var("any_animation_finished", ctx -> getControllerContext(ctx).map(ControllerContext::isAnyAnimationFinished).orElse(false));
        var("life_time", ctx -> ctx.animatableEntity().getSeekTime() / 20.0);
        var("head_x_rotation", ctx -> ctx.data().netHeadYaw);
        var("head_y_rotation", ctx -> ctx.data().headPitch);
        // 1.7.10 has no per-world moon phase override API beyond this getter; it is the same 0..7 value.
        var("moon_phase", ctx -> ctx.level() == null ? 0 : ctx.level().getMoonPhase());
        var("time_of_day", ctx -> ctx.level() == null ? 0f : MolangUtils.normalizeTime(ctx.level().getWorldTime()));
        var("time_stamp", ctx -> ctx.level() == null ? 0L : ctx.level().getWorldTime());
        var("delta_time", ctx -> ctx.animatableEntity().getStateTracker().getRenderTickDelta() / 20);

        entityVar("yaw_speed", QueryBinding::getYawSpeed);
        entityVar("cardinal_facing_2d", ctx -> cardinalFacing(ctx.entity()));
        entityVar("distance_from_camera", ctx -> {
            Entity camera = ctx.mc() == null ? null : ctx.mc().renderViewEntity;
            return camera == null ? 0f : (double) camera.getDistanceToEntity(ctx.entity());
        });
        entityVar("eye_target_x_rotation", ctx -> interpolatedPitch(ctx.entity(), ctx.animationEvent().getRequestedPartialTick()));
        entityVar("eye_target_y_rotation", ctx -> interpolatedYaw(ctx.entity(), ctx.animationEvent().getRequestedPartialTick()));
        entityVar("ground_speed", QueryBinding::getGroundSpeed);
        entityVar("modified_distance_moved", ctx -> ctx.entity().distanceWalkedOnStepModified);
        entityVar("vertical_speed", QueryBinding::getVerticalSpeed);
        entityVar("walk_distance", ctx -> ctx.entity().distanceWalkedModified);
        entityVar("has_rider", ctx -> ctx.entity().riddenByEntity != null);
        entityVar("is_first_person", ctx -> PersonView.getPersonView(ctx) == PersonView.FIRST_PERSON);
        entityVar("is_in_water", ctx -> ctx.entity().isInWater());
        entityVar("is_in_water_or_rain", ctx -> ctx.entity().isInWater() || ctx.entity().isWet());
        entityVar("is_on_fire", ctx -> ctx.entity().isBurning());
        entityVar("is_on_ground", ctx -> ctx.entity().onGround);
        entityVar("is_riding", ctx -> ctx.entity().ridingEntity != null);
        entityVar("is_sneaking", ctx -> ctx.entity().isSneaking());
        // 1.7.10 has no spectator game mode, so nothing is ever a spectator.
        entityVar("is_spectator", ctx -> false);
        entityVar("is_sprinting", ctx -> ctx.entity().isSprinting());
        // 1.7.10 only tracks "in water"; there is no separate swimming state.
        entityVar("is_swimming", ctx -> ctx.entity().isInWater());

        livingEntityVar("body_x_rotation", ctx -> MolangUtils.lerp(ctx.animationEvent().getRequestedPartialTick(), ctx.entity().prevRotationPitch, ctx.entity().rotationPitch));
        livingEntityVar("body_y_rotation", ctx -> MathHelper.wrapAngleTo180_float(MolangUtils.lerp(ctx.animationEvent().getRequestedPartialTick(), ctx.entity().prevRenderYawOffset, ctx.entity().renderYawOffset)));
        livingEntityVar("health", QueryBinding::getHealth);
        livingEntityVar("max_health", QueryBinding::getMaxHealth);
        livingEntityVar("hurt_time", ctx -> ctx.entity().hurtTime);
        livingEntityVar("is_eating", ctx -> {
            ItemStack inUse = itemInUse(ctx.entity());
            return inUse != null && inUse.getItem() != null && inUse.getItem().getItemUseAction(inUse) == EnumAction.eat;
        });
        livingEntityVar("is_playing_dead", ctx -> ctx.entity().getHealth() <= 0.0F);
        livingEntityVar("is_sleeping", ctx -> ctx.entity().isPlayerSleeping());
        livingEntityVar("is_using_item", ctx -> isUsingItem(ctx.entity()));
        livingEntityVar("item_in_use_duration", ctx -> getItemInUseDuration(ctx.entity()) / 20.0);
        livingEntityVar("item_max_use_duration", ctx -> getMaxUseDuration(ctx.entity()) / 20.0);
        livingEntityVar("item_remaining_use_duration", ctx -> getItemInUseCount(ctx.entity()) / 20.0);
        livingEntityVar("equipment_count", ctx -> getEquipmentCount(ctx.entity()));

        playerVar("cape_flap_amount", QueryBinding::getCapeFlapAmount);
        playerVar("player_level", QueryBinding::getExpLevel);
        playerVar("is_jumping", ctx -> !isFlying(ctx) && ctx.entity().ridingEntity == null && !ctx.entity().onGround && !ctx.entity().isInWater());

        clientPlayerVar("has_cape", ctx -> hasCape(ctx.entity()));
    }

    private static Optional<AnimationContext> getAnimationContext(IContext<?> ctx) {
        return Optional.ofNullable(ctx.animationContext());
    }

    private static Optional<ControllerContext> getControllerContext(IContext<?> ctx) {
        return Optional.ofNullable(ctx.controllerContext());
    }

    /**
     * The host's state tracker wins for remote players, exactly as the 1.20.1 capability did: the client does not
     * always have authoritative values for another player.
     */
    private static IMolangPlayerState remotePlayerState(IContext<?> ctx) {
        IMolangStateTracker tracker = ctx.animatableEntity().getStateTracker();
        if (tracker instanceof IMolangPlayerState state && !state.isLocalPlayer()) {
            return state;
        }
        return null;
    }

    private static boolean isFlying(IContext<EntityPlayer> ctx) {
        IMolangPlayerState state = remotePlayerState(ctx);
        if (state != null) {
            return state.isFlying();
        }
        return ctx.entity().capabilities.isFlying;
    }

    private static int getExpLevel(IContext<EntityPlayer> ctx) {
        IMolangPlayerState state = remotePlayerState(ctx);
        if (state != null) {
            return state.getExpLevel();
        }
        return ctx.entity().experienceLevel;
    }

    private static Object getHealth(IContext<EntityLivingBase> ctx) {
        IMolangPlayerState state = remotePlayerState(ctx);
        if (state != null) {
            return state.getHealth();
        }
        return ctx.entity().getHealth();
    }

    private static Object getMaxHealth(IContext<EntityLivingBase> ctx) {
        IMolangPlayerState state = remotePlayerState(ctx);
        if (state != null) {
            return state.getMaxHealth();
        }
        return ctx.entity().getMaxHealth();
    }

    /** 1.7.10 has no {@code PlayerModelPart}; only the cape's presence can be checked. */
    private static boolean hasCape(AbstractClientPlayer player) {
        return player.getLocationCape() != null && !player.isInvisible();
    }

    private static int getEquipmentCount(EntityLivingBase entity) {
        int count = 0;
        for (MolangEquipmentSlot slot : MolangEquipmentSlot.values()) {
            if (!slot.isArmor()) {
                continue;
            }
            ItemStack stack = MolangEquipmentUtil.getEquippedItem(entity, slot);
            if (stack != null) {
                count++;
            }
        }
        return count;
    }

    private static int getMaxUseDuration(EntityLivingBase player) {
        ItemStack useItem = itemInUse(player);
        if (useItem == null || useItem.getItem() == null) {
            return 0;
        }
        return useItem.getItem().getMaxItemUseDuration(useItem);
    }

    private static float getYawSpeed(IContext<Entity> ctx) {
        if (ctx.entity() instanceof EntityPlayerSP) {
            IMolangStateTracker tracker = ctx.animatableEntity().getStateTracker();
            return tracker instanceof IMolangPlayerState state ? state.getYawSpeed() : 0f;
        }
        return 20 * (ctx.entity().rotationYaw - ctx.entity().prevRotationYaw);
    }

    private static float getGroundSpeed(IContext<Entity> ctx) {
        Entity entity = ctx.entity();
        return 20 * MathHelper.sqrt_float((float) ((entity.motionX * entity.motionX) + (entity.motionZ * entity.motionZ)));
    }

    /**
     * 1.20.1 returned {@code EnumFacing.get3DDataValue()}. 1.7.10's {@code EnumFacing.getIndex()} keeps the same
     * 0..5 order (DOWN, UP, NORTH, SOUTH, WEST, EAST), and the quadrant derived from the yaw below is the one the
     * vanilla bed/facing code uses.
     */
    private static int cardinalFacing(Entity entity) {
        int quadrant = MathHelper.floor_double((entity.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
        switch (quadrant) {
            case 0:
                return 3; // SOUTH
            case 1:
                return 4; // WEST
            case 2:
                return 2; // NORTH
            default:
                return 5; // EAST
        }
    }

    /**
     * 1.7.10 keeps the "item in use" state on {@code EntityPlayer} only; every other living entity is always
     * considered to be using nothing.
     */
    @Nullable
    private static ItemStack itemInUse(EntityLivingBase entity) {
        return entity instanceof EntityPlayer ? ((EntityPlayer) entity).getItemInUse() : null;
    }

    private static boolean isUsingItem(EntityLivingBase entity) {
        return entity instanceof EntityPlayer && ((EntityPlayer) entity).isUsingItem();
    }

    private static int getItemInUseDuration(EntityLivingBase entity) {
        return entity instanceof EntityPlayer ? ((EntityPlayer) entity).getItemInUseDuration() : 0;
    }

    private static int getItemInUseCount(EntityLivingBase entity) {
        return entity instanceof EntityPlayer ? ((EntityPlayer) entity).getItemInUseCount() : 0;
    }

    private static float getVerticalSpeed(IContext<Entity> ctx) {
        IMolangStateTracker tracker = ctx.animatableEntity().getStateTracker();
        Vec3 posDelta = tracker.getPositionDelta();
        float renderTickDelta = tracker.getRenderTickDelta();
        if (posDelta == null || renderTickDelta == 0f) {
            return 0f;
        }
        return 20 * (float) posDelta.yCoord / renderTickDelta;
    }

    private static float interpolatedPitch(Entity entity, float partialTicks) {
        return entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
    }

    private static float interpolatedYaw(Entity entity, float partialTicks) {
        return entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
    }

    /**
     * The cape's own tracked position lags the body, which is what makes the cape swing. 1.7.10 keeps those six
     * values in {@code EntityPlayer.field_71091_bM..field_71085_bR} (previous and current chasing position); the
     * modern names are used in the comments below.
     */
    private static float getCapeFlapAmount(IContext<EntityPlayer> ctx) {
        float pPartialTicks = ctx.animationEvent().getPartialTick();
        EntityPlayer pLivingEntity = ctx.entity();

        // field_71091_bM/field_71096_bN/field_71097_bO = prevChasingPosX/Y/Z
        // field_71094_bP/field_71095_bQ/field_71085_bR = chasingPosX/Y/Z
        float d0 = (float) (MolangUtils.lerp(pPartialTicks, (float) pLivingEntity.field_71091_bM, (float) pLivingEntity.field_71094_bP) - MolangUtils.lerp(pPartialTicks, (float) pLivingEntity.prevPosX, (float) pLivingEntity.posX));
        float d1 = (float) (MolangUtils.lerp(pPartialTicks, (float) pLivingEntity.field_71096_bN, (float) pLivingEntity.field_71095_bQ) - MolangUtils.lerp(pPartialTicks, (float) pLivingEntity.prevPosY, (float) pLivingEntity.posY));
        float d2 = (float) (MolangUtils.lerp(pPartialTicks, (float) pLivingEntity.field_71097_bO, (float) pLivingEntity.field_71085_bR) - MolangUtils.lerp(pPartialTicks, (float) pLivingEntity.prevPosZ, (float) pLivingEntity.posZ));
        float f = pLivingEntity.prevRenderYawOffset + (pLivingEntity.renderYawOffset - pLivingEntity.prevRenderYawOffset);
        float d3 = MathHelper.sin(f * (DEG_TO_RAD));
        float d4 = (-MathHelper.cos(f * (DEG_TO_RAD)));
        float f1 = d1 * 10.0F;
        f1 = MathHelper.clamp_float(f1, -6.0F, 32.0F);
        float f2 = (d0 * d3 + d2 * d4) * 100.0F;
        f2 = MathHelper.clamp_float(f2, 0.0F, 150.0F);
        if (f2 < 0.0F) {
            f2 = 0.0F;
        }

        float f4 = MolangUtils.lerp(pPartialTicks, pLivingEntity.prevCameraYaw, pLivingEntity.cameraYaw);
        f1 = f1 + MathHelper.sin(MolangUtils.lerp(pPartialTicks, pLivingEntity.prevDistanceWalkedModified, pLivingEntity.distanceWalkedModified) * 6.0F) * 32.0F * f4;
        if (pLivingEntity.isSneaking()) {
            f1 += 25.0F;
        }

        return MathHelper.clamp_float((6.0F + f2 / 2.0F + f1) / 108, 0, 1);
    }
}
