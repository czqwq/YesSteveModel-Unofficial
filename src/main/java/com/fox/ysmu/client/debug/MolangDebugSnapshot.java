package com.fox.ysmu.client.debug;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator;
import com.fox.ysmu.client.animation.molang.EntityQueryScope;
import com.fox.ysmu.client.gui.debug.DebugOverlay;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * 捕获并格式化 Molang 变量快照，用于调试命令和 debug overlay。
 * 所有方法仅应在客户端调用。
 *
 * <p><b>移植注（与参考分支的偏离）：</b>参考分支的 {@code MolangPhysicsRuntime#end()}
 * （{@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/molang/MolangPhysicsRuntime.java:244-250}）
 * 每一帧都无条件把整张 {@code v.*} 作用域复制进一个新的 {@code HashMap}，只为让本类和
 * {@code /ysm query} 事后能读到值 —— 这是正常游戏里也照付的渲染热路径分配。本仓改为
 * <b>按需捕获</b>：只有 overlay 打开、或一次 query/reset 正在进行时才请求引擎捕获
 * （见 {@link #refreshCaptureDemand()}），并且整套功能挂在默认关闭的
 * {@link Config#DEBUG_MOLANG_OVERLAY} 之后。三处记录偏离的位置：{@code Config} 的字段 javadoc、
 * 引擎捕获点的注释、以及 {@code .agent/} 的 divergence 一节。</p>
 */
public final class MolangDebugSnapshot {

    private MolangDebugSnapshot() {}

    /** 聊天前缀 */
    public static final String CHAT_PREFIX = "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r";

    /**
     * 聊天前缀 + 一条可翻译消息。
     * <p>
     * 用 {@link net.minecraft.util.ChatComponentTranslation} 而不是在这里 {@code I18n.format} 成字符串：
     * 翻译发生在聊天框渲染时（{@code getUnformattedText()}），构造本身不碰客户端的语言表，所以单测 JVM
     * （没有客户端，{@code I18n} 的 {@code i18nLocale} 为 null）也能安全构造这些消息，
     * 命令与包处理器的分支因此可测。
     */
    public static net.minecraft.util.IChatComponent prefixed(String key, Object... args) {
        return new ChatComponentText(CHAT_PREFIX + " ")
            .appendSibling(new net.minecraft.util.ChatComponentTranslation(key, args));
    }

    // ---- 按需捕获（见类 javadoc 的移植注） ----

    /** 一次 query 请求正在进行：要求引擎在下一次 {@code end()} 时捕获帧快照。 */
    private static volatile boolean queryCaptureRequested = false;

    /**
     * {@link #localPlayerOrNull()} 的取数点。包内可见、可替换，<b>只为单元测试存在</b>；生产代码从不设置它。
     * <p>
     * 单元测试 JVM 的 runtime classpath 没有 lwjgl（它只是编译期/开发期依赖），{@code Minecraft} 这个类在那里
     * <b>连链接都做不到</b>：调用点第一次触碰它就抛
     * {@code NoClassDefFoundError: org/lwjgl/LWJGLException}（链路见
     * {@code .agent/phase37-unit-jvm-test-repair.md}），而不是返回下面这个 {@code null}。而"没有客户端"恰恰是
     * 这些调用点<em>已经</em>写好的分支，所以把测试环境的这一事实接进取数点，那些分支才测得到。
     */
    static volatile Supplier<EntityPlayer> localPlayerSource;

    /**
     * 现在是否有什么东西需要帧快照：debug overlay 打开着，或一次 query 正在请求。
     * 纯静态判断，不碰 Minecraft，因此可单测。
     */
    public static boolean isCaptureDemanded() {
        return queryCaptureRequested || DebugOverlay.isActive();
    }

    /**
     * 把"是否需要帧快照"同步给引擎。只有 {@link Config#DEBUG_MOLANG_OVERLAY} 打开
     * <b>且</b> {@link #isCaptureDemanded()} 为真时才请求捕获：开关关闭时引擎一次都不复制，
     * 这就是"默认安装不付这个代价"的落点。
     */
    public static void refreshCaptureDemand() {
        MolangPhysicsRuntime.setSnapshotCaptureEnabled(Config.DEBUG_MOLANG_OVERLAY && isCaptureDemanded());
    }

    /**
     * 一次 query 开始：请求捕获。帧快照是"上一帧 {@code end()} 时捕获的那一份"，所以请求之后的
     * 读取必须等到下一帧渲染完再做（见 {@code PacketQueryMolangVar.Handler} 的两跳调度），
     * 否则读到的是请求之前的旧表。
     */
    public static void requestCapture() {
        queryCaptureRequested = true;
        refreshCaptureDemand();
    }

    /** 一次 query 结束：撤销 {@link #requestCapture()}。 */
    public static void releaseCapture() {
        queryCaptureRequested = false;
        refreshCaptureDemand();
    }

    /** 是否有 query 正在请求捕获（诊断/单测用）。 */
    public static boolean isQueryCaptureRequested() {
        return queryCaptureRequested;
    }

    /**
     * 搜索别名展开：把开头的 "q."（不区分大小写）当作 "query." 处理，
     * 供 debug overlay 过滤框和 /ysm query 命令共用。
     * 例："q.any_animation_finished" → "query.any_animation_finished"。
     */
    public static String expandSearchAlias(String text) {
        if (text == null || text.length() < 2) return text;
        if ((text.charAt(0) == 'q' || text.charAt(0) == 'Q') && text.charAt(1) == '.') {
            return "query." + text.substring(2);
        }
        return text;
    }

    /**
     * 读取单个变量的当前值。
     *
     * <p><b>读取带实体。</b>本类每次可能落到 {@link MolangParser#VARIABLES} 上的读取，都套在
     * {@link #publishForRead(EntityPlayer)} 里：那张表里的 {@code query.*}/{@code ysm.*} 是"最后一次 apply 的那个
     * 实体"的闭包（{@code EntityQueryBindings.apply}），而 overlay 画在帧末、{@code /ysm query} 在客户端 tick 里
     * 读，两者都不是绘制本身 —— 没有这一层，它们读到的是本帧最后画的那个可绑定实体（可能是载具或箭），不是被问的
     * 玩家。上游没有这个问题：它的每次读取都带 {@code IContext.entity()}
     * （{@code geckolib3/core/molang/context/IContext.java:23-24}），本类的读取点就是那句结构在本仓的落点。</p>
     *
     * @param name 变量名，如 {@code "ysm.person_view"}、{@code "query.health"}、{@code "v.roaming.bq_eye"}
     * @return 变量值，如果未知则返回 {@code NaN}
     */
    public static double queryVariable(String name) {
        // 语法糖：q. → query.，/ysm query 可直接输 q.xxx
        name = expandSearchAlias(name);
        try (EntityQueryScope.Publication ignored = publishForRead(localPlayerOrNull())) {
            // ctrl.* 必须最先检查 —— MolangParser.VARIABLES 中可能有
            // computeIfAbsent 残留的默认值 0.0 条目，会覆盖动态评估结果。
            if (name.startsWith("ctrl.")) {
                    EntityPlayer p = localPlayerOrNull();
                if (p == null) return Double.NaN;
                return OpenYsmControllerExpressionEvaluator
                    .evaluateCtrlState(name.substring("ctrl.".length()), p);
            }
            // 动画完成查询是控制器条件里动态计算的，MolangParser.VARIABLES 里的
            // 静态注册值恒为 0，这里返回控制器求值缓存的最新真实值。
            if ("query.any_animation_finished".equals(name)) {
                return OpenYsmControllerExpressionEvaluator.getLastAnyAnimationFinished();
            }
            if ("query.all_animations_finished".equals(name)) {
                return OpenYsmControllerExpressionEvaluator.getLastAllAnimationsFinished();
            }
            // 1. 静态注册变量 (query.* / ysm.* / math.*)
            software.bernie.geckolib3.core.molang.LazyVariable var =
                MolangParser.VARIABLES.get(name);
            if (var != null) {
                return var.get();
            }
            // 2. v.* 变量 — 先查最近一帧 ScopeState 快照（嵌套赋值/动画 timeline
            //    写入的真实值，如 v.wet），再查本地玩家的漫游变量（GUI/轮盘设置的
            //    值），最后回退 MolangParser.VARIABLES（顶层赋值写入的）。
            if (name.startsWith("v.")) {
                Double frameVal = MolangPhysicsRuntime.getLastFrameVariable(name);
                if (frameVal != null) return frameVal;
                Double roamingVal = roamingVariables().get(name);
                if (roamingVal != null) return roamingVal;
                // 也查一下 MolangParser.VARIABLES（InstrutionExecutor 写入的）
                software.bernie.geckolib3.core.molang.LazyVariable gVar =
                    MolangParser.VARIABLES.get(name);
                if (gVar != null) return gVar.get();
                return Double.NaN;
            }
            return Double.NaN;
        }
    }

    /**
     * 一次读取期间，把 {@code player} 的 {@code query.*} / {@code ysm.*} 发布进共享表，读完还原。
     *
     * <p>这就是"读取永远带实体"在本仓的等价实现（上游 {@code IContext.entity()}，见
     * {@code geckolib3/core/molang/context/IContext.java:23-24}）：帧内的绘制有自己的发布时序，帧外的读取在这里自己
     * 发布。{@code player} 为 null（未进世界、或单测 JVM 里没有客户端，见 {@link #localPlayerOrNull()}）时返回空
     * 发布，调用点本来就写好的分支不变。</p>
     */
    private static EntityQueryScope.Publication publishForRead(EntityPlayer player) {
        if (player == null) {
            return EntityQueryScope.NOTHING;
        }
        return EntityQueryScope.publishForRead(
            software.bernie.geckolib3.resource.GeckoLibCache.getInstance().parser,
            player);
    }

    /**
     * 批量读取一组变量。
     */
    public static Map<String, Double> queryVariables(List<String> names) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String name : names) {
            double value = queryVariable(name);
            if (!Double.isNaN(value)) {
                result.put(name, value);
            }
        }
        return result;
    }

    /**
     * 查询匹配通配符模式的所有变量。
     * 支持: {@code "ysm.*"}, {@code "query.is_*"}, {@code "v.roaming.*"}
     */
    public static Map<String, Double> queryWildcard(String pattern) {
        pattern = expandSearchAlias(pattern);
        Map<String, Double> result = new LinkedHashMap<>();
        try (EntityQueryScope.Publication ignored = publishForRead(localPlayerOrNull())) {
            if (!pattern.endsWith("*")) {
                double v = queryVariable(pattern);
                if (!Double.isNaN(v)) result.put(pattern, v);
                return result;
            }
            String prefix = pattern.substring(0, pattern.length() - 1);
            // 扫描 MolangParser.VARIABLES 中的所有注册变量
            for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
                : MolangParser.VARIABLES.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    result.put(entry.getKey(), entry.getValue().get());
                }
            }
            // v.roaming.* 通配符 — 扫描本地玩家的漫游变量
            if ("v.roaming.*".equals(pattern) || "v.*".equals(pattern)) {
                for (Map.Entry<String, Double> entry : roamingVariables().entrySet()) {
                    result.put(entry.getKey(), entry.getValue());
                }
                // 补充最近一帧 ScopeState 快照：动画嵌套赋值（如 v.wet）写入的值
                // 不在漫游表里，也不在 MolangParser.VARIABLES，需从这里读。
                for (Map.Entry<String, Double> entry
                    : MolangPhysicsRuntime.getLastFrameVariables().entrySet()) {
                    if ("v.*".equals(pattern) || entry.getKey().startsWith("v.roaming.")) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            // query.* 通配符 — 补充动画完成查询的真实动态值
            if (prefix.startsWith("query.")) {
                addAnimationFinishedEntries(result);
            }
            return result;
        }
    }

    /**
     * 把动画完成查询的真实动态值写入 result：覆盖全局 query.any/all_animation_finished
     * （MolangParser.VARIABLES 里的静态注册值恒为 0），并补充各控制器单独的结果
     * （query.<q>@<geckoControllerName>）。
     */
    private static void addAnimationFinishedEntries(Map<String, Double> result) {
        result.put("query.any_animation_finished",
            OpenYsmControllerExpressionEvaluator.getLastAnyAnimationFinished());
        result.put("query.all_animations_finished",
            OpenYsmControllerExpressionEvaluator.getLastAllAnimationsFinished());
        for (Map.Entry<String, Double> e : OpenYsmControllerExpressionEvaluator.getLastAnimationFinished().entrySet()) {
            int sep = e.getKey().indexOf('|');
            if (sep > 0) {
                result.put("query." + e.getKey().substring(sep + 1) + "@" + e.getKey().substring(0, sep),
                    e.getValue());
            }
        }
    }

    // ---- 聊天输出 ----

    /**
     * 获取变量当前值的来源模型（debug overlay 最右列显示 @模型来源）。
     * 用于定位跨模型串变量：重名 v.*（如 v.roaming.a）若被其他模型
     * 的 timeline 写入，残留来源会在这里显示。
     * 优先级：
     *  1. 引擎记录的写入来源（{@code MolangPhysicsRuntime#noteGlobalVarOwner}）
     *  2. 其余 v.*：当前玩家模型
     * 非 v.*（query/ctrl/ysm/math 等函数与注册项）返回 null，overlay 保持类型提示。
     *
     * <p><b>移植注：</b>参考分支的 {@code getGlobalVarSource} 直接返回显示名（它的引擎里带着
     * 一张 {@code MODEL_DISPLAY_NAMES} 表）。引擎不能引用宿主
     * （{@code software/bernie/geckolib3/util/GeoStats.java:19}），所以本仓的引擎那一侧只记
     * "写入者的模型 id"，显示名解析留在宿主这里，走 {@link ModelIdUtil} 同一个解码规则。</p>
     */
    public static String getVariableSource(String name) {
        if (name == null) {
            return null;
        }
        // 1. 引擎记录的写入来源（跨模型残留的来源模型）
        String src = MolangPhysicsRuntime.getGlobalVarSource(name);
        if (src != null && !src.isEmpty()) {
            return modelDisplayNameOf(src);
        }
        // 2. 其余 v.*：当前玩家模型（scope / roaming 均由当前模型提供）
        if (name.startsWith("v.")) {
            EntityPlayer p = localPlayerOrNull();
            if (p != null) {
                ExtendedModelInfo eep = ExtendedModelInfo.get(p);
                if (eep != null && eep.getModelId() != null) {
                    return modelDisplayName(eep.getModelId());
                }
            }
        }
        return null;
    }

    /**
     * 获取所有变量的完整快照（用于 overlay 全量显示）。
     * 包括 query.* / ysm.* / math.* / ctrl.* / v.roaming.*
     */
    public static Map<String, Double> getAllVariables() {
        return getAllVariables(localPlayerOrNull());
    }

    /**
     * 变量快照。{@code player} 只影响两段 —— {@code v.roaming.*} 与动态 {@code ctrl.*} ——
     * 传 null 时两段都走调用点本来就写好的"没有本地玩家"分支（空漫游表、不评估 ctrl.*）。
     * <p>
     * 把玩家变成参数，是为了让 overlay 的状态机能在没有客户端的 JVM 里被驱动：单测 JVM 解析不了
     * {@link #localPlayerOrNull()}（见那里的说明），而 {@code null} 正是那个环境的事实状态。生产路径
     * {@link #getAllVariables()} 传入的就是本地玩家，行为不变。
     */
    public static Map<String, Double> getAllVariables(EntityPlayer player) {
        Map<String, Double> result = new LinkedHashMap<>();
        // 见 queryVariable 的说明：整趟读取期间发布 player 自己的 query.*/ysm.*，读完还原。overlay 每帧只走这一趟，
        // 所以发布/还原各一次 —— 表里的值原本是本帧最后画的那个实体的。player 为 null 时是空发布，行为不变。
        try (EntityQueryScope.Publication ignored = publishForRead(player)) {
            // MolangParser.VARIABLES 中的静态注册变量
            for (Map.Entry<String, LazyVariable> entry : MolangParser.VARIABLES.entrySet()) {
                result.put(entry.getKey(), entry.getValue().get());
            }
            // v.roaming.* 漫游变量
            result.putAll(roamingVariables(player));
            // 最近一帧 ScopeState 快照 — 覆盖 VARIABLES 中的默认值，反映动画嵌套赋值
            // （如 v.wet）写入的真实帧值，并补充非 roaming 的 v.*（如 v.anim_ctrl）。
            for (Map.Entry<String, Double> entry : MolangPhysicsRuntime.getLastFrameVariables().entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            // ctrl.* 动态状态 — 使用 evaluator 的实时评估
            if (player != null) {
                // 状态名与判定都来自控制器路径那一份，别在这里再抄一个列表
                // （抄漏 walk 就会显示"走路时 ctrl.idle=1"）。
                for (String s : OpenYsmControllerExpressionEvaluator.CONTROLLER_STATE_NAMES) {
                    result.put("ctrl." + s,
                        OpenYsmControllerExpressionEvaluator.evaluateCtrlState(s, player));
                }
            }
            // 覆盖静态注册的动画完成查询，显示控制器条件求值出的真实动态值，
            // 并补充各控制器单独的结果（query.<q>@<geckoControllerName>）。
            addAnimationFinishedEntries(result);
            return result;
        }
    }

    /**
     * 在聊天框输出一组变量值。
     *
     * <p>消息由 {@link #prefixed(String, Object...)} 构造（前缀 + 一条 {@code ChatComponentTranslation}），
     * 而不是在这里拼 {@code I18n.format} 的字符串：翻译发生在渲染时，构造本身不碰客户端语言表，
     * 单测 JVM（没有客户端）里也能安全走完这些分支。</p>
     */
    public static void printToChat(Map<String, Double> values) {
        EntityPlayer player = localPlayerOrNull();
        if (player == null) return;
        if (values.isEmpty()) {
            player.addChatMessage(prefixed("message.yes_steve_model.debug.no_match"));
            return;
        }
        player.addChatMessage(prefixed("message.yes_steve_model.debug.count", values.size()));
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            player.addChatMessage(prefixed(
                "message.yes_steve_model.debug.item", entry.getKey(), formatValue(entry.getValue())));
        }
    }

    /**
     * 解析变量名，支持精确匹配、通配符(*)前缀匹配、以及模糊子串匹配。
     *
     * @param pattern 变量名模式
     * @return 匹配的变量名 → 值的映射
     */
    public static Map<String, Double> resolveVariables(String pattern) {
        // 语法糖：q. → query.，/ysm query 可直接输 q.xxx
        pattern = expandSearchAlias(pattern);
        // 见 queryVariable 的说明：一次 /ysm query 的整趟匹配都在同一个实体作用域里读，读完还原。
        try (EntityQueryScope.Publication ignored = publishForRead(localPlayerOrNull())) {
            // 1. 精确匹配
            double exact = queryVariable(pattern);
            if (!Double.isNaN(exact)) {
                Map<String, Double> result = new LinkedHashMap<>();
                result.put(pattern, exact);
                return result;
            }
            // 2. 通配符 * 前缀匹配
            if (pattern.contains("*")) {
                String prefix = pattern.substring(0, pattern.lastIndexOf('*'));
                Map<String, Double> result = new LinkedHashMap<>();
                for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
                    : MolangParser.VARIABLES.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        result.put(entry.getKey(), entry.getValue().get());
                    }
                }
                // v.roaming.* 通配符
                if ((prefix.equals("v.") || prefix.equals("v.roaming.")) && result.isEmpty()) {
                    result.putAll(roamingVariables());
                }
                // ctrl.* 通配符 — 使用动态评估
                if (prefix.equals("ctrl.")) {
                    addDynamicCtrlStates(result);
                }
                // query.* 通配符 — 补充动画完成查询的真实动态值
                if (prefix.startsWith("query.")) {
                    addAnimationFinishedEntries(result);
                }
                if (!result.isEmpty()) return result;
            }
            // 3. 模糊子串匹配（不包含通配符但也不是精确匹配）
            Map<String, Double> result = new LinkedHashMap<>();
            String lower = pattern.toLowerCase(java.util.Locale.ROOT);
            for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
                : MolangParser.VARIABLES.entrySet()) {
                if (entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                    result.put(entry.getKey(), entry.getValue().get());
                }
            }
            // v. 模糊匹配 — 扫描本地玩家的漫游变量
            if (lower.startsWith("v.") || lower.startsWith("v.roaming.")) {
                String needle = lower.startsWith("v.roaming.") ? lower.substring(10) : lower.substring(2);
                for (Map.Entry<String, Double> entry : roamingVariables().entrySet()) {
                    if (entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            // ctrl.* 模糊匹配 — 使用动态评估
            if (lower.startsWith("ctrl.")) {
                addDynamicCtrlStates(result);
            }
            // 模糊匹配动画完成查询 — 用真实动态值覆盖静态 0
            if (lower.contains("animation_finished")) {
                addAnimationFinishedEntries(result);
            }
            return result;
        }
    }

    /** 把动态 ctrl.* 状态评估结果加入 result */
    private static void addDynamicCtrlStates(Map<String, Double> result) {
        EntityPlayer p = localPlayerOrNull();
        if (p == null) return;
        for (String s : OpenYsmControllerExpressionEvaluator.CONTROLLER_STATE_NAMES) {
            result.put("ctrl." + s,
                OpenYsmControllerExpressionEvaluator.evaluateCtrlState(s, p));
        }
    }

    /**
     * 求值一个 Molang 表达式/函数并把结果显示在聊天框。
     * 供 {@code /ysm debug eval <expression>} 使用：输入任意函数或表达式
     * （如 {@code math.floor(3.7)}、{@code math.clamp(5, 0, 2) + 1}），
     * 输出其返回值。解析/求值在客户端进行（MolangParser 为客户端单例）。
     */
    public static void evalToChat(String expression) {
        EntityPlayer player = localPlayerOrNull();
        if (player == null) return;
        if (expression == null || expression.trim().isEmpty()) {
            player.addChatMessage(prefixed("message.yes_steve_model.debug.eval_usage"));
            return;
        }
        // eval 是一次性求值：解析未知 token（如 "25e"）时 MolangParser.getVariable()
        // 会用 computeIfAbsent 向全局静态 VARIABLES 写入默认 0 的残留条目，
        // 永久污染 debug overlay 的数据源。先快照求值前的 key，结束后清理
        // 本次新建的条目（未知变量按 Molang 语义求值为 0，但不应残留变量表）。
        java.util.Set<String> keysBefore =
            new java.util.HashSet<>(software.bernie.geckolib3.core.molang.MolangParser.VARIABLES.keySet());
        // 实体上下文：渲染路径每帧写入 ParticleEffectUtil.currentEntity，聊天框里求值时没有渲染，
        // 于是 query.* / ysm.* 里依赖实体的那批函数（is_item_name_any、relative_block_has_any_tag、
        // equipped_enchantment_level、position …）都会拿到 null 而返回 0，看起来像"功能没实现"。
        // 求值期间借用本地玩家，结束后还原，让 /ysm debug eval 成为这些函数的验证入口。
        // 变量面同理（上面的 publishForRead）：求值时 query.health 这类名字在这张表里指向的也必须是本地玩家，
        // 而不是本帧最后画的那个实体。
        try (EntityQueryScope.Publication queries = publishForRead(player)) {
            net.minecraft.entity.Entity previousEntity =
                com.fox.ysmu.client.particle.ParticleEffectUtil.getCurrentEntity();
            com.fox.ysmu.client.particle.ParticleEffectUtil.setCurrentEntity(player);
            try {
                software.bernie.geckolib3.core.molang.MolangParser parser =
                    software.bernie.geckolib3.resource.GeckoLibCache.getInstance().parser;
                software.bernie.geckolib3.core.molang.expressions.MolangExpression expr =
                    parser.parseExpression(expression);
                double result = expr.get();
                player.addChatMessage(prefixed(
                    "message.yes_steve_model.debug.eval_result", sanitize(expression), formatValue(result)));
            } catch (Exception e) {
                player.addChatMessage(prefixed(
                    "message.yes_steve_model.debug.eval_error", sanitize(expression),
                    sanitize(String.valueOf(e.getMessage()))));
            } finally {
                com.fox.ysmu.client.particle.ParticleEffectUtil.setCurrentEntity(previousEntity);
                java.util.List<String> added = new java.util.ArrayList<>();
                for (String key : software.bernie.geckolib3.core.molang.MolangParser.VARIABLES.keySet()) {
                    if (!keysBefore.contains(key)) {
                        added.add(key);
                    }
                }
                for (String key : added) {
                    software.bernie.geckolib3.core.molang.MolangParser.VARIABLES.remove(key);
                }
            }
        }
    }

    /**
     * 去掉字符串里的 MC 格式码字符（0xA7），防止回显时被渲染成颜色/格式码。
     * eval 的表达式是用户输入，含 {@code §} 时应原样显示而非变成聊天格式码。
     */
    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replace('\u00a7', '&');
    }

    /**
     * 在聊天框输出变量值，支持通配符和模糊匹配。
     */
    public static void printSingleToChat(String name) {
        Map<String, Double> resolved = resolveVariables(name);
        EntityPlayer player = localPlayerOrNull();
        if (player == null) return;
        if (resolved.isEmpty()) {
            player.addChatMessage(prefixed("message.yes_steve_model.debug.single_unknown", name));
        } else if (resolved.size() == 1 && resolved.containsKey(name)) {
            // 精确匹配单个变量
            double value = resolved.get(name);
            player.addChatMessage(prefixed("message.yes_steve_model.debug.single_value", name, formatValue(value)));
        } else {
            // 多个匹配结果
            player.addChatMessage(prefixed("message.yes_steve_model.debug.matching", resolved.size(), name));
            for (Map.Entry<String, Double> entry : resolved.entrySet()) {
                player.addChatMessage(prefixed(
                    "message.yes_steve_model.debug.item", entry.getKey(), formatValue(entry.getValue())));
            }
        }
    }

    // ---- 内部辅助 ----

    /** 本地玩家，或 null（未进世界，或单测 JVM 里没有客户端）。 */
    public static EntityPlayer localPlayerOrNull() {
        Supplier<EntityPlayer> installed = localPlayerSource;
        if (installed != null) {
            return installed.get();
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null ? null : mc.thePlayer;
    }

    /**
     * 本地玩家当前模型上的漫游变量（{@code v.roaming.<name> -> 值}）。
     *
     * <p><b>移植注：</b>参考分支读它自己那张全局平坦表
     * {@code OpenYsmPlayerControllerRuntime.PENDING_ROAMING}。本仓的漫游数据按
     * "玩家 + 模型命名空间键"存在 {@code ClientRoamingStore} 里
     * （{@code ClientRoamingStore.valuesFor(UUID, int)}，键由
     * {@code ClientRoamingKeys.keyFor(ResourceLocation)} 从模型内容哈希导出），这里定位同一份数据，
     * 不再造第二张表。差别是这张表只有本地玩家当前模型的那一份命名空间，而参考分支的是一张全局平坦表；
     * 记录在 {@code .agent/} 的 divergence 一节。</p>
     */
    private static Map<String, Double> roamingVariables() {
        return roamingVariables(localPlayerOrNull());
    }

    private static Map<String, Double> roamingVariables(EntityPlayer player) {
        if (player == null) {
            return java.util.Collections.emptyMap();
        }
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep == null || eep.getModelId() == null) {
            return java.util.Collections.emptyMap();
        }
        return ClientRoamingStore.valuesFor(player.getUniqueID(), ClientRoamingKeys.keyFor(eep.getModelId()));
    }

    /**
     * 模型的可读显示名，与参考分支 {@code getModelDisplayName} 同一口径：解码后的名字的最后一段
     * （{@code ysmu:wine_fox_fold/14_momo/main} → {@code 14_momo}）。
     * <p>
     * 参考分支把这个解析放在它自己的 {@code MolangPhysicsRuntime} 里，并在前面插一张
     * {@code MODEL_DISPLAY_NAMES}（lang / ysm.json 名称）表。本仓两者都没有：引擎不得引用宿主
     * （{@code software/bernie/geckolib3/util/GeoStats.java:19}：依赖方向只能是宿主 → 引擎），
     * 所以解析留在宿主这一侧，走 {@link ModelIdUtil} 同一个解码规则，不复制第二份。
     */
    static String modelDisplayName(net.minecraft.util.ResourceLocation modelId) {
        if (modelId == null) {
            return null;
        }
        try {
            // 去掉 /main 等子段，与 BaseModel 的命名一致；也避免 hex 解码被 "/main" 里的 '/' 打断。
            String decoded = ModelIdUtil.getModelDisplayName(ModelIdUtil.getParentModelId(modelId));
            int sep = decoded.lastIndexOf('/');
            return sep >= 0 ? decoded.substring(sep + 1) : decoded;
        } catch (RuntimeException e) {
            return modelId.getResourcePath();
        }
    }

    /** {@link #modelDisplayName} 的字符串入口，用于引擎记录的"写入者模型 id"。 */
    private static String modelDisplayNameOf(String modelIdString) {
        try {
            return modelDisplayName(new net.minecraft.util.ResourceLocation(modelIdString));
        } catch (RuntimeException e) {
            return modelIdString;
        }
    }

    private static String formatValue(double value) {
        if (Double.isNaN(value)) return "\u00a77" + I18n.format("message.yes_steve_model.debug.value.nan");
        if (Double.isInfinite(value)) {
            return "\u00a7c" + I18n.format(value > 0
                ? "message.yes_steve_model.debug.value.pos_inf"
                : "message.yes_steve_model.debug.value.neg_inf");
        }
        if (Math.abs(value) < 0.000001) {
            return "\u00a770.0\u00a7r (" + I18n.format("message.yes_steve_model.debug.value.false") + ")";
        }
        if (Math.abs(value - 1.0) < 0.000001) {
            return "\u00a7a1.0\u00a7r (" + I18n.format("message.yes_steve_model.debug.value.true") + ")";
        }
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return "\u00a7b" + (long) value + ".0";
        }
        return String.format("\u00a7b%.6f", value);
    }
}
