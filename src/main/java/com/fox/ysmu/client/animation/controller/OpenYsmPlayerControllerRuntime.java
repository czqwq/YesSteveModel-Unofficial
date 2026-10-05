package com.fox.ysmu.client.animation.controller;

import static com.fox.ysmu.util.ControllerUtils.CAP_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_MAINHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_OFFHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.MAIN_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.OPENYSM_POST_SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.USE_CONTROLLER;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.condition.ConditionArmor;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.file.AnimationFile;

public final class OpenYsmPlayerControllerRuntime {

    private static final Map<StateKey, RuntimeState> STATES = new ConcurrentHashMap<>();
    /** 调试日志的简易按标签限流表：tag → 上次打印时刻（毫秒）。{@code SOURCE:47}。 */
    private static final Map<String, Long> DEBUG_LOG_LAST_TIME = new ConcurrentHashMap<>();

    private OpenYsmPlayerControllerRuntime() {}

    /**
     * 该标签现在是否可以打一条日志（同一标签最多 1000ms 一条）。SOURCE
     * {@code OpenYsmPlayerControllerRuntime.java:77-86} 原样搬用。
     * <p>
     * 这是本仓"永久诊断"的那一类：{@code DEBUG_CONTROLLER} 开关 + 限流，所以它留在代码里；
     * 调用点自己判 {@code Config.DEBUG_CONTROLLER}，本方法只管限流。
     */
    private static boolean allowDebugLog(String tag) {
        long now = System.currentTimeMillis();
        Long last = DEBUG_LOG_LAST_TIME.get(tag);
        if (last != null && now - last < 1000) {
            return false;
        }
        DEBUG_LOG_LAST_TIME.put(tag, now);
        return true;
    }

    public static PlayState tryApply(AnimationEvent<CustomPlayerEntity> event) {
        if (event == null || event.getController() == null || event.getAnimatable() == null) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            return null;
        }
        // Install this model's animations before reading the controller table, for the same reason the animation
        // lookups do: getAnimation() answers with the built-in default while the model's own animations are parked,
        // so reading the registry first would apply the *default's* controller definitions to this model.
        ClientModelManager.ensureAnimations(animatable.getMainModel());
        ResourceLocation animationId = animatable.getAnimation();
        // Upstream keeps ONE controller map per render target and hands it to both its player entity and its
        // first-person arm entity (`PlayerModelResources.animationControllers()`, read by `PlayerControllerCollection`
        // and `FPArmControllerCollection` through the same `ResourceAdapter.INSTANCE`:
        // `client/controller/collections/FPArmControllerCollection.java:74-76`). The port keys this registry by
        // animation id, and the arm's animation id is the model's `arm` id, so the arm has to be routed back to the
        // same model's controller map - otherwise a pack's declared `fp.arm.misc` / `fp.arm.parallel_<i>` /
        // `fp.arm.armor_<slot>` definition could never be found and the arm would only ever fall back to the coded
        // `parallel<i>` clip. `animationId` stays the arm's own id below, which is what keeps the arm's per-controller
        // runtime state and its animation lookups in the first-person domain.
        ControllerSet set = OpenYsmAnimationControllerRegistry
            .get(com.fox.ysmu.client.animation.AnimationDomain.controllerSetId(animationId));
        if (set == null) {
            return null;
        }

        String geckoControllerName = event.getController().getName();
        for (ControllerMatch match : resolveControllers(set, animationId, geckoControllerName)) {
            PlayState result = tryApplyController(event, player, animationId, geckoControllerName, match);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    static void clear() {
        STATES.clear();
    }

    /**
     * 清掉某玩家某模型下**一个** OpenYSM 控制器的运行时状态（{@code ctrl.reset}）。
     *
     * <p>wiki: molang/script「动画控制」—— {@code ctrl.reset} 要求"立刻重置动画控制器至初始
     * 状态"：不清这个状态机快照的话，控制器下一帧会带着 reset 之前的 state/时间戳继续走，
     * 看起来就像 reset 没生效。</p>
     *
     * @param controllerName OpenYSM 控制器名（如 {@code player.main}）；null 表示清该模型下全部
     */
    public static void clearControllerState(UUID playerId, ResourceLocation modelId, String controllerName) {
        if (playerId == null || modelId == null) {
            return;
        }
        java.util.Iterator<Map.Entry<StateKey, RuntimeState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StateKey, RuntimeState> entry = it.next();
            StateKey key = entry.getKey();
            if (!playerId.equals(key.playerId) || !modelId.equals(key.animationId)) {
                continue;
            }
            if (controllerName == null || matchesControllerName(controllerName, key.openYsmControllerName)
                || matchesControllerName(controllerName, key.geckoControllerName)) {
                it.remove();
            }
        }
    }

    /**
     * {@code ctrl.reset} / {@code state_stop} 的控制器名匹配：调用方可能写 wiki 槽位名
     * （{@code parallel_6}）、OpenYSM 名（{@code player.parallel_6}）或 legacy GeckoLib 名
     * （{@code parallel_6_controller}），三者指的是同一个槽位。
     * <p>
     * 统一去掉 {@code player.} 前缀与 {@code _controller} 后缀再比较。**修的是 reset 路由**：
     * 具名并行槽位的运行时键是 {@code player.parallel_6}（或短名），而 reset 只从脚本拿到槽位名
     * {@code parallel_6} —— 按原样比较匹配不到，状态机不会回到初始状态，看起来就像 reset 没生效。
     */
    static boolean matchesControllerName(String requested, String candidate) {
        if (requested == null || candidate == null) {
            return false;
        }
        return normalizeControllerName(requested).equals(normalizeControllerName(candidate));
    }

