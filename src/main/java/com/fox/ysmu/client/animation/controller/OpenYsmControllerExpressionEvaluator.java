package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.animation.condition.InnerClassify;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.BlockingCompat;
import com.fox.ysmu.compat.EtFuturumCompat;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

public final class OpenYsmControllerExpressionEvaluator {

    private static final double TRUE = 1.0d;
    private static final double FALSE = 0.0d;

    /** Cache of compiled expressions — avoids re-parsing the same string every frame. */
    private static final java.util.concurrent.ConcurrentHashMap<String, CompiledExpr> COMPILED_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 清理编译表达式缓存（模型重载/资源清空时调用），避免按表达式文本累积的
     *  解析结果在长时间运行/反复重载后无限增长。 */
    public static void clearCompiledCache() {
        COMPILED_CACHE.clear();
    }

    /**
     * 各控制器最近一次在条件求值中评估 any/all_animations_finished 的真实结果。
     * key = "<geckoControllerName>|<querySuffix>"，
     * querySuffix ∈ {any_animation_finished, all_animations_finished}。
     *
     * MolangParser.VARIABLES 里注册的 query.any/all_animations_finished 是静态 0
     * （注册时的默认值），并不反映控制器条件里动态计算的结果，因此这里单独缓存
     * 真实值，供 debug overlay / /ysm query 读取。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Double> LAST_ANIMATION_FINISHED =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 全局"最后评估"结果（跨所有控制器），供 query.any/all_animation_finished 的 overlay 显示。 */
    private static volatile double lastAnyAnimationFinished = FALSE;
    private static volatile double lastAllAnimationsFinished = FALSE;

    /** 读取各控制器真实动画完成状态缓存（key 见 LAST_ANIMATION_FINISHED 注释）。 */
    public static java.util.Map<String, Double> getLastAnimationFinished() {
        return LAST_ANIMATION_FINISHED;
    }

    /** 全局最后评估的 query.any_animation_finished 值。 */
    public static double getLastAnyAnimationFinished() {
        return lastAnyAnimationFinished;
    }

    /** 全局最后评估的 query.all_animations_finished 值。 */
    public static double getLastAllAnimationsFinished() {
        return lastAllAnimationsFinished;
    }

    /**
     * 编译后的表达式所需的**最小环境**：只有"读变量"和"调函数"两个查询。
     * <p>
     * 抽成接口有两个好处：编译期只依赖这两个方法（不会顺手摸 event/player），
     * 以及条件求值可以脱离 Minecraft 单测 —— 给一个假的 scope 就能跑真实脚本里的条件
     * （见 {@code OpenYsmConditionEvaluationTest}）。{@link Context} 是它在游戏里的实现。
     */
    interface ConditionScope {
        double variableValue(String name);
        double functionValue(String name, List<Argument> arguments);
    }

    @FunctionalInterface
    interface CompiledExpr {
        double eval(ConditionScope ctx);
    }

    /** Holds a compiled argument for function calls: either a string constant or a compiled expression. */
    static final class CompiledArg {
        final boolean isString;
        final String stringValue;
        final CompiledExpr exprValue;
        private CompiledArg(String s) { isString = true; stringValue = s; exprValue = null; }
        private CompiledArg(CompiledExpr e) { isString = false; stringValue = null; exprValue = e; }
        static CompiledArg ofString(String s) { return new CompiledArg(s); }
        static CompiledArg ofExpr(CompiledExpr e) { return new CompiledArg(e); }
        Argument toArgument(ConditionScope ctx) {
            return isString ? Argument.string(stringValue) : Argument.number(exprValue.eval(ctx));
        }
    }

    /**
     * 编译一条表达式（命中缓存则直接返回）。
     * <p>
     * 这里**不能**用 {@code computeIfAbsent}：函数实参是各自单独编译的（见
     * {@link #parsePrimaryCompiled} 里的 {@code compile(raw)}），也就是编译一条函数调用会在外层
     * 映射函数执行期间再次调用本方法；而 {@code ConcurrentHashMap#computeIfAbsent} 一旦发现同一线程
     * 在同一个桶里重入就抛 {@code IllegalStateException("Recursive update")}。是否重入只看两个字符串
     * 的散列是否落在同一个桶（而且只有桶为空、放了 ReservationNode 的那一条路才会抛），于是同一条模型
     * 表达式会因为"整条表达式的文本"与它某个实参文本恰好同桶而抛异常 —— 例如整条文本
     * {@code ysm.perlin_noise(1,2,3,4)>=0&&...} 与它的第一个实参文本 {@code 1} 都在桶 1。
     * <p>
     * 落到实机上的后果不对称：控制器条件走 {@code evaluateCondition} 没有 catch，异常直接从条件求值里
     * 冒出去；{@code on_entry}/{@code on_exit} 语句走 {@code evaluateNumber}，被吞成 0 并只留一条
     * 警告，也就是那条语句永远不生效。
     * <p>
     * 先 {@code get} 再 {@code putIfAbsent}，把 {@code doCompile} 放在任何桶锁之外：实参文本一定是整条
     * 表达式的**真子串**（{@code readRawArgument} 取的是括号之间的一段），所以递归有穷；并发下最坏情况
     * 是同一条表达式被两个线程各编译一次，两者等价（编译结果是纯的、不可变的）。
     */
    private static CompiledExpr compile(String expression) {
        CompiledExpr cached = COMPILED_CACHE.get(expression);
        if (cached != null) {
            return cached;
        }
        CompiledExpr compiled = doCompile(expression);
        CompiledExpr previous = COMPILED_CACHE.putIfAbsent(expression, compiled);
        return previous != null ? previous : compiled;
    }

    /** Compile an expression string into a reusable CompiledExpr tree. */
    private static CompiledExpr doCompile(String expr) {
        if (StringUtils.isBlank(expr)) {
            return ctx -> TRUE;
        }
        // Use a simple index-based parser that builds a CompiledExpr tree
        int[] idx = {0};
        CompiledExpr result = parseExpressionCompiled(expr, idx);
        return result != null ? result : ctx -> FALSE;
    }

    /**
     * 顶层表达式入口：支持 C 风格三元运算符 (cond?a:b)。
     * 优先级：?: 低于 ||（条件可含 ||），then/else 分支都是完整表达式（可含 ||/?:）。
     * 此前解析器不支持三元，条件 "(v.z_d==0?1:0)&&v.z_cs" 会在 '?' 处停住、
     * 被截断成 "v.z_d==0"（v.z_d 未定义=0 → 恒 true），导致 14_momo 的
     * parallel_4 首帧无条件进入"出来了"遁地状态且无法用 /ysm reset 停止。
     */
    private static CompiledExpr parseExpressionCompiled(String expr, int[] idx) {
        CompiledExpr condition = parseOrCompiled(expr, idx);
        if (condition == null) {
            return null;
        }
        skipWhitespace(expr, idx);
        if (match(expr, idx, "?")) {
            CompiledExpr thenExpr = parseExpressionCompiled(expr, idx);
            skipWhitespace(expr, idx);
            if (match(expr, idx, ":")) {
                CompiledExpr elseExpr = parseExpressionCompiled(expr, idx);
                CompiledExpr c = condition, t = thenExpr, e = elseExpr;
                return ctx -> truthy(c.eval(ctx)) ? t.eval(ctx) : e.eval(ctx);
            }
            // 畸形三元（缺 ':'）——回落为仅条件部分，避免吞掉后续表达式
            return condition;
        }
        return condition;
    }

