package com.fox.ysmu.client.animation;

import java.util.function.BiPredicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.EtFuturumCompat;
import com.fox.ysmu.client.animation.molang.CtrlHoldFunction;
import com.fox.ysmu.client.animation.molang.CtrlScriptBinding;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.client.animation.molang.QueryIsItemNameAnyFunction;
import com.fox.ysmu.client.animation.molang.QueryPositionDeltaFunction;
import com.fox.ysmu.client.animation.molang.QueryPositionFunction;
import com.fox.ysmu.client.animation.molang.YsmParticleFunction;
import com.fox.ysmu.client.animation.molang.YsmSyncFunction;
import com.fox.ysmu.client.animation.molang.YsmRelativeBlockNameFunction;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.MolangUtils;

public class AnimationRegister {

    // Reserved preview-animation names (upstream parity). They are *not* registered as world states: only the GUI
    // preview entity asks for them, and a model enables them simply by defining animations with those names.
    public static final String IDLE = "idle";
    public static final String HOVER = "hover";
    public static final String HOVER_FADEOUT = "hover_fadeout";
    public static final String FOCUS = "focus";
    /** Upstream's "play nothing" sentinel for a preview channel. */
    public static final String EMPTY = "empty";

    private static final double MIN_SPEED = 0.05;
    // S-05: registration is per-JVM state (AnimationManager's tables and the parser's function/variable maps), and
    // a second call would append every AnimationState again - the same state would then be tested twice per frame.
    // ClientProxy#init is the only caller today; the guard keeps a future resource-reload path from duplicating.
    private static boolean animationStatesRegistered;
    private static boolean molangVariablesRegistered;
    private static boolean repeatRegistrationWarned;

    public static void registerAnimationState() {
        if (animationStatesRegistered) {
            warnRepeatedRegistration("registerAnimationState");
            return;
        }
        animationStatesRegistered = true;
        registerHighPriorityStates();
        registerFlyingStates();
        registerDamageJumpSneakStates();
        registerMovementStates();
        registerIdleFallback();
        // B-06: the `ctrl.*` script API a pack script (`functions/<x>@player_ctrl_<name>.molang`) runs against.
        // Registered here, after the state table is complete, because its `ctrl.<state>` queries are keyed by exactly
        // that vocabulary - upstream tests them against the main controller's current animation
        // (`CtrlBinding#testCondition`).
        CtrlScriptBinding.register(
            GeckoLibCache.getInstance().parser,
            AnimationManager.getInstance()
                .registeredStateNames());
    }