    /** 控制器名归一化：去掉 {@code player.} 前缀与 {@code _controller} 后缀。 */
    private static String normalizeControllerName(String name) {
        String result = name;
        if (result.startsWith("player.")) {
            result = result.substring("player.".length());
        }
        if (result.endsWith("_controller")) {
            result = result.substring(0, result.length() - "_controller".length());
        }
        return result;
    }

    private static PlayState tryApplyController(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        ResourceLocation animationId, String geckoControllerName, ControllerMatch match) {
        RuntimeState runtimeState = runtimeState(player, animationId, geckoControllerName, match.controller.name);
        // 跳过依赖未加载模组的并行控制器（如 player.parallel_3）。
        // 没有 TacZ 时并行控制器的 transition 条件永远无法满足，
        // 导致卡在 default 状态循环播放带有音效关键帧的动画。
        // (reference branch OpenYsmPlayerControllerRuntime.java:378-392)
        //
        // 主控制器（post_main/main/base/move）不跳过——它们的非模组
        // 相关状态（如 idle/walk/run/jump）可以正常运行，模组相关条件
        // 自然返回 false。
        if (!match.controller.modDependencies.isEmpty()
            && ModDependencyRegistry.hasUnmetDependencies(match.controller.modDependencies)) {
            // 仅跳过并行控制器，不跳过主身体控制器
            String ctrlName = geckoControllerName;
            boolean isParallel = ctrlName != null
                && (ctrlName.startsWith("parallel_") || ctrlName.startsWith("pre_parallel_"));
            if (isParallel) {
                runtimeState.currentState = "";
                runtimeState.lastSelectedAnimationState = "";
                runtimeState.lastSelectedAnimation = "";
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(player, geckoControllerName);
                event.getController().currentAnimationBuilder = new AnimationBuilder();
                return null;
            }
        }
        OpenYsmControllerExpressionEvaluator.Context context = new OpenYsmControllerExpressionEvaluator.Context(
            event,
            player,
            runtimeState);
        prepareFrameVariables(geckoControllerName, player, runtimeState, context);
        State state = ensureState(event, player, match.controller, runtimeState, context);
        if (state == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            State nextState = applyTransition(event, player, match.controller, state, runtimeState, context);
            if (nextState == state) {
                break;
            }
            state = nextState;
        }

        String animationName = selectAnimation(state, match.preferredAnimationName, context);
        reportDroppedEntries(animationId, geckoControllerName, state, animationName, context);
        if (StringUtils.isBlank(animationName) || !animationExists(animationId, animationName)) {
            return null;
        }
        if (SWING_CONTROLLER.equals(geckoControllerName) && "attack_empty".equals(animationName)) {
            return null;
        }
        if (state.blendTransitionTicks >= 0f) {
            event.getController().transitionLengthTicks = state.blendTransitionTicks;
        }
        applyAnimation(event, runtimeState, state, animationName);
        return PlayState.CONTINUE;
    }

    private static RuntimeState runtimeState(EntityPlayer player, ResourceLocation animationId,
        String geckoControllerName, String openYsmControllerName) {
        StateKey key = new StateKey(player.getUniqueID(), animationId, geckoControllerName, openYsmControllerName);
        RuntimeState state = STATES.get(key);
        if (state == null) {
            state = new RuntimeState();
            STATES.put(key, state);
        }
        return state;
    }

    private static State ensureState(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        Controller controller, RuntimeState runtimeState, OpenYsmControllerExpressionEvaluator.Context context) {
        State state = controller.states.get(runtimeState.currentState);
        if (state != null) {
            return state;
        }
        State initial = controller.getInitialState();
        if (initial == null) {
            return null;
        }
        runtimeState.currentState = initial.name;
        runtimeState.enteredTick = event.getAnimationTick();
        runtimeState.lastSelectedAnimationState = "";
        runtimeState.lastSelectedAnimation = "";
        OpenYsmControllerExpressionEvaluator.executeStatements(initial.onEntry, context);
        playStateSounds(initial, player);
        return initial;
    }

    private static State applyTransition(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        Controller controller, State state, RuntimeState runtimeState,
        OpenYsmControllerExpressionEvaluator.Context context) {
        for (Transition transition : state.transitions) {
            State target = controller.states.get(transition.targetState);
            if (target == null) {
                continue;
            }
            if (!OpenYsmControllerExpressionEvaluator.evaluateBoolean(transition.condition, context)) {
                continue;
            }
            OpenYsmControllerExpressionEvaluator.executeStatements(state.onExit, context);
            runtimeState.currentState = target.name;
            runtimeState.enteredTick = event.getAnimationTick();
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
            OpenYsmControllerExpressionEvaluator.executeStatements(target.onEntry, context);
            playStateSounds(target, player);
            return target;
        }
        return state;
    }

    /**
     * Plays the {@code sound_effects} an OpenYSM controller state declared, at the moment the state is entered.
     * <p>
     * Mirrors the reference branch's {@code OpenYsmPlayerControllerRuntime#playStateSounds} (its lines 689-697): the
     * state's list is parsed into {@code State.soundEffects} by {@code OpenYsmAnimationControllerRegistry}, and each
     * name is handed to the sound manager with no model context and default volume/pitch. The manager resolves a
     * name against the model it was registered from and falls back to a vanilla sound of that name.
     * <p>
     * The controller name is deliberately NOT passed here: the reference branch keys state effects by the player and
     * the sound name only, and passes the controller to {@code stopController} on the paths that end an animation
     * (see the {@code stopController(player, geckoControllerName)} calls in {@code tryApplyController}).
     */
    private static void playStateSounds(State state, EntityPlayer player) {
        if (state.soundEffects == null || state.soundEffects.isEmpty()) {
            return;
        }
        for (String soundName : state.soundEffects) {
            if (soundName != null && !soundName.isEmpty()) {
                com.fox.ysmu.client.audio.YSMSoundManager.playSound(player, soundName, 1.0f, 1.0f);
            }
        }
    }

