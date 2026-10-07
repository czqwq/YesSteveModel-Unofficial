package com.fox.ysmu.client.animation;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.BlockingCompat;
import com.fox.ysmu.compat.EtFuturumCompat;
import com.fox.ysmu.compat.TinkersCrossbowCompat;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.util.MolangUtils;

/**
 * The {@code query.*} / {@code ysm.*} values that are a <b>pure function of the entity being drawn</b>, published for
 * whichever entity the current frame belongs to.
 *
 * <p>Why this exists as one place. The world path binds these names to the local player every frame
 * ({@code AnimationRegister#setParserValue}), because 1.7.10's legacy {@code MolangParser} is stateless with respect to
 * the animatable - a value is a {@code LazyVariable} whose supplier is a closure. A sub-entity that is drawn by hand
 * (an arrow, a vehicle) never goes through that path, so before this class its animation read either the registered
 * default or, worse, whatever the local player's frame had left in the shared table: a vehicle animation asking
 * {@code query.is_in_water} answered with the <em>player's</em> state. Upstream has one answer for both cases - its
 * query bindings read the context's entity ({@code IContext.entity()}), so a vehicle's queries come from the vehicle
 * and a boat's player-only queries answer "not applicable" - and this class is the port's counterpart: the same names,
 * once, parameterised by the entity instead of closed over a player.</p>
 *
 * <p>Upstream's {@code CustomVehicleEntity} is a real animatable, so its queries arrive through the engine's own
 * binding table; the port's vehicles and projectiles are not animatables at all (see
 * {@code VehicleRenderer#tickAnimation}), so both call this.</p>
 *
 * <p><b>What is not here.</b> The names whose value is frame data or camera state rather than the drawn entity stay on
 * the player path, because a sub-entity frame has no source for them at all: the {@code AnimationEvent}-derived head
 * angles ({@code query.head_x_rotation} / {@code head_y_rotation} / {@code head_z_rotation}, {@code ysm.head_yaw} /
 * {@code ysm.head_pitch}), {@code ysm.is_close_eyes}, {@code query.distance_from_camera} and {@code query.is_jumping}.
 * Those are guarded there by "the frame subject is a player" and answer 0 otherwise. The world-level names
 * ({@code query.actor_count}, {@code query.time_of_day}, {@code query.time_stamp}, {@code query.moon_phase}) and the
 * camera/input ones ({@code query.is_first_person}, {@code ysm.person_view}) are deliberately left unguarded: their
 * upstream bindings are not entity state either, so a sub-entity frame reading them reads the same value the player
 * frame does. {@link #PUBLISHED_NAMES} is the exact set this class covers, so that boundary is checkable rather than
 * implied.</p>
 */
public final class EntityQueryBindings {

    /**
     * Every name {@link #apply} writes, for diagnostics and for a caller that needs to know the covered set.
     * <p>
     * The first group is answerable by any entity (upstream's {@code entityVar}); the second needs a living entity and
     * the third a player, and each answers the registered default for the other kinds - which is what upstream's typed
     * registration ({@code livingEntityVar} / {@code playerVar} / {@code clientPlayerVar}, {@code YSMBinding.java:114-175})
     * does when the context entity is not of that type.
     */
    public static final String[] PUBLISHED_NAMES = {
        // any entity
        "query.is_in_water", "query.is_in_water_or_rain", "query.is_on_fire", "query.is_playing_dead",
        "query.is_riding", "query.is_on_ground", "query.has_rider", "query.modified_distance_moved",
        "query.walk_distance", "query.body_y_rotation", "query.body_x_rotation", "query.cardinal_facing_2d",
        "query.eye_target_x_rotation", "query.eye_target_y_rotation", "query.is_swimming", "query.ground_speed",
        "query.yaw_speed", "query.vertical_speed", "query.position_delta", "ysm.is_passenger",
        "ysm.delta_movement_length", "ysm.on_ladder", "ysm.input_vertical",
        // living entity
        "query.health", "query.max_health", "query.hurt_time", "query.is_sprinting", "query.is_sneaking",
        "query.is_sleeping", "ysm.is_sneak", "ysm.is_sleep", "ysm.armor_value", "ysm.hurt_time", "ysm.swinging",
        "ysm.swing_time", "ysm.mainhand_charged_crossbow", "ysm.food_level",
        // player
        "query.is_eating", "query.is_using_item", "query.is_blocking", "query.item_in_use_duration",
        "query.item_max_use_duration", "query.item_remaining_use_duration", "query.equipment_count",
        "query.has_cape", "query.player_level", "ysm.has_helmet", "ysm.has_chest_plate", "ysm.has_leggings",
        "ysm.has_boots", "ysm.has_mainhand", "ysm.has_offhand", "ysm.swinging_arm", "ysm.offhand_charged_crossbow",
        "ysm.is_fishing", "ysm.has_elytra", "ysm.elytra_rot_x", "ysm.elytra_rot_y", "ysm.elytra_rot_z" };

