package com.fox.ysmu.client.animation;

import java.util.function.BiPredicate;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.EtFuturumCompat;
import com.fox.ysmu.client.animation.molang.BonePivotAbsFunction;
import com.fox.ysmu.client.animation.molang.CameraDistanceQuery;
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
import com.fox.ysmu.client.animation.molang.YsmDebugDumpFunction;
import com.fox.ysmu.client.animation.molang.YsmEffectLevelFunction;
import com.fox.ysmu.client.animation.molang.YsmModVersionFunction;
import com.fox.ysmu.client.animation.molang.YsmSoundFunction;
import com.fox.ysmu.client.animation.molang.YsmSyncFunction;
import com.fox.ysmu.client.animation.molang.YsmRelativeBlockNameFunction;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
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

        // parser.register(new LazyVariable("ysm.first_person_mod_hide", MolangUtils.FALSE));
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
        // 相机到实体的距离：第三人称必须算上镜头后退（原实现在本机玩家上恒为 0，
        // 见 CameraDistanceQuery 的类注释）。相机是本机玩家的，所以这个名字只对玩家帧有意义：子实体帧
        // (载具/投射物)按"不适用"返回 0，而不是返回本机玩家到镜头的距离。上游对任意实体都会给出到相机的距离
        // (它读 context 的 entity)，本仓的 CameraDistanceQuery 是玩家形状的 —— 见 task-23 汇报里的遗留项。
        parser.setValue(
            "query.distance_from_camera",
            () -> MolangFrameContext.getPlayer() == null ? 0.0D : CameraDistanceQuery.forPlayer(player));
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
        // 跳跃是**输入**状态（本仓 isPlayerJumping 读移动输入），上游对非玩家实体没有等价输入源，
        // 所以子实体帧按"不适用"返回 0。
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
        parser.setValue("query.time_of_day", () -> MolangUtils.normalizeTime(mc.theWorld.getWorldTime()));
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
            if (PackUserFunctions.consumeInitPending(hookPlayerKey, hookModelId)) {
                PackUserFunctions.fireHook("player_init", hookModelId);
            }
            PackUserFunctions.fireHook(
                "player_update",
                hookModelId,
                PackUserFunctions.frameTicked(hookPlayerKey, animationEvent.getAnimationTick()) ? 1.0D : 0.0D);
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

    // A-15's moveInputVertical moved to EntityQueryBindings: its only caller was the ysm.input_vertical binding, and
    // the table needs it for the same name. It was already a pure function of the entity (position delta + the
    // interpolated body yaw, upstream MoveInputVariable.java:9-29), so parameterising it by the entity costs nothing
    // and a vehicle now reads its own movement direction instead of its rider's.

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
     */
    private static boolean isPlayerJumping(EntityPlayer player) {
        return !isPlayerFlying(player) && !player.isRiding() && !isPlayerOnGround(player) && !player.isInWater();
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