    private static String selectAnimation(State state, String preferredAnimationName,
        OpenYsmControllerExpressionEvaluator.Context context) {
        if (state.animations.isEmpty()) {
            return null;
        }
        if (StringUtils.isNotBlank(preferredAnimationName)) {
            for (AnimationEntry entry : state.animations) {
                if (preferredAnimationName.equals(entry.animationName)) {
                    return animationEntryActive(entry, context) ? entry.animationName : null;
                }
            }
        }
        for (AnimationEntry entry : state.animations) {
            if (animationEntryActive(entry, context)) {
                return entry.animationName;
            }
        }
        return null;
    }

    private static boolean animationEntryActive(AnimationEntry entry,
        OpenYsmControllerExpressionEvaluator.Context context) {
        return StringUtils.isBlank(entry.condition)
            || OpenYsmControllerExpressionEvaluator.evaluateBoolean(entry.condition, context);
    }

    /**
     * B-12: a Bedrock state lists several animations and plays every entry whose own condition holds, but a controller
     * here plays one clip at a time - the engine's queue is a sequential playlist, not a set of layers - so the other
     * matching entries are simply lost.
     * <p>
     * This does not implement the capability; it makes the loss visible. Reported once per (model, controller, state)
     * so a log answers the question the audit could not: whether any pack this client actually loads relies on it.
     * "The pack's second animation never plays" is exactly the kind of silence the rest of this audit keeps finding.
     */
    private static void reportDroppedEntries(ResourceLocation animationId, String geckoControllerName, State state,
        String selectedAnimationName, OpenYsmControllerExpressionEvaluator.Context context) {
        if (StringUtils.isBlank(selectedAnimationName) || state.animations.size() < 2) {
            return;
        }
        StringBuilder dropped = null;
        for (AnimationEntry entry : state.animations) {
            if (entry.animationName.equals(selectedAnimationName) || !animationEntryActive(entry, context)) {
                continue;
            }
            if (dropped == null) {
                dropped = new StringBuilder();
            } else {
                dropped.append(", ");
            }
            dropped.append(entry.animationName);
        }
        if (dropped == null) {
            return;
        }
        OpenYsmAnimationControllerRegistry.warnOnce(
            "multi-animation:" + animationId + ":" + geckoControllerName + ":" + state.name,
            "OpenYSM state '" + state.name
                + "' of controller "
                + geckoControllerName
                + " in "
                + animationId
                + " has more than one animation whose condition holds ("
                + dropped
                + "); this port plays one clip per controller, so only "
                + selectedAnimationName
                + " plays. Further occurrences are not logged.");
    }