    private static void registerHighPriorityStates() {
        register("death", ILoopType.EDefaultLoopTypes.PLAY_ONCE, Priority.HIGHEST, (player, event) -> player.isDead);
        // S-03: 1.7.10 has no `Pose`, so the modern `Pose.SLEEPING` / `Pose.SWIMMING` distinctions (standing sleep,
        // swimming/climbing) cannot be derived from a pose. `sleep` uses the vanilla sleeping flag and `climb`/
        // `climbing` are mapped onto the ladder; those are deliberate 1.7.10 adaptations, not missing work.
        register("sleep", Priority.HIGHEST, (player, event) -> player.isPlayerSleeping());
        register("swim", Priority.HIGHEST, (player, event) -> player.isInWater() && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        // The three directed ladder states are registered BEFORE `climb`/`climbing`, and that order carries the fix:
        // `climbing` matches every frame on a ladder, and `predicateMain` returns the first match whose animation the
        // model declares, so anything registered after it can never be reached while the player is on a ladder.
        // Upstream needs no such order - its `climb`/`climbing` answer to `Pose.SWIMMING` and therefore never match a
        // ladder at all (client/animation/AnimationRegister.java:27-32 there, where `ladder_*` own `onClimbable()`) -
        // but this port has no swimming pose and maps both onto the ladder, so the specific states must come first.
        // A model that declares no `ladder_*` still falls through to `climb`/`climbing`, because `predicateMain` only
        // accepts a state the model actually defines; the directed clips are no longer dead for the packs that have
        // both, which is every shipped pack.
        register("ladder_up", Priority.HIGHEST, (player, event) -> player.isOnLadder() && motionYState(player, 0.1D) == 1);
        register("ladder_stillness", Priority.HIGHEST, (player, event) -> player.isOnLadder() && motionYState(player, 0.1D) == 0);
        register("ladder_down", Priority.HIGHEST, (player, event) -> player.isOnLadder() && motionYState(player, 0.1D) == -1);
        register("climb", Priority.HIGHEST, (player, event) -> player.isOnLadder() && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("climbing", Priority.HIGHEST, (player, event) -> player.isOnLadder());
    }

    private static void registerFlyingStates() {
        // B-04: upstream has no riding states in the main table at all - `ride_pig` / `ride` / `boat` / `sit` belong to
        // its `player.vehicle` controller (client/animation/predicate/VehiclePredicate.java:73-96), which this port now
        // has too. Keeping them here as well is what made a minecart rider play the horse clip: the main table's `ride`
        // needs only `isRiding() && !boat`, so it matched first and the vehicle controller could never reach its own
        // `sit` fallback.
        register("fly", Priority.HIGH, (player, event) -> isPlayerFlying(player));
        // A-04: upstream registers `elytra_fly` here, between `fly` and `swim_stand`, at HIGH
        // (client/animation/AnimationRegister.java:40), gated on the fall-flying pose. 1.7.10 has no pose and no
        // elytra of its own, so the flag comes from Et Futurum Requiem when it is installed; without the mod the
        // predicate is a constant false and the state simply never matches, which is the behaviour the port already
        // had - now declared instead of silently missing. (`riptide`, upstream's other unported state at :24, has no
        // 1.7.10 source at all: neither the base game nor Et Futurum Requiem has a trident, so it stays unregistered.)
        register("elytra_fly", Priority.HIGH, (player, event) -> EtFuturumCompat.isElytraFlying(player));
        // A-08: upstream gates this on `isInWater() && !onGround()` (client/animation/AnimationRegister.java:42), and
        // the ground half matters in 1.7.10: standing on the bottom of a shallow puddle has `onGround` true, and
        // without it the model played the treading-water clip there where upstream plays walk or idle. The other half
        // of A-08 - `swim`, which upstream gates on `isSwimming()` (:26) - is left as it is: `isSwimming()` is a 1.20
        // vanilla method whose definition is not in this workspace, so copying it here would be a guess, not a port.
        register("swim_stand", Priority.NORMAL, (player, event) -> player.isInWater() && !player.onGround);
    }

    private static void registerDamageJumpSneakStates() {
        register("attacked", ILoopType.EDefaultLoopTypes.PLAY_ONCE, Priority.NORMAL, (player, event) -> player.hurtTime > 0);
        register("jump", Priority.NORMAL, (player, event) -> isPlayerJumping(player));
        register("sneak", Priority.NORMAL, (player, event) -> isPlayerOnGround(player) && player.isSneaking() && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("sneaking", Priority.NORMAL, (player, event) -> isPlayerOnGround(player) && player.isSneaking());
    }

    private static void registerMovementStates() {
        register("run", Priority.LOW, (player, event) -> isPlayerOnGround(player) && player.isSprinting());
        register("walk", Priority.LOW, (player, event) -> isPlayerOnGround(player) && event.getLimbSwingAmount() > MIN_SPEED);
    }

    private static void registerIdleFallback() {
        register("idle", Priority.LOWEST, (player, event) -> true);
    }

    @SuppressWarnings("deprecation")
    public static void registerVariables() {
        if (molangVariablesRegistered) {
            warnRepeatedRegistration("registerVariables");
            return;
        }
        molangVariablesRegistered = true;
        MolangParser parser = GeckoLibCache.getInstance().parser;
        parser.functions.put("query.position_delta", QueryPositionDeltaFunction.class);
        parser.functions.put("query.position", QueryPositionFunction.class);
        parser.functions.put("query.is_item_name_any", QueryIsItemNameAnyFunction.class);
        parser.functions.put("ysm.particle", YsmParticleFunction.class);
        // A-16: upstream YSMBinding.java:190 `function("sync", new Sync())` - a pack's way to hand values to every
        // client that can see the model; the echo runs the model's `@sync` script.
        parser.functions.put("ysm.sync", YsmSyncFunction.class);
        parser.functions.put("ysm.relative_block_name", YsmRelativeBlockNameFunction.class);
        parser.functions.put("ysm.relative_block_name_any", YsmRelativeBlockNameFunction.class);
        parser.functions.put("ctrl.hold", CtrlHoldFunction.class);
        // fn.<name> 的函数体随模型而定（pack 的 functions/*.molang），无法静态注册一个类，交给动态解析。
        PackUserFunctions.installResolver(parser);
        // Bedrock 的布尔字面量：OpenYSM 的 pack 脚本会写 v.x=true / false，而本解析器把它们当普通变量（默认 0）。
        // 注册成 1/0 之后，赋值与判断才和上游一致。
        parser.register(new LazyVariable("true", 1));
        parser.register(new LazyVariable("false", 0));
        registerQueryVariables(parser);
        registerYsmVariables(parser);
    }

    /** One line, not one per repeat, so a caller that re-runs registration cannot spam the log. */
    private static void warnRepeatedRegistration(String method) {
        if (repeatRegistrationWarned) {
            return;
        }
        repeatRegistrationWarned = true;
        ysmu.LOG.warn(
            "AnimationRegister#{} was called twice; the repeat is ignored (states/variables are registered once per JVM)",
            method);
    }

    private static void registerQueryVariables(MolangParser parser) {
        parser.register(new LazyVariable("query.actor_count", 0));
        parser.register(new LazyVariable("query.anim_time", 0));

        parser.register(new LazyVariable("query.body_x_rotation", 0));
        parser.register(new LazyVariable("query.body_y_rotation", 0));
        parser.register(new LazyVariable("query.cardinal_facing_2d", 0));
        parser.register(new LazyVariable("query.distance_from_camera", 0));
        parser.register(new LazyVariable("query.equipment_count", 0));
        parser.register(new LazyVariable("query.eye_target_x_rotation", 0));
        parser.register(new LazyVariable("query.eye_target_y_rotation", 0));
        parser.register(new LazyVariable("query.ground_speed", 0));

        parser.register(new LazyVariable("query.has_cape", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.has_rider", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.head_x_rotation", 0));
        parser.register(new LazyVariable("query.head_y_rotation", 0));
        parser.register(new LazyVariable("query.health", 0));
        parser.register(new LazyVariable("query.hurt_time", 0));

        parser.register(new LazyVariable("query.is_eating", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_first_person", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_in_water", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_in_water_or_rain", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_jumping", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_on_fire", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_on_ground", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_playing_dead", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_riding", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_sleeping", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_sneaking", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_spectator", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_sprinting", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_swimming", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_using_item", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.item_in_use_duration", 0));
        parser.register(new LazyVariable("query.item_max_use_duration", 0));
        parser.register(new LazyVariable("query.item_remaining_use_duration", 0));

        parser.register(new LazyVariable("query.life_time", 0));
        parser.register(new LazyVariable("query.max_health", 0));
        parser.register(new LazyVariable("query.modified_distance_moved", 0));
        parser.register(new LazyVariable("query.moon_phase", 0));

        parser.register(new LazyVariable("query.player_level", 0));
        parser.register(new LazyVariable("query.time_of_day", 0));
        parser.register(new LazyVariable("query.time_stamp", 0));
        parser.register(new LazyVariable("query.vertical_speed", 0));
        parser.register(new LazyVariable("query.walk_distance", 0));
        parser.register(new LazyVariable("query.yaw_speed", 0));

        parser.register(new LazyVariable("query.position_delta", 0));
    }

    private static void registerYsmVariables(MolangParser parser) {
        parser.register(new LazyVariable("ysm.head_yaw", 0));
        parser.register(new LazyVariable("ysm.head_pitch", 0));
        parser.register(new LazyVariable("ysm.has_helmet", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_chest_plate", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_leggings", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_boots", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_mainhand", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_offhand", MolangUtils.FALSE));

        parser.register(new LazyVariable("ysm.has_elytra", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.elytra_rot_x", 0));
        parser.register(new LazyVariable("ysm.elytra_rot_y", 0));
        parser.register(new LazyVariable("ysm.elytra_rot_z", 0));

        parser.register(new LazyVariable("ysm.is_close_eyes", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_passenger", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_sleep", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_sneak", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_riptide", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.on_ladder", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_fishing", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.swinging", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.swing_time", 0));
        parser.register(new LazyVariable("ysm.swinging_arm", 0));
        parser.register(new LazyVariable("ysm.mainhand_charged_crossbow", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.offhand_charged_crossbow", MolangUtils.FALSE));

        parser.register(new LazyVariable("ysm.armor_value", 0));
        parser.register(new LazyVariable("ysm.hurt_time", 0));
        parser.register(new LazyVariable("ysm.food_level", 20));

        // A-15: see the assignment in setYsmValues for what this is and why it was missing.
        parser.register(new LazyVariable("ysm.input_vertical", 0));

        // parser.register(new LazyVariable("ysm.first_person_mod_hide", MolangUtils.FALSE));
    }

    public static void setParserValue(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        // A-05 / A-06③: publish the frame's subject before anything is evaluated. The engine opens its own MoLang
        // scope in CustomPlayerModel#setMolangQueries, but it exposes no accessor for the animatable, so
        // query.position_delta(axis) and the animation-file ctrl.hold read the entity from here.
        MolangFrameContext.begin(player);
        // Same reason, for the pack's own scripts: fn.<name> resolves against the model being rendered.
        PackUserFunctions.begin(
            animationEvent.getAnimatable() == null ? null : animationEvent.getAnimatable()
                .getMainModel());
        RemotePlayerAnimationQueries.QueryValues queryValues = RemotePlayerAnimationQueries
            .get(animationEvent, player, data.netHeadYaw);
        setEntityQueryValues(parser, data, player, mc, queryValues);
        setStateQueryValues(parser, player, mc);
        setItemUseQueryValues(parser, player);
        setWorldQueryValues(parser, player, mc);
        setYsmValues(animationEvent, parser, data, player, queryValues);
    }

    private static void setEntityQueryValues(MolangParser parser, EntityModelData data, EntityPlayer player,
        Minecraft mc, RemotePlayerAnimationQueries.QueryValues queryValues) {
        parser.setValue("query.actor_count", () -> mc.theWorld.loadedEntityList.size());
        parser.setValue("query.body_x_rotation", player.rotationPitch);
        parser.setValue("query.body_y_rotation", () -> MathHelper.wrapAngleTo180_float(player.rotationYaw));
        parser.setValue("query.cardinal_facing_2d", () -> MathHelper.floor_double((double) (player.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3);
        parser.setValue("query.distance_from_camera", () -> mc.renderViewEntity.getDistanceToEntity(player));
        parser.setValue("query.equipment_count", () -> getEquipmentCount(player));
        parser.setValue("query.eye_target_x_rotation", () -> player.rotationPitch);
        parser.setValue("query.eye_target_y_rotation", () -> player.rotationYaw);
        parser.setValue("query.ground_speed", queryValues.groundSpeed());
        parser.setValue("query.has_cape", () -> MolangUtils.booleanToFloat(hasCape(player)));
        parser.setValue("query.has_rider", () -> MolangUtils.booleanToFloat(player.riddenByEntity != null));
        parser.setValue("query.head_x_rotation", queryValues.headYaw());
        parser.setValue("query.head_y_rotation", () -> data.headPitch);
        parser.setValue("query.health", player::getHealth);
        parser.setValue("query.hurt_time", () -> player.hurtTime);
        parser.setValue("query.modified_distance_moved", () -> player.distanceWalkedModified);
        parser.setValue("query.vertical_speed", () -> getVerticalSpeed(player));
        parser.setValue("query.walk_distance", () -> player.distanceWalkedOnStepModified);
        parser.setValue("query.yaw_speed", queryValues.yawSpeed());

        parser.setValue("query.position_delta", () -> {
            double dx = player.posX - player.prevPosX;
            double dy = player.posY - player.prevPosY;
            double dz = player.posZ - player.prevPosZ;
            return Math.sqrt(dx*dx + dy*dy + dz*dz);
        });
    }

    private static void setStateQueryValues(MolangParser parser, EntityPlayer player, Minecraft mc) {
        parser.setValue("query.is_eating", () -> MolangUtils.booleanToFloat(player.getItemInUse() != null && player.getItemInUse().getItemUseAction() == EnumAction.eat));
        parser.setValue("query.is_first_person", () -> MolangUtils.booleanToFloat(mc.gameSettings.thirdPersonView == 0));
        parser.setValue("query.is_in_water", () -> MolangUtils.booleanToFloat(player.isInWater()));
        parser.setValue("query.is_in_water_or_rain", () -> MolangUtils.booleanToFloat(player.isWet()));
        parser.setValue("query.is_jumping", () -> MolangUtils.booleanToFloat(isPlayerJumping(player)));
        parser.setValue("query.is_on_fire", () -> MolangUtils.booleanToFloat(player.isBurning()));
        parser.setValue("query.is_on_ground", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player)));
        parser.setValue("query.is_playing_dead", () -> MolangUtils.booleanToFloat(player.isDead));
        parser.setValue("query.is_riding", () -> MolangUtils.booleanToFloat(player.isRiding()));
        parser.setValue("query.is_sleeping", () -> MolangUtils.booleanToFloat(player.isPlayerSleeping()));
        parser.setValue("query.is_sneaking", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player) && player.isSneaking()));
        parser.setValue("query.is_sprinting", () -> MolangUtils.booleanToFloat(player.isSprinting()));
        parser.setValue("query.is_swimming", () -> MolangUtils.booleanToFloat(player.isInWater()));
        parser.setValue("query.is_using_item", () -> MolangUtils.booleanToFloat(player.isUsingItem()));
    }

    private static void setItemUseQueryValues(MolangParser parser, EntityPlayer player) {
        // In 1.7.10, item use ticks count down. Modern versions count up. The logic is inverted.
        parser.setValue("query.item_in_use_duration", () -> (getMaxUseDuration(player) - player.getItemInUseCount()) / 20.0);
        parser.setValue("query.item_max_use_duration", () -> getMaxUseDuration(player) / 20.0);
        parser.setValue("query.item_remaining_use_duration", () -> player.getItemInUseCount() / 20.0);
    }

    private static void setWorldQueryValues(MolangParser parser, EntityPlayer player, Minecraft mc) {
        parser.setValue("query.max_health", player::getMaxHealth);
        parser.setValue("query.moon_phase", () -> mc.theWorld.getMoonPhase());
        parser.setValue("query.player_level", () -> player.experienceLevel);
        parser.setValue("query.time_of_day", () -> MolangUtils.normalizeTime(mc.theWorld.getWorldTime()));
        parser.setValue("query.time_stamp", () -> mc.theWorld.getWorldTime());
    }

    private static void setYsmValues(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player, RemotePlayerAnimationQueries.QueryValues queryValues) {
        parser.setValue("ysm.head_yaw", queryValues.headYaw());
        parser.setValue("ysm.head_pitch", () -> data.headPitch);
        parser.setValue("ysm.has_helmet", () -> getSlotValue(player, 4));
        parser.setValue("ysm.has_chest_plate", () -> getSlotValue(player, 3));
        parser.setValue("ysm.has_leggings", () -> getSlotValue(player, 2));
        parser.setValue("ysm.has_boots", () -> getSlotValue(player, 1));
        // A-16: the two per-frame hooks upstream fires from preAnimationSetup
        // (client/entity/CustomHumanoidEntity.java:205-218). `player_init` runs once after a model loads;
        // PackUserFunctions#clear (called when the model caches are rebuilt) re-arms it. `player_update` runs every
        // frame and is handed "did this frame actually tick" as args[0], so a script guarding on it does not do its
        // work twice for a frame this port renders twice.
        String hookPlayerKey = player.getUniqueID()
            .toString();
        ResourceLocation hookModelId = animationEvent.getAnimatable()
            .getMainModel();
        if (PackUserFunctions.consumeInitPending(hookPlayerKey, hookModelId)) {
            PackUserFunctions.fireHook("player_init", hookModelId);
        }
        PackUserFunctions.fireHook(
            "player_update",
            hookModelId,
            PackUserFunctions.frameTicked(hookPlayerKey, animationEvent.getAnimationTick()) ? 1.0D : 0.0D);

        parser.setValue("ysm.has_mainhand", () -> getSlotValue(player, 0));
        parser.setValue("ysm.has_offhand", () -> getSlotValue(player, 5));
        // A-06: these four were registered when the port began and never assigned, so every pack expression that read
        // them got the LazyVariable default - a constant zero. `has_elytra` is upstream's "an elytra is worn"
        // (client/animation/molang/YSMBinding.java:120) and the rotations are the wing angles in degrees
        // (YSMBinding.java:173-175, which converts the same radians). 1.7.10 only has an elytra at all through
        // Et Futurum Requiem, so that is where they come from; without it they stay at their defaults by design.
        // The wing angles themselves are advanced by EtFuturumElytraLayer, which is the one caller of ETFR's wing
        // renderer: ETFR updates them from inside it and nothing else may, or the 0.1 lerp toward the target angle
        // would run more than once per pass.
        parser.setValue(
            "ysm.has_elytra",
            () -> MolangUtils.booleanToFloat(EtFuturumCompat.getEquippedElytra(player) != null));
        parser.setValue("ysm.elytra_rot_x", () -> EtFuturumCompat.elytraRotX(player));
        parser.setValue("ysm.elytra_rot_y", () -> EtFuturumCompat.elytraRotY(player));
        parser.setValue("ysm.elytra_rot_z", () -> EtFuturumCompat.elytraRotZ(player));
        // A-15: upstream MoveInputVariable#getVertical
        // (client/animation/molang/variable/MoveInputVariable.java:9-29) - the movement direction relative to where the
        // entity is facing, as a cosine: 1 straight ahead, negative walking backwards, 0 when not moving.
        // This name had no producer at all, and the parser answers an unknown variable with a silent 0 - so a script
        // comparing `ysm.input_vertical < 0.1` compared against 0 and was therefore *always true*.
        // wine_fox/18_wedding's backwards-walk script (functions/简单倒走动画@player_ctrl_main.molang) played its clip
        // on every walk instead of only when walking backwards.
        parser.setValue("ysm.input_vertical", () -> moveInputVertical(player, animationEvent.getPartialTick()));
        // 1.7.10 has no riptide and Et Futurum Requiem does not add one - its whole tree has no trident - so there is
        // no source to read: upstream binds this to `entity.isAutoSpinAttack()` (YSMBinding.java:121). Assigned
        // explicitly so the constant reads as a decision rather than as a name someone forgot to wire up.
        parser.setValue("ysm.is_riptide", MolangUtils.FALSE);
        parser.setValue("ysm.is_close_eyes", () -> getEyeCloseState(animationEvent, player));
        parser.setValue("ysm.is_passenger", () -> MolangUtils.booleanToFloat(player.isRiding()));
        parser.setValue("ysm.is_sleep", () -> MolangUtils.booleanToFloat(player.isPlayerSleeping()));
        parser.setValue("ysm.is_sneak", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player) && player.isSneaking()));
        parser.setValue("ysm.on_ladder", () -> MolangUtils.booleanToFloat(player.isOnLadder()));
        parser.setValue("ysm.is_fishing", () -> MolangUtils.booleanToFloat(player.fishEntity != null));
        parser.setValue("ysm.swinging", () -> MolangUtils.booleanToFloat(player.isSwingInProgress));
        parser.setValue("ysm.swing_time", () -> player.swingProgressInt);
        parser.setValue("ysm.swinging_arm", () -> BackhandCompat.swingingArm(player) ? 0.0d : 1.0d);
        parser.setValue("ysm.mainhand_charged_crossbow", MolangUtils.FALSE);
        parser.setValue("ysm.offhand_charged_crossbow", MolangUtils.FALSE);
        parser.setValue("ysm.armor_value", player::getTotalArmorValue);
        parser.setValue("ysm.hurt_time", () -> player.hurtTime);
        parser.setValue("ysm.food_level", () -> player.getFoodStats().getFoodLevel());
    }

    private static boolean hasCape(EntityPlayer player) {
        if (player instanceof AbstractClientPlayer) {
            AbstractClientPlayer clientPlayer = (AbstractClientPlayer) player;
            // 'isCapeLoaded' & 'isModelPartShown' are modern. 1.7.10 has simpler checks.
            // func_152122_n() checks if the cape texture is available and should be rendered.
            return !player.isInvisible() && clientPlayer.func_152122_n() && clientPlayer.getLocationCape() != null;
        }
        return false;
    }

    private static int getEquipmentCount(EntityPlayer player) {
        int count = 0;
        for (ItemStack s : player.inventory.armorInventory) {
            if (s != null) {
                count += 1;
            }
        }
        return count;
    }

    private static float getMaxUseDuration(EntityPlayer player) {
        ItemStack useItem = player.getItemInUse();
        if (useItem == null) {
            return 0.0f;
        } else {
            return useItem.getMaxItemUseDuration();
        }
    }

    private static float getVerticalSpeed(EntityPlayer player) {
        return (float) ((player.posY - player.prevPosY) * 20.0);
    }

    private static void register(String animationName, ILoopType loopType, int priority,
        BiPredicate<EntityPlayer, AnimationEvent<CustomPlayerEntity>> predicate) {
        AnimationManager manager = AnimationManager.getInstance();
        manager.register(new AnimationState(animationName, loopType, priority, predicate));
    }

    private static void register(String animationName, int priority,
        BiPredicate<EntityPlayer, AnimationEvent<CustomPlayerEntity>> predicate) {
        register(animationName, ILoopType.EDefaultLoopTypes.LOOP, priority, predicate);
    }

    private static double getEyeCloseState(AnimationEvent<CustomPlayerEntity> animationEvent, EntityPlayer player) {
        double remainder = (animationEvent.getAnimationTick() + Math.abs(
            player.getUniqueID()
                .getLeastSignificantBits())
            % 10) % 90;
        boolean isBlinkTime = 85 < remainder && remainder < 90;
        return MolangUtils.booleanToFloat(player.isPlayerSleeping() || isBlinkTime);
    }

    private static double getSlotValue(EntityPlayer player, int slotIndex) {
        if (slotIndex == 5) {
            return MolangUtils.booleanToFloat(BackhandCompat.getOffhandItem(player) != null);
        } else {
            return MolangUtils.booleanToFloat(player.getEquipmentInSlot(slotIndex) != null);
        }
    }

    private static boolean isPlayerOnGround(EntityPlayer player) {
        // 本地玩家
        if (player == Minecraft.getMinecraft().thePlayer) {
            return player.onGround;
        } else {
            return RemotePlayerMotionStates.isOnGround(player);
        }
    }

    private static boolean isPlayerFlying(EntityPlayer player) {
        // 本地玩家
        if (player == Minecraft.getMinecraft().thePlayer) {
            return player.capabilities.isFlying;
        } else {
            return RemotePlayerMotionStates.isFlying(player);
        }
    }

    /**
     * A-15: upstream {@code MoveInputVariable#getVertical} - the direction the entity is moving relative to the way it
     * faces, as a cosine: 1 straight ahead, negative walking backwards, 0 while not moving.
     * <p>
     * Upstream compares the angle of the per-frame position delta against {@code entity.getViewYRot(partialTick)} and
     * returns {@code cos(relative)}; the horizontal delta below {@code 1.0E-4} answers 0, so a standing entity reads 0
     * rather than a direction derived from noise
     * ({@code client/animation/molang/variable/MoveInputVariable.java:9-29}).
     * <p>
     * The yaw is 1.7.10's {@code renderYawOffset}, interpolated - this port's "body yaw", i.e. the direction the entity
     * is actually facing, which is what a movement direction has to be measured against; the same choice
     * {@code YsmParticleFunction} makes when it rotates an offset by the body yaw. (Upstream's {@code getViewYRot} is a
     * 1.20 vanilla method whose body-versus-head semantics are not readable from this workspace, but both candidates
     * agree on the sign this variable is used for - walking forwards versus backwards.)
     */
    private static double moveInputVertical(EntityPlayer player, float partialTicks) {
        double deltaX = player.posX - player.prevPosX;
        double deltaZ = player.posZ - player.prevPosZ;
        if (Math.sqrt(deltaX * deltaX + deltaZ * deltaZ) < 1.0E-4D) {
            return 0.0D;
        }
        float moveAngleDeg = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX));
        float yawDeg = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
        float entityYawDeg = 90.0F - MathHelper.wrapAngleTo180_float(-yawDeg);
        float relativeDeg = MathHelper.wrapAngleTo180_float(moveAngleDeg - entityYawDeg);
        // 0.017453292F is degrees-to-radians, the same literal the port already uses for the body-yaw particle rotation.
        return MathHelper.cos(relativeDeg * 0.017453292F);
    }

    private static boolean isPlayerJumping(EntityPlayer player) {
        if (isPlayerFlying(player) || player.isRiding() || isPlayerOnGround(player) || player.isInWater()) {
            return false;
        }
        if (player == Minecraft.getMinecraft().thePlayer) {
            return motionYState(player, 0.0D) != 0;
        }
        return true;
    }

    /**
     * 获取玩家的垂直移动状态
     * 返回值: 0=静止/未知, 1=向上, -1=向下
     */
    private static int motionYState(EntityPlayer player, double threshold) {
        double motionY;
        if (player == Minecraft.getMinecraft().thePlayer) {
            motionY = player.motionY;
        } else {
            motionY = (player.posY - player.prevPosY) * 2.0D;
        }
        if (motionY > threshold) {
            return 1;
        } else if (motionY < -threshold) {
            return -1;
        } else {
            return 0;
        }
    }
}