    private EntityQueryBindings() {}

    /**
     * Binds every name in {@link #PUBLISHED_NAMES} to {@code entity}.
     *
     * <p>The definitions are the ones the player path already used, unchanged - {@code AnimationRegister}'s
     * {@code setEntityQueryValues} / {@code setStateQueryValues} / {@code setYsmValues} now call this instead of
     * repeating them, so a name has one definition and cannot drift between the two paths. Two of them keep a
     * player-only <em>refinement</em> applied afterwards by that path, and say so there:
     * {@code query.is_on_ground} / {@code query.is_sneaking} / {@code ysm.is_sneak} (1.7.10 does not sync
     * {@code onGround} for a remote player, so the port extrapolates it) and {@code query.ground_speed} /
     * {@code query.yaw_speed} (the port smooths and clamps them for a remote player). Upstream has no such
     * refinements; a sub-entity takes the plain value here, which is upstream's.</p>
     *
     * @param partialTicks the render partial tick, needed by the one binding that interpolates a body yaw
     *                     ({@code ysm.input_vertical})
     */
    public static void apply(MolangParser parser, Entity entity, float partialTicks) {
        if (parser == null || entity == null) {
            return;
        }
        EntityLivingBase living = entity instanceof EntityLivingBase ? (EntityLivingBase) entity : null;

        // Entity-level: answerable for every entity, including a boat or a minecart.
        parser.setValue("query.is_in_water", () -> MolangUtils.booleanToFloat(entity.isInWater()));
        parser.setValue("query.is_in_water_or_rain", () -> MolangUtils.booleanToFloat(entity.isWet()));
        parser.setValue("query.is_on_fire", () -> MolangUtils.booleanToFloat(entity.isBurning()));
        parser.setValue("query.is_playing_dead", () -> MolangUtils.booleanToFloat(entity.isDead));
        parser.setValue("query.is_riding", () -> MolangUtils.booleanToFloat(entity.isRiding()));
        parser.setValue("query.is_on_ground", () -> MolangUtils.booleanToFloat(entity.onGround));
        parser.setValue("query.has_rider", () -> MolangUtils.booleanToFloat(entity.riddenByEntity != null));
        parser.setValue("query.modified_distance_moved", () -> (double) entity.distanceWalkedModified);
        parser.setValue("query.walk_distance", () -> (double) entity.distanceWalkedOnStepModified);
        parser.setValue("query.body_y_rotation", () -> (double) MathHelper.wrapAngleTo180_float(entity.rotationYaw));
        parser.setValue("query.is_swimming", () -> MolangUtils.booleanToFloat(entity.isInWater()));
        parser.setValue("ysm.is_passenger", () -> MolangUtils.booleanToFloat(entity.isRiding()));

        // Movement. The per-tick deltas, because 1.7.10 does not sync motionX/Z for a living entity and the port's
        // own remote-player branch reads the same deltas for exactly that reason
        // (RemotePlayerAnimationQueries#getGroundSpeed). The x20 matches the port's query.ground_speed convention.
        parser.setValue("query.ground_speed", () -> groundSpeed(entity));
        parser.setValue(
            "query.yaw_speed",
            () -> (double) MathHelper.wrapAngleTo180_float(entity.rotationYaw - entity.prevRotationYaw));
        parser.setValue("query.vertical_speed", () -> (entity.posY - entity.prevPosY) * 20.0D);
        // The arrow publishes this one itself today (ArrowProjectileRenderer:305-314); same definition, no x20.
        parser.setValue("ysm.delta_movement_length", () -> movementLength(entity));

        // Living-only. A boat or a minecart is not a living entity, so these answer 0/false - upstream's "not
        // applicable" answer for the same queries on the same entity.
        parser.setValue("query.health", () -> living == null ? 0.0D : (double) living.getHealth());
        parser.setValue("query.max_health", () -> living == null ? 0.0D : (double) living.getMaxHealth());
        parser.setValue("query.hurt_time", () -> living == null ? 0.0D : (double) living.hurtTime);
        parser.setValue("query.is_sprinting", () -> MolangUtils.booleanToFloat(living != null && living.isSprinting()));
        parser.setValue(
            "query.is_sneaking",
            () -> MolangUtils.booleanToFloat(living != null && entity.onGround && living.isSneaking()));
        parser.setValue("ysm.is_sneak", () -> MolangUtils.booleanToFloat(living != null && entity.onGround
            && living.isSneaking()));

        // Orientation, which is on Entity in 1.7.10 and on any entity upstream.
        parser.setValue("query.body_x_rotation", () -> (double) entity.rotationPitch);
        parser.setValue(
            "query.cardinal_facing_2d",
            () -> (double) (MathHelper.floor_double((double) (entity.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3));
        parser.setValue("query.eye_target_x_rotation", () -> (double) entity.rotationPitch);
        parser.setValue("query.eye_target_y_rotation", () -> (double) entity.rotationYaw);
        // The bare-name spelling of the position_delta *function* (AnimationRegister registers the function table
        // entry separately); same value as ysm.delta_movement_length, which is why both call one helper.
        parser.setValue("query.position_delta", () -> movementLength(entity));
        // Upstream binds on_ladder against LivingEntity (YSMBinding.java:125, `livingEntityVar`), so a non-living
        // entity answers the registered default - false. 1.7.10 declares isOnLadder() on EntityLivingBase
        // (EntityLivingBase.java:1098), not on Entity, so this is a living guard rather than a plain call.
        parser.setValue("ysm.on_ladder", () -> MolangUtils.booleanToFloat(living != null && living.isOnLadder()));
        // Upstream registers input_vertical as entityVar (YSMBinding.java:96) and derives it from the entity's own
        // movement delta against the interpolated *view* yaw (MoveInputVariable.getVertical reads
        // `entity.getViewYRot(partialTick)`), so it is entity-parameterised there too. The port's player path uses the
        // body yaw instead (pre-existing; see moveInputVertical), and the body yaw lives on EntityLivingBase
        // (EntityLivingBase.java:91-92) - so a non-living entity takes the 1.7.10 equivalent of upstream's own input,
        // the interpolated rotationYaw on Entity (Entity.java:86,89), rather than the player's value or 0.
        parser.setValue("ysm.input_vertical", () -> moveInputVertical(entity, partialTicks));

        // ---- names upstream registers against a narrower type ----
        // Upstream's binding table is typed: livingEntityVar (YSMBinding.java:114-150), playerVar (:151-171) and
        // clientPlayerVar (:173-175) each require the context entity to be of that type, and ContextBinding answers
        // the registered default when it is not - so a boat's has_helmet is false and its food_level is 0 rather than
        // "whatever the local player has". The same guards are applied here; each value is otherwise the expression
        // the player path already used.
        EntityPlayer player = entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
        parser.setValue("query.is_sleeping", () -> MolangUtils.booleanToFloat(living != null && living.isPlayerSleeping()));
        // 1.7.10 keeps the item-in-use accessors on EntityPlayer only (EntityPlayer.java:196-213), which is why this
        // whole family is player-guarded rather than living-guarded as upstream's is.
        parser.setValue(
            "query.is_eating",
            () -> MolangUtils.booleanToFloat(player != null && player.getItemInUse() != null
                && player.getItemInUse()
                    .getItemUseAction() == EnumAction.eat));
        parser.setValue("query.is_using_item", () -> MolangUtils.booleanToFloat(player != null && player.isUsingItem()));
        parser.setValue("query.is_blocking", () -> MolangUtils.booleanToFloat(player != null && BlockingCompat.isBlocking(player)));
        parser.setValue(
            "query.item_in_use_duration",
            () -> player == null ? 0.0D : (getMaxUseDuration(player) - player.getItemInUseCount()) / 20.0D);
        parser.setValue("query.item_max_use_duration", () -> player == null ? 0.0D : getMaxUseDuration(player) / 20.0D);
        parser.setValue("query.item_remaining_use_duration", () -> player == null ? 0.0D : player.getItemInUseCount() / 20.0D);
        // A player's inventory is the only armor source 1.7.10 has for this query.
        parser.setValue("query.equipment_count", () -> player == null ? 0.0D : (double) getEquipmentCount(player));
        parser.setValue("query.has_cape", () -> MolangUtils.booleanToFloat(hasCape(player)));
        parser.setValue("query.player_level", () -> player == null ? 0.0D : (double) player.experienceLevel);

        parser.setValue("ysm.has_helmet", () -> MolangUtils.booleanToFloat(player != null && getSlotValue(player, 4)));
        parser.setValue("ysm.has_chest_plate", () -> MolangUtils.booleanToFloat(player != null && getSlotValue(player, 3)));
        parser.setValue("ysm.has_leggings", () -> MolangUtils.booleanToFloat(player != null && getSlotValue(player, 2)));
        parser.setValue("ysm.has_boots", () -> MolangUtils.booleanToFloat(player != null && getSlotValue(player, 1)));
        parser.setValue("ysm.has_mainhand", () -> MolangUtils.booleanToFloat(player != null && getSlotValue(player, 0)));
        // Slot 5 is Backhand's offhand, a player feature in 1.7.10.
        parser.setValue("ysm.has_offhand", () -> MolangUtils.booleanToFloat(player != null && getSlotValue(player, 5)));
        // Upstream: livingEntityVar (YSMBinding.java:122-123).
        parser.setValue("ysm.armor_value", () -> living == null ? 0.0D : (double) living.getTotalArmorValue());
        parser.setValue("ysm.hurt_time", () -> living == null ? 0.0D : (double) living.hurtTime);
        parser.setValue("ysm.swinging", () -> MolangUtils.booleanToFloat(living != null && living.isSwingInProgress));
        parser.setValue("ysm.swing_time", () -> living == null ? 0.0D : (double) living.swingProgressInt);
        parser.setValue(
            "ysm.swinging_arm",
            () -> player == null ? 0.0D : (BackhandCompat.swingingArm(player) ? 0.0D : 1.0D));
        parser.setValue(
            "ysm.mainhand_charged_crossbow",
            () -> MolangUtils.booleanToFloat(living != null && TinkersCrossbowCompat.isCrossbowLoaded(living.getHeldItem())));
        parser.setValue(
            "ysm.offhand_charged_crossbow",
            () -> MolangUtils.booleanToFloat(
                player != null && TinkersCrossbowCompat.isCrossbowLoaded(BackhandCompat.getOffhandItem(player))));
        parser.setValue("ysm.is_fishing", () -> MolangUtils.booleanToFloat(player != null && player.fishEntity != null));
        parser.setValue("ysm.is_sleep", () -> MolangUtils.booleanToFloat(living != null && living.isPlayerSleeping()));
        parser.setValue(
            "ysm.has_elytra",
            () -> MolangUtils.booleanToFloat(player != null && EtFuturumCompat.getEquippedElytra(player) != null));
        parser.setValue("ysm.elytra_rot_x", () -> player == null ? 0.0D : (double) EtFuturumCompat.elytraRotX(player));
        parser.setValue("ysm.elytra_rot_y", () -> player == null ? 0.0D : (double) EtFuturumCompat.elytraRotY(player));
        parser.setValue("ysm.elytra_rot_z", () -> player == null ? 0.0D : (double) EtFuturumCompat.elytraRotZ(player));
        // Upstream returns 20 for a living entity that is not a player (YSMBinding.java:342-350).
        parser.setValue(
            "ysm.food_level",
            () -> player != null ? (double) player.getFoodStats()
                .getFoodLevel() : (living != null ? 20.0D : 0.0D));
    }

    /** Upstream's {@code getSlotValue} (YSMBinding.java:358-360): is anything equipped in that slot. */
    private static boolean getSlotValue(EntityPlayer player, int slotIndex) {
        if (slotIndex == 5) {
            return BackhandCompat.getOffhandItem(player) != null;
        }
        return player.getEquipmentInSlot(slotIndex) != null;
    }

    /** The player path's armor count, unchanged; 1.7.10 has no other armor source for this query. */
    private static int getEquipmentCount(EntityPlayer player) {
        int count = 0;
        for (ItemStack stack : player.inventory.armorInventory) {
            if (stack != null) {
                count += 1;
            }
        }
        return count;
    }

    /** The player path's cape test, unchanged ({@code func_152122_n} is 1.7.10's "cape is available"). */
    private static boolean hasCape(EntityPlayer player) {
        if (player instanceof AbstractClientPlayer) {
            AbstractClientPlayer clientPlayer = (AbstractClientPlayer) player;
            return !player.isInvisible() && clientPlayer.func_152122_n() && clientPlayer.getLocationCape() != null;
        }
        return false;
    }

    /**
     * The player path's helper, unchanged for a living entity: movement direction relative to where the entity faces,
     * as a cosine. The yaw it faces by is the body yaw ({@code renderYawOffset}), which 1.7.10 declares on
     * {@link EntityLivingBase} - so a non-living entity (boat, minecart, arrow) uses the interpolated
     * {@code rotationYaw} on {@link Entity} instead, which is the 1.7.10 counterpart of the {@code getViewYRot} upstream
     * feeds this same variable ({@code MoveInputVariable.getVertical}). The delta test and the trigonometry are
     * identical on both branches, so this stays one rule with one substitution.
     */
    private static double moveInputVertical(Entity entity, float partialTicks) {
        double deltaX = entity.posX - entity.prevPosX;
        double deltaZ = entity.posZ - entity.prevPosZ;
        if (Math.sqrt(deltaX * deltaX + deltaZ * deltaZ) < 1.0E-4D) {
            return 0.0D;
        }
        float yawDeg;
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            yawDeg = living.prevRenderYawOffset + (living.renderYawOffset - living.prevRenderYawOffset) * partialTicks;
        } else {
            yawDeg = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
        }
        float moveAngleDeg = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX));
        float entityYawDeg = 90.0F - MathHelper.wrapAngleTo180_float(-yawDeg);
        float relativeDeg = MathHelper.wrapAngleTo180_float(moveAngleDeg - entityYawDeg);
        // 0.017453292F is degrees-to-radians, the same literal the port already uses for the body-yaw particle rotation.
        return MathHelper.cos(relativeDeg * 0.017453292F);
    }

    /** The player path's helper, unchanged (1.7.10 counts item use down; see AnimationRegister's note). */
    private static float getMaxUseDuration(EntityPlayer player) {
        ItemStack useItem = player.getItemInUse();
        if (useItem == null) {
            return 0.0f;
        } else {
            return useItem.getMaxItemUseDuration();
        }
    }

    /** Horizontal per-tick distance in the port's {@code query.ground_speed} unit (blocks per tick x 20). */
    private static double groundSpeed(Entity entity) {
        double dx = entity.posX - entity.prevPosX;
        double dz = entity.posZ - entity.prevPosZ;
        return Math.sqrt(dx * dx + dz * dz) * 20.0D;
    }

    /** The three-axis per-tick distance, which is what {@code ysm.delta_movement_length} reports. */
    private static double movementLength(Entity entity) {
        double dx = entity.posX - entity.prevPosX;
        double dy = entity.posY - entity.prevPosY;
        double dz = entity.posZ - entity.prevPosZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