    private static boolean animationExists(ResourceLocation animationId, String animationName) {
        // Installs the model's animations on first use; going straight to the cache would report a controller's
        // animation as missing for a model that simply has not been drawn yet.
        AnimationFile file = ClientModelManager.animationFileFor(animationId);
        // 用 getAnimation() 判断存在性（而非直接查 map），使内置 "empty" 兜底也能命中：
        // 否则 空闲 等引用 "empty" 的状态会被过滤为无动画，回到 all_animations_finished
        // 误判的旧 bug 路径。（SOURCE OpenYsmPlayerControllerRuntime.java:706-711 —— SOURCE 把
        // 这一步从 containsKey 改成 getAnimation，是 YsmBuiltinAnimations 兜底生效的另一半；
        // 少了它，加载期丢弃模型自定义 "empty" 之后 containsKey("empty") 恒为 false，
        // 引用 empty 的控制器状态会被判成"缺动画"。）
        if (file == null || file.getAnimation(animationName) == null) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "missing-animation:" + animationId + ":" + animationName,
                "OpenYSM controller selected missing animation " + animationName + " for " + animationId);
            return false;
        }
        return true;
    }

    private static void applyAnimation(AnimationEvent<CustomPlayerEntity> event, RuntimeState runtimeState, State state,
        String animationName) {
        AnimationBuilder builder = new AnimationBuilder().addAnimation(animationName);
        boolean sameState = state.name.equals(runtimeState.lastSelectedAnimationState);
        boolean changedInSameState = sameState && StringUtils.isNotBlank(runtimeState.lastSelectedAnimation)
            && !runtimeState.lastSelectedAnimation.equals(animationName);
        runtimeState.lastAnimation = animationName;
        runtimeState.lastSelectedAnimationState = state.name;
        runtimeState.lastSelectedAnimation = animationName;
        if (changedInSameState) {
            double elapsedTick = Math.max(0.0D, event.getAnimationTick() - runtimeState.enteredTick);
            if (event.getController()
                .setAnimationPreservingTick(builder, event.getAnimationTick(), elapsedTick)) {
                return;
            }
        }
        event.getController()
            .setAnimation(builder);
    }

    /**
     * Publishes this frame's {@code v.*} state into the controller and detects a new swing. Port of SOURCE
     * {@code OpenYsmPlayerControllerRuntime.java:1506-1603}.
     *
     * <p>
     * 该成员每一帧对**每个**控制器都跑一遍（SOURCE 的注释：{@code parallel_2} 这类没有
     * {@code post_swing} 控制器的并行槽位也必须能各自发现挥动），每个控制器用自己的
     * {@code lastSwingActive} / {@code lastSwingProgress}，所以 {@code on_exit} 写的变量不会被别的
     * 控制器覆盖。
     *
     * <p>
     * 1.7.10 的 {@code swingProgressInt} <b>递减</b>（{@code getArmSwingAnimationEnd()} → 0），
     * 与 Bedrock 的递增相反，而"挥到一半再次点击"会把计数重置为 {@code -1} 但
     * {@code isSwingInProgress} 保持 true（{@code EntityLivingBase#swingItem}）。所以上升沿判定
     * 收在 {@link com.fox.ysmu.util.SwingEdge#isNewSwing} 里，两条路径共用；只比较进度值会在整段
     * 挥动里每帧都判成"新挥动"，{@code v.swing=1} 被反复写、状态机在子状态之间弹跳。
     *
     * <p>
     * 同步必须在挥动检测<b>之前</b>（SOURCE 的注释）：挥动状态的 {@code on_entry} 语句
     * （如 {@code v.swing=0;v.swing_end=1}）直接写引擎本帧作用域，那些值跨帧留在
     * {@link MolangPhysicsRuntime} 里；若同步在检测之后跑，就会用上一段挥动的 {@code on_entry}
     * 值把刚写下的 {@code v.swing=1} 覆盖掉，控制器永久锁在 default 状态。
     *
     * <p>
     * 1.7.10 专属的格挡分支：剑/盾右键格挡会置起 {@code isSwingInProgress}，但不应让 OpenYSM
     * 控制器播挥动动画（依据 SOURCE {@code :1557-1566}），所以格挡期间把 {@code swing} /
     * {@code swing_sword} 归零、并记录本帧的挥动采样，让解格挡后的第一次挥动仍被认成新沿。
     * 上游没有对应机制：1.20 的 {@code SwingPredicate} 只看 {@code entity.swinging}，而 1.20 的
     * 盾牌格挡不会把它置起。
     */
    private static void prepareFrameVariables(String geckoControllerName, EntityPlayer player, RuntimeState state,
        OpenYsmControllerExpressionEvaluator.Context context) {
        // 关键帧 Molang 与时间轴写的是引擎本帧作用域；控制器条件读的是 RuntimeState。SOURCE 这一步
        // （:1537）把作用域里的 v.* 同步进控制器侧的表。本仓的 RuntimeState#getVariable 已经先读作用域、
        // 再回落这张表，所以这次同步对求值结果不构成改变（见 MolangPhysicsRuntime#syncToRuntimeState 的说明）。
        MolangPhysicsRuntime.syncToRuntimeState(state.variables);
        // post_swing 控制器上的取值诊断（SOURCE :1538-1555，限流 1s）。
        if (Config.DEBUG_CONTROLLER && geckoControllerName.contains("post_swing")) {
            double postSyncAttack = state.variables.getOrDefault("attack", -999.0);
            double postSyncSwingEnd = state.variables.getOrDefault("swing_end", -999.0);
            if ((postSyncAttack != -999.0 || postSyncSwingEnd != -999.0) && allowDebugLog("PS-SYNC")) {
                ysmu.LOG.info(
                    "[YSMU-PS-SYNC] after syncToRuntimeState: attack={} swing_end={}",
                    postSyncAttack,
                    postSyncSwingEnd);
            }
            // 驱动「默认挥剑」连段的 v.qh 变量（SOURCE :1546-1555，限流 1s）。
            if (allowDebugLog("PS-QH")) {
                double qh = state.variables.getOrDefault("qh", Double.NaN);
                double qh2 = state.variables.getOrDefault("qh2", Double.NaN);
                double jump = state.variables.getOrDefault("jump", Double.NaN);
                double vrandom = state.variables.getOrDefault("random", Double.NaN);
                ysmu.LOG.info("[YSMU-PS-QH] qh={} qh2={} jump={} random={}", qh, qh2, jump, vrandom);
            }
        }

        if (player == null) {
            // 预览实体没有玩家状态，也就没有挥动可测；SOURCE 在这里也只在 player != null 时继续。
            return;
        }
        if (com.fox.ysmu.compat.BlockingCompat.isBlocking(player)) {
            // 1.7.10: 格挡中把 swing / swing_sword 归零（依据 SOURCE :1557-1566）。
            state.lastSwingActive = player.isSwingInProgress;
            state.lastSwingProgress = player.isSwingInProgress ? player.swingProgressInt : -1;
            state.setVariable("swing", 0.0d);
            state.setVariable("swing_sword", 0.0d);
            return;
        }
        if (isPostSwingController(geckoControllerName)) {
            boolean newSwing = com.fox.ysmu.util.SwingEdge
                .isNewSwing(player.isSwingInProgress, player.swingProgressInt, state.lastSwingProgress,
                    state.lastSwingActive);
            if (newSwing) {
                state.setVariable("swing", 1.0d);
                state.setVariable("swing_end", 0.0d);
                boolean swordSwing = OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                    "ctrl.swing('mainhand', ':sword')||ctrl.swing('offhand', ':sword')",
                    context);
                if (swordSwing) {
                    // A-03: written through the shared variable entry so the value lands in the same store the
                    // animation file and its timeline instructions use (see RuntimeState#setVariable).
                    state.setVariable("swing_sword", 1.0d);
                    if (Config.DEBUG_CONTROLLER && allowDebugLog("CTRL-SWING-" + geckoControllerName)) {
                        ysmu.LOG.info(
                            "[YSMU-CTRL] {}: sword swing detected, set swing_sword=1",
                            geckoControllerName);
                    }
                }
                state.setVariable(
                    "jump",
                    OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                        "q.is_jumping&&(q.vertical_speed<0)",
                        context) ? 1.0d : 0.0d);
            } else {
                // SOURCE :1595-1598：没有新挥动时把这两个脉冲变量收回 0，否则一次性触发会粘住。
                state.setVariable("swing", 0.0d);
                state.setVariable("swing_sword", 0.0d);
            }
            state.lastSwingActive = player.isSwingInProgress;
            state.lastSwingProgress = player.isSwingInProgress ? player.swingProgressInt : -1;
        }
    }

    private static boolean isPostSwingController(String geckoControllerName) {
        return OPENYSM_POST_SWING_CONTROLLER.equals(geckoControllerName)
            || SWING_CONTROLLER.equals(geckoControllerName);
    }

    /** 包内可见（单测直接断言"名字 → 匹配列表"）：{@link ControllerSet#routeCache} 缓存它。 */
    static List<ControllerMatch> resolveControllers(ControllerSet set, ResourceLocation animationId,
        String geckoControllerName) {
        // 名字 → 匹配列表的解析只依赖这个模型的控制器表，缓存到 ControllerSet 上
        // （set 发布后不再被改动，见 ControllerSet#routeCache 的说明）。
        List<ControllerMatch> cached = set.routeCache.get(geckoControllerName);
        if (cached != null) {
            return cached;
        }
        List<ControllerMatch> matches = new ArrayList<>();
        String preferredAnimationName = parallelAnimationName(geckoControllerName);
        // 具名并行槽位的备用池必须先分流：它的名字也带 pre_parallel_/parallel_ 前缀，
        // 落到下面的数字槽位解析会得到一个 -1 然后什么都不匹配。
        NamedParallelRoute namedRoute = routeNamedParallel(animationId, geckoControllerName);
        if (namedRoute != null) {
            if (namedRoute.controllerKey != null) {
                addMatch(matches, set, namedRoute.controllerKey, preferredAnimationName);
            }
            List<ControllerMatch> stored = matches.isEmpty() ? java.util.Collections.emptyList() : matches;
            set.routeCache.put(geckoControllerName, stored);
            return stored;
        }
        // 槽位后缀控制器的共享备用池：名字里没有模型信息，路由按当前模型算
        // （wiki「动画控制器」2.6.3：同一组内的控制器按名称字母序排序后依次加载；
        // 官方对 player.pre_main_* 这类带后缀的名字也各发一个独立控制器）。
        if (isSlotExtraController(geckoControllerName)) {
            OpenYsmAnimationControllerRegistry.SlotExtra slotExtra =
                routeSlotExtra(animationId, geckoControllerName);
            if (slotExtra != null) {
                addMatch(matches, set, slotExtra.controllerKey, preferredAnimationName);
            }
            List<ControllerMatch> stored = matches.isEmpty() ? java.util.Collections.emptyList() : matches;
            set.routeCache.put(geckoControllerName, stored);
            return stored;
        }
        for (String candidate : candidateControllerNames(geckoControllerName)) {
            addMatch(matches, set, candidate, preferredAnimationName);
        }
        List<ControllerMatch> stored = matches.isEmpty() ? java.util.Collections.emptyList() : matches;
        set.routeCache.put(geckoControllerName, stored);
        return stored;
    }

    /**
     * Every name a pack may have declared this GeckoLib controller under, in the order they are trusted.
     * <p>
     * The port names its controllers after its own Java wiring while packs are written against upstream's
     * names, so one controller usually has several spellings and they belong in one reviewable table. The
     * per-slot armor aliases are the ones a pack actually writes - upstream keys them {@code player.armor_<slot>}
     * (client/controller/ArmorControllerDiscovery.java:35) and the shipped wine_fox/14_momo pack declares
     * {@code player.armor_head} (controller/armor.animation_controllers.json:4) - whereas this port names its
     * controller {@code <slot>_controller} (CustomPlayerEntity#registerControllers). Without those two lines a
     * pack's whole per-slot armor state machine could never be matched and only the Java
     * {@code <slot>:$id} / {@code <slot>:default} path would ever play.
     * <p>
     * Order is priority: a name both sides agree on is tried before an alias.
     */
    static List<String> candidateControllerNames(String geckoControllerName) {
        List<String> names = new ArrayList<>();
        if (geckoControllerName == null || geckoControllerName.isEmpty()) {
            return names;
        }
        if (getParallelIndex(geckoControllerName) >= 0) {
            // B-02: upstream pairs a parallel controller with its own clip by SUFFIX. A model that declares
            // `player.parallel_<i>` gets that definition as the state machine, and a model that declares none gets
            // the coded `parallel<i>` (PlayerControllerCollection.java:68-69 only builds ParallelPredicate(anim)
            // as the fallback, and ParallelControllerDiscovery.java:52-70 derives the controller name from the
            // animation `parallel<i>` itself). The grouped form - one `player.parallel_0` whose `animations` list
            // holds all eight - is therefore a fallback for the controller-0 spelling only, not the definition
            // every controller indexes into: 14_momo's list is `[parallel0, parallel2, parallel3, parallel1, ...]`,
            // an order that carries no meaning precisely because upstream plays every entry of a state at once.
            int parallelIndex = getParallelIndex(geckoControllerName);
            boolean preParallel = geckoControllerName.startsWith("pre_parallel_");
            String base = preParallel ? "pre_parallel_" : "parallel_";
            names.add("player." + base + parallelIndex);
            names.add(base + parallelIndex);
            names.add("player." + base + "0");
            names.add(base + "0");
        } else if (MAIN_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.main");
            names.add("player.base");
            names.add("player.move");
            names.add("main");
        } else if (HOLD_MAINHAND_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.hold_mainhand");
            names.add("hold_mainhand");
        } else if (HOLD_OFFHAND_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.hold_offhand");
            names.add("hold_offhand");
        } else if (SWING_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.swing");
            names.add("swing");
        } else if (USE_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.use");
            names.add("use");
        } else if (CAP_CONTROLLER.equals(geckoControllerName)) {
            names.add("player.cap");
            names.add("cap");
        }
        names.add(geckoControllerName);
        if (geckoControllerName.startsWith("player.")) {
            names.add(geckoControllerName.substring("player.".length()));
        }
        if (geckoControllerName.endsWith("_controller")) {
            names.add(geckoControllerName.substring(0, geckoControllerName.length() - 11));
        }
        String armorSlot = armorSlotOf(geckoControllerName);
        if (armorSlot != null) {
            names.add("player.armor_" + armorSlot);
            names.add("armor_" + armorSlot);
        }
        return names;
    }

    /**
     * The armor slot a GeckoLib controller stands for - {@code head} / {@code chest} / {@code legs} /
     * {@code feet} - or {@code null} for every other controller. The slot vocabulary comes from the shared
     * table in {@link ConditionArmor} rather than a second copy of it.
     */
    static String armorSlotOf(String geckoControllerName) {
        if (geckoControllerName == null) {
            return null;
        }
        for (int slotIndex = 1; slotIndex <= 4; slotIndex++) {
            String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
            if (!slotName.isEmpty() && geckoControllerName.equals(slotName + "_controller")) {
                return slotName;
            }
        }
        return null;
    }

    private static void addMatch(List<ControllerMatch> matches, ControllerSet set, String controllerName,
        String preferredAnimationName) {
        Controller controller = set.controllers.get(controllerName);
        if (controller == null) {
            return;
        }
        for (ControllerMatch match : matches) {
            if (match.controller == controller) {
                return;
            }
        }
        matches.add(new ControllerMatch(controller, preferredAnimationName));
    }

    /**
     * The clip a parallel controller stands for, by upstream's suffix rule: controller
     * {@code parallel_<i>_controller} plays {@code parallel<i>}, and {@code pre_parallel_<i>_controller} plays
     * {@code pre_parallel<i>} ({@code PlayerControllerCollection.java:31-32,68-69} plus
     * {@code ParallelControllerDiscovery.java:52-70}, which derives the controller name from the animation name).
     * <p>
     * Selecting by name rather than by position is what makes a controller independent of the order of a grouped
     * {@code animations} list - and that order demonstrably carries no meaning, because upstream plays every entry
     * of a state simultaneously ({@code BedrockAnimationController#process} builds one player per entry).
     *
     * @return the clip name, or {@code null} for a controller that is not a parallel one
     */
    static String parallelAnimationName(String geckoControllerName) {
        int parallelIndex = getParallelIndex(geckoControllerName);
        if (parallelIndex < 0) {
            return null;
        }
        return (geckoControllerName.startsWith("pre_parallel_") ? "pre_parallel" : "parallel") + parallelIndex;
    }

    private static int getParallelIndex(String geckoControllerName) {
        if (geckoControllerName == null || !geckoControllerName.endsWith("_controller")) {
            return -1;
        }
        String name = geckoControllerName.substring(0, geckoControllerName.length() - 11);
        String prefix = null;
        if (name.startsWith("pre_parallel_")) {
            prefix = "pre_parallel_";
        } else if (name.startsWith("parallel_")) {
            prefix = "parallel_";
        }
        if (prefix == null) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 具名并行槽位的备用池：{@code (player.)?<族>_extra_<i>_controller} 的第 i 个池控制器
     * 承载该族第 i 个具名槽位（见
     * {@link OpenYsmAnimationControllerRegistry#namedParallelSlots(ResourceLocation, String)}）。
     * <p>
     * 这个映射是**按当前模型**算的：换模型后同一个池下标可能承载另一个槽位，所以不能缓存
     * "下标 → 控制器名"到池控制器上（槽位表本身按模型缓存，见注册表）。
     *
     * @return 路由结果；{@code null} 表示这个名字不是池控制器
     */
    static NamedParallelRoute routeNamedParallel(ResourceLocation animationId, String geckoControllerName) {
        String[] pool = parseNamedParallelPoolController(geckoControllerName);
        if (pool == null) {
            return null;
        }
        String family = pool[0];
        int index = Integer.parseInt(pool[1]);
        java.util.List<String> slots = OpenYsmAnimationControllerRegistry.namedParallelSlots(animationId, family);
        int poolSize = Config.NAMED_PARALLEL_EXTRA_SLOTS;
        if (slots.size() > poolSize) {
            // 模型声明的具名槽位比池子大：多出来的那些没有池控制器承载，永远不会播放。
            // 只警告一次、不静默截断（提高配置项 NamedParallelExtraSlots 即可解决）。
            OpenYsmAnimationControllerRegistry.warnOnce(
                "parallel-extra-overflow:" + animationId + ":" + family,
                animationId + " declares " + slots.size() + " named " + family + " slots but only "
                    + poolSize + " extra pool controllers exist; raise Config NamedParallelExtraSlots"
                    + " (slots=" + slots + ")");
        }
        String slot = index < slots.size() ? slots.get(index) : null;
        String controlSlot = slot == null ? null : family + "_" + slot;
        String controllerKey = slot == null ? null
            : OpenYsmAnimationControllerRegistry.resolveParallelSlotKey(animationId, family, slot);
        return new NamedParallelRoute(family, index, controlSlot, controllerKey, slots.size(), poolSize);
    }

    /**
     * 池控制器本帧应该交给动画控制脚本的**槽位名**（如 {@code parallel_6} / {@code pre_parallel_表情}）。
     * <p>
     * {@code controlSlotName()} 对池名故意返回 null（模型给的槽位名只有这里知道），
     * 所以 {@code AnimationManager} 在具名并行槽位上用这个方法补齐脚本路由；
     * 该槽位只有脚本、没有 JSON 控制器时也能命中脚本。
     *
     * @return 槽位名；不是池控制器、或该模型没有这么多具名槽位时返回 {@code null}
     */
    public static String namedParallelControlSlot(ResourceLocation animationId, String geckoControllerName) {
        NamedParallelRoute route = routeNamedParallel(animationId, geckoControllerName);
        return route == null ? null : route.controlSlot;
    }

    /** 一个具名并行池控制器的路由结果。 */
    static final class NamedParallelRoute {

        final String family;
        final int index;
        /** 该池下标承载的具名槽位对应的控制脚本槽位名（{@code <族>_<后缀>}），没分到为 null。 */
        final String controlSlot;
        /** 该槽位解析出的 ControllerSet 键名（{@code player.<族>_<后缀>} 优先），
         *  只有脚本、没有 JSON 控制器时为 null。 */
        final String controllerKey;
        /** 模型声明的具名槽位数。 */
        final int declaredSlots;
        /** 已注册的池控制器数量（{@code Config.NAMED_PARALLEL_EXTRA_SLOTS}）。 */
        final int poolSize;

        NamedParallelRoute(String family, int index, String controlSlot, String controllerKey,
            int declaredSlots, int poolSize) {
            this.family = family;
            this.index = index;
            this.controlSlot = controlSlot;
            this.controllerKey = controllerKey;
            this.declaredSlots = declaredSlots;
            this.poolSize = poolSize;
        }

        boolean overflow() {
            return declaredSlots > poolSize;
        }
    }

    /** 具名并行槽位的备用池控制器名：{@code (player.)?<族>_extra_<序号>_controller}。 */
    private static final java.util.regex.Pattern PARALLEL_EXTRA_CONTROLLER =
        java.util.regex.Pattern.compile("^(?:player\\.)?(pre_parallel|parallel)_extra_(\\d+)_controller$");

    /**
     * 从 GeckoLib 控制器名里解析出备用池槽位 {族名, 序号字符串}；不是池控制器返回 null。
     * <p>
     * 池名也带 {@code pre_parallel_} / {@code parallel_} 前缀，所以必须先认出它，
     * 否则会掉进 {@link #getParallelIndex} 的数字槽位解析（对 {@code extra_0} 抛
     * NumberFormatException 后返回 -1，什么都不匹配）。
     */
    static String[] parseNamedParallelPoolController(String geckoControllerName) {
        if (geckoControllerName == null) {
            return null;
        }
        Matcher matcher = PARALLEL_EXTRA_CONTROLLER.matcher(geckoControllerName);
        if (!matcher.matches()) {
            return null;
        }
        return new String[] { matcher.group(1), matcher.group(2) };
    }

    /** 槽位后缀控制器的共享备用池控制器名：{@code openysm_slot_extra_<序号>_controller}。 */
    private static final java.util.regex.Pattern SLOT_EXTRA_CONTROLLER =
        java.util.regex.Pattern.compile("^"
            + java.util.regex.Pattern.quote(com.fox.ysmu.util.ControllerUtils.SLOT_EXTRA_CONTROLLER_PREFIX)
            + "(\\d+)_controller$");

    /** 名字是不是槽位后缀备用池控制器。谓词路径每帧都问，所以先用廉价的 startsWith 挡掉绝大多数。 */
    public static boolean isSlotExtraController(String geckoControllerName) {
        return geckoControllerName != null
            && geckoControllerName.startsWith(com.fox.ysmu.util.ControllerUtils.SLOT_EXTRA_CONTROLLER_PREFIX)
            && SLOT_EXTRA_CONTROLLER.matcher(geckoControllerName).matches();
    }

    /**
     * 槽位后缀池控制器的路由：第 i 个池控制器承载当前模型第 i 个
     * {@code player.<slot>_<后缀>}（见
     * {@link OpenYsmAnimationControllerRegistry#slotExtraControllers}）。
     *
     * <p>池控制器名里没有模型信息，所以路由必须按当前模型算——与具名并行槽位同样的理由：
     * {@code registerControllers} 对每个 animatable 只跑一次，而模型可以随时切换。</p>
     *
     * @return 路由到的条目；不是池控制器、或该下标在当前模型里没有对应后缀控制器时返回 {@code null}
     */
    public static OpenYsmAnimationControllerRegistry.SlotExtra routeSlotExtra(ResourceLocation animationId,
        String geckoControllerName) {
        if (animationId == null || geckoControllerName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = SLOT_EXTRA_CONTROLLER.matcher(geckoControllerName);
        if (!matcher.matches()) {
            return null;
        }
        int index = Integer.parseInt(matcher.group(1));
        java.util.List<OpenYsmAnimationControllerRegistry.SlotExtra> entries =
            OpenYsmAnimationControllerRegistry.slotExtraControllers(animationId);
        return index < entries.size() ? entries.get(index) : null;
    }

    /**
     * 池控制器本帧应该交给动画控制脚本的**槽位名**（{@code <slot>_<后缀>}）。
     * <p>
     * {@code AnimationManager.controlSlotName()} 对池名故意返回 null（模型里没有这个槽位名，
     * 真正的名字只有这里知道），所以那里必须补上这次路由 —— 与具名并行槽位
     * （{@link #namedParallelControlSlot}）完全同一个套路。
     *
     * @return 槽位名；不是池控制器、或没路由到条目时返回 {@code null}
     */
    public static String slotExtraControlSlot(ResourceLocation animationId, String geckoControllerName) {
        OpenYsmAnimationControllerRegistry.SlotExtra route = routeSlotExtra(animationId, geckoControllerName);
        return route == null ? null : route.controlSlot;
    }

    /** The prefix the engine registers scoped variables under (`ScopedMolangVariable#getName`). */
    private static final String VARIABLE_PREFIX = "v.";

    static final class RuntimeState {
        String currentState = "";
        String lastAnimation = "";
        String lastSelectedAnimationState = "";
        String lastSelectedAnimation = "";
        double enteredTick;
        boolean lastSwingActive;
        int lastSwingProgress = -1;
        /**
         * Fallback store used only while no engine frame scope is open. Normally {@code v.*} reads and writes go to
         * the engine's per-frame scope ({@link MolangPhysicsRuntime}), which is the same store the animation file's
         * MoLang and its timeline instructions use - that is the A-03 fix: one store for a name, shared between the
         * controllers and the animation, instead of one map per (player, model, controller).
         */
        final Map<String, Double> variables = new ConcurrentHashMap<>();

        /**
         * Writes {@code v.<name>} (name without the {@code v.} prefix). Prefers the engine scope; falls back to the
         * local map when no scope is open, so a controller that writes in one evaluation can still read it in the
         * next.
         * <p>
         * 变量名先过 {@link MolangParser#canonicalVariableName} —— 与动画文件、{@code .molang} 脚本、漫游变量
         * 完全同一条规则；上游 1.20 的每一个标识符也都走这条小写化规则
         * （{@code api/molang/MolangNames#identifier:29-35}）。控制器 JSON 的过渡条件按包里的字面写法读
         * {@code v.is_Idle}，而动画文件时间轴里的 {@code v.is_Idle=1;} 是先被
         * {@code MolangParser.parseExpression} 整体小写化（{@code lowerCaseOutsideStrings}）再存进作用域的，
         * 于是这条轴不改写名字就会拿 {@code v.is_Idle} 去查一个只存着 {@code v.is_idle} 的表：
         * 条件恒为假、控制器永远出不了 initial_state，动画整条不可达。
         * 与 {@code v.roaming.C} 是同一个缺陷，见 AGENTS.md「Molang variable names are case-insensitive」。
         */
        void setVariable(String name, double value) {
            String canonical = MolangParser.canonicalVariableName(name);
            if (!MolangPhysicsRuntime.setVariable(VARIABLE_PREFIX + canonical, value)) {
                variables.put(canonical, value);
            }
        }

        /**
         * Reads {@code v.<name>} (name without the {@code v.} prefix). The engine scope wins over the local
         * fallback, and when neither has the name {@code fallback} is returned. The name is canonicalised first,
         * for the reason given on {@link #setVariable} - the local fallback map is keyed the same way, so both
         * stores answer to every spelling Molang accepts.
         */
        double getVariable(String name, double fallback) {
            String canonical = MolangParser.canonicalVariableName(name);
            Double local = variables.get(canonical);
            return MolangPhysicsRuntime
                .getVariable(VARIABLE_PREFIX + canonical, local == null ? fallback : local);
        }
    }

    /** 包内可见：{@link OpenYsmControllerDefinitions.ControllerSet#routeCache} 缓存它，单测读 {@code controller.name}。 */
    static final class ControllerMatch {
        private final Controller controller;
        /**
         * The clip this controller is expected to play, or {@code null} when the definition's own state picks it.
         * Only parallel controllers set it (see {@link #parallelAnimationName}).
         */
        private final String preferredAnimationName;

        private ControllerMatch(Controller controller, String preferredAnimationName) {
            this.controller = controller;
            this.preferredAnimationName = preferredAnimationName;
        }
    }

    /**
     * How long one animation takes to play through, in ticks - its period, for the purpose of scheduling repeated
     * timeline events.
     * <p>
     * Package-visible because {@link ProjectileTimelineRuntime} must reach the same answer this path does; two
     * independent guesses at "how often does this animation loop" would make a projectile's trail pulse at a
     * different rate than the same animation on a player.
     * <p>
     * A declared {@code animation_length} wins. Otherwise the longest bone channel is used, and a clip with no
     * keyframe timing at all answers 0, which callers treat as "no period" rather than as a real one.
     * <p>
     * One upstream input is deliberately absent: {@code anim_time_update}, which lets an animation drive its own
     * clock. The engine does not parse that field yet, so an animation using it is timed by its declared length here
     * - which is also what the engine actually plays, so the schedule still matches what is on screen.
     */
    static double playbackLengthTicks(software.bernie.geckolib3.core.builder.Animation animation) {
        if (animation == null) {
            return 0.0d;
        }
        Double declared = animation.animationLength;
        if (declared != null && declared > 0.0d && declared < Double.MAX_VALUE) {
            return declared;
        }
        double longest = 0.0d;
        if (animation.boneAnimations != null) {
            for (software.bernie.geckolib3.core.keyframe.BoneAnimation bone : animation.boneAnimations) {
                longest = Math.max(longest, channelLengthTicks(bone.rotationKeyFrames));
                longest = Math.max(longest, channelLengthTicks(bone.positionKeyFrames));
                longest = Math.max(longest, channelLengthTicks(bone.scaleKeyFrames));
            }
        }
        return longest;
    }

    /** The total duration of one keyframe channel in ticks; the three axes share a length. */
    private static double channelLengthTicks(
        software.bernie.geckolib3.core.keyframe.VectorKeyFrameList<software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue>> channel) {
        if (channel == null || channel.xKeyFrames == null || channel.xKeyFrames.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue> frame : channel.xKeyFrames) {
            total += frame.getLengthPrimitive();
        }
        return total;
    }

    private static final class StateKey {
        private final UUID playerId;
        private final ResourceLocation animationId;
        private final String geckoControllerName;
        private final String openYsmControllerName;

        private StateKey(UUID playerId, ResourceLocation animationId, String geckoControllerName,
            String openYsmControllerName) {
            this.playerId = playerId;
            this.animationId = animationId;
            this.geckoControllerName = geckoControllerName;
            this.openYsmControllerName = openYsmControllerName;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof StateKey)) {
                return false;
            }
            StateKey other = (StateKey) obj;
            return playerId.equals(other.playerId) && animationId.equals(other.animationId)
                && geckoControllerName.equals(other.geckoControllerName)
                && openYsmControllerName.equals(other.openYsmControllerName);
        }

        @Override
        public int hashCode() {
            int result = playerId.hashCode();
            result = 31 * result + animationId.hashCode();
            result = 31 * result + geckoControllerName.hashCode();
            result = 31 * result + openYsmControllerName.hashCode();
            return result;
        }
    }
}
