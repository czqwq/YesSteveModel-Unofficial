package com.fox.ysmu.client.animation;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.condition.*;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.animation.molang.CtrlScriptBinding;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.animation.molang.PackFunctionScript;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.data.EntityClips;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class AnimationManager {

    private static AnimationManager MANAGER;
    private final Int2ObjectOpenHashMap<LinkedList<AnimationState>> data = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, Integer> swingProgressByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> useDurationByPlayer = new ConcurrentHashMap<>();
    /**
     * 格挡尾迹 tick 计数器（SOURCE {@code AnimationManager.java:89-96}，字段名与语义原样搬用）。
     * <p>
     * Battlegear2 的 shield flag ({@code battlegear2$isBlockingWithShield}) 只闪 1-2 tick，格挡标志
     * 一消失 {@link #predicateUse} 就会走"没在使用/格挡"那条分支把 {@code use_controller} 停掉，
     * 动画来不及播到盾牌格挡姿态。所以格挡期间每帧把这个计数器重置为 5，标志消失后由它再放行
     * 若干帧（返回 {@link PlayState#CONTINUE}，让当前姿态继续），归零那一帧才真正停。
     */
    private final Map<UUID, Integer> blockingTailTicks = new ConcurrentHashMap<>();

    public static AnimationManager getInstance() {
        if (MANAGER == null) {
            MANAGER = new AnimationManager();
        }
        return MANAGER;
    }

    /**
     * Idempotent "client disconnected / switched server" cleanup, the entry point `fix-core-ui`'s `CU-10` calls
     * (returned to `[animation]` by the phase 7 plan as `A-11`).
     *
     * <p>
     * Clears every per-player state this package keeps and never releases on its own:
     * <ul>
     * <li>{@code swingProgressByPlayer} / {@code useDurationByPlayer} / {@code blockingTailTicks}
     * (this class's three per-player tables, A-11)</li>
     * <li>{@link RemotePlayerAnimationQueries} (remote-player interpolation state)</li>
     * <li>{@link RemotePlayerMotionStates} (remote-player onGround/isFlying flags)</li>
     * </ul>
     * Idempotence: the body only empties concurrent maps
     * ({@code ConcurrentHashMap.clear()} and the two {@code RemotePlayer*}.clear() calls), it does not depend on
     * call order, and repeating it has no effect. When {@link #MANAGER} has not been created yet the three local
     * tables are simply skipped and the rest is still cleared.
     */
    public static void clearPlayerState() {
        AnimationManager manager = MANAGER;
        if (manager != null) {
            manager.swingProgressByPlayer.clear();
            manager.useDurationByPlayer.clear();
            manager.blockingTailTicks.clear();
        }
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playLoopAnimation(AnimationEvent<P> event, String animationName) {
        return playAnimation(event, animationName, ILoopType.EDefaultLoopTypes.LOOP);
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName,
        ILoopType loopType) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, loopType));
        return PlayState.CONTINUE;
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName));
        return PlayState.CONTINUE;
    }

    public void register(AnimationState state) {
        if (data.containsKey(state.getPriority())) {
            data.get(state.getPriority())
                .add(state);
        } else {
            LinkedList<AnimationState> states = Lists.newLinkedList();
            states.add(state);
            data.put(state.getPriority(), states);
        }
    }

    public PlayState predicateParallel(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        return playParallelIfPresent(event, animationName);
    }

    /**
     * 并行动画族（{@code pre_parallel0..7} / {@code parallel0..7}）这一帧的收尾：模型真的声明了
     * {@code animationName} 才交给引擎，否则 {@link PlayState#STOP}。
     *
     * <p>
     * 这一段就是 SOURCE 的收尾：{@code predicateParallel} 末尾先问
     * {@code animationExistsInFile(animId, animationName)}，命中才 {@code playLoopAnimation}，否则
     * {@code PlayState.STOP}（SOURCE {@code client/animation/AnimationManager.java:852-855}，判定在
     * {@code :740-746}），注释写明理由就是 "Skip if the animation doesn't exist in the model's file to avoid
     * GeckoLib's System.out.printf spam ("Could not load animation: ...")"（SOURCE {@code :822-823}）。
     * 本仓的等价判定是 {@link #hasAnimation}（先 {@code ensureAnimationFile} 安装懒加载的动画文件，再查表），
     * 所以这里复用它，没有新机制。
     *
     * <p>
     * 少了这一步的后果有两层，第二层才是真正的 bug：
     * <ol>
     * <li>引擎那条分支会打印 "Could not load animation: parallelN. Is it missing?"；</li>
     * <li>它在填 {@code animationQueue} 之前就 {@code return}，于是
     * {@code currentAnimationBuilder} 不更新、{@code currentAnimation} 不被清，下一帧同样的 builder
     * 又判定为"变了"、又走一遍同一分支 —— 每帧刷屏，控制器还停在上一次动画（或什么都没有）。</li>
     * </ol>
     * 8 个槽位是**按 animatable 固定注册**的（{@code CustomPlayerEntity#registerControllers} 的
     * {@code pre_parallel%d} / {@code parallel%d}，以及第一人称手臂的同名族），而模型可以随时切换，
     * 所以"这个模型有没有 parallelN"只能在运行时问。上游没有这个问题，因为它在构建模型资源集时只为
     * "模型声明且非空"的 {@code parallelN} 建控制器（{@code client/controller/ParallelControllerDiscovery.java:64-71}），
     * 永远不会向引擎要一个不存在的名字；固定池 + 运行时路由是本仓既有的分歧，存在性检查就是它对应的形状。
     *
     * <p>
     * 包内可见是为了让 JUnit 能钉住这个判定：{@code predicateParallel} 本身要一个活着的
     * {@code Minecraft}（{@link Minecraft#isGamePaused()}），普通测试 JVM 起不来。
     */
    static PlayState playParallelIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (animationName == null || animationName.isEmpty() || !hasAnimation(event, animationName)) {
            return PlayState.STOP;
        }
        return playLoopAnimation(event, animationName);
    }

    public PlayState predicateOpenYsmSlot(AnimationEvent<CustomPlayerEntity> event) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        // 动画控制脚本优先（pre_main / post_main / pre_hold / pre_swing / pre_use …）。
        PlayState scriptState = applyControlScript(event, controlSlotFor(event));
        if (scriptState != null) {
            return scriptState;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        return controllerState == null ? PlayState.STOP : controllerState;
    }

    public PlayState predicateCap(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            // GUI preview entity: upstream's CapPredicate plays the tile's preview channel (see
            // ModelPreviewAnimationState). Before this, nothing ever set the name and the tile stayed a frozen pose.
            return playPreviewChannel(event, animatable.getPreviewInfo().getPreview());
        }

        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isPlayAnimation()) {
            if (eep.consumeReplayRequest() && event.getController() != null) {
                // A-05: pressing the same emote key again must restart its clip. The engine's setAnimation is a no-op
                // while it already holds that builder and was not told to reload, so the request has to be turned into
                // a reload - which is exactly what upstream's CapPredicate does with the entity's dirty flag
                // (client/animation/predicate/CapPredicate.java:23-28: clearExtraAnimationDirty + indicateReload).
                event.getController()
                    .markNeedsReload();
            }
            return playAnimation(event, eep.getAnimation());
        }
        return PlayState.STOP;
    }

    /** The GUI preview's hover channel (upstream {@code HoverPredicate}). */
    public PlayState predicateHover(AnimationEvent<CustomPlayerEntity> event) {
        return playPreviewChannel(event, event.getAnimatable().getPreviewInfo().getHover());
    }

    /** The GUI preview's focus channel (upstream {@code FocusPredicate}). */
    public PlayState predicateFocus(AnimationEvent<CustomPlayerEntity> event) {
        return playPreviewChannel(event, event.getAnimatable().getPreviewInfo().getFocus());
    }

    /**
     * Plays one of the three preview channels, but only on a preview entity: a model in the world never gets its
     * preview channels applied, and a channel that the model does not define (or the {@code empty} sentinel) stops the
     * controller instead of asking the engine for a missing animation.
     */
    private static PlayState playPreviewChannel(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        CustomPlayerEntity animatable = event.getAnimatable();
        if (animatable.getPlayer() != null || animatable.getMainModel() == null) {
            return PlayState.STOP;
        }
        net.minecraft.util.ResourceLocation modelId = com.fox.ysmu.util.ModelIdUtil
            .getModelIdFromMainId(animatable.getMainModel());
        if (!com.fox.ysmu.client.gui.ModelPreviewRegistry.isPlayable(modelId, animationName)) {
            return PlayState.STOP;
        }
        return playLoopAnimation(event, animationName);
    }

    @NotNull
    public PlayState predicateMain(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            // Non-player animatables (NPCs and companions handed to YSM by another mod) have no player
            // state, so the player-typed state machine below can never match and the main controller would
            // stop outright - meaning such an entity never played walk/idle at all. Drive the same
            // locomotion states from the animatable's own entity instead.
            return predicateEntityLocomotion(event);
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            rememberMainState(player, "");
            return controllerState;
        }
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            if (!data.containsKey(i)) {
                continue;
            }
            LinkedList<AnimationState> states = data.get(i);
            for (AnimationState state : states) {
                if (state.getPredicate().test(player, event)) {
                    String animationName = state.getAnimationName();
                    if (!definesAnimation(event, animationName)) {
                        // The model does not define this state's animation. Handing it over anyway drives
                        // AnimationController#setAnimation into its "Could not load animation: ... Is it missing?"
                        // branch, which prints to stdout and leaves the controller with no fallback at all - so the
                        // model keeps whatever it was showing. For a model without `run` that is exactly "walking
                        // animates, sprinting does not". Fall through to the next matching state instead, which is
                        // what predicateEntityLocomotion has always done (a70479ad) and what playIfPresent does for
                        // the hold/swing controllers (D-A5).
                        warnMissingState(animationName, event);
                        continue;
                    }
                    ILoopType loopType = state.getLoopType();
                    rememberMainState(player, animationName);
                    return playAnimation(event, animationName, loopType);
                }
            }
        }
        rememberMainState(player, "");
        return PlayState.STOP;
    }

    /**
     * The main controller's currently selected state, per player - upstream's {@code HumanoidStateTracker}
     * main-animation cache, which its {@code ctrl.<state>} script queries test against
     * ({@code CtrlBinding#testCondition}).
     * <p>
     * Written by {@link #predicateMain} and read by the scripts of the controllers registered before `main`
     * (`pre_parallel`, `vehicle`, `pre_main`), so those see the previous frame's value. Upstream has the same
     * ordering, for the same reason: the cache is filled as the frame's controllers are processed in order.
     */
    private final Map<UUID, String> mainAnimationCache = new ConcurrentHashMap<>();

    private void rememberMainState(EntityPlayer player, String animationName) {
        if (animationName == null || animationName.isEmpty()) {
            mainAnimationCache.remove(player.getUniqueID());
        } else {
            mainAnimationCache.put(player.getUniqueID(), animationName);
        }
    }

    @Nullable
    private String mainStateOf(EntityPlayer player) {
        return mainAnimationCache.get(player.getUniqueID());
    }

    /**
     * Every registered main-state name. The {@code ctrl.<state>} script queries are keyed by this vocabulary, so it is
     * read off the state table rather than listing the names a second time.
     */
    public Set<String> registeredStateNames() {
        Set<String> names = new LinkedHashSet<>();
        for (LinkedList<AnimationState> states : data.values()) {
            for (AnimationState state : states) {
                names.add(state.getAnimationName());
            }
        }
        return names;
    }

    /** 控制脚本动态覆盖动画名的日志（每个 模型×槽位 只打一条，避免每帧刷屏）。 */
    private static final java.util.Set<String> LOGGED_CONTROL_SCRIPT = java.util.concurrent.ConcurrentHashMap
        .newKeySet();

    /**
     * GeckoLib 控制器名 → wiki 的槽位名（{@code @player_ctrl_<槽位>.molang} 里那个槽位）。
     *
     * <p>两种命名都要认：OpenYSM 槽位形如 {@code player.pre_main} / {@code player.parallel_6}
     * （wiki 的控制器名把 {@code .} 换成 {@code _ctrl_}），legacy 控制器形如
     * {@code main_controller} / {@code use_controller} / {@code parallel_6_controller}。
     * 认不出（例如具名并行备用池 {@code pre_parallel_extra_0_controller}）返回 null ——
     * 它的槽位名要问运行时路由结果，这里不猜；{@link #controlSlotFor} 会用
     * {@link OpenYsmPlayerControllerRuntime#namedParallelControlSlot} 把这块补上。</p>
     */
    @Nullable
    static String controlSlotName(@Nullable String geckoControllerName) {
        if (geckoControllerName == null || geckoControllerName.isEmpty()) {
            return null;
        }
        // 具名并行备用池（pre_parallel_extra_N_controller / parallel_extra_N_controller）是 YSMU
        // 的实现细节，模型里没有对应槽位名：要控制的是运行时路由到的**具名**槽位
        // （如 parallel_6），那个名字只有 OpenYsmPlayerControllerRuntime 知道，这里不猜。
        if (geckoControllerName.startsWith("pre_parallel_extra_")
            || geckoControllerName.startsWith("parallel_extra_")) {
            return null;
        }
        // 槽位后缀备用池（openysm_slot_extra_N_controller）同理：要控制的是运行时路由到的
        // player.<slot>_<后缀>（如 pre_main_mecha_mgr），名字只有运行时路由知道。
        if (geckoControllerName.startsWith(com.fox.ysmu.util.ControllerUtils.SLOT_EXTRA_CONTROLLER_PREFIX)) {
            return null;
        }
        if (geckoControllerName.startsWith("player.")) {
            return geckoControllerName.substring("player.".length());
        }
        if (geckoControllerName.endsWith("_controller")) {
            return geckoControllerName.substring(0, geckoControllerName.length() - "_controller".length());
        }
        return null;
    }

    /**
     * 这一帧要交给动画控制脚本的槽位名：普通槽位由 {@link #controlSlotName} 从控制器名推导；
     * 具名并行备用池（{@code *_extra_N_controller}）的槽位名取决于**当前模型**的槽位表，
     * 只有 {@link OpenYsmPlayerControllerRuntime#namedParallelControlSlot} 知道，在这里补齐。
     * <p>
     * 不补的话，模型给具名并行槽位写的 {@code @player_ctrl_<槽位>.molang} 永远拿不到控制权：
     * 池控制器的谓词每帧先查脚本，查到的槽位名是 null，脚本阶段直接跳过。
     */
    @Nullable
    static String controlSlotFor(AnimationEvent<CustomPlayerEntity> event) {
        if (event == null || event.getController() == null) {
            return null;
        }
        String name = event.getController().getName();
        String slot = controlSlotName(name);
        if (slot != null) {
            return slot;
        }
        // 池名：解析它只为给动画控制脚本用。没开脚本、或当前模型根本没有脚本时，
        // applyControlScript 也会直接返回 null，所以省掉这次按模型的路由（绝大多数模型走这里）。
        if (!Config.MOLANG_CONTROL_SCRIPTS) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        ResourceLocation animId = animatable == null ? null : animatable.getAnimation();
        if (animId == null
            || !com.fox.ysmu.client.animation.molang.MolangScriptRegistry.hasScripts(animId)) {
            return null;
        }
        // 槽位后缀备用池：名字里没有槽位名，按当前模型路由到 player.<slot>_<后缀> 对应的脚本槽位。
        if (OpenYsmPlayerControllerRuntime.isSlotExtraController(name)) {
            return OpenYsmPlayerControllerRuntime.slotExtraControlSlot(animId, name);
        }
        return OpenYsmPlayerControllerRuntime.namedParallelControlSlot(animId, name);
    }

    /**
     * 某个槽位的动画控制脚本本帧的决定 → {@code PlayState}；没有脚本/交回内置逻辑返回 null。
     *
     * <p>有决定时在这里就把动画播出去（走 {@link #playAnimation}，所以过渡时长、播放倍速、
     * 脚本里的循环类型都会生效），调用方直接返回该 {@code PlayState}。</p>
     */
    @Nullable
    static PlayState applyControlScript(AnimationEvent<CustomPlayerEntity> event, @Nullable String slot) {
        if (!Config.MOLANG_CONTROL_SCRIPTS || event == null || slot == null || event.getAnimatable() == null) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        ResourceLocation animId = animatable.getAnimation();
        if (animId == null
            || !com.fox.ysmu.client.animation.molang.MolangScriptRegistry.hasScripts(animId)) {
            // 绝大多数模型没有任何 .molang 脚本：一次 map 查询就退出，不做后面的动画表查询。
            return null;
        }
        AnimationFile animFile = GeckoLibCache.getInstance()
            .getAnimations()
            .get(animId);
        com.fox.ysmu.client.animation.molang.AnimationControlResult result =
            com.fox.ysmu.client.animation.molang.AnimationControlScripts.evaluate(animId, slot, () -> {
                EntityPlayer player = animatable.getPlayer();
                return player == null ? null : new com.fox.ysmu.client.animation.controller.OpenYsmScriptScope(
                    player, event, animId, java.util.Collections.emptyList());
            });
        if (result == null) {
            return null;
        }
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            return null;
        }
        com.fox.ysmu.client.animation.molang.AnimationControlResult.LoopType scriptLoop = result.loopType();
        ControlScriptDecision decision = decideControlScript(result, animFile == null ? null : animFile.animations,
            null, animId, slot);
        if (decision == null) {
            return null;
        }
        if (decision.stop) {
            applyControlScriptStop(animId, slot, event, decision.reset);
            return PlayState.STOP;
        }
        if (decision.animationName == null) {
            return null;
        }
        ILoopType loopType = loopTypeOf(scriptLoop);
        // wiki：若要重载当前动画，必须在 ctrl.set_animation **之前**调用 ctrl.indicate_reload ——
        // 同名动画否则会被忽略。所以在 playAnimation 之前 markNeedsReload，让 setAnimation 的
        // "同名但 needsAnimationReload" 分支走重载。
        if (decision.indicateReload && event.getController() != null) {
            event.getController()
                .markNeedsReload();
        }
        PlayState state = loopType == null ? playAnimation(event, decision.animationName)
            : playAnimation(event, decision.animationName, loopType);
        // 过渡时长放在 playAnimation 之后：applyMolangPlaybackHints 可能用静态提取的 map 设过它，
        // 而脚本本帧真正执行出来的值更权威。
        if (decision.transitionSeconds != null && decision.transitionSeconds >= 0.0d
            && event.getController() != null) {
            event.getController().transitionLengthTicks = decision.transitionSeconds * 20.0d;
        }
        return state;
    }

    /** 脚本给出的循环类型 → GeckoLib 的 loop 类型；没写（null）返回 null（用动画自带的）。 */
    @Nullable
    private static ILoopType loopTypeOf(@Nullable com.fox.ysmu.client.animation.molang.AnimationControlResult.LoopType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case LOOP:
                return ILoopType.EDefaultLoopTypes.LOOP;
            case PLAY_ONCE:
                return ILoopType.EDefaultLoopTypes.PLAY_ONCE;
            case HOLD_ON_LAST_FRAME:
                return ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
            default:
                return null;
        }
    }

    /** 控制脚本对本帧的决定：要播哪个动画、要不要中止当前动画、要不要重置控制器状态。 */
    static final class ControlScriptDecision {

        @Nullable
        final String animationName;
        /** {@code state_stop} / {@code ctrl.reset}：中止当前动画（{@code PlayState.STOP}）。 */
        final boolean stop;
        /** {@code ctrl.reset}：同时清掉该槽位的控制器运行时状态（回到初始状态）。 */
        final boolean reset;
        /**
         * 脚本**本帧**给的过渡时长（秒，{@code ctrl.set_beginning_transition_length}）；null = 没给。
         * <p>注册期静态提取的 {@code MOLANG_TRANSITION_MAP} 只能绑定字面量动画名，动态算出来的
         * 名字绑不上 —— 这个字段是那条路径的补充。
         */
        @Nullable
        final Double transitionSeconds;
        /**
         * 脚本**本帧**调用了 {@code ctrl.indicate_reload}。wiki 明确要求它在
         * {@code ctrl.set_animation} **之前**调用才生效（否则同名动画会被忽略而不是重载），
         * 所以应用方必须先 markNeedsReload 再播。
         */
        final boolean indicateReload;

        ControlScriptDecision(@Nullable String animationName, boolean stop, boolean reset) {
            this(animationName, stop, reset, null, false);
        }

        ControlScriptDecision(@Nullable String animationName, boolean stop, boolean reset,
            @Nullable Double transitionSeconds, boolean indicateReload) {
            this.animationName = animationName;
            this.stop = stop;
            this.reset = reset;
            this.transitionSeconds = transitionSeconds;
            this.indicateReload = indicateReload;
        }
    }

    /**
     * 控制脚本结果 → 本帧决定（纯逻辑，便于单测）；副作用只在
     * {@link #applyControlScriptStop} 里做。
     *
     * <p>规则：只在**明确的 {@code state_continue} + 具体动画名 + 该动画确实存在于模型里**时
     * 覆盖调用方的目标；{@code state_bypass}/{@code NONE}/脚本报错/动画不存在一律返回 null，
     * 让调用方沿用内置逻辑与静态映射 —— 开启这个功能不会把本来能动的模型弄坏。</p>
     */
    @Nullable
    static ControlScriptDecision decideControlScript(
        com.fox.ysmu.client.animation.molang.AnimationControlResult result,
        java.util.Map<String, ?> animations, @Nullable String fallbackName, ResourceLocation animId, String slot) {
        if (result == null) {
            return null;
        }
        com.fox.ysmu.client.animation.molang.AnimationControlResult.Action action = result.action();
        String name = result.animationName();
        if (animations != null && name != null && !animations.containsKey(name)) {
            // 脚本算出了一个模型里不存在的动画：不覆盖（否则看起来像"动画丢了"）。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|missing")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' set_animation('{}') but that animation is not in the file; keeping '{}'",
                    animId, slot, name, fallbackName);
            }
            return null;
        }
        if (action == com.fox.ysmu.client.animation.molang.AnimationControlResult.Action.PAUSE) {
            // GeckoLib 没有"暂停播放但不暂停时间轴"的原语：tick 由 AnimationProcessor 统一推进，
            // 骨骼关键帧与 timeline/音效/粒子事件都吃同一个 tick。先按"不动内置逻辑"处理。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|pause")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' requested state_pause - GeckoLib has no pause-without-stopping-clock "
                        + "primitive, keeping the built-in logic",
                    animId, slot);
            }
            return null;
        }
        if (result.reset()) {
            // wiki：ctrl.reset = 立刻重置控制器至初始状态、粗暴中止当前动画，并含 indicate_reload。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|reset")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' ctrl.reset -> aborting the current animation and resetting the "
                        + "controller state",
                    animId, slot);
            }
            return new ControlScriptDecision(null, true, true);
        }
        if (action == com.fox.ysmu.client.animation.molang.AnimationControlResult.Action.STOP) {
            // wiki 说的是"平滑地停止"，但 GeckoLib 的 STOP 会把骨骼队列直接清掉、由
            // AnimationProcessor 的 reset 分支还原初值，而那个分支当前是**瞬时**的
            // （resetTickLength 默认 1 且无人设置；rotation/position 的 mostRecentReset*Tick
            // 被写成 0，见那里的 TODO）。所以这里只能做到"中止"，"平滑"要改 vendored 才能做。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|stop")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' state_stop -> aborting the current animation (smooth fade-out "
                        + "not implemented)",
                    animId, slot);
            }
            return new ControlScriptDecision(null, true, false);
        }
        if (action != com.fox.ysmu.client.animation.molang.AnimationControlResult.Action.CONTINUE || name == null) {
            // bypass / NONE：脚本明确要求交回内置逻辑。
            return null;
        }
        if (!name.equals(fallbackName) && Config.DEBUG_CONTROLLER
            && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|override|" + name)) {
            if (fallbackName == null) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-CTRLSCRIPT] {} slot '{}' -> '{}'", animId, slot, name);
            } else {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' -> '{}' (script overrides static mapping '{}')", animId, slot,
                    name, fallbackName);
            }
        }
        return new ControlScriptDecision(name, false, false, result.transitionSeconds(),
            result.indicateReload());
    }

    /**
     * 应用 {@code ctrl.reset} / {@code state_stop}：中止当前动画，并（reset 时）清掉该槽位的
     * 控制器运行时状态，让内置逻辑从初始状态重新评估。
     */
    private static void applyControlScriptStop(ResourceLocation animId, String slot,
        AnimationEvent<CustomPlayerEntity> event, boolean reset) {
        if (event != null && event.getController() != null) {
            // wiki：ctrl.reset 包含 indicate_reload 的作用；state_stop 也一并重载，
            // 否则下次播同一个动画会"接着上次的进度"而不像重新开始。
            event.getController()
                .markNeedsReload();
        }
        if (!reset || animId == null || event == null) {
            return;
        }
        EntityPlayer player = event.getAnimatable() == null ? null : event.getAnimatable()
            .getPlayer();
        if (player != null) {
            // 这里只给得出 wiki 槽位名（如 parallel_6）；clearControllerState 内部会把
            // player. 前缀 / _controller 后缀归一化后再匹配，因此具名并行备用池承载的
            // 槽位（运行时键是 player.parallel_6 或短名）也能被 reset 到。
            com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.clearControllerState(
                player.getUniqueID(), animId, "player." + slot);
        }
    }

    /**
     * Wraps a built-in predicate so a pack script bound to that controller is consulted first - see
     * {@link #predicateWithPackScript}. Applied at every registration site, because upstream binds scripts in the
     * shared controller class rather than per controller.
     */
    public AnimationController.IAnimationPredicate<CustomPlayerEntity> scripted(
        AnimationController.IAnimationPredicate<CustomPlayerEntity> fallback) {
        return event -> predicateWithPackScript(event, fallback::test);
    }

    /**
     * Runs the script a pack bound to this controller when it has one, and otherwise defers to the built-in predicate.
     * <p>
     * Upstream binds scripts inside {@code CodedAnimationController}: {@code updateModel} looks the handler up by
     * {@code name.replace(".", "_ctrl_")} and {@code process} lets it decide before the coded predicate
     * ({@code geckolib3/core/controller/CodedAnimationController.java:65-72,124-134}). This port wires its controllers
     * straight to their predicates, so the equivalent seam is here - the one place every controller's predicate passes
     * through - and it is applied at the registration site in {@code CustomPlayerEntity#registerControllers}.
     *
     * @param fallback the built-in predicate, used whenever the script has no opinion this frame
     */
    public PlayState predicateWithPackScript(AnimationEvent<CustomPlayerEntity> event,
        Function<AnimationEvent<CustomPlayerEntity>, PlayState> fallback) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null || event.getController() == null) {
            return fallback.apply(event);
        }
        PackFunctionScript script = PackUserFunctions.scriptForController(event.getController().getName());
        if (script == null) {
            return fallback.apply(event);
        }
        PlayState scripted = CtrlScriptBinding.evaluate(
            script,
            GeckoLibCache.getInstance().parser,
            player,
            event.getController(),
            mainStateOf(player));
        return scripted == null ? fallback.apply(event) : scripted;
    }

    /** States already reported as missing, so a state evaluated every frame warns once per model. */
    private static final java.util.Set<String> REPORTED_MISSING_STATES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Whether the model defines an animation, answering {@code true} when its animation file is unknown.
     * <p>
     * Fail-open on purpose: {@link #hasAnimation} answers {@code false} for an unknown file, which is right where a
     * caller can stop, but in the main state loop it would skip every state and freeze a model whose animations simply
     * had not been registered yet.
     */
    private static boolean definesAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        AnimationFile file = ensureAnimationFile(event);
        return file == null || file.animations.containsKey(animationName);
    }

    /**
     * The animation file for this event, installing the model's animations first if they are still parked.
     * <p>
     * A model's animations are installed on first use rather than at registration, and both of the lookups below
     * *decide* whether anything will ever ask the engine for an animation: {@link #hasAnimation} gates the non-player
     * locomotion states, so a model whose animations were never installed would answer "no" to every state, select
     * nothing, and never reach the engine lookup that would have installed them. Asking here closes that loop.
     */
    @Nullable
    private static AnimationFile ensureAnimationFile(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatable();
        // Keyed on the model: getAnimation() answers with the built-in default until this model's own animations
        // exist, so resolving the animation id first would install the wrong model's payload.
        ClientModelManager.ensureAnimations(animatable.getMainModel());
        return ClientModelManager.animationFileFor(getAnimationId(event));
    }

    /** Whether the model backing this animation event actually defines the named animation. */
    private static boolean hasAnimation(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        AnimationFile file = ensureAnimationFile(event);
        return file != null && file.animations.containsKey(animationName);
    }

    private static void warnMissingState(String animationName, AnimationEvent<CustomPlayerEntity> event) {
        String key = getAnimationId(event) + "|" + animationName;
        if (!REPORTED_MISSING_STATES.add(key)) {
            return;
        }
        ysmu.LOG.warn(
            "Model {} does not define the '{}' state; the next matching state is used instead, so that state falls "
                + "back rather than leaving the main controller without an animation",
            getAnimationId(event),
            animationName);
    }

    /**
     * Animation for a non-player animatable, in the order the answers are trusted.
     * <p>
     * A host mod that owns the animation logic for the entity - Touhou Little Maid decides a maid's clip from
     * its own priority table, {@code sit} included - pushes the single resulting clip through
     * {@code EntityAnimationApi}. That clip wins outright: the host has already resolved every rule this
     * method could re-derive, and the clip is replayed with the loop mode the host asked for.
     * <p>
     * Otherwise the model's own seat and locomotion states are used, and only states the model actually
     * defines are selected, so a model without {@code run}/{@code walk}/{@code idle} keeps the controller
     * stopped instead of being handed a missing animation.
     */
    @NotNull
    private PlayState predicateEntityLocomotion(AnimationEvent<CustomPlayerEntity> event) {
        EntityLivingBase entity = event.getAnimatable()
            .getEntity();
        if (entity == null) {
            return PlayState.STOP;
        }
        // A-05 / A-06③: a non-player animatable never reaches AnimationRegister#setParserValue (it takes an
        // EntityPlayer), so this is where the per-frame MoLang context - current entity plus per-axis movement -
        // is published. The player branch publishes the same context in setParserValue, which runs before any
        // controller is evaluated. The model id travels with it so ysm.play_sound resolves against the right pack.
        MolangFrameContext.begin(entity, event.getAnimatable() == null ? null : event.getAnimatable().getMainModel());
        // The pack's own scripts resolve fn.<name> against the frame's model; a non-player animatable never reaches
        // AnimationRegister#setParserValue, so this is its publish point.
        PackUserFunctions.begin(event.getAnimatable() == null ? null : event.getAnimatable().getMainModel());
        // Already resolved by whoever owns the animation logic for this entity; replay it verbatim.
        EntityClips.Clip pushed = EntityClips.get(entity);
        if (pushed != null && hasAnimation(event, pushed.getName())) {
            return playAnimation(event, pushed.getName(), pushed.getLoopType());
        }
        // What she is holding, before what she is doing. The condition names are the ones the player path asks
        // through ConditionalHold (hold_mainhand$<registry name>), and both paths now build them with the shared
        // InnerClassify#holdConditionName helper (D-A3) - before that, a maid holding a sword fell back to walk or
        // idle instead of playing the model's own held-item animation.
        String heldAnimation = findEntityHoldAnimation(event, entity);
        if (heldAnimation != null) {
            return playLoopAnimation(event, heldAnimation);
        }
        // A sitting tameable - a vanilla cat or wolf, or a companion another mod hands us - is neither walking nor
        // idle, so it plays the model's own seat animation while the flag is set. The ending holds the last frame
        // rather than looping, because a seat animation is usually a transition into a pose and looping that
        // transition stands the entity up and sits it back down again every cycle. The flag is read through vanilla's
        // EntityTameable API, so this stays a general rule rather than knowledge of one mod's entity.
        if (entity instanceof EntityTameable && ((EntityTameable) entity).isSitting() && hasAnimation(event, "sit")) {
            return playAnimation(event, "sit", ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
        }
        if (entity.onGround) {
            if (entity.isSprinting() && hasAnimation(event, "run")) {
                return playLoopAnimation(event, "run");
            }
            if (Math.abs(event.getLimbSwingAmount()) > 0.05F && hasAnimation(event, "walk")) {
                return playLoopAnimation(event, "walk");
            }
        }
        return hasAnimation(event, "idle") ? playLoopAnimation(event, "idle") : PlayState.STOP;
    }

    public PlayState predicateOffhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }

        // A-01: 1.7.10 has no vanilla offhand slot (Backhand provides it). With Backhand present an *empty*
        // offhand must still reach ConditionalHold#doTest so it can answer `hold_offhand:empty`, so the
        // "offhand must hold something" precondition is gone; the slot's existence is what is checked now.
        if (!BackhandCompat.isBackhandLoaded()) {
            // Without the off-hand slot there is no off-hand pose to hold.
            return PlayState.STOP;
        }
        if (checkSwingAndUse(player, false)) {
            // TiCon 十字弩已装填 → 显示副手蓄能待机动画（参考库同一分支：
            // reference branch AnimationManager.java:1258-1264）。
            if (com.fox.ysmu.compat.TinkersCrossbowCompat
                .isCrossbowLoaded(BackhandCompat.getOffhandItem(player))) {
                PlayState charged = playIfPresent(
                    event,
                    "hold_offhand:charged_crossbow",
                    ILoopType.EDefaultLoopTypes.LOOP);
                if (charged != PlayState.STOP) {
                    return charged;
                }
            }
            return playIfPresent(event, findHoldAnimation(event, player, false));
        }
        // A-07: upstream answers PAUSE here, not STOP (client/animation/predicate/OffhandPredicate.java:27-29).
        // Stopping made the off-hand clip reload mid-swing; pausing holds the pose the player had instead.
        return PlayState.PAUSE;
    }

    public PlayState predicateMainhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (!player.isSwingInProgress && !player.isUsingItem()) {
            // 1.7.10 没有**原版**弩（Items.CROSSBOW / CrossbowItem.isCharged 不存在），但 Tinkers' Construct
            // 的十字弩就是 1.7.10 上的那把弩：装填状态在物品 NBT（InfiTool.Loaded）里，
            // TinkersCrossbowCompat 读它。所以蓄能待机分支不是"不可能命中"，而是走 TiCon 这一路。
            // (reference branch AnimationManager.java:1313-1319；参考库把这条判断放在同一位置)
            if (com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(player.getHeldItem())) {
                PlayState charged = playIfPresent(
                    event,
                    "hold_mainhand:charged_crossbow",
                    ILoopType.EDefaultLoopTypes.LOOP);
                if (charged != PlayState.STOP) {
                    return charged;
                }
            }
            if (player.fishEntity != null) {
                // D-A5: exist-checked, so a model without `hold_mainhand:fishing` gets STOP instead of driving
                // AnimationController#setAnimation into its "Could not load animation" branch every frame.
                return playIfPresent(event, "hold_mainhand:fishing", ILoopType.EDefaultLoopTypes.LOOP);
            }
        }

        // A-01: the "main hand must hold something" precondition is gone - an empty hand now reaches
        // ConditionalHold#doTest, which answers `hold_mainhand:empty` (1.20.1 MainHandHoldPredicate does the same).
        if (checkSwingAndUse(player, true)) {
            return playIfPresent(event, findHoldAnimation(event, player, true));
        }
        // A-07: upstream answers PAUSE here, not STOP (client/animation/predicate/MainhandPredicate.java:31-33).
        // Stopping made the held-item clip reload mid-swing; pausing holds the pose the player had instead.
        return PlayState.PAUSE;
    }

    public PlayState predicateSwing(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        // 1.7.10: 剑/盾右键格挡会触发 isSwingInProgress，但不应播放挥动动画。
        // 依据 SOURCE OpenYsmPlayerControllerRuntime.java:1557-1566：格挡期间把 swing /
        // swing_sword 清零，不让 OpenYSM 控制器播放挥动动画；内置模型没有 OpenYSM 控制器
        // （上面的 tryApply 返回 null），挥动只由这条 legacy 选择路径产出，所以抑制点必须在这里。
        // 上游没有对应机制：1.20 的 SwingPredicate.java:50 只看 entity.swinging，而 1.20 的盾牌
        // 格挡不会把它置起；1.7.10 的右键格挡会（Minecraft.java:1532 的 swingItem()）。
        if (swingMustYieldToBlocking(com.fox.ysmu.compat.BlockingCompat.isBlocking(player))) {
            // 连挥动沿的采样一起清掉：解格挡后第一次挥动必须被 markSwingStart 认成新沿。
            swingProgressByPlayer.remove(player.getUniqueID());
            return PlayState.STOP;
        }
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(player.getUniqueID());
            return PlayState.STOP;
        }
        if (!player.isPlayerSleeping()) {
            if (markSwingStart(player)) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            boolean isMainHand = BackhandCompat.swingingArm(player);
            String conditionalAnimation = findSwingAnimation(event, player, isMainHand);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.PLAY_ONCE);
            }
            // A-02: the offhand fallback name is `swing_offhand` (1.20.1 ItemHoldAnimationPredicate), and both
            // fallbacks go through the exist-check so a model without them stops instead of spamming the log.
            return playIfPresent(
                event,
                isMainHand ? "swing_hand" : "swing_offhand",
                ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        return PlayState.STOP;
    }

    /**
     * 格挡中的挥动抑制：true = 本帧 {@code swing_controller} 必须放弃挥动动画。
     * <p>
     * 依据 SOURCE 的抑制分支（{@code OpenYsmPlayerControllerRuntime.java:1557-1566}）：格挡期间把
     * {@code swing} / {@code swing_sword} 清零，不让控制器播放挥动动画。上游没有对应机制
     * （1.20 的 {@code SwingPredicate.java:50} 只看 {@code entity.swinging}），这条是 1.7.10 专属：
     * 右键格挡会置起 {@code isSwingInProgress}（{@code Minecraft.java:1532}）。
     * </p>
     * <p>
     * 做成纯谓词是为了可单测：调用点 {@link #predicateSwing} 需要完整的 {@code AnimationEvent} /
     * {@code CustomPlayerEntity} 客户端上下文，而 {@link com.fox.ysmu.compat.BlockingCompat#isBlocking}
     * 自己也没法在测试 JVM 里跑 —— 它的第三条兜底路径解引用 {@code Minecraft.getMinecraft()}
     * （测试里是 null），"未格挡"那一半必然 NPE。所以 {@code SwingBlockingSuppressionTest}
     * 锁的是这条规则本身。
     * </p>
     */
    static boolean swingMustYieldToBlocking(boolean blocking) {
        return blocking;
    }

    private boolean markSwingStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(playerId);
            return false;
        }
        int currentProgress = player.swingProgressInt;
        Integer previousProgress = swingProgressByPlayer.put(playerId, currentProgress);
        return previousProgress == null || currentProgress < previousProgress;
    }

    /**
     * The {@code use_controller}: the model's use pose, and - in 1.7.10 - its block pose.
     *
     * <p>
     * 上游没有"格挡"这条独立分支：{@code UsePredicate.java:24-47} 只问 {@code entity.isUsingItem()}
     * 一次，1.20 的盾牌格挡本身就是"正在使用物品"（{@code LivingEntity#startUsingItem}），
     * 于是格挡自然落到 use 分支；挥动动画由 {@code MainhandPredicate#checkSwingAndUse:78-83} 让位。
     * 1.7.10 没有盾牌物品类，Battlegear2 的格挡标记（以及右键兜底）可以完全不进
     * {@code isUsingItem()}，所以"格挡也算在用物品"这一步必须显式写出来 —— 这就是 SOURCE
     * {@code AnimationManager.java:1474-1515} 的形状：{@code isUsingItem || isBlocking}，条件动画与
     * 回退名都用 {@code HOLD_ON_LAST_FRAME} 播（格挡是一个姿态，不是一个循环），回退名在格挡时
     * 走 {@link #playBlockingAnimation}。
     *
     * <p>
     * 与 SOURCE 的一处差异，是本树既有结构决定的，不是遗漏：回退名先过 {@link #hasAnimation} ——
     * 本仓对每个"模型未必定义"的名字都这样判（{@link #playIfPresent} 一族）。SOURCE 直接播
     * {@code use_mainhand}，而多数 1.7.10 模型只定义 {@code use_mainhand$<id>} 之类的条件名，
     * 无条件播缺失名会把 {@code AnimationController#setAnimation} 打进 "Could not load animation"
     * 分支并留下一个没有回退的控制器。名字缺失时这里返回 {@link PlayState#STOP} 并顺手清掉本帧
     * 状态，让尾迹计数器当帧就归零 —— 与"动画结束"是同一个结尾，而不是又起一段 5 tick 空转。
     * <p>
     * 循环类型与 SOURCE 一致：条件动画与格挡回退是 {@code HOLD_ON_LAST_FRAME}（格挡是一个姿态），
     * 非格挡回退保持原来的 {@code LOOP}。
     */
    public PlayState predicateUse(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        // SOURCE AnimationManager.java:1474-1477: 使用物品，或 TiCon 十字弩正在装填（1.7.10 的
        // "拉弦"没有 isUsingItem 标志），或正在格挡；睡觉时不播。
        boolean isUsingItem = (player.isUsingItem()
            || com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowReloading(player.getHeldItem()))
            && !player.isPlayerSleeping();
        boolean isBlocking = com.fox.ysmu.compat.BlockingCompat.isBlocking(player);
        UUID playerId = player.getUniqueID();

        if (isUsingItem || isBlocking) {
            // 格挡期间每帧重置尾迹：标志消失后再放行 5 帧（SOURCE :1487）。
            blockingTailTicks.put(playerId, BLOCKING_TAIL_TICKS);

            boolean needReset = isUsingItem && markUseStart(player);
            if (needReset || event.getController()
                .getAnimationState() == software.bernie.geckolib3.core.AnimationState.Stopped) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }

            // Battlegear2 的盾牌永远在副手，而 SOURCE :1501 的意思是"只在格挡、没有在用自己的
            // 主手物品时才把姿态算到副手" —— 剑右键格挡（EnumAction.block 走标准使用链）仍然是主手。
            boolean isMainHand = !isBlocking || isUsingItem ? BackhandCompat.getUsedItemHand(player) : false;
            String conditionalAnimation = findUseAnimation(event, player, isMainHand);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
            }
            String fallbackAnim = isMainHand ? "use_mainhand" : "use_offhand";
            if (hasAnimation(event, fallbackAnim)) {
                return isBlocking ? playBlockingAnimation(event, fallbackAnim)
                    : playAnimation(event, fallbackAnim, ILoopType.EDefaultLoopTypes.LOOP);
            }
            // 模型既没有条件名也没有回退名：没有姿态可播，当帧结束（见方法说明第①条）。
            blockingTailTicks.remove(playerId);
            useDurationByPlayer.remove(playerId);
            return PlayState.STOP;
        }

        // 不使用物品也不格挡：由尾迹计数器决定何时停（SOURCE :1517-1523）。
        int tail = blockingTailTicks.getOrDefault(playerId, 0);
        if (tail > 0) {
            blockingTailTicks.put(playerId, tail - 1);
            return PlayState.CONTINUE;
        }
        blockingTailTicks.remove(playerId);
        useDurationByPlayer.remove(playerId);
        return PlayState.STOP;
    }

    /**
     * 格挡尾迹长度（tick）。SOURCE 在 {@code AnimationManager.java:1487} 直接写死 {@code 5}；
     * 这里给它一个名字，因为上面那条"模型没有可用姿态"的提前返回也要用到同一个值。
     */
    private static final int BLOCKING_TAIL_TICKS = 5;

    /**
     * 播放盾牌/剑格挡姿态（SOURCE {@code AnimationManager.java:720-738}，逐行搬用）。
     * <p>
     * 把动画长度改成 2.0s，再以 {@code HOLD_ON_LAST_FRAME} 播放：动画播完后停在最后一帧，也就是
     * 包作者画的格挡姿态，而不是回到第一帧或回到绑定姿势。{@code animationLength} 是引擎
     * {@code Animation} 上的可变字段，包本身多半声明了更短的长度，格挡姿态通常画在末尾几帧上，
     * 所以这里必须就地加长。
     */
    private static PlayState playBlockingAnimation(AnimationEvent<CustomPlayerEntity> event, String fallbackAnim) {
        AnimationFile file = ensureAnimationFile(event);
        if (file != null) {
            software.bernie.geckolib3.core.builder.Animation animation = file.getAnimation(fallbackAnim);
            if (animation != null) {
                animation.animationLength = 2.0;
            }
        }
        return playAnimation(event, fallbackAnim, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
    }

    private boolean markUseStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isUsingItem()) {
            useDurationByPlayer.remove(playerId);
            return false;
        }
        int currentDuration = player.getItemInUseDuration();
        Integer previousDuration = useDurationByPlayer.put(playerId, currentDuration);
        return previousDuration == null || currentDuration < previousDuration;
    }

    public PlayState predicateArmor(AnimationEvent<CustomPlayerEntity> event, int slotIndex) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        ItemStack itemBySlot = player.getEquipmentInSlot(slotIndex);
        if (itemBySlot == null) {
            return PlayState.STOP;
        }

        String conditionalAnimation = findArmorAnimation(event, player, slotIndex);
        if (StringUtils.isNoneBlank(conditionalAnimation)) {
            return playLoopAnimation(event, conditionalAnimation);
        }

        AnimationFile animationFile = ensureAnimationFile(event);
        String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
        String defaultName = slotName + ":default";
        if (animationFile != null && animationFile.animations.containsKey(defaultName)) {
            return playAnimation(event, defaultName, ILoopType.EDefaultLoopTypes.LOOP);
        }
        return PlayState.STOP;
    }

    /**
     * The {@code player.vehicle} controller (B-04): the per-vehicle clip a model declares, else the legacy riding
     * states.
     * <p>
     * The order is upstream's ({@code client/animation/predicate/VehiclePredicate.java:51-96}): the {@code vehicle$…}
     * condition name first, then the pig / saddleable / boat fallbacks, then {@code sit}. Upstream's mod-specific
     * branches (SWEM, the Touhou chair, CarryOn, maid vehicles) have no 1.7.10 counterpart, and its {@code Saddleable}
     * group is exactly the pig and the horse here. Falling through with {@code hasAnimation} rather than playing
     * unconditionally is this port's rule for every state that a model may not define.
     */
    public PlayState predicateVehicle(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null || player.ridingEntity == null || !player.ridingEntity.isEntityAlive()) {
            return PlayState.STOP;
        }
        ConditionalVehicle condition = ConditionManager.getVehicle(getAnimationId(event));
        if (condition != null) {
            String name = condition.doTest(player);
            if (StringUtils.isNoneBlank(name) && hasAnimation(event, name)) {
                return playLoopAnimation(event, name);
            }
        }
        Entity vehicle = player.ridingEntity;
        if (vehicle instanceof EntityPig && hasAnimation(event, "ride_pig")) {
            return playLoopAnimation(event, "ride_pig");
        }
        if (vehicle instanceof EntityHorse && hasAnimation(event, "ride")) {
            return playLoopAnimation(event, "ride");
        }
        if (vehicle instanceof EntityBoat && hasAnimation(event, "boat")) {
            return playLoopAnimation(event, "boat");
        }
        return hasAnimation(event, "sit") ? playLoopAnimation(event, "sit") : PlayState.STOP;
    }

    /**
     * The {@code player.passenger} controller (B-04): the clip a model declares for whoever is riding the player
     * ({@code passenger$…}), and nothing otherwise - upstream's predicate has no fallback either
     * ({@code client/animation/predicate/PassengerPredicate.java:23-35}).
     */
    public PlayState predicatePassenger(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null || player.riddenByEntity == null || !player.riddenByEntity.isEntityAlive()) {
            return PlayState.STOP;
        }
        ConditionalPassenger condition = ConditionManager.getPassenger(getAnimationId(event));
        if (condition == null) {
            return PlayState.STOP;
        }
        String name = condition.doTest(player);
        if (StringUtils.isNoneBlank(name) && hasAnimation(event, name)) {
            return playLoopAnimation(event, name);
        }
        return PlayState.STOP;
    }

    private static ResourceLocation getAnimationId(AnimationEvent<CustomPlayerEntity> event) {
        return event.getAnimatable()
            .getAnimation();
    }

    private static PlayState playIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        return playIfPresent(event, animationName, null);
    }

    /**
     * Plays the name only when the model actually defines it (D-A5).
     *
     * <p>
     * Conditional names (`hold_mainhand:empty`, `swing_offhand`, the `held-item` fallbacks) are not part of every
     * model; calling {@code playAnimation} for a missing one drives {@code AnimationController#setAnimation} into
     * its "Could not load animation: ... Is it missing?" branch, which prints to stdout and leaves the controller
     * without a fallback. 1.20.1 guards the same calls with {@code playAnimationWithValid}. A {@code null}
     * loop type keeps the animation file's own loop setting, exactly like the previous behaviour.
     */
    private static PlayState playIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName,
        ILoopType loopType) {
        if (StringUtils.isBlank(animationName) || !hasAnimation(event, animationName)) {
            return PlayState.STOP;
        }
        return loopType == null ? playAnimation(event, animationName) : playAnimation(event, animationName, loopType);
    }

    /**
     * The held-item animation for an entity that is not a player, or null when the model has none for what she holds.
     * <p>
     * The id form is the one that answers "a sword in her hand": the same
     * {@code hold_mainhand$<registry name>} shape {@code ConditionalHold} tests, built by the single shared helper
     * {@link InnerClassify#holdConditionName} (D-A3), and it is only answered when the model has that clip, so a
     * model that does not name its items by registry id simply says nothing here. The ore-dictionary and "kind"
     * forms the player path also tries are not consulted on this path yet.
     */
    private static String findEntityHoldAnimation(AnimationEvent<CustomPlayerEntity> event, EntityLivingBase entity) {
        String byId = InnerClassify.holdConditionName(entity.getHeldItem(), true);
        if (StringUtils.isBlank(byId) || !hasAnimation(event, byId)) {
            return null;
        }
        return byId;
    }

    private static String findHoldAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalHold conditionalHold = isMainHand ? ConditionManager.getHoldMainhand(id)
            : ConditionManager.getHoldOffhand(id);
        return conditionalHold == null ? null : conditionalHold.doTest(player, isMainHand);
    }

    private static String findSwingAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        // A-02: main hand and off hand have separate prefix sets (swing_offhand$/#/: on the off hand), so the
        // table is chosen by which arm is swinging rather than by handing both cases the main-hand table.
        ConditionalSwing conditionalSwing = ConditionManager.getSwing(getAnimationId(event), isMainHand);
        return conditionalSwing == null ? null : conditionalSwing.doTest(player, isMainHand);
    }

    private static String findUseAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalUse conditionalUse = isMainHand ? ConditionManager.getUseMainhand(id)
            : ConditionManager.getUseOffhand(id);
        return conditionalUse == null ? null : conditionalUse.doTest(player, isMainHand);
    }

    private static String findArmorAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        int slotIndex) {
        // The table is chosen by the asking animatable's domain, which is upstream's split between
        // `ConditionManager#getArmor` (the body's controllers) and `getFPArmConditionManager().getArmor()`
        // (client/animation/predicate/FPArmArmorPredicate.java:36, the `fp.arm.armor_<slot>` family). The animation
        // id is the same key those tables are registered under, so `armorFor` answers this without a second rule.
        ConditionArmor conditionArmor = ConditionManager.armorFor(getAnimationId(event));
        return conditionArmor == null ? null : conditionArmor.doTest(player, slotIndex);
    }

    private boolean checkSwingAndUse(EntityPlayer player, boolean isMainHand) {
        if (player.isSwingInProgress && BackhandCompat.swingingArm(player) == isMainHand) {
            return false;
        }
        return !player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != isMainHand;
    }
}
