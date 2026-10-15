package com.fox.ysmu.client.animation;

import java.util.function.BiPredicate;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.EtFuturumCompat;
import com.fox.ysmu.client.animation.molang.BonePivotAbsFunction;
import com.fox.ysmu.client.animation.molang.CtrlArmorFunction;
import com.fox.ysmu.client.animation.molang.CtrlHoldFunction;
import com.fox.ysmu.client.animation.molang.CtrlItemFunction;
import com.fox.ysmu.client.animation.molang.CtrlRideFunction;
import com.fox.ysmu.client.animation.molang.CtrlScriptBinding;
import com.fox.ysmu.client.animation.molang.EquippedEnchantmentLevelFunction;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.client.animation.molang.ParticleFunction;
import com.fox.ysmu.client.animation.molang.PerlinNoiseFunction;
import com.fox.ysmu.client.animation.molang.QueryBlockTagFunction;
import com.fox.ysmu.client.animation.molang.QueryDebugOutputFunction;
import com.fox.ysmu.client.animation.molang.QueryDurabilityFunction;
import com.fox.ysmu.client.animation.molang.QueryHasAnyCuriosFunction;
import com.fox.ysmu.client.animation.molang.QueryItemNameAnyFunction;
import com.fox.ysmu.client.animation.molang.QueryItemTagFunction;
import com.fox.ysmu.client.animation.molang.QueryPositionDeltaFunction;
import com.fox.ysmu.client.animation.molang.QueryPositionFunction;
import com.fox.ysmu.client.animation.molang.Rot2CameraFunction;
import com.fox.ysmu.client.animation.molang.YsmDebugDumpFunction;
import com.fox.ysmu.client.animation.molang.YsmDeferFunction;
import com.fox.ysmu.client.animation.molang.YsmEffectLevelFunction;
import com.fox.ysmu.client.animation.molang.YsmEntityQueries;
import com.fox.ysmu.client.animation.molang.YsmModVersionFunction;
import com.fox.ysmu.client.animation.molang.YsmSoundFunction;
import com.fox.ysmu.client.animation.molang.YsmSyncFunction;
import com.fox.ysmu.client.animation.molang.YsmRelativeBlockNameFunction;
import com.fox.ysmu.client.animation.molang.YsmWorldQueries;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.molang.functions.BoneColor;
import software.bernie.geckolib3.core.molang.functions.BoneGlow;
import software.bernie.geckolib3.core.molang.functions.BoneTransparency;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.molang.util.PersonView;
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
        // 函数表只有一份（见 registerFunctions）：这里装到共享 parser 上，registerMolangHooks() 再把它
        // 装到 registrar 上供每个新建的 parser 继承。
        registerFunctions(parser.functions);
        // fn.<name> 的函数体随模型而定（pack 的 functions/*.molang），无法静态注册一个类，交给动态解析。
        PackUserFunctions.installResolver(parser);
        // Bedrock 的布尔字面量：OpenYSM 的 pack 脚本会写 v.x=true / false，而本解析器把它们当普通变量（默认 0）。
        // 注册成 1/0 之后，赋值与判断才和上游一致。
        parser.register(new LazyVariable("true", 1));
        parser.register(new LazyVariable("false", 0));
        registerQueryVariables(parser);
        registerYsmVariables(parser);
        registerPackFacingVariables(parser);
    }

    /**
     * The pack-facing names this task adds to the keyframe path's registry: upstream's
     * {@code ysm.first_person_mod_hide} (A-3), the "1.7.10 has a value source, it was just never wired up" group from
     * {@code .agent/audit-pack-facing-surface.md} §3 (A-5), the {@code query.any/all_animations_finished} pair (A-6),
     * and the names of mods that have no 1.7.10 counterpart (A-6, {@link #registerThirdPartyCompatPlaceholders}).
     *
     * <p>Why registering matters even for the names whose value is a constant: an unknown <em>variable</em> is not an
     * error in this legacy parser - {@code MolangParser#getVariable} creates it as a silent 0 - so a missing name and a
     * name that is legitimately false are indistinguishable to a pack author. Registering each one with its upstream
     * default makes "this name is answered" explicit, and the audit's §3 table is the list of names that were silently
     * reading 0 while upstream answered something real.
     *
     * <p>Two kinds of value live here, and the difference is the input they need:
     * <ul>
     * <li>values that belong to the <em>frame entity</em> (or its world) are bound once with a supplier reading
     * {@link MolangFrameContext}, which is published per frame for players and sub-entities alike - so these work on
     * the arrow/vehicle path too, which never reaches {@link #setParserValue};</li>
     * <li>values that belong to <em>which pass this frame is</em> (the animatable, the crosshair of the local client,
     * the controller the engine is processing) are only known while the frame's {@link AnimationEvent} is in hand, so
     * they are registered here and bound per frame in {@link #setYsmValues}.</li>
     * </ul>
     *
     * <p>Package-private rather than private, like {@code EntityQueryBindings#PUBLISHED_NAMES} is public: a unit test
     * registers this group onto a fresh parser and checks the names and defaults without the once-per-JVM
     * {@link #registerVariables} guard getting in the way.
     */
    static void registerPackFacingVariables(MolangParser parser) {
        // ---- A-3: ysm.first_person_mod_hide --------------------------------------------------------------
        // Upstream: playerVar("first_person_mod_hide", new FirstPersonModHideVariable()) (YSMBinding.java:152).
        // It was registered as a commented-out constant FALSE (the line above registerYsmVariables' end), which is
        // still "never true" for a pack. The value is bound per frame in setYsmValues; see the variable class.
        parser.register(new LazyVariable("ysm.first_person_mod_hide", MolangUtils.FALSE));

        // ---- A-5: values whose source is the frame entity's world or the local client's crosshair ---------
        // SOURCE(上游 1.20): YSMBinding.java:89 (weather), :90 (dimension_name), :101 (block_light),
        //   :102 (sky_light), :108 (is_open_air), :109 (eye_in_water), :126 (ladder_facing),
        //   :177-178 (hit_target_id / hit_target_type). Every method in YsmWorldQueries names its own substitute.
        parser.register(new LazyVariable("ysm.weather", 0));
        parser.setValue("ysm.weather", YsmWorldQueries::weather);
        parser.register(new LazyVariable("ysm.dimension_name", MolangStringPool.EMPTY_ID));
        parser.setValue("ysm.dimension_name", YsmWorldQueries::dimensionName);
        parser.register(new LazyVariable("ysm.block_light", 0));
        parser.setValue("ysm.block_light", YsmWorldQueries::blockLight);
        parser.register(new LazyVariable("ysm.sky_light", 0));
        parser.setValue("ysm.sky_light", YsmWorldQueries::skyLight);
        parser.register(new LazyVariable("ysm.is_open_air", MolangUtils.FALSE));
        parser.setValue("ysm.is_open_air", YsmWorldQueries::isOpenAir);
        parser.register(new LazyVariable("ysm.eye_in_water", MolangUtils.FALSE));
        parser.setValue("ysm.eye_in_water", YsmWorldQueries::eyeInWater);
        parser.register(new LazyVariable("ysm.ladder_facing", 0));
        parser.setValue("ysm.ladder_facing", YsmWorldQueries::ladderFacing);
        parser.register(new LazyVariable("ysm.hit_target_id", MolangStringPool.EMPTY_ID));
        parser.setValue("ysm.hit_target_id", YsmWorldQueries::hitTargetId);
        parser.register(new LazyVariable("ysm.hit_target_type", MolangStringPool.EMPTY_ID));
        parser.setValue("ysm.hit_target_type", YsmWorldQueries::hitTargetType);

        // ---- A-5: values whose source is the frame entity itself ------------------------------------------
        // SOURCE(上游 1.20): YSMBinding.java:111 (air_supply), :149 (attack_time), :158 (attack_damage),
        //   :161 (movement_speed), :162 (knockback_resistance), :135-137 (xxa / yya / zza),
        //   :97 (input_horizontal), :94 (ground_speed2), :92 (time_delta), :129-130 (entity_type / is_player).
        //   See YsmEntityQueries.
        parser.register(new LazyVariable("ysm.air_supply", 0));
        parser.setValue("ysm.air_supply", YsmEntityQueries::airSupply);
        parser.register(new LazyVariable("ysm.attack_time", 0));
        parser.setValue("ysm.attack_time", YsmEntityQueries::attackTime);
        // ysm.is_player / ysm.entity_type: upstream's livingEntityVar pair from the same getEntityType() helper
        // (YSMBinding.java:129-131). `is_player` is the one a pack branches on ("only for players"), and it used to
        // read a silent 0, i.e. every such branch took its non-player side.
        parser.register(new LazyVariable("ysm.is_player", MolangUtils.FALSE));
        parser.setValue("ysm.is_player", YsmEntityQueries::isPlayer);
        parser.register(new LazyVariable("ysm.entity_type", MolangStringPool.EMPTY_ID));
        parser.setValue("ysm.entity_type", YsmEntityQueries::entityType);
        parser.register(new LazyVariable("ysm.attack_damage", 0));
        parser.setValue("ysm.attack_damage", YsmEntityQueries::attackDamage);
        parser.register(new LazyVariable("ysm.movement_speed", 0));
        parser.setValue("ysm.movement_speed", YsmEntityQueries::movementSpeed);
        parser.register(new LazyVariable("ysm.knockback_resistance", 0));
        parser.setValue("ysm.knockback_resistance", YsmEntityQueries::knockbackResistance);
        parser.register(new LazyVariable("ysm.xxa", 0));
        parser.setValue("ysm.xxa", YsmEntityQueries::xxa);
        parser.register(new LazyVariable("ysm.yya", 0));
        parser.setValue("ysm.yya", YsmEntityQueries::yya);
        parser.register(new LazyVariable("ysm.zza", 0));
        parser.setValue("ysm.zza", YsmEntityQueries::zza);
        parser.register(new LazyVariable("ysm.input_horizontal", 0));
        parser.setValue("ysm.input_horizontal", YsmEntityQueries::inputHorizontal);
        parser.register(new LazyVariable("ysm.ground_speed2", 0));
        parser.setValue("ysm.ground_speed2", YsmEntityQueries::groundSpeed2);
        // Upstream has this name as well as query.delta_time (YSMBinding.java:92 vs QueryBinding.java:63, the same
        // expression); this port had only the latter (written by the engine per animation). Bound to that store
        // rather than to a second frame clock - see YsmEntityQueries#timeDelta.
        parser.register(new LazyVariable("ysm.time_delta", 1.0D / 20.0D));
        parser.setValue("ysm.time_delta", YsmEntityQueries::timeDelta);

        // ---- A-5: values that belong to which render pass this frame is (bound per frame in setYsmValues) -----
        // SOURCE(上游 1.20): YSMBinding.java:151 (texture_name), :99 (rendering_in_paperdoll),
        //   :100 (rendering_in_inventory). See FrameRenderQueries for the pass rules and the one residual.
        parser.register(new LazyVariable("ysm.texture_name", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("ysm.rendering_in_inventory", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.rendering_in_paperdoll", MolangUtils.FALSE));

        // ---- A-6: the two animation-completion queries on the keyframe path -------------------------------
        // Upstream binds them at QueryBinding.java:55-56 (`var`, from the controller context). The controller path
        // here already answers them (OpenYsmControllerExpressionEvaluator#finishedAnimations), but nothing registered
        // them for an animation file, so the same name was real in a controller condition and a silent 0 in a
        // keyframe. The value is bound per frame in setYsmValues.
        parser.register(new LazyVariable("query.any_animation_finished", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.all_animations_finished", MolangUtils.FALSE));

        // ---- A-6: the names of mods that have no 1.7.10 counterpart ---------------------------------------
        registerThirdPartyCompatPlaceholders(parser);
    }

    /**
     * A-6: the 40 names belonging to mods that are not installed (24 {@code ctrl.*} and 16 {@code tlm.*}), registered
     * as the constants upstream registers when the mod is absent.
     *
     * <p><b>Why register names for mods that cannot be installed here.</b> Upstream does it for exactly that reason:
     * {@code CarryOnCompat#addEmptyBinding} carries the comment "没有安装此模组时，这些 molang 应该存在，否则会报错"
     * ({@code client/compat/carryon/CarryOnCompat.java:49-54}) and {@code TLMBinding}'s class comment says the same
     * for the maid mod's names ("为了兼容女仆新增的 molang，即使女仆模组未安装，也应该存在，否则动画会报错",
     * {@code client/animation/molang/TLMBinding.java:7-9}). A pack writes the condition once and ships it; a client
     * without the mod must still evaluate the rest of that expression. Some of these names are read by this port's own
     * bundled assets - {@code builtin/default/animations/im.animation.json} reads {@code ctrl.im_delta} and
     * {@code ctrl.im_pitch} in keyframe expressions - so "no 1.7.10 mod has them" does not mean "nothing reads them".
     *
     * <p>Types and defaults are upstream's, name by name, from the {@code addEmptyBinding} bodies: the type
     * ({@code var} / {@code entityVar} / {@code livingEntityVar} / {@code playerVar} / {@code clientPlayerVar}) is
     * recorded because it is part of the contract (upstream answers the registered default when the context entity is
     * not of that type), but this legacy parser has a single flat variable table, so what is registered here is the
     * default value itself. Booleans are {@code MolangUtils.FALSE}/{@code TRUE}, the numbers are upstream's own
     * literals, and the string defaults are {@code StringUtils.EMPTY}, whose pooled id is
     * {@link MolangStringPool#EMPTY_ID} - the same 0 a string comparison against {@code ''} lands on.
     *
     * <p>No value source is invented for any of them: a pack reading one gets upstream's "the mod is not there"
     * answer, and there is nothing in 1.7.10 that could make it anything else.
     */
    private static void registerThirdPartyCompatPlaceholders(MolangParser parser) {
        // ---- TACZ (TACZCompat.java:42-50, all livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.tac_hold_gun", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.tac_gun_type", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("ctrl.tac_gun_id", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("ctrl.tac_is_fire", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.tac_is_aim", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.tac_is_reload", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.tac_is_melee", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.tac_is_draw", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.tac_fire_mode", MolangStringPool.EMPTY_ID));

        // ---- Immersive Melodies (ImmersiveMelodiesCompat.java:33-37, all livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.im_pitch", 0));
        parser.register(new LazyVariable("ctrl.im_volume", 0));
        parser.register(new LazyVariable("ctrl.im_current", 0));
        parser.register(new LazyVariable("ctrl.im_delta", 0));
        parser.register(new LazyVariable("ctrl.im_time", 0));

        // ---- Carry On (CarryOnCompat.java:53-54, livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.carryon_type", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("ctrl.carryon_is_princess", MolangUtils.FALSE));

        // ---- SWEM (SwemCompat.java:41-42, livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.swem_is_ride", MolangUtils.FALSE));
        parser.register(new LazyVariable("ctrl.swem_state", MolangStringPool.EMPTY_ID));

        // ---- Create (CreateCompat.java:34-36, playerVar; the same default on both branches) ----
        parser.register(new LazyVariable("ctrl.create_hanging_skyhook", MolangUtils.FALSE));
        // ---- Sophisticated Backpacks (SophisticatedCompat.java:72, livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.has_sophisticated_backpack", MolangUtils.FALSE));
        // ---- Iron's Spells 'n Spellbooks (IronsSpellBooksCompat.java:44, clientPlayerVar) ----
        parser.register(new LazyVariable("ctrl.iss_animation", MolangStringPool.EMPTY_ID));
        // ---- ParCool (ParCoolCompat.java:89, livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.parcool_state", MolangStringPool.EMPTY_ID));
        // ---- SlashBlade (SlashBladeCompat.java:80, livingEntityVar) ----
        parser.register(new LazyVariable("ctrl.slashblade_animation", MolangStringPool.EMPTY_ID));
        // ---- Better Combat (BetterCombatCompat.java:34, clientPlayerVar) ----
        parser.register(new LazyVariable("ctrl.bcombat_attack_animation", MolangStringPool.EMPTY_ID));

        // ---- Touhou Little Maid (TlmClientCompat.java:118-133, all livingEntityVar) ----
        parser.register(new LazyVariable("tlm.is_begging", MolangUtils.FALSE));
        parser.register(new LazyVariable("tlm.is_sitting", MolangUtils.FALSE));
        parser.register(new LazyVariable("tlm.has_backpack", MolangUtils.FALSE));
        parser.register(new LazyVariable("tlm.favorability_point", 0));
        parser.register(new LazyVariable("tlm.favorability_level", 0));
        parser.register(new LazyVariable("tlm.task_id", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("tlm.schedule", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("tlm.activity", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("tlm.gomoku_win_count", 0));
        // Upstream's odd one out: the rank default is 1, not 0 (TlmClientCompat.java:127).
        parser.register(new LazyVariable("tlm.gomoku_rank", 1));
        parser.register(new LazyVariable("tlm.game_statue", MolangStringPool.EMPTY_ID));
        parser.register(new LazyVariable("tlm.backpack_type", MolangStringPool.EMPTY_ID));
        // is_entity is *true* by default (TlmClientCompat.java:130): a name that is not a maid is an ordinary entity.
        parser.register(new LazyVariable("tlm.is_entity", MolangUtils.TRUE));
        parser.register(new LazyVariable("tlm.is_statue", MolangUtils.FALSE));
        parser.register(new LazyVariable("tlm.is_garage_kit", MolangUtils.FALSE));
        parser.register(new LazyVariable("tlm.show_item", MolangStringPool.EMPTY_ID));
    }

    /**
     * 注册引擎的反向控制钩子：把 YSMU 特有的 ctrl.* / query.* / ysm.* Molang 函数表交给
     * {@code MolangParser.ysmFunctionRegistrar}（反向控制，引擎侧不引用 mod 类）。
     * <p>
     * 只调用一次即可（重复调用只是幂等赋同一个方法引用），运行时只读；必须在任何模型/动画加载前执行
     * （ClientProxy.init 在 FML init 阶段，早于模型加载）：registrar 是在 {@code MolangParser} 的
     * <b>构造器</b>里被调用的，所以设好之后，引擎自己 new 的 parser、脚本运行时的 parser、以及测试里的
     * {@code new MolangParser()} 都会继承同一张表 —— 而不是只有 {@code GeckoLibCache} 那个共享 parser 有。
     * <p>
     * Register the reverse-control hook the engine exposes. The registrar runs from the MolangParser
     * constructor, so this one assignment is what makes <em>every</em> new parser inherit YSMU's function
     * table; putting the table on the shared parser alone ({@link #registerVariables()}) would leave the
     * engine's own parsers - and the ported script runtime - without those names. Idempotent (it reassigns
     * the same method reference) and read-only at runtime, and it must run before any model or animation is
     * loaded, which {@code ClientProxy#init} guarantees.
     */
    public static void registerMolangHooks() {
        MolangParser.ysmFunctionRegistrar = AnimationRegister::registerFunctions;
        // 箭头表达式（`a -> b`）的宿主：上游的 `->` 是"把右边放进左边指出的上下文里求值"
        // （ExpressionEvaluatorImpl.java:58-69；`projectile_owner` 就是一个 context），本引擎的值只有 double，
        // 所以由宿主把左侧解释成上下文句柄、在右侧求值期间换掉 subject（见 MolangStructContextAccess）。
        // 与上面的函数表同理，必须是静态钩子：引擎里任何一处 `new MolangParser()` 都要看到它。
        com.eliotlash.mclib.math.MathBuilder.structAccessHost =
            com.fox.ysmu.client.animation.molang.MolangStructContextAccess.INSTANCE;
    }

    /**
     * YSMU Molang 函数表 —— <b>唯一一份</b>（原 vendored doCoreRemaps() 里的那批已迁移至此）。
     * <p>
     * 有两个应用点，都指向这个方法：{@link #registerVariables()} 把它装到 {@code GeckoLibCache} 的共享
     * parser 上，{@link #registerMolangHooks()} 把它装到 registrar 上，于是每个新建的 MolangParser 也都
     * 继承同一张表。不要再把这张表内联到任何地方。
     */
    private static void registerFunctions(java.util.Map<String, Class<? extends com.eliotlash.mclib.math.functions.Function>> functions) {
        // YSMU 特有 Molang 函数注册（registrar 让每个新构造的 MolangParser 都执行一次）。
        // ctrl.* / query.* / ysm.* 说明见原 vendored doCoreRemaps()（已迁移至此）。
        functions.put("query.position_delta", QueryPositionDeltaFunction.class);
        functions.put("query.position", QueryPositionFunction.class);
        // query.relative_block_has_any_tag：只支持能原生回答的标签（minecraft:replaceable），
        // 其余返回 false —— 1.7.10 没有数据驱动的方块标签。
        functions.put("query.relative_block_has_any_tag", QueryBlockTagFunction.class);
        // query.is_item_name_any：按槽位取物品注册名匹配（物品标签类查询仍不支持）。
        functions.put("query.is_item_name_any", QueryItemNameAnyFunction.class);
        // query.max_durability / query.remaining_durability(slotType)：YSM-wiki molang/ref 2.2.1。
        functions.put("query.max_durability", QueryDurabilityFunction.class);
        functions.put("query.remaining_durability", QueryDurabilityFunction.class);
        // query.equipped_item_{any_tag,all_tags}(slotType, tag...)：1.7.10 没有数据驱动的物品
        // 标签，只回答能原生回答的（物品类型 + 矿物词典），其余 false。
        // 必须注册：未注册函数会让整条关键帧表达式解析失败、整个动画被丢弃。
        functions.put("query.equipped_item_any_tag", QueryItemTagFunction.class);
        functions.put("query.equipped_item_all_tags", QueryItemTagFunction.class);
        // query.debug_output(...)：调试输出（YSM-wiki molang/ref，1.2.0）。函数名必须注册：解析期按字面名
        // 查表，查不到会抛 Function 'q.debug_output' couldn't be found!，整条关键帧表达式失效
        // （MolangQueryAbbreviationTest 覆盖）。未开动画调试模式时 MolangDebugOutput 静默丢弃。
        functions.put("query.debug_output", QueryDebugOutputFunction.class);
        // ysm.dump_equipped_item(slotType) / ysm.dump_relative_block(x,y,z)：调试转储，恒返回 0，只在动画调试
        // 模式下输出。另三个 dump_* 由 wiki/上游写成**裸变量**，注册在 registerYsmVariables
        // （YsmDebugDumpFunction.registerDumpVariables）。五个名字都必须存在，否则按 wiki 写的调用是一条
        // 解析失败（函数名）或静默 0（变量名）。
        // SOURCE(上游 1.20): YSMBinding.java:67-71 `function("dump_equipped_item"/"dump_relative_block", …)`.
        functions.put("ysm.dump_equipped_item", YsmDebugDumpFunction.class);
        functions.put("ysm.dump_relative_block", YsmDebugDumpFunction.class);
        // ysm.perlin_noise：3D 柏林噪声（返回 [0,1]），自实现。
        functions.put("ysm.perlin_noise", PerlinNoiseFunction.class);
        // ysm.keyboard(键码...)：关键帧/时间轴 Molang 里的按键查询。
        // 以前和 ctrl.* 共用恒返回 0 的 CtrlHoldFunction，导致按键驱动的模型收不到输入。
        functions.put("ysm.keyboard", com.fox.ysmu.client.animation.molang.YsmKeyboardFunction.class);
        // ysm.mouse(按钮...)：鼠标按钮查询（wiki 2.5.0），此前完全没有实现。
        functions.put("ysm.mouse", com.fox.ysmu.client.animation.molang.YsmMouseFunction.class);
        // ysm.particle / particle / abs_particle：OpenYSM 粒子 Molang 函数。
        // ParticleFunction 通过 MolangStringPool 还原字符串参数（粒子 id），
        // 实体上下文由 ParticleEffectUtil.setCurrentEntity 每帧写入。
        functions.put("ysm.particle", ParticleFunction.class);
        // ysm.abs_particle：与 ysm.particle 成对。漏注册时整条关键帧表达式解析失败、
        // 整个 animation 被丢弃（与 query.equipped_item_*_tag 同一条约定），
        // 所以四个名字必须都在。ParticleFunction 按注册名里的 "abs_" 判定绝对模式。
        functions.put("ysm.abs_particle", ParticleFunction.class);
        functions.put("particle", ParticleFunction.class);
        functions.put("abs_particle", ParticleFunction.class);
        // ysm.bone_pivot_abs：骨骼绝对枢轴（模型单位），沿父链应用完整变换。
        // .x/.y/.z 后缀由 MolangParser.rewriteVectorFunction 重写为 _x/_y/_z 注册名。
        functions.put("ysm.bone_pivot_abs_x", BonePivotAbsFunction.class);
        functions.put("ysm.bone_pivot_abs_y", BonePivotAbsFunction.class);
        functions.put("ysm.bone_pivot_abs_z", BonePivotAbsFunction.class);
        // 无后缀的向量形式也必须注册：上面的重写只覆盖"调用的右括号紧跟着 ).x"这一种位置，
        // 而模型会把它当普通数值用（`math.max(ysm.bone_pivot_abs('Root'),ysm.bone_pivot_abs('AllBody'))`，
        // 见 tmp/LaPluma2.5.3/animations/main.animation.json 的 `v.bv=…` 指令）。上游在该位置返回结构体
        // （BoneParamFunction.java:19-31 → Vec3fStruct.java:11-23），本解析器只有 double，所以这里直接注册
        // 裸名，由 axis() 在未命名轴时取 x；引擎侧的 ysm.bone_rot/bone_pos/bone_scale 同理，注册在
        // MolangParser.doCoreRemaps（引擎自己的表）。
        functions.put("ysm.bone_pivot_abs", BonePivotAbsFunction.class);
        // A-16: upstream YSMBinding.java:190 `function("sync", new Sync())` - a pack's way to hand values to every
        // client that can see the model; the echo runs the model's `@sync` script.
        functions.put("ysm.sync", YsmSyncFunction.class);
        functions.put("ysm.relative_block_name", YsmRelativeBlockNameFunction.class);
        functions.put("ysm.relative_block_name_any", YsmRelativeBlockNameFunction.class);
        // ysm.equipped_enchantment_level：返回指定槽位物品上给定附魔的等级之和。
        functions.put("ysm.equipped_enchantment_level", EquippedEnchantmentLevelFunction.class);
        // ysm.effect_level：返回渲染实体身上给定药水效果的等级之和（1 级 = 1）。
        functions.put("ysm.effect_level", YsmEffectLevelFunction.class);
        // ysm.mod_version(modid)：返回指定模组的版本。上游返回的是**字符串**，本引擎只有 double，所以按本仓
        // 已有的字符串机制返回 MolangStringPool 池化 id —— 与 ysm.relative_block_name 同一条路
        // （YsmRelativeBlockNameFunction.java:50），包里 `ysm.mod_version('ysm')=='2.6.5'` 两侧因此落在同一个 id。
        // 参考分支注册的是恒 0 桩（.../perf-previewUI/.../AnimationRegister.java:160-161），
        // 理由是"1.7.10 模组版本是字符串、无统一数字语义" —— 那正是池化 id 要解决的问题，不是绕过它的理由。
        // SOURCE(上游 1.20): YSMBinding.java:73 `function("mod_version", new ModVersion())`,
        //   ModVersion.java:14-28（返回 Maven 版本字符串，validateArgumentSize == 1）。
        functions.put("ysm.mod_version", YsmModVersionFunction.class);
        // A pack's own sound instructions. Upstream registers all three in YSMBinding.java:185-187; without them a
        // pack's `sound_effects` timeline entry is a parse failure, not a silent no-op.
        functions.put("ysm.play_sound", YsmSoundFunction.Play.class);
        functions.put("ysm.stop_sound", YsmSoundFunction.Stop.class);
        functions.put("ysm.stop_all_sounds", YsmSoundFunction.StopAll.class);
        // ysm.has_any_curios(槽位, id...)：Curios 在 1.7.10 上用前身 Baubles（GTNH 是
        // Baubles-Expanded）实现；未安装时恒 false，槽位没有对应物时也会提示一次。
        functions.put("ysm.has_any_curios", QueryHasAnyCuriosFunction.class);
        functions.put("ctrl.hold", CtrlHoldFunction.class);
        // ctrl.use / ctrl.swing：keyframe/时间轴/.molang 脚本里的真实现（控制器条件路径
        // OpenYsmControllerExpressionEvaluator.functionValue 有同样的 hand/use/swing 分支）。
        // ctrl.hold 仍走上面的 CtrlHoldFunction（A-06③：两条路径都汇到 InnerClassify）。
        functions.put("ctrl.use", CtrlItemFunction.class);
        functions.put("ctrl.swing", CtrlItemFunction.class);
        // ctrl.armor(slot, matcher)：护甲槽匹配，只认 $物品ID 与 empty（与控制器路径同一规则）。
        functions.put("ctrl.armor", CtrlArmorFunction.class);
        // ctrl.ride(type, matcher)：wiki 用法 `ctrl.ride('vehicle', '$minecraft:pig')` /
        // `ctrl.ride('passenger', '$minecraft:pig')`。此前只有控制器条件路径有（且被简化成"是否骑乘"），
        // 关键帧/时间轴查的是这张表，表里没有这个名字 —— 于是 ctrl.ride(...) 在关键帧里是解析失败、整条通道被丢弃。
        // 参考分支注册的是恒 0 的 CtrlHoldFunction 桩（恒 0 桩，不能照抄）；这里按上游的真机制：
        // SOURCE(上游 1.20): CtrlBinding.java:82 `function("ride", RideCheck.rideCheck())`,
        //   functions/RideCheck.java:28-72（type = vehicle/passenger，matcher = $实体ID / #实体标签）。
        // 1.7.10 没有实体标签管理器，所以只有 $ 那一半能回答（见 CtrlRideFunction 的类注释）。
        functions.put("ctrl.ride", CtrlRideFunction.class);
        // A-4: the three per-bone render functions upstream registers under the `ysm.` prefix
        // (YSMBinding.java:83-85 `function("bone_color"/"bone_transparency"/"bone_glow", …)`, mounted under `ysm` by
        // CustomMolangParser.java:47). The port had them only under their bare names (MolangParser.doCoreRemaps), and
        // an unknown *function* is not a silent 0 like an unknown variable: the whole keyframe expression fails to
        // parse and the channel is dropped. Both spellings stay: the bare name is this port's existing extension, and
        // the prefixed one is what a pack written for upstream calls.
        functions.put("ysm.bone_color", BoneColor.class);
        functions.put("ysm.bone_transparency", BoneTransparency.class);
        functions.put("ysm.bone_glow", BoneGlow.class);
        // A-4: query.rotation_to_camera(axis) - upstream QueryBinding.java:47, body Rot2Camera.java:9-27.
        functions.put("query.rotation_to_camera", Rot2CameraFunction.class);
        // A-4: ysm.defer(name, args...) - upstream YSMBinding.java:191, body functions/Defer.java:8-21. See
        // YsmDeferFunction for why the port's implementation is a no-op rather than a new deferral mechanism.
        functions.put("ysm.defer", YsmDeferFunction.class);
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
        // YSM-wiki: molang/ref 只有 head_x/head_y，没有 head_z（1.7.10 也没有实体 roll）。
        // YSMU 扩展：用**相机 roll** 当这个值 —— 本机玩家的头部朝向与镜头一致，而原版从不写
        // EntityRenderer.camRoll（恒 0，仅相机类 mod 会写），所以无相机 mod 时与官方行为一致。
        parser.register(new LazyVariable("query.head_z_rotation", 0));
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
        parser.register(new LazyVariable("query.is_blocking", MolangUtils.FALSE));
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

        // G: upstream `entityVar("person_view", PersonView::getPersonView)` (client/animation/molang/YSMBinding.java:98).
        // The value is the modern CameraType ordinal, and 1.7.10's gameSettings.thirdPersonView shares that numbering
        // (0 = first person, 1 = third person back, 2 = third person front - the constants on PersonView say so).
        // Every frame that is not the local player's own body drawn in the world answers THIRD_PERSON_FRONT, which is
        // upstream's fallback too; see personView() for the two conditions behind that.
        parser.register(new LazyVariable("ysm.person_view", PersonView.THIRD_PERSON_FRONT));

        // Projectile (sub-entity) values, published per frame by the projectile renderer. They are registered here
        // rather than only written at render time because an animation file is parsed once, when its model is
        // installed: a name that does not exist yet at that moment is not a variable the keyframe expression can
        // resolve, and every projectile animation that reads one would parse to a constant.
        //   ysm.in_ground            - the arrow is stuck in the ground this frame
        //   ysm.on_ground_time       - ticks it has been stuck (YSM-wiki: unit is ticks, reset when moved)
        //   ysm.delta_movement_length- the distance it moved this frame, for speed-driven effects
        //   ysm.shoot_item_id        - pooled string id of the firing item, so a pack can pick its bow/crossbow model
        parser.register(new LazyVariable("ysm.in_ground", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.on_ground_time", 0));
        parser.register(new LazyVariable("ysm.delta_movement_length", 0));
        parser.register(new LazyVariable("ysm.shoot_item_id", 0));
        // H: upstream `projectileVar("projectile_owner", ctx -> ctx.createChild(ctx.entity().getOwner()))`
        // (client/animation/molang/YSMBinding.java:193) - the value is a *context*, so a pack reaches the shooter
        // through the `->` operator (`ysm.projectile_owner->query.health`). The legacy mclib parser this port
        // evaluates animation files with has no `->` at all (com/eliotlash/mclib/math/Operation.java registers only
        // `+ - * / && || < <= >= >` and `== !=`), so a chained form cannot even be tokenised, let alone resolved.
        // What is published instead is the flat question the port can answer from the wire: 1 when the projectile
        // was fired by a player, 0 otherwise. See ArrowProjectileRenderer's owner publish for what that rests on.
        parser.register(new LazyVariable("ysm.projectile_owner", MolangUtils.FALSE));

        // ysm.fps：注册前它在关键帧路径上会落进 newVariable() 的默认 0，而控制器路径返回 60，
        // 同一条表达式在两条路径读到不同的值（见 FpsQuery）。显式注册（与其他 ysm.* 常量变量
        // 同一约定）后立刻用 supplier 绑定，使其到处都是"客户端当前帧率"的实时值。
        parser.register(new LazyVariable("ysm.fps", com.fox.ysmu.client.animation.molang.FpsQuery.FALLBACK_FPS));
        parser.setValue("ysm.fps", com.fox.ysmu.client.animation.molang.FpsQuery::clientFps);

        // ysm.dump_mods / ysm.dump_effects / ysm.dump_biome：wiki 与上游把它们写成**裸变量**（无括号），
        // 读一次转储一次。它们不属于函数表，所以在这里注册（与 ysm.dump_equipped_item/dump_relative_block
        // 那两个带参函数共用 YsmDebugDumpFunction 的一份实现）。
        // SOURCE(上游 1.20): YSMBinding.java:69-71 `var("dump_mods", …)` / `entityVar("dump_effects", …)` /
        //   `entityVar("dump_biome", …)`.
        YsmDebugDumpFunction.registerDumpVariables(parser);

        // ysm.first_person_mod_hide used to be the commented-out constant below; it is registered (with a real value)
        // by registerPackFacingVariables together with the rest of this task's names.
    }

    public static void setParserValue(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player) {
        setParserValue(animationEvent, parser, data, player, true);
    }

    /**
     * @param humanoidHooks whether this animatable is one of upstream's humanoids, i.e. whether the two humanoid
     *                      script hooks ({@code player_init}, {@code player_update}) belong to this tick. Upstream
     *                      fires them from {@code CustomHumanoidEntity#preAnimationSetup} only
     *                      ({@code client/entity/CustomHumanoidEntity.java:195-218}) and its first-person arm entity
     *                      is a plain {@code CustomEntity}, so the arm's own per-frame tick publishes the same values
     *                      with {@code false} - the world's tick already fired them for this frame.
     */
    public static void setParserValue(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player, boolean humanoidHooks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        // A-05 / A-06③: publish the frame's subject before anything is evaluated. The engine opens its own MoLang
        // scope in CustomPlayerModel#setMolangQueries, but it exposes no accessor for the animatable, so
        // query.position_delta(axis) and the animation-file ctrl.hold read the entity from here. The model id
        // travels with it so ysm.play_sound resolves a sound name against the model that declared it.
        MolangFrameContext.begin(
            player,
            animationEvent.getAnimatable() == null ? null
                : animationEvent.getAnimatable()
                    .getMainModel());
        // 粒子 Molang 函数（particle/abs_particle）的实体上下文：mclib Function
        // 无状态，粒子函数在 get() 时刻从这里读取当前渲染帧的玩家。
        ParticleEffectUtil.setCurrentEntity(player);
        // Same reason, for the pack's own scripts: fn.<name> resolves against the model being rendered.
        PackUserFunctions.begin(
            animationEvent.getAnimatable() == null ? null : animationEvent.getAnimatable()
                .getMainModel());
        RemotePlayerAnimationQueries.QueryValues queryValues = RemotePlayerAnimationQueries
            .get(animationEvent, player, data.netHeadYaw);
        setEntityQueryValues(parser, data, player, mc, queryValues, animationEvent.getPartialTick());
        setStateQueryValues(parser, player, mc);
        setWorldQueryValues(parser, player, mc);
        setYsmValues(animationEvent, parser, data, player, queryValues, humanoidHooks);
    }

    private static void setEntityQueryValues(MolangParser parser, EntityModelData data, EntityPlayer player,
        Minecraft mc, RemotePlayerAnimationQueries.QueryValues queryValues, float partialTicks) {
        // The entity-parameterised half first: one definition per name, shared with the hand-written sub-entity
        // sampler (VehicleRenderer / ArrowProjectileRenderer), which is why it is not spelled out again here. The
        // player-only names and the two remote-player refinements below are written afterwards, so they win.
        EntityQueryBindings.apply(parser, player, partialTicks);
        parser.setValue("query.actor_count", () -> mc.theWorld.loadedEntityList.size());
        // query.distance_from_camera is owned by EntityQueryBindings for players and sub-entities alike.
        // 头朝向来自帧数据(AnimationEvent/EntityModelData)，不是实体状态：上游把它注册成 `var`（读 ctx.data()，
        // YSMBinding.java:87-88），所以只有玩家帧有值。子实体帧没有 EntityModelData，按 0 回答。
        parser.setValue(
            "query.head_x_rotation",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D : (double) queryValues.headYaw());
        parser.setValue(
            "query.head_y_rotation",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D : (double) data.headPitch);
        // 同上：相机 roll，且只对本机玩家（远程玩家的 roll 无同步字段）。详见 CameraRollQuery。
        parser.setValue("query.head_z_rotation", () -> MolangFrameContext.getPlayer() == null ? 0.0d
            : com.fox.ysmu.client.animation.molang.CameraRollQuery
                .isLocalPlayer(player) ? com.fox.ysmu.client.animation.molang.CameraRollQuery.interpolatedRoll() : 0.0d);
        // 本仓对远程玩家的平滑/夹取是**精化**，叠在 EntityQueryBindings 的通用原值之上（上游没有这层）；
        // 本机玩家的 groundSpeed 走 motionX/Z，与通用表的位置差分同值。
        parser.setValue("query.ground_speed", queryValues.groundSpeed());
        // 同 ground_speed：远程玩家的 yawSpeed 精化。
        parser.setValue("query.yaw_speed", queryValues.yawSpeed());
    }

    private static void setStateQueryValues(MolangParser parser, EntityPlayer player, Minecraft mc) {
        parser.setValue("query.is_first_person", () -> MolangUtils.booleanToFloat(mc.gameSettings.thirdPersonView == 0));
        // Player refinement of the shared binding: isPlayerJumping is an airborne predicate, NOT jump input.
        // Upstream QueryBinding.java:102 is playerVar with the same exclusions; non-player frames are reset by
        // EntityQueryBindings. Preserve the port's remote flying/onGround handling here.
        parser.setValue(
            "query.is_jumping",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D
                : MolangUtils.booleanToFloat(isPlayerJumping(player)));
        // 1.7.10 不同步远程玩家的 onGround，本仓的 isPlayerOnGround 对它做外推——这是叠在通用值之上的精化
        // （EntityQueryBindings 里 query.is_on_ground 是 entity.onGround），子实体取通用值，与上游一致。
        parser.setValue("query.is_on_ground", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player)));
        // 同上：精化（isPlayerOnGround 的远程外推）。
        parser.setValue("query.is_sneaking", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player) && player.isSneaking()));
    }

    private static void setWorldQueryValues(MolangParser parser, EntityPlayer player, Minecraft mc) {
        parser.setValue("query.moon_phase", () -> mc.theWorld.getMoonPhase());
        // A-1: `query.time_of_day` is the fraction of a day in [0,1) and `query.time_stamp` is the raw world time;
        // upstream binds exactly those two (geckolib3/core/molang/builtin/QueryBinding.java:61-62:
        // `normalizeTime(level.getDayTime())` and `level.getDayTime()`), and its normalizeTime is
        // `((float)(timestamp + 6000L) / 24000) % 1` - the phase that puts midnight at 0 and dawn at 0.25
        // (com/elfmcys/ysm/geckolib3/util/MolangUtils.java:30-32 in the upstream tree).
        //
        // The implementation called here is that one, and it was already vendored: the port carries two
        // `MolangUtils.normalizeTime` methods, and the legacy one this line used to call
        // (software/bernie/geckolib3/util/MolangUtils.java:8-10) is `timestamp / 24000` - no modulo and no phase, so
        // 1.7.10's ever-growing World#getWorldTime() made `query.time_of_day` a number like 41.7 instead of a
        // fraction of a day. That is a real defect against upstream, not a formatting difference: a pack asking
        // `query.time_of_day > 0.5` for "night" got an answer that is true for most of the day after day two, and the
        // controller path returned the raw timestamp for the same name (see the A-1 branch in
        // OpenYsmControllerExpressionEvaluator#queryValue, which now calls the same method).
        parser.setValue(
            "query.time_of_day",
            () -> software.bernie.geckolib3.molang.util.MolangUtils.normalizeTime(mc.theWorld.getWorldTime()));
        parser.setValue("query.time_stamp", () -> mc.theWorld.getWorldTime());
    }

    private static void setYsmValues(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player, RemotePlayerAnimationQueries.QueryValues queryValues,
        boolean humanoidHooks) {
        // 头朝向/眼闭是帧数据（AnimationEvent 的动画 tick + 玩家 UUID），上游注册成 `var` 读 ctx.data()
        // (YSMBinding.java:87-88、:124)，所以只有玩家帧有值；子实体帧按"不适用"返回 0。
        parser.setValue(
            "ysm.head_yaw",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D : (double) queryValues.headYaw());
        parser.setValue(
            "ysm.head_pitch",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D : (double) data.headPitch);
        // G: the per-frame half of ysm.person_view; see the registration in registerYsmVariables for the upstream
        // source and the numbering. 相机状态，与实体类型无关（上游 entityVar 读的也是相机，YSMBinding.java:98），
        // 所以子实体帧照旧取值，不设守卫。
        parser.setValue("ysm.person_view", () -> personView(player));
        // A-16: the two per-frame hooks upstream fires from preAnimationSetup
        // (client/entity/CustomHumanoidEntity.java:205-218). `player_init` runs once after a model loads;
        // PackUserFunctions#clear (called when the model caches are rebuilt) re-arms it. `player_update` runs every
        // frame and is handed "did this frame actually tick" as args[0], so a script guarding on it does not do its
        // work twice for a frame this port renders twice.
        //
        // Only the humanoid entities fire them. The first-person arm publishes the same values for its own tick
        // (which is what its clips read) but is not a humanoid, so it passes humanoidHooks=false and the world's tick
        // remains the one that runs a pack's `@player_update` script for this frame.
        if (humanoidHooks) {
            String hookPlayerKey = player.getUniqueID()
                .toString();
            ResourceLocation hookModelId = animationEvent.getAnimatable()
                .getMainModel();
            boolean ui = com.fox.ysmu.client.renderer.CustomPlayerRenderer.isPreviewRendering();
            Object soundOwner = ui ? animationEvent.getAnimatable() : player;
            try (AutoCloseable soundScope = com.fox.ysmu.client.audio.MolangSoundContext.push(
                soundOwner, ui ? null : player, hookModelId, null, ui, true)) {
                if (PackUserFunctions.consumeInitPending(hookPlayerKey, hookModelId)) {
                    PackUserFunctions.fireHook("player_init", hookModelId);
                }
                PackUserFunctions.fireHook(
                    "player_update",
                    hookModelId,
                    PackUserFunctions.frameTicked(hookPlayerKey, animationEvent.getAnimationTick()) ? 1.0D : 0.0D);
            } catch (Exception failure) {
                com.fox.ysmu.ysmu.LOG.warn("Failed to execute player MoLang hook in its sound scope", failure);
            }
        }

        // ysm.has_mainhand / has_offhand / has_helmet / has_chest_plate / has_leggings / has_boots / has_elytra and
        // ysm.elytra_rot_x/y/z are now bound by EntityQueryBindings, guarded the way upstream registers them
        // (livingEntityVar / clientPlayerVar, YSMBinding.java:114-120,173-175): a non-player frame gets the
        // registered default instead of the local player's equipment. 1.7.10 only has an elytra through Et Futurum
        // Requiem, which is where the value still comes from; its wing angles are advanced by EtFuturumElytraLayer,
        // the one caller of ETFR's wing renderer (ETFR updates them from inside it and nothing else may, or the 0.1
        // lerp toward the target angle would run more than once per pass).
        // A-15: ysm.input_vertical is bound by EntityQueryBindings now (upstream registers it as entityVar,
        // YSMBinding.java:96, and the port's implementation is derived from the entity's movement delta and body yaw,
        // so it is entity-parameterised and a vehicle gets its own value). History kept: the name had no producer at
        // all, and the parser answers an unknown variable with a silent 0 - so a script comparing
        // `ysm.input_vertical < 0.1` compared against 0 and was therefore *always true*;
        // wine_fox/18_wedding's backwards-walk script (functions/简单倒走动画@player_ctrl_main.molang) played its clip
        // on every walk instead of only when walking backwards.
        // 1.7.10 has no riptide and Et Futurum Requiem does not add one - its whole tree has no trident - so there is
        // no source to read: upstream binds this to `entity.isAutoSpinAttack()` (YSMBinding.java:121). Assigned
        // explicitly so the constant reads as a decision rather than as a name someone forgot to wire up.
        parser.setValue("ysm.is_riptide", MolangUtils.FALSE);
        // 同 ysm.head_yaw：帧数据（动画 tick），上游注册成 livingEntityVar 读 ctx.animationEvent()
        // (YSMBinding.java:124)，子实体帧按"不适用"返回 0。
        parser.setValue(
            "ysm.is_close_eyes",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D
                : getEyeCloseState(animationEvent, player));
        // 同 query.is_sneaking：isPlayerOnGround 的远程外推是精化，通用值在 EntityQueryBindings 里。
        parser.setValue("ysm.is_sneak", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player) && player.isSneaking()));
        // ysm.is_sleep / on_ladder / is_fishing / swinging / swing_time / swinging_arm /
        // mainhand_charged_crossbow / offhand_charged_crossbow / armor_value / hurt_time / food_level are bound by
        // EntityQueryBindings now, each with upstream's guard (livingEntityVar / playerVar / clientPlayerVar):
        // a boat answers false/0 for them instead of the local player's value, and a living non-player answers the
        // living-level ones (food_level is 20, matching YSMBinding.java:342-350).

        // A-3 / A-5 / A-6: the values whose input is this frame's own AnimationEvent - which animatable is being
        // drawn (the first-person arm, the selected texture, the screen-owned preview), which render pass that is,
        // and the controller the engine is currently processing. Nothing else carries that, so unlike the
        // MolangFrameContext-backed A-5 names (bound once at registration) these are rebound every frame. Their rules
        // and their upstream sources live in FirstPersonModHideVariable, FrameRenderQueries and finishedAnimation().
        parser.setValue("ysm.first_person_mod_hide", () -> FirstPersonModHideVariable.value(animationEvent));
        parser.setValue("ysm.texture_name", () -> FrameRenderQueries.textureName(animationEvent));
        parser.setValue("ysm.rendering_in_inventory", () -> FrameRenderQueries.renderingInInventory(animationEvent));
        parser.setValue("ysm.rendering_in_paperdoll", () -> FrameRenderQueries.renderingInPaperdoll(animationEvent));
        parser.setValue("query.any_animation_finished", () -> finishedAnimation(animationEvent));
        parser.setValue("query.all_animations_finished", () -> finishedAnimation(animationEvent));
    }

    /**
     * A-6: whether the controller the engine is processing right now has finished its animation - the value behind
     * {@code query.any_animation_finished} / {@code query.all_animations_finished} on the keyframe path.
     *
     * <p>Upstream binds both as {@code var}s read from the controller context
     * ({@code geckolib3/core/molang/builtin/QueryBinding.java:55-56}) and documents the two as the same answer, because
     * a controller plays a single animation at a time ("目前控制器只能同时播放单一动画，所以两个 molang 都是一样的结果",
     * {@code :54}); its coded controller sets both from the one {@code animationPlayer.currentAnimFinished()}
     * ({@code geckolib3/core/controller/CodedAnimationController.java:103-104}). This port has that one flag on the
     * engine's controller: {@code AnimationController#hasCurrentAnimationFinished()} ({@code AnimationController.java:370-371},
     * set at {@code :1292}/{@code :1301} when the clip passes its end).
     *
     * <p>Why the <em>event's</em> controller: {@code AnimationProcessor} sets it to each controller immediately before
     * that controller processes ({@code AnimationProcessor.java:196}), and keyframe {@code IValue}s are evaluated
     * inside that processing ({@code AnimationController#getAnimationPointAtTick:1593-1617}), so the field is the
     * controller whose animation file is being sampled. The event published here is the same object the processor
     * mutates - {@code AnimatedGeoModel#setLivingAnimations:80-89} hands its {@code predicate} to
     * {@code tickAnimation} - so a supplier closing over it reads the live field.
     *
     * <p><b>One gap, recorded rather than papered over:</b> a concurrent (Bedrock state machine) playback evaluates
     * its children's keyframes with a <em>child</em> event the engine creates per child
     * ({@code AnimationController#processConcurrent:1084-1091}), so during concurrent playback this reads the parent
     * controller's flag - which the parent does not set, because it delegates instead of running its own animation.
     * A faithful answer there needs the child's completion state, which the engine does not expose to the keyframe
     * evaluator; the controller path's own answer for the same name
     * ({@code OpenYsmControllerExpressionEvaluator#finishedAnimations}) is about the Bedrock runtime's states, not
     * about this controller.
     */
    private static double finishedAnimation(AnimationEvent<?> animationEvent) {
        AnimationController<?> controller = animationEvent == null ? null : animationEvent.getController();
        if (controller == null) {
            return MolangUtils.FALSE;
        }
        return MolangUtils.booleanToFloat(controller.hasCurrentAnimationFinished());
    }

    // hasCape / getEquipmentCount / getMaxUseDuration / getSlotValue moved to EntityQueryBindings together with the
    // bindings that were their only callers: the table needs them for the same names, and one definition is the rule.

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

    /**
     * A-2: whether the player this frame is drawn for is on the ground. 1.7.10 does not sync {@code onGround} for a
     * remote player, so the port extrapolates it through {@link RemotePlayerMotionStates} - the same refinement the
     * entity-query table documents.
     *
     * <p>Public because the controller-path evaluator asks the very same question
     * ({@code OpenYsmControllerExpressionEvaluator#isOnGround}) and a second copy of this rule is how
     * {@code ctrl.fly} / {@code query.is_jumping} drifted from the keyframe path's states in the first place. The null
     * guard is for the detached evaluation the evaluator also supports (a condition with no actor), which upstream's
     * typed bindings answer false for.
     */
    public static boolean isPlayerOnGround(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc == null) {
            return false;
        }
        // 本地玩家
        if (player == mc.thePlayer) {
            return player.onGround;
        } else {
            return RemotePlayerMotionStates.isOnGround(player);
        }
    }

    /**
     * A-2: whether the player is flying, in the sense upstream's {@code QueryBinding} means it - the flight ability,
     * or for a remote player the value the port extrapolates. <b>Elytra gliding is not flight here</b>, and that is
     * deliberate: upstream's own {@code isFlying} ({@code geckolib3/core/molang/builtin/QueryBinding.java:115-121},
     * used by {@code query.is_jumping} at {@code :102}) tests the flying ability only, and the glide has its own state
     * ({@code elytra_fly}, registered at {@code registerFlyingStates}) and its own query. Counting the glide as flight
     * was the controller path's divergence - see the note on the evaluator's delegation.
     */
    public static boolean isPlayerFlying(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc == null) {
            return false;
        }
        // 本地玩家
        if (player == mc.thePlayer) {
            return player.capabilities.isFlying;
        } else {
            return RemotePlayerMotionStates.isFlying(player);
        }
    }

    // A-15's moveInputVertical moved to EntityQueryBindings: its only caller was the ysm.input_vertical binding, and
    // the table needs it for the same name. It was already a pure function of the entity (position delta + the
    // interpolated body yaw, upstream MoveInputVariable.java:9-29), so parameterising it by the entity costs nothing
    // and a vehicle now reads its own movement direction instead of its rider's. A-5's sibling ysm.input_horizontal
    // lives there too, as the same rule with a sine instead of a cosine.

    /**
     * Whether the player is in the airborne phase that plays the model's {@code jump} clip.
     * <p>
     * Upstream is {@code !player.onGround() && !player.isInWater()}
     * ({@code tmp/YesSteveModel-dev-1.20 .../client/animation/AnimationRegister.java:36}): the <b>whole</b> airborne
     * phase, rising and falling alike. This port keeps that duration and adds two exclusions, each for a 1.7.10 reason:
     * <ul>
     *   <li>{@code isPlayerFlying} - {@code fly} is a {@code HIGH} state and {@code jump} is {@code NORMAL}, so a
     *       flying player already prefers {@code fly} whenever the model declares it; this exclusion only decides the
     *       fallback for a model that declares no {@code fly} clip (which would otherwise play the airborne clip while
     *       hovering).</li>
     *   <li>{@code isRiding} - the riding states live in this port's {@code player.vehicle} controller
     *       ({@code VehiclePredicate}), not in this table (B-04, see {@link #registerFlyingStates}), so a rider must
     *       not also match the main table's airborne clip.</li>
     * </ul>
     * <p>
     * <b>Removed: the local player's {@code motionYState(player, 0.0) != 0} condition.</b> That was not upstream's
     * rule and it had a visible failure mode: {@code motionY} passes through exactly 0 at the apex of a jump (and a
     * mod or a plugin may zero it), so for that tick the local player dropped out of {@code jump}, the state machine
     * fell through to {@code idle} - the only state left once {@code onGround} is false, since {@code run}/{@code walk}
     * need the ground - and the arms snapped from the jump pose's folded forearms (X = -80 empty-handed, see the
     * model's own {@code animations/main.animation.json}) back to neutral and out again. Remote players never had the
     * condition, so one player's jump looked different depending on who was watching it. The whole airborne phase is
     * now a single state for everyone, which is what upstream does.
     * <p>
     * A-2: the controller path's {@code query.is_jumping} / {@code ctrl.jump} now call this method instead of keeping
     * their own copy with the removed condition still in it, so the two paths cannot disagree again. See the
     * delegation note in {@code OpenYsmControllerExpressionEvaluator#isJumping}.
     */
    public static boolean isPlayerJumping(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        return isPlayerJumping(
            isPlayerFlying(player),
            player.isRiding(),
            isPlayerOnGround(player),
            player.isInWater());
    }

    /**
     * A-2: the rule itself, so the decision table is testable without an {@link EntityPlayer} (1.7.10's
     * {@code EntityPlayer} constructor needs a live world and cannot exist in a unit JVM). Every caller above passes
     * the frame's facts in, and no caller adds a term of its own: the two paths agree because there is one expression.
     */
    static boolean isPlayerJumping(boolean flying, boolean riding, boolean onGround, boolean inWater) {
        return !flying && !riding && !onGround && !inWater;
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

    /**
     * G: the value {@code ysm.person_view} reports for one frame.
     *
     * <p>
     * Upstream answers the camera mode only for the local player's own body while the world is being drawn, and
     * {@code CameraType.THIRD_PERSON_FRONT} for everything else - another player, and any frame that is not the world
     * pass ({@code PersonView#getPersonView}, client/animation/molang/YSMBinding.java:98). The port has the same two
     * inputs: the identity of the frame's player, and whether this pass is the world or a preview. The preview signal
     * is {@link CustomPlayerRenderer#isPreviewRendering()}, which the renderer already maintains for the GUI preview
     * grid and the HUD paper doll - both are the local player, and both are exactly "not the world" passes, which is
     * what upstream's {@code renderContext.level()} answers.
     *
     * <p>
     * The ordinal itself is read straight from {@code gameSettings.thirdPersonView}: 1.7.10 numbers the three modes
     * the way the modern enum does (0 = first person, 1 = third person back, 2 = third person front), which is what
     * {@link PersonView}'s constants record.
     */
    private static double personView(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player != null && player == mc.thePlayer && !CustomPlayerRenderer.isPreviewRendering()) {
            return mc.gameSettings.thirdPersonView;
        }
        return PersonView.THIRD_PERSON_FRONT;
    }
}