    private static CompiledExpr parseOrCompiled(String expr, int[] idx) {
        CompiledExpr left = parseAndCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "||")) {
                CompiledExpr right = parseAndCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> truthy(l.eval(ctx)) || truthy(r.eval(ctx)) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseAndCompiled(String expr, int[] idx) {
        CompiledExpr left = parseEqualityCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "&&")) {
                CompiledExpr right = parseEqualityCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> truthy(l.eval(ctx)) && truthy(r.eval(ctx)) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseEqualityCompiled(String expr, int[] idx) {
        CompiledExpr left = parseComparisonCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "==")) {
                CompiledExpr right = parseComparisonCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> {
                    double lv = l.eval(ctx);
                    double rv = r.eval(ctx);
                    // NaN is never equal to anything, not even itself.
                    if (Double.isNaN(lv) || Double.isNaN(rv)) return FALSE;
                    return nearlyEqual(lv, rv) ? TRUE : FALSE;
                };
            } else if (match(expr, idx, "!=")) {
                CompiledExpr right = parseComparisonCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> !nearlyEqual(l.eval(ctx), r.eval(ctx)) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseComparisonCompiled(String expr, int[] idx) {
        CompiledExpr left = parseAdditiveCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, ">=")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) >= r.eval(ctx) ? TRUE : FALSE;
            } else if (match(expr, idx, "<=")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) <= r.eval(ctx) ? TRUE : FALSE;
            } else if (match(expr, idx, ">")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) > r.eval(ctx) ? TRUE : FALSE;
            } else if (match(expr, idx, "<")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) < r.eval(ctx) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseAdditiveCompiled(String expr, int[] idx) {
        CompiledExpr left = parseMultiplicativeCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "+")) {
                CompiledExpr right = parseMultiplicativeCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) + r.eval(ctx);
            } else if (match(expr, idx, "-")) {
                CompiledExpr right = parseMultiplicativeCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) - r.eval(ctx);
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseMultiplicativeCompiled(String expr, int[] idx) {
        CompiledExpr left = parseUnaryCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "*")) {
                CompiledExpr right = parseUnaryCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) * r.eval(ctx);
            } else if (match(expr, idx, "/")) {
                CompiledExpr right = parseUnaryCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> { double d = r.eval(ctx); return d == 0.0d ? 0.0d : l.eval(ctx) / d; };
            } else if (match(expr, idx, "%")) {
                CompiledExpr right = parseUnaryCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> { double d = r.eval(ctx); return d == 0.0d ? 0.0d : l.eval(ctx) % d; };
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseUnaryCompiled(String expr, int[] idx) {
        skipWhitespace(expr, idx);
        if (match(expr, idx, "!")) {
            CompiledExpr operand = parseUnaryCompiled(expr, idx);
            return ctx -> truthy(operand.eval(ctx)) ? FALSE : TRUE;
        }
        if (match(expr, idx, "-")) {
            CompiledExpr operand = parseUnaryCompiled(expr, idx);
            return ctx -> -operand.eval(ctx);
        }
        return parsePrimaryCompiled(expr, idx);
    }

    private static CompiledExpr parsePrimaryCompiled(String expr, int[] idx) {
        skipWhitespace(expr, idx);
        if (idx[0] >= expr.length()) {
            return ctx -> FALSE;
        }
        char c = expr.charAt(idx[0]);
        if (c == '(') {
            idx[0]++;
            CompiledExpr inner = parseExpressionCompiled(expr, idx);
            match(expr, idx, ")");
            return inner;
        }
        if (c == '\'' || c == '"') {
            String s = readQuotedString(expr, idx);
            // 空字符串返回 FALSE (0.0)，使 ctrl.x=='' 能正确表示"变量为空"。
            // 非空字符串返回 NaN，防止 ctrl.tac_gun_type=='rifle' 在无 TacZ 时
            // 误判为 0.0==0.0（所有 TacZ 条件动画同时播放）。
            if (s.isEmpty()) {
                return ctx -> FALSE;
            }
            return ctx -> Double.NaN;
        }
        if (Character.isDigit(c) || (c == '.' && idx[0] + 1 < expr.length()
            && Character.isDigit(expr.charAt(idx[0] + 1)))) {
            return parseNumberCompiled(expr, idx);
        }
        int start = idx[0];
        String identifier = parseIdentifier(expr, idx);
        if (identifier.isEmpty()) {
            idx[0]++;
            return ctx -> FALSE;
        }
        skipWhitespace(expr, idx);
        if (match(expr, idx, "(")) {
            // Function call — compile arguments
            List<CompiledArg> compiledArgs = new ArrayList<>();
            while (true) {
                skipWhitespace(expr, idx);
                if (match(expr, idx, ")")) break;
                if (idx[0] >= expr.length()) break;
                char ac = expr.charAt(idx[0]);
                if (ac == '\'' || ac == '"') {
                    String s = readQuotedString(expr, idx);
                    compiledArgs.add(CompiledArg.ofString(s));
                } else {
                    String raw = readRawArgument(expr, idx);
                    compiledArgs.add(CompiledArg.ofExpr(compile(raw)));
                }
                skipWhitespace(expr, idx);
                if (!match(expr, idx, ",")) {
                    match(expr, idx, ")");
                    break;
                }
            }
            String funcName = identifier;
            return ctx -> {
                List<Argument> evaluated = new ArrayList<>();
                for (CompiledArg a : compiledArgs) {
                    evaluated.add(a.toArgument(ctx));
                }
                return ctx.functionValue(funcName, evaluated);
            };
        }
        // Variable reference
        String varName = identifier;
        return ctx -> ctx.variableValue(varName);
    }

    private static CompiledExpr parseNumberCompiled(String expr, int[] idx) {
        int start = idx[0];
        boolean dotSeen = false;
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (Character.isDigit(c)) {
                idx[0]++;
            } else if (c == '.' && !dotSeen) {
                dotSeen = true;
                idx[0]++;
            } else {
                break;
            }
        }
        double value = Double.parseDouble(expr.substring(start, idx[0]));
        return ctx -> value;
    }

    // Reuse the original evaluator's static helper methods by delegating

    private static void skipWhitespace(String expr, int[] idx) {
        while (idx[0] < expr.length() && expr.charAt(idx[0]) <= ' ') {
            idx[0]++;
        }
    }

    private static boolean match(String expr, int[] idx, String expected) {
        if (expr.regionMatches(idx[0], expected, 0, expected.length())) {
            idx[0] += expected.length();
            return true;
        }
        return false;
    }

    private static String parseIdentifier(String expr, int[] idx) {
        int start = idx[0];
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (Character.isLetter(c) || Character.isDigit(c) || c == '_' || c == '.') {
                idx[0]++;
            } else {
                break;
            }
        }
        return expr.substring(start, idx[0]);
    }

    private static String readQuotedString(String expr, int[] idx) {
        if (idx[0] >= expr.length()) return "";
        char quote = expr.charAt(idx[0]);
        idx[0]++;
        int start = idx[0];
        while (idx[0] < expr.length() && expr.charAt(idx[0]) != quote) {
            idx[0]++;
        }
        String result = expr.substring(start, idx[0]);
        if (idx[0] < expr.length()) idx[0]++; // skip closing quote
        return result;
    }

    private static String readRawArgument(String expr, int[] idx) {
        int start = idx[0];
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (quoted) {
                if (c == quote) quoted = false;
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) break;
                depth--;
            } else if ((c == ',' || c == ';') && depth == 0) {
                break;
            }
            idx[0]++;
        }
        return expr.substring(start, idx[0]);
    }

    private final String expression;
    private final Context context;
    private int index;

    private OpenYsmControllerExpressionEvaluator(String expression, Context context) {
        this.expression = expression == null ? "" : expression;
        this.context = context;
    }

    static boolean evaluateBoolean(String expression, Context context) {
        return evaluateCondition(expression, context);
    }

    static double evaluateNumber(String expression, Context context) {
        try {
            return compile(expression).eval(context);
        } catch (RuntimeException e) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "expr:" + expression,
                "Failed to evaluate OpenYSM controller expression: " + expression + " (" + e.getMessage() + ")");
            // DEBUG_CONTROLLER 下打印完整堆栈，方便定位表达式求值的真实失败点
            //（warnOnce 只警告一次，掩盖了每次触发的上下文）。
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                com.fox.ysmu.ysmu.LOG.debug("Failed to evaluate OpenYSM controller expression: {}", expression, e);
            }
            return FALSE;
        }
    }

    /**
     * 用给定环境求值条件表达式（空白 = true）。包内可见，便于在没有 Minecraft 的测试里
     * 喂一个假 {@link ConditionScope}。
     */
    static boolean evaluateCondition(String expression, ConditionScope scope) {
        if (StringUtils.isBlank(expression)) {
            return true;
        }
        return truthy(compile(expression).eval(scope));
    }

    /**
     * 求值 {@code .molang} 脚本里的条件（供 {@code AnimationManager} 的名称映射使用）。
     * <p>
     * 这里没有控制器状态机上下文（{@code state == null}），{@code v.*} 走共享作用域
     * （脚本写的变量在 {@code MolangPhysicsRuntime}，轮盘/指令写的 roaming 变量在
     * {@code PENDING_ROAMING}）。表达式里的 {@code ctrl.*} / {@code ysm.*} / {@code query.*}
     * 照常按玩家状态求值。
     * <p>
     * 玩家或事件缺失时返回 false：条件无从判断，宁可不用替代动画，也不要凭空命中一条分支。
     */
    public static boolean evaluateCondition(String expression, EntityPlayer player, AnimationEvent<?> event) {
        if (player == null || event == null) {
            return false;
        }
        if (StringUtils.isBlank(expression)) {
            return true;
        }
        try {
            return evaluateCondition(expression, new Context(event, player, null));
        } catch (RuntimeException e) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "molang-cond:" + expression,
                "Failed to evaluate .molang condition: " + expression + " (" + e.getMessage() + ")");
            return false;
        }
    }

    static void executeStatements(List<String> statements, Context context) {
        for (String statement : statements) {
            executeStatement(statement, context);
        }
    }

    private static void executeStatement(String statements, Context context) {
        if (StringUtils.isBlank(statements)) {
            return;
        }
        String[] split = statements.split(";");
        for (String statement : split) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = findAssignmentOperator(trimmed);
            if (equals <= 0) {
                evaluateNumber(trimmed, context);
                continue;
            }
            String target = normalizeVariableName(trimmed.substring(0, equals).trim());
            String valueExpression = trimmed.substring(equals + 1).trim();
            if (target.startsWith("v.")) {
                // A-03: on_entry/on_exit assignments go through the shared variable entry, i.e. into the engine's
                // per-frame scope, the same store the animation file and its timeline instructions use.
                context.state.setVariable(target.substring(2), evaluateNumber(valueExpression, context));
            }
        }
    }

    private double parseExpression() {
        return parseOr();
    }

    private double parseOr() {
        double value = parseAnd();
        while (true) {
            skipWhitespace();
            if (match("||")) {
                double right = parseAnd();
                value = truthy(value) || truthy(right) ? TRUE : FALSE;
            } else {
                return value;
            }
        }
    }

    private double parseAnd() {
        double value = parseEquality();
        while (true) {
            skipWhitespace();
            if (match("&&")) {
                double right = parseEquality();
                value = truthy(value) && truthy(right) ? TRUE : FALSE;
            } else {
                return value;
            }
        }
    }

    private double parseEquality() {
        double value = parseComparison();
        while (true) {
            skipWhitespace();
            if (match("==")) {
                value = nearlyEqual(value, parseComparison()) ? TRUE : FALSE;
            } else if (match("!=")) {
                value = !nearlyEqual(value, parseComparison()) ? TRUE : FALSE;
            } else {
                return value;
            }
        }
    }

    private double parseComparison() {
        double value = parseAdditive();
        while (true) {
            skipWhitespace();
            if (match(">=")) {
                value = value >= parseAdditive() ? TRUE : FALSE;
            } else if (match("<=")) {
                value = value <= parseAdditive() ? TRUE : FALSE;
            } else if (match(">")) {
                value = value > parseAdditive() ? TRUE : FALSE;
            } else if (match("<")) {
                value = value < parseAdditive() ? TRUE : FALSE;
            } else {
                return value;
            }
        }
    }

    private double parseAdditive() {
        double value = parseMultiplicative();
        while (true) {
            skipWhitespace();
            if (match("+")) {
                value += parseMultiplicative();
            } else if (match("-")) {
                value -= parseMultiplicative();
            } else {
                return value;
            }
        }
    }

    private double parseMultiplicative() {
        double value = parseUnary();
        while (true) {
            skipWhitespace();
            if (match("*")) {
                value *= parseUnary();
            } else if (match("/")) {
                double divisor = parseUnary();
                value = divisor == 0.0d ? 0.0d : value / divisor;
            } else if (match("%")) {
                double divisor = parseUnary();
                value = divisor == 0.0d ? 0.0d : value % divisor;
            } else {
                return value;
            }
        }
    }

    private double parseUnary() {
        skipWhitespace();
        if (match("!")) {
            return truthy(parseUnary()) ? FALSE : TRUE;
        }
        if (match("-")) {
            return -parseUnary();
        }
        return parsePrimary();
    }

    private double parsePrimary() {
        skipWhitespace();
        if (index >= expression.length()) {
            return FALSE;
        }
        char c = expression.charAt(index);
        if (c == '(') {
            index++;
            double value = parseExpression();
            match(")");
            return value;
        }
        if (c == '\'' || c == '"') {
            readQuotedString();
            return FALSE;
        }
        if (Character.isDigit(c) || (c == '.' && index + 1 < expression.length()
            && Character.isDigit(expression.charAt(index + 1)))) {
            return parseNumber();
        }
        String identifier = parseIdentifier();
        if (identifier.isEmpty()) {
            index++;
            return FALSE;
        }
        skipWhitespace();
        if (match("(")) {
            List<Argument> arguments = parseArguments();
            return context.functionValue(identifier, arguments);
        }
        return context.variableValue(identifier);
    }

    private List<Argument> parseArguments() {
        List<Argument> arguments = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (match(")")) {
                return arguments;
            }
            if (index >= expression.length()) {
                return arguments;
            }
            char c = expression.charAt(index);
            if (c == '\'' || c == '"') {
                arguments.add(Argument.string(readQuotedString()));
            } else {
                String raw = readRawArgument();
                arguments.add(Argument.number(evaluateNumber(raw, context)));
            }
            skipWhitespace();
            if (match(",")) {
                continue;
            }
            match(")");
            return arguments;
        }
    }

    private String readRawArgument() {
        int start = index;
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        while (index < expression.length()) {
            char c = expression.charAt(index);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                break;
            }
            index++;
        }
        return expression.substring(start, index).trim();
    }

    private String readQuotedString() {
        char quote = expression.charAt(index++);
        StringBuilder out = new StringBuilder();
        while (index < expression.length()) {
            char c = expression.charAt(index++);
            if (c == quote) {
                break;
            }
            if (c == '\\' && index < expression.length()) {
                out.append(expression.charAt(index++));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private double parseNumber() {
        int start = index;
        while (index < expression.length()) {
            char c = expression.charAt(index);
            if (!Character.isDigit(c) && c != '.') {
                break;
            }
            index++;
        }
        return Double.parseDouble(expression.substring(start, index));
    }

    private String parseIdentifier() {
        int start = index;
        while (index < expression.length()) {
            char c = expression.charAt(index);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != '$' && c != ':') {
                break;
            }
            index++;
        }
        return expression.substring(start, index);
    }

    private boolean match(String token) {
        skipWhitespace();
        if (expression.startsWith(token, index)) {
            index += token.length();
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (index < expression.length() && Character.isWhitespace(expression.charAt(index))) {
            index++;
        }
    }

    private static boolean truthy(double value) {
        return Math.abs(value) > 0.000001d;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.000001d;
    }

    private static String normalizeVariableName(String variable) {
        if (variable.startsWith("variable.")) {
            return "v." + variable.substring("variable.".length());
        }
        return variable;
    }

    private static int findAssignmentOperator(String statement) {
        for (int i = 0; i < statement.length(); i++) {
            if (statement.charAt(i) != '=') {
                continue;
            }
            char before = i > 0 ? statement.charAt(i - 1) : 0;
            char after = i + 1 < statement.length() ? statement.charAt(i + 1) : 0;
            if (before != '=' && before != '!' && before != '<' && before != '>' && after != '=') {
                return i;
            }
        }
        return -1;
    }

    /** 当前求值上下文所属的模型 id（用于 roaming 显式标记的模型维度）；
     *  拿不到（event/animatable 缺失或非玩家实体）时返回 null，退化为全局标记。 */
    private static net.minecraft.util.ResourceLocation currentModelId(Context ctx) {
        if (ctx == null || ctx.event == null || ctx.event.getAnimatable() == null) {
            return null;
        }
        Object animatable = ctx.event.getAnimatable();
        if (animatable instanceof CustomPlayerEntity) {
            return ((CustomPlayerEntity) animatable).getAnimation();
        }
        return null;
    }

    /**
     * 控制器路径能回答的 {@code ctrl.*} 状态名。调试叠加层/命令的枚举与
     * {@link #evaluateCtrlState} 的合法性检查都读这一份，避免再抄一份漏项的实现。
     */
    public static final java.util.List<String> CONTROLLER_STATE_NAMES = java.util.Collections.unmodifiableList(
        java.util.Arrays.asList(
            "idle", "death", "sleep", "swim", "climb", "climbing",
            "ladder_up", "ladder_stillness", "ladder_down",
            "ride", "ride_pig", "boat", "sit",
            "elytra_fly", "fly", "swim_stand",
            "attacked", "jump", "sneak", "sneaking", "run", "walk"));

    /**
     * Static helper for debug queries — evaluates a {@code ctrl.*} state using only the player reference, without
     * needing an {@link AnimationEvent} or a {@link OpenYsmPlayerControllerRuntime.RuntimeState}.
     * <p>
     * 必须与控制器路径**同源**：这里以前自己抄了一份判定，{@code ctrl.idle} 的排除列表漏了 {@code walk}/{@code run}
     * （还有 fly/elytra_fly），于是叠加层和 {@code /ysm query} 在走路时显示 {@code ctrl.idle=1}，而模型看到的却是 0。
     * YSM 的 {@code ctrl.*} 是优先级状态机 —— {@code evaluateState} 从最高优先级往下取第一个成立的谓词，run 先于
     * walk、walk 先于 idle（idle 的谓词恒真）—— 走路时命中 walk，idle 必为 0。两套口径不一致时排查会直接走错方向。
     * <p>
     * 没有 {@link AnimationEvent} 时摆臂类状态退化为实际水平位移，这与控制器路径自己的做法一致
     * （{@link Context#limbSwingAmount()} 在 event 为 null 时就走这条路），不引入新的近似。
     *
     * @return 状态成立 1、不成立 0；名字不是已知状态时返回 NaN（调用方据此显示"未知变量"）
     */
    public static double evaluateCtrlState(String name, EntityPlayer player) {
        if (!CONTROLLER_STATE_NAMES.contains(name)) {
            return Double.NaN;
        }
        if (player == null) {
            return FALSE;
        }
        return new Context(null, player, null).isControllerState(name) ? TRUE : FALSE;
    }

    /**
     * 净头偏航（渲染器同口径）：{@code wrapDegrees(rotationYawHead - renderYawOffset)}。
     * 关键帧路径的 {@code ysm.head_yaw}/{@code query.head_x_rotation} 取它的负值。
     */
    private static double netHeadYaw(EntityPlayer player) {
        return net.minecraft.util.MathHelper
            .wrapAngleTo180_float(player.rotationYawHead - player.renderYawOffset);
    }

    static final class Context implements ConditionScope {
        private final AnimationEvent<?> event;
        private final EntityPlayer player;
        private final OpenYsmPlayerControllerRuntime.RuntimeState state;

        Context(AnimationEvent<?> event, EntityPlayer player, OpenYsmPlayerControllerRuntime.RuntimeState state) {
            this.event = event;
            this.player = player;
            this.state = state;
        }

        @Override
        public double variableValue(String name) {
            String normalized = normalizeVariableName(name);
            if ("true".equals(normalized)) {
                return TRUE;
            }
            if ("false".equals(normalized)) {
                return FALSE;
            }
            if (normalized.startsWith("q.")) {
                normalized = "query." + normalized.substring(2);
            }
            if (normalized.startsWith("v.")) {
                return localVariableValue(normalized.substring(2));
            }
            if (normalized.startsWith("query.")) {
                return queryValue(normalized.substring("query.".length()));
            }
            if (normalized.startsWith("ctrl.")) {
                return ctrlValue(normalized.substring("ctrl.".length()));
            }
            if (normalized.startsWith("ysm.")) {
                return ysmValue(normalized.substring("ysm.".length()));
            }
            // math.pi / math.e 也可以写成**变量**（无括号）——参考分支把它放在变量分支里
            // (SOURCE:665-670)，关键帧路径则由 mclib 的 MolangParser 注册成 LazyVariable
            // ("math.pi"/"math.e"，见 MolangParser:158-159)。少了这两条，控制器条件里写
            // math.pi 的模型拿到 0（并留下一条 "Unsupported ... variable" 提示）。
            if ("math.pi".equals(normalized)) {
                return Math.PI;
            }
            if ("math.e".equals(normalized)) {
                return Math.E;
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "var:" + normalized,
                "Unsupported OpenYSM controller variable: " + normalized);
            return FALSE;
        }

        @Override
        public double functionValue(String name, List<Argument> arguments) {
            // 与变量分支同一条缩写规则：Bedrock/YSM 把 q. 当作 query. 的短写。上游把同一张
            // QueryBinding 同时注册在 "query" 与 "q" 下（PrimaryBinding.java:32-33），本仓的关键帧
            // 路径也早就做同一次折叠（MolangParser#createFunction → normalizeVariableName,
            // MolangParser.java:200-208，那里的注释与本条是同一个理由）。参考分支的分支只比较
            // "query.*" 字面量（SOURCE:793-805）—— 少了这一行，包里的短写（例如
            // wine_fox/15_kluonoa 车辆脚本的 q.position_delta(0)）会落到"Unsupported controller
            // function"并恒 0。
            if (name.startsWith("q.")) {
                name = "query." + name.substring(2);
            }
            if ("ctrl.hold".equals(name)) {
                return handMatch(arguments, false, false);
            }
            if ("ctrl.use".equals(name)) {
                return handMatch(arguments, true, false);
            }
            if ("ctrl.swing".equals(name)) {
                return handMatch(arguments, false, true);
            }
            if ("ctrl.ride".equals(name)) {
                // wiki 用法是 ctrl.ride('vehicle'|'passenger', '$实体ID')，所以这里和关键帧/脚本路径共用
                // CtrlRideFunction（AnimationRegister:ctrl.ride，移植上游 RideCheck.java:28-72）。
                // 以前这里只回答"是否骑乘"，于是按 wiki 写的控制器条件（例如只看被骑的是不是猪）会恒为真。
                return rideMatch(arguments);
            }
            // ctrl.armor(slot, matcher)：helper（armorMatch）一直在，缺的只是这一条转发，
            // 于是关键帧/脚本路径（AnimationRegister:268 CtrlArmorFunction）能答、控制器条件恒 0。
            // (reference branch same file:692-694)
            if ("ctrl.armor".equals(name)) {
                return armorMatch(arguments);
            }
            // --- math.* 家族（SOURCE:695-792；上游权威路径见 mathValue 的注释）---
            if (name.startsWith("math.")) {
                return mathValue(name, arguments);
            }
            // --- query.position(axis) / query.position_delta(axis)（SOURCE:793-805）---
            if ("query.position".equals(name) && !arguments.isEmpty()) {
                // 复用本类已有的 queryPositionValue（渲染插值位置）：它此前只有定义、没有调用点。
                return queryPositionValue((int) arguments.get(0).asNumber());
            }
            if ("query.position_delta".equals(name) && !arguments.isEmpty()) {
                // 与关键帧路径同一个取数点：QueryPositionDeltaFunction 读 MolangFrameContext
                // （molang/QueryPositionDeltaFunction.java:38），后者由 AnimationRegister#setParserValue
                // （玩家，AnimationRegister.java:421）与 AnimationManager（非玩家，
                // AnimationManager.java:710）每帧写入，都在控制器条件求值之前。
                // 参考分支把同一个槽放在 QueryPositionDeltaFunction.delta(axis) 静态方法上
                // (SOURCE:796-805)——本仓没有那个方法，MolangFrameContext 就是它的对应物，
                // 这里不再新开一个侧信道。
                return com.fox.ysmu.client.animation.molang.MolangFrameContext
                    .getPositionDelta((int) arguments.get(0).asNumber());
            }
            // --- ysm.* 函数（兼容层取数点，与关键帧路径共用同一个 compat 类）---
            // 键码是 GLFW 的（wiki），1.7.10 上要经 KeyboardCompat 换算成 LWJGL2；
            // 多参数语义是"任一按下即真"。(reference branch same file:807-822)
            if ("ysm.keyboard".equals(name) && arguments.size() >= 1) {
                java.util.List<Double> codes = new java.util.ArrayList<>(arguments.size());
                for (Argument argument : arguments) {
                    codes.add(argument.asNumber());
                }
                return com.fox.ysmu.compat.KeyboardCompat.isAnyKeyDown(codes) ? TRUE : FALSE;
            }
            if ("ysm.mouse".equals(name) && arguments.size() >= 1) {
                java.util.List<Double> buttons = new java.util.ArrayList<>(arguments.size());
                for (Argument argument : arguments) {
                    buttons.add(argument.asNumber());
                }
                return com.fox.ysmu.compat.KeyboardCompat.isAnyMouseButtonDown(buttons) ? TRUE : FALSE;
            }
            // ysm.has_any_curios(槽位, id...)：Curios 在 1.7.10 上用前身 Baubles(Baubles-Expanded)
            // 实现，与关键帧路径共用 BaublesCompat。(reference branch same file:849-861)
            if ("ysm.has_any_curios".equals(name) && !arguments.isEmpty()) {
                java.util.List<String> ids = new java.util.ArrayList<>(arguments.size());
                for (int i = 1; i < arguments.size(); i++) {
                    String id = arguments.get(i).asString();
                    if (id != null && !id.isEmpty()) {
                        ids.add(id);
                    }
                }
                return com.fox.ysmu.compat.BaublesCompat.hasAnyCurio(player, arguments.get(0).asString(), ids)
                    ? TRUE
                    : FALSE;
            }
            // query.equipped_item_any_tag / all_tags：标签匹配与关键帧路径共用 ItemTagMatcher，
            // 槽位解析共用 MolangEquipmentSlots。(reference branch same file:862-889)
            // 没有玩家（脱离帧的单测、无实体的调用点）时取不到槽位，按假处理，不再往下走。
            if (("query.equipped_item_any_tag".equals(name) || "query.equipped_item_all_tags".equals(name))
                && arguments.size() >= 2) {
                if (player == null) {
                    return FALSE;
                }
                String slotType = arguments.get(0).asString();
                ItemStack stack = com.fox.ysmu.client.animation.molang.MolangEquipmentSlots.get(player, slotType);
                if (stack == null || stack.getItem() == null) {
                    return FALSE;
                }
                boolean requireAll = name.endsWith("all_tags");
                boolean matchedAny = false;
                int checked = 0;
                for (int i = 1; i < arguments.size(); i++) {
                    String tag = arguments.get(i).asString();
                    if (tag == null || tag.isEmpty()) {
                        continue;
                    }
                    checked++;
                    boolean matched = com.fox.ysmu.client.animation.molang.ItemTagMatcher.matchesTag(stack, tag);
                    if (matched && !requireAll) {
                        return TRUE;
                    }
                    if (!matched && requireAll) {
                        return FALSE;
                    }
                    matchedAny |= matched;
                    // 与关键帧路径同一份"这个标签 1.7.10 答不了"提示，避免两条路径一条提示一条静默。
                    com.fox.ysmu.client.animation.molang.QueryItemTagFunction.reportUnsupported(tag);
                }
                return requireAll && checked > 0 && matchedAny ? TRUE : FALSE;
            }
            // --- particle / abs_particle（副作用：生成粒子，返回 1.0/0.0） ---
            // 参数布局与关键帧路径的 ParticleFunction 完全一致
            // (molang/ParticleFunction.java:62-74)，连实现都汇到同一个 ParticleEffectUtil。
            // (reference branch same file:831-848)
            if (("particle".equals(name) || "abs_particle".equals(name)
                || "ysm.particle".equals(name) || "ysm.abs_particle".equals(name)) && !arguments.isEmpty()) {
                if (player == null) {
                    return FALSE;
                }
                String id = arguments.get(0).asString();
                double ox = argNumber(arguments, 1);
                double oy = argNumber(arguments, 2);
                double oz = argNumber(arguments, 3);
                double dx = argNumber(arguments, 4);
                double dy = argNumber(arguments, 5);
                double dz = argNumber(arguments, 6);
                double speed = argNumber(arguments, 7);
                int count = (int) argNumber(arguments, 8);
                // OpenYSM 默认 lifetime=20（同 ParticleFunction:71）
                int lifetime = arguments.size() > 9 ? (int) arguments.get(9).asNumber() : 20;
                boolean abs = name.startsWith("abs_") || name.startsWith("ysm.abs_");
                return com.fox.ysmu.client.particle.ParticleEffectUtil.handleParticle(player, id,
                    ox, oy, oz, dx, dy, dz, speed, count, lifetime, abs) ? TRUE : FALSE;
            }
            // --- ysm.* 音效函数（SOURCE:890-921，参数布局对齐上游 SoundFunction）---
            // ysm.play_sound('id', 'sound_name', flags?, volume?, pitch?)：2~5 参数，
            // 与本仓关键帧路径的 YsmSoundFunction.Play 同一布局（molang/YsmSoundFunction.java:24-33,
            // 上游 authority: client/animation/molang/functions/SoundFunction.java:64-130）。
            if ("ysm.play_sound".equals(name) && arguments.size() >= 2) {
                if (player == null) {
                    return FALSE;
                }
                String soundName = arguments.get(1).asString();
                float volume = arguments.size() > 3 ? (float) arguments.get(3).asNumber() : 1.0f;
                float pitch = arguments.size() > 4 ? (float) arguments.get(4).asNumber() : 1.0f;
                // 音乐归属模型用关键帧路径同一个取数点（YsmSoundFunction.java:149）：音效名要按
                // "声明它的模型"解析，否则会命中别的模型的同名音效。
                com.fox.ysmu.client.audio.YSMSoundManager.playSound(player, soundName,
                    software.bernie.geckolib3.core.molang.MolangPhysicsRuntime.getCurrentModelId(), volume, pitch);
                // YSMSoundManager.playSound 返回 void（关键帧路径也只能回报 1），所以这里按参考分支
                // 无条件 1.0，而不是上游 playSound(...) 的布尔结果。
                return TRUE;
            }
            if ("ysm.stop_sound".equals(name) && !arguments.isEmpty()) {
                // 与关键帧路径（YsmSoundFunction）共用同一套归属规则：缺省只停**当前实体**上下文的音效，
                // 显式 global 参数（第二个实参非 0）才全局停止。(SOURCE:898-911)
                if (player == null) {
                    return FALSE;
                }
                String stopId = arguments.get(0).asString();
                boolean global = arguments.size() > 1 && arguments.get(1).asNumber() != 0;
                if (global) {
                    com.fox.ysmu.client.audio.YSMSoundManager.stopSound(stopId);
                } else {
                    com.fox.ysmu.client.audio.YSMSoundManager
                        .stopSound(com.fox.ysmu.client.audio.YSMSoundManager.ownerKey(player), stopId);
                }
                return TRUE;
            }
            if ("ysm.stop_all_sounds".equals(name)) {
                if (player == null) {
                    return FALSE;
                }
                boolean global = !arguments.isEmpty() && arguments.get(0).asNumber() != 0;
                if (global) {
                    com.fox.ysmu.client.audio.YSMSoundManager.stopAll();
                } else {
                    com.fox.ysmu.client.audio.YSMSoundManager
                        .stopAll(com.fox.ysmu.client.audio.YSMSoundManager.ownerKey(player));
                }
                return TRUE;
            }
            // ysm.sync(v0, v1, ...)：主动同步（wiki: molang/script）。与关键帧路径的
            // YsmSyncFunction 共用同一个发送点，这里只是把已经求值好的数值交给它。
            // (SOURCE:823-830；上游 authority: functions/Sync.java:14-63)
            if ("ysm.sync".equals(name) && !arguments.isEmpty()) {
                if (player == null) {
                    return FALSE;
                }
                java.util.List<Double> values = new java.util.ArrayList<>(arguments.size());
                for (Argument argument : arguments) {
                    values.add(argument.asNumber());
                }
                return com.fox.ysmu.client.animation.molang.YsmSyncFunction.request(player, values);
            }
            // ysm.perlin_noise(seed, x, y, z)：0-1 的 3D 柏林噪声，复用关键帧路径的实现。
            // (SOURCE:922-929)
            if ("ysm.perlin_noise".equals(name) && arguments.size() >= 4) {
                return com.fox.ysmu.client.animation.molang.PerlinNoiseFunction.noise(
                    (int) arguments.get(0).asNumber(),
                    arguments.get(1).asNumber(),
                    arguments.get(2).asNumber(),
                    arguments.get(3).asNumber());
            }
            // ysm.mod_version(modid)：**与关键帧路径同一个池化机制**，不是第二份惰性实现。
            // 关键帧/时间轴路径把名字注册到 AnimationRegister.java:277，函数体是 YsmModVersionFunction；
            // 它的字符串→double 规则（MolangStringPool 池化 id，空/未知 mod → 0）是 static 包内可见的，
            // 这里只是把已经求值好的字符串实参交给它。
            // 少了这一条，控制器条件里的 ysm.mod_version('ysm') 落到 unsupportedFunction 恒 0，
            // 而同一个名字在动画文件里答的是池化 id —— 又一处"一个名字两个答案"。
            // SOURCE(上游 1.20): YSMBinding.java:73 `function("mod_version", new ModVersion())`;
            //   ModVersion.java:14-28（validateArgumentSize == 1，返回 Maven 版本字符串）。
            // 参考分支在这条路径上既没有分支也没有桩；本仓的关键帧注册（AnimationRegister.java:277）就是
            // 唯一的实现，这里复用它。
            if ("ysm.mod_version".equals(name) && !arguments.isEmpty()) {
                return com.fox.ysmu.client.animation.molang.YsmModVersionFunction.versionId(
                    com.fox.ysmu.client.animation.molang.YsmModVersionFunction.modVersion(arguments.get(0).asString()));
            }
            return unsupportedFunction(name);
        }

        /** 未知函数：一条一次性提示 + 0（两条路径共用的收尾）。 */
        private static double unsupportedFunction(String name) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "func:" + name,
                "Unsupported OpenYSM controller function: " + name);
            return FALSE;
        }

        /**
         * {@code math.*} 家族：与关键帧路径（mclib {@code MathBuilder} + {@code MolangParser} 的
         * {@code math.*} 重映射，MolangParser.java:131-168）和上游 {@code MathBinding}
         * (tmp/YesSteveModel-dev-1.20 .../geckolib3/core/molang/builtin/MathBinding.java:9-58)
         * 同一批名字。
         *
         * <p><b>语义按上游</b>（本仓的行为权威），逐条落在
         * {@code builtin/math/*.java}；与参考分支（SOURCE:695-792）不一致的地方都在下面注明，
         * 因为参考分支在几处自己改了上游的算法。</p>
         *
         * <p>弹射物轴（{@code ProjectileControllerRuntime.evaluateFunction}）分派的同名函数不再各写一份：
         * {@code sqrt} / {@code mod} / {@code atan} / {@code atan2} / {@code min_angle} / {@code lerprotate} /
         * {@code clamp} / {@code random} / {@code die_roll} / {@code die_roll_integer} 的实现收在
         * {@link ControllerMath} 一份，两轴都调它（AGENTS.md「Prefer one implementation to two」）。
         * 只有本轴分派、弹射物轴没有分支的名字（{@code trunc} / {@code acos} / {@code asin} /
         * {@code random_integer} / {@code randomi}）仍是这里的局部实现。</p>
         *
         * <p>参数个数按上游的 {@code validateArgumentSize} 处理：个数不对时与"这个名字答不了"
         * 同一个结果（0），不会因为少写一个实参就抛异常。</p>
         */
        private static double mathValue(String name, List<Argument> arguments) {
            int size = arguments.size();
            // 常量：无参数形式（SOURCE:774-775）。变量形式见 Context#variableValue。
            if ("math.pi".equals(name)) {
                return Math.PI;
            }
            if ("math.e".equals(name)) {
                return Math.E;
            }
            if (size < 1) {
                return unsupportedFunction(name);
            }
            double a = arguments.get(0).asNumber();
            // --- 单参数 ---
            if ("math.floor".equals(name)) {
                return Math.floor(a);
            }
            if ("math.round".equals(name)) {
                return Math.round(a);
            }
            if ("math.ceil".equals(name)) {
                return Math.ceil(a);
            }
            // 上游 Trunc.java:12-15
            if ("math.trunc".equals(name)) {
                return a < 0 ? Math.ceil(a) : Math.floor(a);
            }
            if ("math.abs".equals(name)) {
                return Math.abs(a);
            }
            // 上游 Sqrt.java:9：Math.sqrt（负数得 NaN）；参考分支加了 "v<0 → 0" 的护栏。
            // 求值体与弹射物轴共用 ControllerMath.sqrt（同名函数在两轴上只有一个实现）。
            if ("math.sqrt".equals(name)) {
                return ControllerMath.sqrt(a);
            }
            if ("math.exp".equals(name)) {
                return Math.exp(a);
            }
            // 上游 Ln.java: Math.log（参考分支加了 "v<=0 → 0" 的护栏）
            if ("math.ln".equals(name)) {
                return Math.log(a);
            }
            // 上游 HermitBlend.java:8-11：ceil 后再 floor 三次 Hermite；关键帧路径的
            // mclib HermiteBlend 是同一个式子。参考分支用的是未取整的 v*v*(3-2v)。
            if ("math.hermite_blend".equals(name) || "math.hermite".equals(name)) {
                double c = Math.ceil(a);
                return Math.floor(3.0d * c * c - 2.0d * c * c * c);
            }
            // 上游 Sin.java / Cos.java：入参是**角度**（与参考分支一致，与关键帧路径的
            // mclib Sin/Cos 不一致——那对函数直接把入参当弧度用）。
            if ("math.sin".equals(name)) {
                return Math.sin(Math.toRadians(a));
            }
            if ("math.cos".equals(name)) {
                return Math.cos(Math.toRadians(a));
            }
            // 上游 ACos.java / ASin.java：弧度。参考分支没有这两个名字（这里补上是为了
            // 与上游和关键帧路径（MolangParser 重映射 acos/asin）同一集合）。
            if ("math.acos".equals(name)) {
                return Math.acos(a);
            }
            if ("math.asin".equals(name)) {
                return Math.asin(a);
            }
            // 上游 Atan.java:9：Math.atan → **弧度**；参考分支返回角度（Math.toDegrees），
            // 与关键帧路径的 mclib ATan 也不一致。求值体与弹射物轴共用 ControllerMath.atan。
            if ("math.atan".equals(name)) {
                return ControllerMath.atan(a);
            }
            // 上游 MinAngle.java:9-16：**一参**归一化到 (-180, 180]（>= 180 判成 -180，与 mclib
            // classic/MinAngle 的 > 180 不同）；实现与弹射物轴共用 ControllerMath.minAngle。
            if ("math.min_angle".equals(name)) {
                return ControllerMath.minAngle(a);
            }
            // --- 双参数 ---
            if (size < 2) {
                // 参考分支的 math.randomi 是**单参**形态（小于 n 的随机整数，SOURCE:777-779），
                // 上游/关键帧路径把它当 2 参的 random_integer，所以只有 1 个实参时走这里。
                return oneArgumentFallback(name, a);
            }
            double b = arguments.get(1).asNumber();
            // 上游 Mod.java:9：Java 取余（参考分支把负数结果折成正数）。
            // 实现与弹射物轴共用 ControllerMath.mod。
            if ("math.mod".equals(name)) {
                return ControllerMath.mod(a, b);
            }
            if ("math.max".equals(name)) {
                return Math.max(a, b);
            }
            if ("math.min".equals(name)) {
                return Math.min(a, b);
            }
            if ("math.pow".equals(name)) {
                return Math.pow(a, b);
            }
            // 上游 ATan2.java:10：弧度（参考分支返回角度）。与弹射物轴共用 ControllerMath.atan2。
            if ("math.atan2".equals(name)) {
                return ControllerMath.atan2(a, b);
            }
            // 上游 Random.java:14-24：min/range 排序后 [min, max)。上游用帧的 RandomSource，本仓这条
            // 路径没有对应的 RandomSource 所有者（MolangFrameContext 不带随机源），关键帧路径的 mclib
            // Random 与参考分支都用 Math.random()，这里跟随它们，不新开侧信道；实现与弹射物轴共用
            // ControllerMath.randomRange（那边此前在端点写反时返回第一个实参，是个常量）。
            if ("math.random".equals(name)) {
                return ControllerMath.randomRange(a, b);
            }
            // 上游把 randomi 注册成 RandomInteger（**2 参**，MathBinding.java:54），关键帧路径同
            // (MolangParser:164 把 math.random_integer 映射到 randomi 类)；参考分支把它当 1 参
            // "小于 n 的随机整数"(SOURCE:777-779)。两种形态都接受，互不冲突。
            if ("math.random_integer".equals(name)) {
                return randomInteger(a, b);
            }
            if ("math.randomi".equals(name)) {
                return randomInteger(a, b);
            }
            // --- 三参数 ---
            if (size < 3) {
                return unsupportedFunction(name);
            }
            double c = arguments.get(2).asNumber();
            // 上游 Clamp.java:10-12 = Mth.clamp（a < min ? min : Math.min(a, max)）。
            // 实现与弹射物轴共用 ControllerMath.clamp（那边此前是另一种 clamp 约定）。
            if ("math.clamp".equals(name)) {
                return ControllerMath.clamp(a, b, c);
            }
            // 上游 Lerp.java / LerpRotate.java 直接调 mclib 的 Interpolations，
            // 这里复用同一份实现（关键帧路径的 mclib Lerp/LerpRotate 就是它）。
            if ("math.lerp".equals(name)) {
                return com.eliotlash.mclib.utils.Interpolations.lerp((float) a, (float) b, (float) c);
            }
            if ("math.lerprotate".equals(name)) {
                return ControllerMath.lerpRotate(a, b, c);
            }
            // 上游 DieRoll.java:15-31 / DieRollInteger.java:15-31，与弹射物轴共用 ControllerMath：
            // 整数形的次数是 Math.round（不是截断）、端点是 getAsInt、上界**独占**，以及本移植的
            // 100 次护栏都在那一份实现里。
            if ("math.die_roll".equals(name) || "math.roll".equals(name)) {
                return ControllerMath.dieRoll(a, b, c);
            }
            if ("math.die_roll_integer".equals(name) || "math.rolli".equals(name)) {
                return ControllerMath.dieRollInteger(a, b, c);
            }
            return unsupportedFunction(name);
        }

        /**
         * {@code math.randomi(limit)}（参考分支的单参形态，SOURCE:777-779）：只有 2 参形态应不上时
         * 才走这里 —— 上游/关键帧路径的 {@code randomi} 是 2 参的 {@code random_integer}。
         */
        private static double oneArgumentFallback(String name, double a) {
            if ("math.randomi".equals(name)) {
                return (int) (Math.random() * ((int) a));
            }
            return unsupportedFunction(name);
        }

        /**
         * 上游 RandomInteger.java:17-26 / DieRollInteger.java 的整数形态：{@code min + nextInt(span)}。
         *
         * <p>两条与上游不同的地方，都是"不要照抄一个会抛异常的写法"：上游在 {@code min == max}
         * 时 {@code nextInt(0)} 抛 {@code IllegalArgumentException}（整条表达式失效），这里退化区间
         * 直接返回那个唯一值；随机源用 {@link Math#random()}（与关键帧路径 mclib
         * RandomInteger 和参考分支一致，原因见 {@code math.random} 的注释）。</p>
         *
         * <p>只服务 {@code math.random_integer} / {@code math.randomi} —— 这两个名字弹射物轴不分派，
         * 所以它们没有 {@link ControllerMath} 里的对应项。{@code math.random} / {@code math.die_roll} /
         * {@code math.die_roll_integer} 是两轴共有的名字，实现已经收进 {@link ControllerMath}。</p>
         */
        private static double randomInteger(double a, double b) {
            int min = (int) a;
            int max = (int) b;
            if (min > max) {
                int swap = min;
                min = max;
                max = swap;
            }
            int span = max - min;
            if (span <= 0) {
                return min;
            }
            return min + (int) (Math.random() * span);
        }

        private static double argNumber(List<Argument> args, int index) {
            return index < args.size() ? args.get(index).asNumber() : 0.0d;
        }

        private double localVariableValue(String name) {
            if ("jump".equals(name)) {
                // A-04 (implemented after A-03 unified the store): an explicitly written v.jump - by a timeline
                // instruction, an on_entry/on_exit statement or prepareFrameVariables - wins over the live
                // `q.is_jumping && q.vertical_speed < 0` latch. Only when nothing ever wrote it does the live
                // value answer, which keeps a model that never sets v.jump behaving as before.
                double stored = state.getVariable(name, Double.NaN);
                if (Double.isNaN(stored)) {
                    return isJumping() ? TRUE : FALSE;
                }
                return stored;
            }
            return state.getVariable(name, FALSE);
        }

        private double queryValue(String name) {
            if ("anim_time".equals(name)) {
                return Math.max(0.0d, event.getAnimationTick() - state.enteredTick) / 20.0d;
            }
            if ("life_time".equals(name)) {
                return event.getAnimationTick() / 20.0d;
            }
            if ("all_animations_finished".equals(name) || "any_animation_finished".equals(name)) {
                OpenYsmPlayerControllerRuntime.FinishedAnimations result = finishedAnimations();
                return ("any_animation_finished".equals(name) ? result.any : result.all) ? TRUE : FALSE;
            }
            if ("ground_speed".equals(name)) {
                return horizontalSpeed();
            }
            if ("vertical_speed".equals(name)) {
                return (player.posY - player.prevPosY) * 20.0d;
            }
            if ("modified_distance_moved".equals(name)) {
                return player.distanceWalkedModified;
            }
            if ("walk_distance".equals(name)) {
                return player.distanceWalkedOnStepModified;
            }
            if ("is_on_ground".equals(name)) {
                return isOnGround() ? TRUE : FALSE;
            }
            if ("is_sneaking".equals(name)) {
                return isOnGround() && player.isSneaking() ? TRUE : FALSE;
            }
            if ("is_sprinting".equals(name)) {
                return player.isSprinting() ? TRUE : FALSE;
            }
            if ("is_swimming".equals(name) || "is_in_water".equals(name)) {
                return player.isInWater() ? TRUE : FALSE;
            }
            if ("is_in_water_or_rain".equals(name)) {
                return player.isWet() ? TRUE : FALSE;
            }
            if ("is_using_item".equals(name)) {
                return player.isUsingItem() ? TRUE : FALSE;
            }
            if ("is_jumping".equals(name)) {
                return isJumping() ? TRUE : FALSE;
            }
            if ("is_riding".equals(name)) {
                return player.isRiding() ? TRUE : FALSE;
            }
            if ("is_sleeping".equals(name)) {
                return player.isPlayerSleeping() ? TRUE : FALSE;
            }
            if ("is_on_fire".equals(name)) {
                return player.isBurning() ? TRUE : FALSE;
            }
            if ("is_playing_dead".equals(name)) {
                return player.isDead ? TRUE : FALSE;
            }
            if ("is_eating".equals(name)) {
                return player.getItemInUse() != null && player.getItemInUse().getItemUseAction() == EnumAction.eat
                    ? TRUE
                    : FALSE;
            }
            if ("is_blocking".equals(name)) {
                return BlockingCompat.isBlocking(player) ? TRUE : FALSE;
            }
            if ("health".equals(name)) {
                return player.getHealth();
            }
            if ("max_health".equals(name)) {
                return player.getMaxHealth();
            }
            if ("hurt_time".equals(name)) {
                return player.hurtTime;
            }
            // A-1: `query.time_of_day` is the fraction of a day in [0,1) and `query.time_stamp` is the raw world
            // time; upstream binds exactly those two (geckolib3/core/molang/builtin/QueryBinding.java:61-62) and
            // normalizes the first with `((float)(timestamp + 6000L) / 24000) % 1`
            // (com/elfmcys/ysm/geckolib3/util/MolangUtils.java:30-32 upstream).
            //
            // They used to share one branch that returned the raw `getWorldTime()`, so a controller condition written
            // as `query.time_of_day < 0.5` ("is it night") was false in every normal world and `> 0.5` true - the
            // keyframe path normalized the same name while this path did not ("one name, two answers"). The value now
            // comes from the *same* implementation the keyframe path publishes with
            // (AnimationRegister#setWorldQueryValues calls this same method; there is no second copy of the
            // arithmetic here), so the two paths cannot disagree about the phase either.
            if ("time_of_day".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.theWorld == null ? 0.0d
                    : software.bernie.geckolib3.molang.util.MolangUtils.normalizeTime(mc.theWorld.getWorldTime());
            }
            if ("time_stamp".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.theWorld == null ? 0.0d : mc.theWorld.getWorldTime();
            }
            // Do not answer FALSE for a `query.*` name this chain does not list. The inventory's nineteen -
            // `head_x_rotation`, `head_y_rotation`, `cardinal_facing_2d`, `distance_from_camera`, `equipment_count`,
            // `eye_target_x_rotation`, `eye_target_y_rotation`, `has_cape`, `has_rider`, `actor_count`,
            // `is_spectator`, `player_level`, `moon_phase`, `yaw_speed`, `item_in_use_duration`,
            // `item_max_use_duration`, `item_remaining_use_duration`, plus the reference branch's own
            // `head_z_rotation` extension - were all answered FALSE here while the **keyframe** path registered them
            // AND gave each one a per-frame supplier (`AnimationRegister.registerQueryVariables`,
            // `AnimationRegister.java:316-369`, and `setEntityQueryValues` / `setStateQueryValues` /
            // `setItemUseQueryValues` / `setWorldQueryValues`, `:484-545`). So the same name read the real value in an
            // animation channel and 0/false in a controller condition: the "one name, two stores" defect A-03 fixed
            // for `v.*`, which the sibling `ysmValue` branch below already fixed for `ysm.*`
            // (`OpenYsmControllerExpressionEvaluator.java:1529-1539`). Each of them also emitted one
            // `Unsupported OpenYSM controller query: query.<name>` per JVM, and a controller gated on one was never
            // entered.
            //
            // This is a **fallback, not a second table**: the values live in `MolangParser.VARIABLES`, the one store
            // `AnimationRegister` publishes them into, and it is read exactly the way the sibling `ysmValue` branch
            // below reads it (a direct `VARIABLES.get`), not through a parser instance - this is a per-frame path and
            // constructing a parser here would allocate on every miss. `VARIABLES` is keyed by the canonical name
            // (`MolangParser#register`), which for a `query.*` name is the name itself: `parseExpression` lower-cases
            // the whole expression before parsing it (`MolangParser#lowerCaseOutsideStrings`), so `name` is already
            // canonical. A name nothing published is `null` here and still warns, so the fallback cannot mask a
            // genuinely unsupported name by creating it as a zero.
            LazyVariable published = MolangParser.VARIABLES.get("query." + name);
            if (published != null) {
                return published.get();
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "query:" + name,
                "Unsupported OpenYSM controller query: query." + name);
            return FALSE;
        }

        private double ctrlValue(String name) {
            // `ctrl.playing_extra_animation` is NOT a controller-state predicate like walk/idle/death: it asks whether
            // the cap controller is playing an external (EEP) animation, so it must not be added to
            // `isControllerStateDirect`/`hasNonIdleControllerState` - doing that would make `ctrl.idle` false for
            // every actor that has an extra animation lit.
            //
            // Upstream registers it as its own variable, `CtrlBinding.java:75`
            // `var("playing_extra_animation", CtrlBinding::checkExtraAnimationState)`, body at `:112-117`. The
            // reference branch answers it at `OpenYsmControllerExpressionEvaluator.java:1180` from
            // `isPlayingExtraAnimation()` (`:1343-1355`), which is `eep.isPlayAnimation()` OR the wheel lock.
            //
            // This port has the EEP half and no wheel half. The wheel half of the reference
            // (`WheelPlaybackScope.countsAsExtraAnimationFor` -> `PENDING_ROAMING["lock_wheel"]`/`["wheel_anim"]` +
            // `AnimationManager.getCurrentWheelAnimName()`) has no counterpart here at all: this port's
            // `AnimationRouletteScreen.mouseClicked` sends `SetPlayAnimation` to the server and the animation arrives
            // back as the same `ExtendedModelInfo.playAnimation` flag the EEP half below reads, so one source answers
            // for both. See `.agent/phase41-controller-path-values.md` rows 10/11.
            if ("playing_extra_animation".equals(name)) {
                return isPlayingExtraAnimation() ? TRUE : FALSE;
            }
            // The vocabulary check is what tells "a known state that is currently false" apart from "a name nothing
            // implements"; `isControllerState` answers false for both. Without it a `ctrl.<typo>` read 0 silently,
            // while the two sibling prefixes (`query.*`, `ysm.*`) already emit this same one-shot diagnostic - so the
            // one warning that would have named a broken pack condition was the one `ctrl.*` never produced.
            // `CONTROLLER_STATE_NAMES` is the same single copy the debug overlay and `evaluateCtrlState` read
            // (`:851-861`), so there is no second list to drift.
            if (CONTROLLER_STATE_NAMES.contains(name)) {
                return isControllerState(name) ? TRUE : FALSE;
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "var:ctrl." + name,
                "Unsupported OpenYSM controller variable: ctrl." + name);
            return FALSE;
        }

        /**
         * Whether this actor is playing an external animation - the source of {@code ctrl.playing_extra_animation}.
         *
         * <p>Same store {@code AnimationManager.predicateCap} plays from ({@code eep.getAnimation()} guarded by
         * {@code eep.isPlayAnimation()}, {@code AnimationManager.java:207-218}), so the condition and the hand-over
         * cannot disagree about whether an EEP animation is lit - the "one name, two stores" defect class A-03 fixed
         * for {@code v.*}.
         *
         * <p>Upstream gates the same fact on the cap controller not being IDLE
         * ({@code CtrlBinding.java:112-117} {@code entity.isPlayingExtraAnimation() &&
         * entity.getCodedAnimationStates(CAP_CONTROLLER) != AnimationState.IDLE}). That half has no equivalent on
         * this path: the controller state is only readable while the cap controller is being processed, and
         * {@code Context} is also used for {@code .molang} script conditions with no controller at all
         * ({@link #evaluateCondition(String, EntityPlayer, AnimationEvent)}).
         */
        private boolean isPlayingExtraAnimation() {
            if (player == null) {
                // Detached evaluation (the unit-test path, and a preview that has no player): no EEP to read.
                return false;
            }
            com.fox.ysmu.eep.ExtendedModelInfo eep = com.fox.ysmu.eep.ExtendedModelInfo.get(player);
            return eep != null && eep.isPlayAnimation();
        }

        private double ysmValue(String name) {
            if ("is_fishing".equals(name)) {
                return player.fishEntity != null ? TRUE : FALSE;
            }
            if ("swinging".equals(name)) {
                return player.isSwingInProgress ? TRUE : FALSE;
            }
            if ("swing_time".equals(name)) {
                return player.swingProgressInt;
            }
            if ("swinging_arm".equals(name)) {
                return BackhandCompat.swingingArm(player) ? 0.0d : 1.0d;
            }
            if ("is_passenger".equals(name)) {
                return player.isRiding() ? TRUE : FALSE;
            }
            if ("is_sleep".equals(name)) {
                return player.isPlayerSleeping() ? TRUE : FALSE;
            }
            if ("is_sneak".equals(name)) {
                return isOnGround() && player.isSneaking() ? TRUE : FALSE;
            }
            if ("on_ladder".equals(name)) {
                return player.isOnLadder() ? TRUE : FALSE;
            }
            if ("is_riptide".equals(name)) {
                return FALSE;
            }
            if ("has_mainhand".equals(name)) {
                return player.getHeldItem() != null ? TRUE : FALSE;
            }
            if ("has_offhand".equals(name)) {
                return BackhandCompat.getOffhandItem(player) != null ? TRUE : FALSE;
            }
            if ("mainhand_charged_crossbow".equals(name)) {
                return com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(player.getHeldItem()) ? TRUE : FALSE;
            }
            if ("offhand_charged_crossbow".equals(name)) {
                return com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(
                    BackhandCompat.getOffhandItem(player)) ? TRUE : FALSE;
            }
            if ("armor_value".equals(name)) {
                return player.getTotalArmorValue();
            }
            if ("hurt_time".equals(name)) {
                return player.hurtTime;
            }
            if ("food_level".equals(name)) {
                return player.getFoodStats().getFoodLevel();
            }
            // Do not answer FALSE for a `ysm.*` name this chain does not list. `AnimationRegister` publishes far more
            // of them than are enumerated above - `has_helmet`, `has_chest_plate`, `has_leggings`, `has_boots`,
            // `has_elytra`, `elytra_rot_*`, ... - and every one of those used to be answered "false" here while the
            // animation file's own MoLang read the real value: the one-name-two-stores defect A-03 fixed for `v.*`,
            // still present on this axis. It is what kept wine_fox/14_momo's helmet off the head: its
            // `player.armor_head` state machine transitions on `ysm.has_helmet`, which this chain never listed, so the
            // condition was constant false and the state was never entered even once the controller name resolved.
            LazyVariable published = MolangParser.VARIABLES.get("ysm." + name);
            if (published != null) {
                return published.get();
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "ysm:" + name,
                "Unsupported OpenYSM controller ysm variable: ysm." + name);
            return FALSE;
        }

        /** Returns the entity's position component (0=x, 1=y, 2=z), using
         *  partial tick interpolation.  Implements query.position(index). */
        private double queryPositionValue(int index) {
            if (index < 0 || index > 2 || player == null) return 0.0d;
            // 事件脚本（@player_init / @player_update / @sync）没有 AnimationEvent，也就没有
            // 渲染插值进度：用 1.0（当前位置）而不是 0（上一 tick 位置），否则脚本读到的是
            // 落后一 tick 的坐标。控制器路径上仍用 event 的真实 partialTick。
            float partialTicks = event != null ? event.getPartialTick() : 1.0f;
            switch (index) {
                case 0:
                    return player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
                case 1:
                    return player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
                case 2:
                    return player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
                default:
                    return 0.0d;
            }
        }

        private boolean isControllerState(String name) {
            // Detached evaluation (`new Context(null, null, null)`, the unit-test and preview path) has no actor to
            // read a state from: answer false instead of dereferencing a null player. The reference branch guards the
            // same way at the head of its own state-reading branch (`SOURCE:1187`
            // `private double ysmValue(String name) { if (player == null) return FALSE; ...`), and every other reader
            // in this class that can run detached guards too (`:1555`, `:1633`, `:1775`, `:1792`, `:1835`).
            if (player == null) {
                return false;
            }
            // A-09: upstream answers every `ctrl.<state>` with false while the entity has a live vehicle
            // (CtrlBinding#testCondition, client/animation/molang/CtrlBinding.java:158-162): the main controller is not
            // what plays the riding animation - the vehicle controller is - so its state queries mean nothing there.
            // Without this rule `ctrl.idle` was *true* for a rider, because the riding names were not in the non-idle
            // list either, and a controller expression branching on `ctrl.idle` selected the wrong state.
            // (Upstream has the same short-circuit for ParCool; 1.7.10 has no such mod.)
            if (player.ridingEntity != null && player.ridingEntity.isEntityAlive()) {
                return false;
            }
            if ("idle".equals(name)) {
                return !hasNonIdleControllerState();
            }
            return isControllerStateDirect(name);
        }

        private boolean hasNonIdleControllerState() {
            return isControllerStateDirect("death")
                || isControllerStateDirect("sleep")
                || isControllerStateDirect("swim")
                || isControllerStateDirect("climb")
                || isControllerStateDirect("climbing")
                || isControllerStateDirect("ladder_up")
                || isControllerStateDirect("ladder_stillness")
                || isControllerStateDirect("ladder_down")
                || isControllerStateDirect("fly")
                // A-04 registered this one; a glide is as much "not idle" as a flight is.
                || isControllerStateDirect("elytra_fly")
                || isControllerStateDirect("swim_stand")
                || isControllerStateDirect("attacked")
                || isControllerStateDirect("jump")
                || isControllerStateDirect("sneak")
                || isControllerStateDirect("sneaking")
                || isControllerStateDirect("run")
                || isControllerStateDirect("walk");
        }

        private boolean isControllerStateDirect(String name) {
            if ("death".equals(name)) {
                return player.isDead;
            }
            if ("sleep".equals(name)) {
                return player.isPlayerSleeping();
            }
            if ("swim".equals(name)) {
                return player.isInWater() && Math.abs(event.getLimbSwingAmount()) > 0.05f;
            }
            if ("climb".equals(name) || "climbing".equals(name)) {
                return player.isOnLadder();
            }
            if ("ladder_up".equals(name)) {
                return player.isOnLadder() && motionYState(0.1d) > 0;
            }
            if ("ladder_stillness".equals(name)) {
                return player.isOnLadder() && motionYState(0.1d) == 0;
            }
            if ("ladder_down".equals(name)) {
                return player.isOnLadder() && motionYState(0.1d) < 0;
            }
            if ("ride_pig".equals(name)) {
                return player.ridingEntity instanceof EntityPig;
            }
            if ("boat".equals(name)) {
                return player.ridingEntity instanceof EntityBoat;
            }
            if ("ride".equals(name) || "sit".equals(name)) {
                return player.isRiding();
            }
            if ("fly".equals(name)) {
                return isFlying();
            }
            if ("elytra_fly".equals(name)) {
                // A-04: the very source the animation state uses, so `ctrl.elytra_fly` and the `elytra_fly` state
                // cannot disagree about whether the player is gliding.
                return EtFuturumCompat.isElytraFlying(player);
            }
            if ("swim_stand".equals(name)) {
                return player.isInWater();
            }
            if ("attacked".equals(name)) {
                return player.hurtTime > 0;
            }
            if ("jump".equals(name)) {
                return isJumping();
            }
            if ("sneak".equals(name)) {
                return isOnGround() && player.isSneaking() && Math.abs(event.getLimbSwingAmount()) > 0.05f;
            }
            if ("sneaking".equals(name)) {
                return isOnGround() && player.isSneaking();
            }
            if ("run".equals(name)) {
                return isOnGround() && player.isSprinting();
            }
            if ("walk".equals(name)) {
                return isOnGround() && event.getLimbSwingAmount() > 0.05f;
            }
            return false;
        }

        /**
         * 摆臂幅度（{@code query.limb_swing_amount} 同口径），没有 AnimationEvent 时用实际水平位移近似。
         * <p>
         * 事件脚本（{@code @player_init} / {@code @player_update} / {@code @sync}）没有
         * AnimationEvent（{@code OpenYsmScriptRuntime.runEvent} 传 {@code event = null}），
         * 而 {@code ctrl.walk} / {@code ctrl.swim} 原来直接读 {@code event.getLimbSwingAmount()}，
         * 于是一读 {@code ctrl.*} 就 NPE，整个脚本每帧失败。水平位移与
         * {@code horizontalSpeed()}（{@code query.ground_speed}）同源，是这些调用点能拿到的最好近似。
         */
        private float limbSwingAmount() {
            if (event != null) {
                return Math.abs(event.getLimbSwingAmount());
            }
            return (float) horizontalSpeed();
        }

        /** ctrl.armor('chest'|'feet'|'legs'|'head', '$物品ID'|'empty'|'#tag')。
         *  1.7.10 无物品 tag 系统，'#tag' 恒 false（对齐 OpenYSM 语义）。 */
        /** 与关键帧/脚本路径共用 {@code CtrlItemMatcher}，避免两条路径规则漂移。 */
        private double armorMatch(List<Argument> arguments) {
            String slot = arguments.size() > 0 ? arguments.get(0).asString() : "";
            String matcher = arguments.size() > 1 ? arguments.get(1).asString() : "";
            if (StringUtils.isBlank(matcher)) {
                return FALSE; // OpenYSM：id 为空返回 0
            }
            if (player == null || player.inventory == null) {
                // 没有实体（脱离帧的求值）取不到护甲槽：按假回答，不抛异常。
                return FALSE;
            }
            int armorIndex = com.fox.ysmu.client.animation.molang.CtrlItemMatcher.armorSlotIndex(slot);
            if (armorIndex < 0) {
                return FALSE; // mainhand/offhand 不属于 ctrl.armor
            }
            ItemStack stack = player.inventory.armorInventory[armorIndex];
            return com.fox.ysmu.client.animation.molang.CtrlItemMatcher.armorMatches(stack, matcher) ? TRUE : FALSE;
        }

        /** ctrl.ride('vehicle'|'passenger', '$实体ID')；与关键帧/脚本路径共用 {@code CtrlRideFunction}。
         *  {@code #实体标签} 那一半在 1.7.10 无对应物，恒 false（见 CtrlRideFunction 的类注释）。 */
        private double rideMatch(List<Argument> arguments) {
            String type = arguments.size() > 0 ? arguments.get(0).asString() : "";
            String matcher = arguments.size() > 1 ? arguments.get(1).asString() : "";
            if (player == null) {
                // 没有实体（脱离帧的求值）时按假回答，不抛异常。
                return FALSE;
            }
            return com.fox.ysmu.client.animation.molang.CtrlRideFunction.ridingMatches(player, type, matcher) ? TRUE
                : FALSE;
        }

        private double handMatch(List<Argument> arguments, boolean requireUse, boolean requireSwing) {
            String hand = arguments.size() > 0 ? arguments.get(0).asString() : "mainhand";
            String matcher = arguments.size() > 1 ? arguments.get(1).asString() : "";
            // A-06③ / A-07: the animation-file `ctrl.hold` function calls the very same method, so the two paths
            // cannot disagree about hands, `$id` / `#ore-dictionary` / `:kind` / `empty`, or the aliases
            // (trident/spear, fishingrod/fishing_rod, EnumAction names).
            return InnerClassify.matchesHandCondition(player, hand, matcher, requireUse, requireSwing) ? TRUE : FALSE;
        }

        /** 与关键帧/脚本路径共用 {@code CtrlItemMatcher}，避免两条路径规则漂移。 */
        private boolean itemMatches(ItemStack stack, String matcher) {
            return com.fox.ysmu.client.animation.molang.CtrlItemMatcher
                .matches(stack, StringUtils.isBlank(matcher) ? "" : matcher);
        }

        private String itemId(ItemStack stack) {
            return com.fox.ysmu.client.animation.molang.CtrlItemMatcher.itemId(stack);
        }

        private OpenYsmPlayerControllerRuntime.FinishedAnimations finishedAnimations() {
            // BedrockAnimationController:227-243 reads previous processed holder conditions,
            // before this frame reevaluates activation. Newly entered states have no applied players yet.
            return state.finishedAnimations(
                entry -> state.activeState != null && state.currentState.equals(state.lastSelectedAnimationState)
                    && event.getController().concurrentAnimationApplies(state.activeState.animations.indexOf(entry)),
                entry -> event.getController().concurrentAnimationFinished(state.activeState.animations.indexOf(entry)));
        }

        /**
         * A-2: the same question the keyframe path asks, answered by the same implementation.
         *
         * <p>This used to be a second copy of the local/remote split ({@code Minecraft.getMinecraft().thePlayer} vs
         * {@code RemotePlayerMotionStates}), which is how the two paths could drift apart in the first place. It now
         * delegates to {@link com.fox.ysmu.client.animation.AnimationRegister#isPlayerOnGround}, which the animation
         * states ({@code run}/{@code walk}/{@code jump}/...) are themselves gated on - one rule, so a controller
         * condition and the state it selects cannot disagree about whether the actor is standing.
         */
        private boolean isOnGround() {
            return AnimationRegister.isPlayerOnGround(player);
        }

        /**
         * A-2: whether the player is flying, taken from the keyframe path's implementation.
         *
         * <p><b>Two deliberate changes from what this method used to be.</b> It counted Et Futurum Requiem's elytra
         * glide as flight ({@code EtFuturumCompat.isElytraFlying}) while the {@code fly} state and
         * {@code query.is_jumping} on the keyframe path did not, so a gliding player was "flying" in a controller
         * condition and "jumping" in the animation file - the exact divergence A-2 is about. Upstream does not count
         * the glide as flight either: {@code QueryBinding#isFlying} ({@code geckolib3/core/molang/builtin/
         * QueryBinding.java:115-121}, which {@code query.is_jumping} at {@code :102} uses) reads the flying ability
         * only, and the glide has its own dedicated state ({@code elytra_fly}, registered in
         * {@code AnimationRegister#registerFlyingStates}) and its own controller query ({@code ctrl.elytra_fly},
         * {@code isControllerStateDirect}), both of which still answer from {@code EtFuturumCompat}. The history is
         * kept because it was a real fix for a real symptom - see below - and it is now answered by the same source as
         * the state it was meant to fix.
         *
         * <p>History: {@code ctrl.fly=false} while {@code isJumping()=true} made a glide read as a jump; with the glide
         * excluded from both sides, {@code query.is_jumping} is true during a glide on <em>both</em> paths (upstream's
         * airborne predicate cannot tell a glide from a fall) and the {@code elytra_fly} state still wins over
         * {@code jump} by priority ({@code HIGH} against {@code NORMAL}).
         */
        private boolean isFlying() {
            return AnimationRegister.isPlayerFlying(player);
        }

        /**
         * A-2: the airborne predicate behind {@code query.is_jumping} and {@code ctrl.jump}, delegating to
         * {@link com.fox.ysmu.client.animation.AnimationRegister#isPlayerJumping}.
         *
         * <p><b>Removed: {@code && motionYState(0.0d) != 0}.</b> The keyframe path deleted that term with a written
         * record of the defect it caused ({@code AnimationRegister#isPlayerJumping}'s javadoc: {@code motionY} is
         * exactly 0 at the apex of a jump, so for that tick the local player dropped out of the jump state and the arms
         * snapped back to neutral and out again, while remote players - which never had the term - looked different).
         * This path had kept it, so the same tick answered {@code query.is_jumping} = 0 and {@code ctrl.jump} = 0 in a
         * controller condition while the animation file's {@code query.is_jumping} and the {@code jump} state read 1.
         * The whole airborne phase is one state on both paths now, which is upstream's rule.
         */
        private boolean isJumping() {
            return AnimationRegister.isPlayerJumping(player);
        }

        private double horizontalSpeed() {
            double x = player.posX - player.prevPosX;
            double z = player.posZ - player.prevPosZ;
            return MathHelper.sqrt_double(x * x + z * z) * 20.0d;
        }

        private int motionYState(double threshold) {
            double motionY = player == Minecraft.getMinecraft().thePlayer ? player.motionY
                : (player.posY - player.prevPosY) * 2.0d;
            if (motionY > threshold) {
                return 1;
            }
            if (motionY < -threshold) {
                return -1;
            }
            return 0;
        }
    }

    static final class Argument {
        private final String stringValue;
        private final double numberValue;
        private final boolean string;

        private Argument(String stringValue, double numberValue, boolean string) {
            this.stringValue = stringValue;
            this.numberValue = numberValue;
            this.string = string;
        }

        static Argument string(String value) {
            return new Argument(value, 0.0d, true);
        }

        static Argument number(double value) {
            return new Argument("", value, false);
        }

        String asString() {
            return string ? stringValue : Double.toString(numberValue);
        }

        double asNumber() {
            return string ? parseStringAsNumber(stringValue) : numberValue;
        }

        private static double parseStringAsNumber(String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0d;
            }
        }
    }
}
