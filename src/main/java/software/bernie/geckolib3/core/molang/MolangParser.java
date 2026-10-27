package software.bernie.geckolib3.core.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.MathBuilder;
import com.eliotlash.mclib.math.Variable;
import com.eliotlash.mclib.math.functions.Function;
import com.eliotlash.mclib.math.functions.classic.MinAngle;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import software.bernie.geckolib3.GeckoLib;
import software.bernie.geckolib3.core.molang.expressions.MolangAssignment;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.molang.expressions.MolangMultiStatement;
import software.bernie.geckolib3.core.molang.expressions.MolangValue;
import software.bernie.geckolib3.core.molang.functions.BoneColor;
import software.bernie.geckolib3.core.molang.functions.BoneGlow;
import software.bernie.geckolib3.core.molang.functions.BonePosition;
import software.bernie.geckolib3.core.molang.functions.BoneRotation;
import software.bernie.geckolib3.core.molang.functions.BoneScale;
import software.bernie.geckolib3.core.molang.functions.BoneTransparency;
import software.bernie.geckolib3.core.molang.functions.CosDegrees;
import software.bernie.geckolib3.core.molang.functions.FirstOrder;
import software.bernie.geckolib3.core.molang.functions.SecondOrder;
import software.bernie.geckolib3.core.molang.functions.SinDegrees;

/**
 * MoLang 解析器
 * <a href="https://bedrock.dev/docs/1.19.0.0/1.19.30.23/Molang#Math%20Functions">Wiki</a>
 *
 * YSMU: Heavily modified — added function namespace remapping (ysm.*),
 * null-coalescing (??) operator, vector function rewriting (bone_rot/bone_pos),
 * string literal pooling, scoped variable support (ScopedMolangVariable),
 * and OpenYSM expression compatibility. The original MolangParser has been
 * largely rewritten to support YSMU's extended animation system.
 */
public class MolangParser extends MathBuilder {

    public static final Map<String, LazyVariable> VARIABLES = new Object2ObjectOpenHashMap<>();
    public static final MolangExpression ZERO = new MolangValue(null, new Constant(0));
    public static final MolangExpression ONE = new MolangValue(null, new Constant(1));
    public static final String RETURN = "return ";

    /**
     * Host-mod-injected hook: registers YSMU-specific Molang functions on every
     * newly constructed parser. Inverted control — this vendored file has no
     * compile-time dependency on mod code (same pattern as
     * AnimationFile.builtinFallback). Set once at mod init; read-only afterwards;
     * null → no YSMU-specific functions are registered (graceful fallback).
     */
    @FunctionalInterface
    public interface MolangFunctionRegistrar {
        void register(Map<String, Class<? extends Function>> functions);
    }

    public static volatile MolangFunctionRegistrar ysmFunctionRegistrar = null;

    /**
     * Host-mod-injected hook: decides, for the current render frame, whether a
     * {@code v.} variable was EXPLICITLY set (even to 0) vs merely
     * default-initialized. Used by the {@code ??} null-coalescing operator.
     * Receives the full variable name as written (e.g. {@code v.roaming.x}).
     * Null → treated as "not explicitly set" (graceful fallback).
     */
    @FunctionalInterface
    public interface ExplicitVariableLookup {
        boolean isExplicitlySet(String fullVariableName);
    }

    public static volatile ExplicitVariableLookup explicitVariableLookup = null;

    private static final Logger LOG = GeckoLib.LOG;
    /** 已经报告过的解析失败原因，同一个原因（例如同一个未注册函数）之后不再刷日志。 */
    private static final Set<String> REPORTED_PARSE_FAILURES = ConcurrentHashMap.newKeySet();

    /**
     * The statement {@link #parseExpression} is currently on, for {@link #reportParseFailure}. Not cleared: a failure
     * can surface from a nested parse, and the innermost expression is the more useful one to name.
     */
    private String parsingExpression;

    public MolangParser() {
        super();
        // 将函数重新映射为 MoLang 标准名
        doCoreRemaps();
    }

    private void doCoreRemaps() {
        // 将 sin 和 cos 改成角度参数
        this.functions.put("cos", CosDegrees.class);
        this.functions.put("sin", SinDegrees.class);
        this.functions.put("ysm.first_order", FirstOrder.class);
        this.functions.put("ysm.second_order", SecondOrder.class);
        this.functions.put("ysm.bone_rot_x", BoneRotation.class);
        this.functions.put("ysm.bone_rot_y", BoneRotation.class);
        this.functions.put("ysm.bone_rot_z", BoneRotation.class);
        this.functions.put("ysm.bone_pos_x", BonePosition.class);
        this.functions.put("ysm.bone_pos_y", BonePosition.class);
        this.functions.put("ysm.bone_pos_z", BonePosition.class);
        this.functions.put("ysm.bone_position_x", BonePosition.class);
        this.functions.put("ysm.bone_position_y", BonePosition.class);
        this.functions.put("ysm.bone_position_z", BonePosition.class);
        this.functions.put("ysm.bone_scale_x", BoneScale.class);
        this.functions.put("ysm.bone_scale_y", BoneScale.class);
        this.functions.put("ysm.bone_scale_z", BoneScale.class);
        // The vector form itself is a real registration, not only something the rewrite below turns into an
        // `_x/_y/_z` name. Upstream returns a struct here and lets `.x`/`.y`/`.z` pick a member
        // (client/animation/molang/functions/BoneParamFunction.java:19-31 + struct/Vec3fStruct.java:11-23), and the
        // variables wiki documents the `.x/.y/.z` accessors only
        // (https://yesstevemodel.github.io/wiki/molang/ref/). This engine's mclib parser has no struct support
        // (`IValue` is a bare `double`), so the member access is a rewrite - but a vector call in a position the
        // rewrite does not reach (as an argument to another function, or bare) used to fall through to
        // `Function 'ysm.bone_scale' couldn't be found!`, which killed the whole timeline instruction. Registering
        // the bare name makes that position fall back to the axis the function answers when no member is named -
        // `axis()` returns 'x' unless the name's last character is a/y/z. Zero extra state: the same class the
        // `_x` name uses reads the same bone.
        this.functions.put("ysm.bone_rot", BoneRotation.class);
        this.functions.put("ysm.bone_pos", BonePosition.class);
        this.functions.put("ysm.bone_position", BonePosition.class);
        this.functions.put("ysm.bone_scale", BoneScale.class);

        // Upstream's per-bone render functions, registered under the names a pack actually calls them by
        // (com/elfmcys/ysm/client/animation/molang/YSMBinding.java:82-85) rather than under the ysm. prefix the
        // query functions above use. bone_color(bone, r, g, b) is white by default, bone_transparency(bone, alpha)
        // is opaque by default and bone_glow(bone, level) is -1, i.e. not emissive, so a model that calls none of
        // them renders exactly as it did before these existed.
        this.functions.put("bone_color", BoneColor.class);
        this.functions.put("bone_transparency", BoneTransparency.class);
        this.functions.put("bone_glow", BoneGlow.class);

        // YSMU 特有的 ysm.* / ctrl.* / query.* 函数由宿主 mod 通过
        // ysmFunctionRegistrar 钩子注入（反向控制，本文件不引用 mod 类）。
        MolangFunctionRegistrar registrar = ysmFunctionRegistrar;
        if (registrar != null) {
            registrar.register(this.functions);
        }

        remap("abs", "math.abs");
        remap("acos", "math.acos");
        remap("asin", "math.asin");
        remap("atan", "math.atan");
        remap("atan2", "math.atan2");
        remap("ceil", "math.ceil");
        remap("clamp", "math.clamp");
        remap("cos", "math.cos");
        // MathBuilder 将这些函数注册为短名，需要直接复制到 math.* 名下
        this.functions.put("math.die_roll", this.functions.get("roll"));
        this.functions.put("math.die_roll_integer", this.functions.get("rolli"));
        this.functions.put("math.hermite_blend", this.functions.get("hermite"));
        // 上游把同一批实现同时挂在 `math` 绑定的两个名字下（tmp/YesSteveModel-dev-1.20
        // .../geckolib3/core/molang/builtin/MathBinding.java:46-48 `die_roll`/`die_roll_integer`/`hermite_blend`
        // 与 :54-57 `randomi`/`roll`/`rolli`/`hermite` 指向同一批类）。这里此前只复制了长名，于是一个按上游
        // 写的 `math.roll(...)`/`math.randomi(...)` 在 MathBuilder.createFunction 查表落空、整条表达式判死。
        // 复用上面那张表的同一批类（不是 `remap`，那会把短名键删掉，而短名是现有 pack 在用的名字）。
        this.functions.put("math.roll", this.functions.get("roll"));
        this.functions.put("math.rolli", this.functions.get("rolli"));
        this.functions.put("math.hermite", this.functions.get("hermite"));
        this.functions.put("math.randomi", this.functions.get("randomi"));
        // math.min_angle：Bedrock/OpenYSM 单参数角度归一化（mclib 原版没有）。
        this.functions.put("math.min_angle", MinAngle.class);
        remap("exp", "math.exp");
        remap("floor", "math.floor");
        remap("lerp", "math.lerp");
        remap("lerprotate", "math.lerprotate");
        remap("ln", "math.ln");
        remap("max", "math.max");
        remap("min", "math.min");
        remap("mod", "math.mod");
        // π/e 在 MathBuilder 中注册为变量（键 "PI"/"E"），并非函数。原
        // remap("pi", "math.pi") 会从函数表 remove 不存在的 "pi" 键并
        // 把 null 塞进 "math.pi"，既污染函数表又让变量查询得到默认 0。
        // 这里改为直接注册 math.* 常量变量，overlay/query 即可显示正确值。
        // math.pi 是常量而不是函数（原来把它 remap 成函数只会留下一个空类）
        this.register(new LazyVariable("math.pi", Math.PI));
        this.register(new LazyVariable("math.e", Math.E));
        remap("pow", "math.pow");
        remap("random", "math.random");
        // 源名必须是真正注册过的名字：MathBuilder 里是 randomi/roll/rolli/hermite
        // MathBuilder 将 random_integer 注册为 "randomi"，而非 "random_integer"
        this.functions.put("math.random_integer", this.functions.get("randomi"));
        remap("round", "math.round");
        remap("sin", "math.sin");
        remap("sqrt", "math.sqrt");
        remap("trunc", "math.trunc");
    }

    @Override

    public void register(Variable variable) {
        if (!(variable instanceof LazyVariable)) {
            variable = LazyVariable.from(variable);
        }
        String name = normalizeVariableName(variable.getName());
        if (name.startsWith("v.") && !(variable instanceof ScopedMolangVariable)) {
            Variable fallback = variable;
            variable = new ScopedMolangVariable(name, fallback::get);
        }
        VARIABLES.put(name, (LazyVariable) variable);
    }

    /**
     * 重映射方法：把 {@code old} 注册的函数改到 Bedrock 的 {@code math.*} 名字下。
     * <p>
     * 源名不存在时**不写入**：此前的 {@code functions.put(newName, remove(old))} 会把 {@code null} 放进表里，
     * 于是 {@code math.random_integer} 这类名字能"查到"却拿到空类，最终表现为
     * "Function 'math.random_integer' couldn't be found!"。源名写错（注册的是 {@code randomi}/{@code roll}/
     * {@code rolli}/{@code hermite}）就正是这样发生的。
     */
    public void remap(String old, String newName) {
        Class<? extends Function> function = this.functions.remove(old);
        if (function != null) {
            this.functions.put(newName, function);
        }
    }

    /**
     * 函数名同样接受 {@code q.} 前缀：{@code normalizeVariableName} 早就把变量里的 {@code q.} 归一成
     * {@code query.}，但函数查表用的是原样字符串，于是宿主注册的 {@code query.position} 对
     * {@code q.position(...)} 不可见（模型包里两种写法都有）。
     */
    @Override
    protected IValue createFunction(String first, List<Object> args) throws Exception {
        return super.createFunction(normalizeVariableName(first), args);
    }

    /**
     * Stores a plain number in the variable. Kept {@code @Deprecated} for call-site signalling only: it used to
     * delegate to {@link #setValue(String, DoubleSupplier)} with a capturing {@code () -> value} lambda, which made
     * every one of the per-controller clock writes ({@code query.life_time} / {@code query.anim_time} /
     * {@code query.delta_time}, three per controller per frame) allocate. The supplier overload is for values that
     * must not be evaluated unless read; this one is for values the caller already has.
     */
    @Deprecated
    public void setValue(String name, double value) {
        LazyVariable variable = getVariable(name);
        if (variable != null) {
            variable.setLiteral(value);
        }
    }

    public void setValue(String name, DoubleSupplier value) {
        LazyVariable variable = getVariable(name);
        if (variable != null) {
            variable.set(value);
        }
    }

    @Override

    protected LazyVariable getVariable(String name) {
        name = normalizeVariableName(name);
        return VARIABLES.computeIfAbsent(name, MolangParser::newVariable);
    }

    public LazyVariable getVariable(String name, MolangMultiStatement currentStatement) {
        name = normalizeVariableName(name);
        LazyVariable variable;
        if (currentStatement != null) {
            variable = currentStatement.locals.get(name);
            if (variable != null) {
                return variable;
            }
        }
        return getVariable(name);
    }

    /**
     * The single rule for every Molang variable name. Names are case-insensitive, so the canonical form is
     * lower-case, and the two interchangeable prefixes are folded into one spelling.
     * <p>
     * {@link #parseExpression} lower-cases a whole expression before parsing it, so a variable registered in any
     * other case can never be found: a pack writing {@code v.roaming.C} had it stored as written and looked up as
     * {@code v.roaming.c}, read as the neutral 0, and every {@code v.roaming.C==0?...} in that pack took its first
     * branch - the pack's own settings toggles silently did nothing. Upstream canonicalises identifiers the same way,
     * in {@code com.elfmcys.ysm.api.molang.MolangNames#identifier}.
     */
    public static String canonicalVariableName(String name) {
        String canonical = name.toLowerCase(Locale.ROOT);
        if (canonical.startsWith("q.")) {
            return "query." + canonical.substring(2);
        }
        if (canonical.startsWith("variable.")) {
            return "v." + canonical.substring("variable.".length());
        }
        return canonical;
    }

    private static String normalizeVariableName(String name) {
        return canonicalVariableName(name);
    }

    private static LazyVariable newVariable(String key) {
        return key.startsWith("v.") ? new ScopedMolangVariable(key, 0) : new LazyVariable(key, 0);
    }

    public MolangExpression parseJson(JsonElement element) throws MolangException {
        if (!element.isJsonPrimitive()) {
            return ZERO;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return new MolangValue(this, new Constant(primitive.getAsDouble()));
        }
        if (primitive.isString()) {
            String string = primitive.getAsString();
            try {
                return new MolangValue(this, new Constant(Double.parseDouble(string)));
            } catch (NumberFormatException ex) {
                return parseExpression(string);
            }
        }
        return ZERO;
    }

    /**
     * 解析一个 MoLang 表达式
     */
    public MolangExpression parseExpression(String expression) throws MolangException {
        MolangMultiStatement result = null;
        for (String split : splitStatements(lowerCaseOutsideStrings(expression).trim())) {
            String trimmed = split.trim();
            if (!trimmed.isEmpty()) {
                // Remembered for reportParseFailure: the warning used to name only the reason, so a log could say
                // "There is no such operator ':'" without ever telling anyone which expression caused it.
                this.parsingExpression = trimmed;
                if (result == null) {
                    result = new MolangMultiStatement(this);
                }
                result.expressions.add(parseOneLine(trimmed, result));
            }
        }
        if (result == null) {
            throw new MolangException("Molang expression cannot be blank!");
        }
        return result;
    }

    /**
     * 按顶层 {@code ;} 切分语句。
     *
     * <p>顶层 = 不在字符串字面量里、也不在花括号执行作用域里。upstream 的左花括号是一个执行作用域
     * （{@code molang/parser/MolangParserImpl.java:90-118}），块内的 {@code ;} 是块内语句的分隔符，
     * 不是表达式之间的分隔符；在块内按 {@code ;} 切开会把一个块切成以右花括号开头、或由右花括号加冒号
     * 开头、或以问号加左花括号开头的碎片，每一片都解析失败。
     *
     * <p>深度钳在 0：多出来的右花括号只是一条解析错误的语句，不能让深度变负而把后面所有
     * {@code ;} 都吞掉（那会把整条 timeline 变成一句）。
     */
    public static List<String> splitStatements(String expression) throws MolangException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(expression.length());
        boolean quoted = false;
        char quote = 0;
        int braceDepth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                current.append(c);
                if (c == '\\' && i + 1 < expression.length()) {
                    current.append(expression.charAt(++i));
                } else if (c == quote) {
                    quoted = false;
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                current.append(c);
            } else if (c == '{') {
                braceDepth++;
                current.append(c);
            } else if (c == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                }
                current.append(c);
            } else if (c == ';' && braceDepth == 0) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new MolangException("Unclosed string literal in Molang expression!");
        }
        statements.add(current.toString());
        return statements;
    }

    /**
     * OpenYSM/Bedrock 的花括号执行作用域（{@code {a;b;}}），以及以作用域为分支的三元表达式
     * （{@code cond?{a;b}:{c}}，含无冒号的 {@code cond?{a;b;}}）。
     *
     * <p>upstream 的 Molang 前端有真正的作用域语法：{@code '{'} 在
     * {@code molang/parser/MolangParserImpl.java:90-118} 被读成 {@code ExecutionScopeExpression}
     * （{@code molang/parser/ast/ExecutionScopeExpression.java:42-56}），求值时块内表达式按顺序执行、
     * 块的值为最后一条（{@code molang/runtime/ExpressionEvaluatorImpl.java:241-257}）；三元只执行被选中的
     * 那一支（同文件 {@code :393-399}）；没有冒号的 {@code cond?value} 在条件为假时给 0
     * （{@code MolangParserImpl.java:269} + {@code ExpressionEvaluatorImpl.java:105-111}）。
     * 本端口的表达式引擎是 mclib 的 {@link MathBuilder}，分词器不认识 {@code '{'}，所以在把语句交给
     * 分词器之前，这里按同一语义把它构造成惰性求值的 {@link MolangExpression}：块是
     * {@link MolangMultiStatement}（顺序执行、取最后一条），三元是只在 {@code get()} 时选分支的
     * {@link IValue}。端口自己的 {@code PackFunctionScript}（{@code com/fox/ysmu/client/animation/molang/
     * PackFunctionScript.java:229-309}）对 {@code .molang} 函数文件用的就是这套形状。
     *
     * <p>不处理、保持原样的两种情况：作用域嵌在更大的算式中（如 {@code 1+{a;b}}）；以及 {@code {data3}}
     * 这类占位符 —— 它由 {@link #replaceBracePlaceholders} 取 0（艾莲·乔1.4.0 的 302 条通道依赖它），
     * 这里不能把它当成"块里只有一条语句"。
     *
     * @return 该语句的表达式；语句里没有作用域时返回 {@code null}，由原有分词路径处理
     */
    private MolangExpression parseBracedStatement(String expression, MolangMultiStatement currentStatement)
        throws MolangException {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // 赋值：`v.x = <作用域>` / `v.x = cond?{...}:{...}`。upstream 的赋值右侧就是一条普通表达式
        // （MolangParserImpl.java:78-104 的 ASSIGN 求值），块可以出现在那里。
        int assign = findTopLevelAssignment(trimmed);
        if (assign > 0) {
            String name = trimmed.substring(0, assign)
                .trim();
            if (!isPlainVariableName(name)) {
                return null;
            }
            MolangExpression value = parseBracedStatement(trimmed.substring(assign + 1), currentStatement);
            if (value == null) {
                return null;
            }
            return new MolangAssignment(
                this,
                getVariable(normalizeVariableName(name), currentStatement),
                value);
        }
        String body = enclosedScopeBody(trimmed);
        if (body != null) {
            return scopeValue(body);
        }
        int question = findTopLevelQuestion(trimmed);
        if (question < 0) {
            return null;
        }
        String condition = trimmed.substring(0, question)
            .trim();
        String rest = trimmed.substring(question + 1);
        int colon = findTernaryColon(rest);
        String whenTrue = (colon < 0 ? rest : rest.substring(0, colon)).trim();
        String whenFalse = colon < 0 ? null : rest.substring(colon + 1)
            .trim();
        String trueBody = enclosedScopeBody(whenTrue);
        String falseBody = whenFalse == null ? null : enclosedScopeBody(whenFalse);
        if (trueBody == null && falseBody == null) {
            // 两个分支都是普通表达式：这是已有的三元路径（mclib 的 tryTernary），不要抢过来。
            return null;
        }
        final MolangExpression conditionValue = parseOneLine(condition, currentStatement);
        final MolangExpression trueValue = trueBody == null ? parseOneLine(whenTrue, currentStatement)
            : scopeValue(trueBody);
        final MolangExpression falseValue;
        if (whenFalse == null) {
            falseValue = ZERO;
        } else {
            falseValue = falseBody == null ? parseOneLine(whenFalse, currentStatement) : scopeValue(falseBody);
        }
        return new MolangValue(this, new IValue() {

            @Override
            public double get() {
                // 只执行被选中的那一支：upstream ExpressionEvaluatorImpl.java:393-399
                return conditionValue.get() != 0 ? trueValue.get() : falseValue.get();
            }
        });
    }

    /**
     * 一个执行作用域：块内语句按顺序执行，整个块的值是最后一条语句的值。
     * <p>
     * 对应 upstream 的 {@code ExecutionScopeExpression}
     * （{@code molang/runtime/ExpressionEvaluatorImpl.java:241-257}：逐条求值，返回最后一条的结果）。
     * 块内的名字解析走本块自己的 {@link MolangMultiStatement#locals}，即 upstream 的"新作用域"：
     * 块内新建的非 {@code v.} 名字只在块内可见（{@code v.*} 与 {@code v.roaming.*} 走共享变量表，
     * 不受影响，模型包的 timeline 用的就是它们）。
     */
    private MolangExpression scopeValue(String body) throws MolangException {
        MolangMultiStatement scope = new MolangMultiStatement(this);
        for (String statement : splitStatements(body)) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                scope.expressions.add(parseOneLine(trimmed, scope));
            }
        }
        return scope;
    }

    /**
     * 整个字符串就是一个花括号组时返回组内内容，否则返回 {@code null}。
     * <p>
     * 只有单个标识符的组（{@code {data3}}）不算作用域：它是本端口既有的占位符写法，由
     * {@link #replaceBracePlaceholders} 取 0，改判会让艾莲·乔1.4.0 的 302 条通道重新解析失败。
     */
    private static String enclosedScopeBody(String expression) {
        String trimmed = expression.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        int close = findMatchingBrace(trimmed, 0);
        if (close != trimmed.length() - 1) {
            return null;
        }
        String body = trimmed.substring(1, close);
        return isBracePlaceholder(body) ? null : body;
    }

    private static boolean isBracePlaceholder(String body) {
        String trimmed = body.trim();
        return !trimmed.isEmpty() && isPlainVariableName(trimmed);
    }

    private static boolean isPlainVariableName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '_' && c != '.' && !Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return !name.isEmpty();
    }

    /** {@code name = ...} 里顶层 {@code =} 的下标；{@code ==}/{@code !=}/{@code <=}/{@code >=} 不算，没有则 -1。 */
    private static int findTopLevelAssignment(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (isQuote(c)) {
                i = skipString(expression, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == '=' && depth == 0) {
                char previous = i > 0 ? expression.charAt(i - 1) : 0;
                char next = i + 1 < expression.length() ? expression.charAt(i + 1) : 0;
                if (previous == '=' || previous == '!' || previous == '<' || previous == '>' || next == '=') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    /** 顶层 {@code ?} 的下标（跳过字符串与括号，{@code ??} 不算），没有则 -1。 */
    private static int findTopLevelQuestion(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (isQuote(c)) {
                i = skipString(expression, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == '?' && depth == 0
                && !(i + 1 < expression.length() && expression.charAt(i + 1) == '?')) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 与某个 {@code ?} 配对的顶层 {@code :} 的下标；入参是 {@code ?} 之后的剩余文本。
     * <p>
     * 三元从右往左结合，所以真值分支里更内层的 {@code ?} 会把它的 {@code :} 一起吃掉
     * （upstream 也是这么读的：{@code MolangParserImpl.java:256-271}）。没有配对时返回 -1，
     * 即无冒号的 {@code cond?value}。
     */
    private static int findTernaryColon(String expression) {
        int depth = 0;
        int nested = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (isQuote(c)) {
                i = skipString(expression, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0) {
                if (c == '?' && !(i + 1 < expression.length() && expression.charAt(i + 1) == '?')) {
                    nested++;
                } else if (c == ':') {
                    if (nested == 0) {
                        return i;
                    }
                    nested--;
                }
            }
        }
        return -1;
    }

    /** {@code openIndex} 处 {@code '{'} 的配对 {@code '}'} 下标；没有则 -1。 */
    private static int findMatchingBrace(String expression, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (isQuote(c)) {
                i = skipString(expression, i);
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isQuote(char c) {
        return c == '\'' || c == '"';
    }

    /** {@code quoteIndex} 处字符串字面量的收尾引号下标；没有收尾（非法输入）时返回末尾下标。 */
    private static int skipString(String expression, int quoteIndex) {
        char quote = expression.charAt(quoteIndex);
        for (int i = quoteIndex + 1; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return expression.length() - 1;
    }

    @Override
    public String[] breakdown(String expression) throws Exception {
        return super.breakdown(rewriteOpenYsmExpression(expression));
    }

    /**
     * 解析单个 MoLang 表达式
     */
    protected MolangExpression parseOneLine(String expression, MolangMultiStatement currentStatement)
        throws MolangException {
        if (expression.startsWith(RETURN)) {
            try {
                return new MolangValue(this, parse(expression.substring(RETURN.length()))).addReturn();
            } catch (Exception e) {
                throw new MolangException("Couldn't parse return '" + expression + "' expression!");
            }
        }

        // 花括号执行作用域 / 以作用域为分支的三元：必须在 mclib 分词之前拦下，'{' 不是合法记号。
        MolangExpression braced = this.parseBracedStatement(expression, currentStatement);
        if (braced != null) {
            return braced;
        }

        // Handle null-coalescing operator: a ?? b
        // Intended semantics: use a if it was explicitly set (even to 0),
        // otherwise fall back to b.  The original implementation checked
        // l != 0, which treated explicit 0 the same as "never set".
        int ncIdx = findNullCoalesce(expression);
        if (ncIdx > 0) {
            String leftExpr = expression.substring(0, ncIdx).trim();
            String rightExpr = expression.substring(ncIdx + 2).trim();
            MolangExpression leftVal = parseOneLine(leftExpr, currentStatement);
            MolangExpression rightVal = parseOneLine(rightExpr, currentStatement);
            return new MolangValue(this, new com.eliotlash.mclib.math.IValue() {
                @Override
                public double get() {
                    double l = leftVal.get();
                    // Check if the variable was EXPLICITLY set by the user (via GUI/config),
                    // not just default-initialized.  Default-initialized variables are in
                    // PENDING_ROAMING but NOT in EXPLICIT_ROAMING, so they still fall
                    // through to the default when their value is 0.
                    boolean userSet = false;
                    ExplicitVariableLookup lookup = explicitVariableLookup;
                    if (lookup != null) {
                        userSet = lookup.isExplicitlySet(leftExpr);
                    }
                    if (userSet || l != 0) {
                        return l;
                    }
                    // 上游的 `??` 只在左侧为 null 时才访问右侧（ExpressionEvaluatorImpl.java:70-77）；本引擎没有
                    // null，"未定义"就是 0。以前两侧都先求值，于是 `v.flame_level ?? (v.flame_level = …)`
                    // 这类"只初始化一次"的写法每帧都会重跑右侧、把变量重写一遍。
                    return rightVal.get();
                }
            });
        }

        try {
            // 将表达式拆分
            List<Object> symbols = breakdownChars(this.breakdown(expression));
            // 如果是赋值表达式
            if (symbols.size() >= 3 && (symbols.get(0) instanceof String name)
                && isVariable(symbols.get(0))
                && symbols.get(1)
                    .equals("=")) {
                symbols = symbols.subList(2, symbols.size());
                name = normalizeVariableName(name);
                LazyVariable variable;
                if (!name.startsWith("v.")
                    && !VARIABLES.containsKey(name)
                    && !currentStatement.locals.containsKey(name)) {
                    currentStatement.locals.put(name, (variable = new LazyVariable(name, 0)));
                } else {
                    variable = getVariable(name, currentStatement);
                }
                return new MolangAssignment(this, variable, parseSymbolsMolang(symbols));
            }
            // 如果是其他表达式
            return new MolangValue(this, parseSymbolsMolang(symbols));
        } catch (Exception e) {
            throw new MolangException("Couldn't parse '" + expression + "' expression!", e);
        }
    }

    /**
     * 将 parseSymbols 方法包装，并抛出 MolangException
     */
    private IValue parseSymbolsMolang(List<Object> symbols) throws MolangException {
        try {
            return this.parseSymbols(symbols);
        } catch (Exception e) {
            reportParseFailure(e);
            throw new MolangException("Couldn't parse an expression!", e);
        }
    }

    /**
     * OpenYSM 支持三元/括号内的嵌套赋值（如 {@code (cond) ? (v.wet = 30) : 0}）。
     * mclib 的 {@link Operation#ASSIGN} 只是纯函数（返回右值、不写回变量），
     * 无法产生副作用，因此这里把 {@code [var, "=", expr...]} 模式改写为带
     * 副作用的 {@link MolangAssignment}。
     */
    @Override
    public IValue parseSymbols(List<Object> symbols) throws Exception {
        if (symbols.size() >= 3
            && symbols.get(0) instanceof String
            && symbols.get(1) instanceof String
            && "=".equals(symbols.get(1))
            && isVariable(symbols.get(0))) {
            String name = normalizeVariableName((String) symbols.get(0));
            LazyVariable variable = getVariable(name);
            IValue value = parseSymbols(symbols.subList(2, symbols.size()));
            return new MolangAssignment(this, variable, value);
        }

        // The ternary is resolved before anything else, which is upstream's precedence: in its Pratt parser `?:`
        // sits at the lowest level (PRECEDENCE_QUES), so it is decided outside every other operator *and* outside
        // unary minus ({@code molang/parser/MolangParserImpl.java}'s ternary branch runs after the compound
        // expression's operators). MathBuilder#parseSymbols instead tries its leading-unary-minus repair first, and
        // that repair slices the symbol list before a ternary is recognised: {@code a?(b):-(c)} recursed into a slice
        // that still held ':' and threw {@code There is no such operator ':'} - the line the live log shows for
        // {@code (query.vertical_speed>0)?(math.clamp(…)):-(math.clamp(…))}. Both branches of that expression parse
        // on their own, so taking the ternary first fixes it and leaves every non-ternary list to MathBuilder.
        IValue ternary = this.tryTernary(symbols);
        if (ternary != null) {
            return ternary;
        }
        return super.parseSymbols(symbols);
    }

    /**
     * Finds the first {@code ??} (null-coalescing) operator in the expression,
     * skipping over parenthesized groups and string literals.
     * Returns the index of the first {@code ?}, or -1 if not found.
     */
    private static int findNullCoalesce(String expression) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < expression.length() - 1; i++) {
            char c = expression.charAt(i);
            if (c == '\'' || c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && c == '?' && expression.charAt(i + 1) == '?') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析失败的诊断：同一个原因只报一次，且不再往 stderr 打整条栈。
     * <p>
     * 模型包是第三方数据，一个未注册的函数就会让整条表达式解析失败。这条路径以前对**每一次**失败调用
     * {@code printStackTrace()}：Wine Fox &amp; Friends 一个包就因为 {@code query.position} 等名字失败 79 次、
     * 每次 12 行栈，单次会话刷出 3985 行 STDERR。表达式仍然失败（语义不变），但原因只记一行；栈降级为 debug，
     * 并且同样只在该原因第一次出现时输出。
     */
    private void reportParseFailure(Exception failure) {
        String reason = failure.getMessage() == null ? failure.getClass()
            .getName() : failure.getMessage();
        if (!REPORTED_PARSE_FAILURES.add(reason)) {
            return;
        }
        // The expression itself rides along (first occurrence of this reason only, so the line count is unchanged):
        // pack authors and this repository both need to know WHICH expression produced the reason.
        LOG.warn(
            "Molang expression could not be parsed: {} — first occurrence: {}",
            reason,
            this.parsingExpression);
        LOG.debug("Molang parse failure detail", failure);
    }

    /**
     * 拓展此方法，从而让 {@link #breakdownChars(String[])} 能够解析等号
     * 这样就能更加轻松解析赋值表达式
     */
    @Override

    protected boolean isOperator(String s) {
        return super.isOperator(s) || s.equals("=") || s.equals("??");
    }

    private static String lowerCaseOutsideStrings(String expression) {
        StringBuilder out = new StringBuilder(expression.length());
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                out.append(c);
                if (c == '\\' && i + 1 < expression.length()) {
                    out.append(expression.charAt(++i));
                } else if (c == quote) {
                    quoted = false;
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                out.append(c);
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static String rewriteOpenYsmExpression(String expression) throws MolangException {
        String rewritten = replaceStringLiterals(expression);
        rewritten = replaceBracePlaceholders(rewritten);
        // wiki: molang/var —— query. 可以缩写成 q.。normalizeVariableName 只覆盖变量，
        // 函数是按字面名在 functions 表里查的，所以这里必须把 q.xxx( 改写成 query.xxx(，
        // 否则 q.max_durability('mainhand') 这类关键帧表达式整条解析失败。
        rewritten = rewriteQueryAbbreviation(rewritten);
        for (String vector : VECTOR_FUNCTIONS) {
            rewritten = rewriteVectorFunction(rewritten, vector, vector);
        }
        return rewritten;
    }

    /**
     * The vector bone queries: a {@code ysm.bone_*('Arm')} call whose {@code .x}/{@code .y}/{@code .z} member access
     * this engine cannot parse, because mclib's {@code IValue} is a bare {@code double}. Upstream needs no such
     * table - it hands back a struct and the member access is a real AST node
     * ({@code molang/parser/MolangParserImpl.java:132-150}, {@code runtime/ExpressionEvaluatorImpl.java:317-325}).
     * <p>
     * One table, two readers: {@link #rewriteOpenYsmExpression} rewrites the member-access form into the flat
     * {@code _x}/{@code _y}/{@code _z} registrations, and hosts that diagnose a pack's expressions
     * ({@code com.fox.ysmu.client.animation.molang.MolangInstructionExecutor}) ask {@link #isVectorFunctionName}
     * so they do not report a name this step supports as "not registered".
     */
    public static final String[] VECTOR_FUNCTIONS = { "ysm.bone_rot", "ysm.bone_pos", "ysm.bone_position",
        "ysm.bone_scale", "ysm.bone_pivot_abs" };

    /**
     * Whether {@code name} is the unsuffixed form of a vector bone query, i.e. a name
     * {@link #rewriteOpenYsmExpression} handles by rewriting it (and which is also registered directly, so a position
     * the rewrite does not reach still parses).
     */
    public static boolean isVectorFunctionName(String name) {
        for (String vector : VECTOR_FUNCTIONS) {
            if (vector.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code q.}<b>函数调用</b> → {@code query.}。只匹配调用形式（后面紧跟 {@code (}），
     * 且要求前面不是标识符字符或 {@code .}，这样 {@code v.q}、{@code v.aq} 不会被误改
     * （变量缩写已由 {@link #normalizeVariableName} 处理）。
     *
     * <p>调用点在 {@code replaceStringLiterals} 之后，表达式里已无字符串字面量，
     * 因此不必再担心改到引号内容。</p>
     */
    private static final Pattern QUERY_ABBREVIATION_CALL =
        Pattern.compile("(?<![\\w.])q\\.([a-z_][a-z0-9_]*)\\s*\\(");

    private static String rewriteQueryAbbreviation(String expression) {
        if (expression.indexOf("q.") < 0) {
            return expression;
        }
        Matcher matcher = QUERY_ABBREVIATION_CALL.matcher(expression);
        StringBuilder out = new StringBuilder(expression.length() + 16);
        int last = 0;
        while (matcher.find()) {
            out.append(expression, last, matcher.start());
            out.append("query.").append(matcher.group(1)).append('(');
            last = matcher.end();
        }
        if (last == 0) {
            return expression;
        }
        out.append(expression, last, expression.length());
        return out.toString();
    }

    /** Placeholder names already reported, so a channel evaluated every frame warns once. */
    private static final java.util.Set<String> REPORTED_BRACE_PLACEHOLDERS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Replaces a brace placeholder such as {@code {data3}} with {@code 0}.
     * <p>
     * A pack can write a value the authoring tool kept in a separate data table, for example the animation file of
     * 艾莲·乔1.4.0 writes {@code (v.hold?(v.speed?0.24:{data3}))} in its {@code run} and {@code walk} position
     * channels - 302 expressions across those two animations, and nothing in the pack defines {@code data3}. The
     * tokenizer rejects '{' as an illegal character, so every one of those channels failed to parse and the animation
     * silently lost the bones it drove, which is what made that model's walking and running look broken while its
     * numeric channels were fine.
     * <p>
     * The placeholder's value is unknown to this runtime, so it becomes the neutral 0 - the same answer an unset
     * {@code v.*} variable gives - and the name is warned about once, because a pack that really needs a non-zero
     * value there has to be told apart from one that does not. Quoted strings are pooled before this runs, so a brace
     * inside a string literal is untouched.
     */
    private static String replaceBracePlaceholders(String expression) {
        if (expression.indexOf('{') < 0) {
            return expression;
        }
        StringBuilder out = new StringBuilder(expression.length());
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current == '{') {
                int close = expression.indexOf('}', index + 1);
                if (close > index + 1) {
                    String name = expression.substring(index + 1, close)
                        .trim();
                    if (REPORTED_BRACE_PLACEHOLDERS.add(name)) {
                        LOG.warn(
                            "Molang brace placeholder '{{{}}}' has no value in this runtime; using 0 for it",
                            name);
                    }
                    out.append('0');
                    index = close + 1;
                    continue;
                }
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static String replaceStringLiterals(String expression) throws MolangException {
        StringBuilder out = new StringBuilder(expression.length());
        boolean quoted = false;
        char quote = 0;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < expression.length()) {
                    literal.append(expression.charAt(++i));
                } else if (c == quote) {
                    quoted = false;
                    out.append(MolangStringPool.intern(literal.toString()));
                    literal.setLength(0);
                } else {
                    literal.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else {
                out.append(c);
            }
        }
        if (quoted) {
            throw new MolangException("Unclosed string literal in Molang expression!");
        }
        return out.toString();
    }

    /**
     * Rewrites the <b>member-access</b> form of a vector bone query: a call to {@code sourceFunction} whose closing
     * parenthesis is followed immediately by {@code ).x}/{@code ).y}/{@code ).z} becomes the flat, registered
     * {@code <targetPrefix>_<axis>(same args)}. No regex - a literal {@code indexOf(name + "(")} plus a balanced
     * parenthesis scan - so the match is exactly "the call's own {@code )} is the {@code } of {@code ).<axis>}".
     * <p>
     * That is the whole of what this step covers, and it is deliberate: a suffix appearing after anything at all
     * ({@code ysm.bone_scale('X').y} inside {@code (-…)*-1}, say) still rewrites, because the call's own {@code )} is
     * still the next character, while a vector call in a position with no member access - bare, or as an argument to
     * another function ({@code math.max(ysm.bone_pivot_abs('Root'), …)}) - is not this step's business. Those
     * positions are covered by the bare names being registered in {@link #doCoreRemaps()}, which is why a missing
     * rewrite there is no longer a parse failure. Deleting the bare registrations without extending this method would
     * reintroduce it.
     */
    private static String rewriteVectorFunction(String expression, String sourceFunction, String targetPrefix) {
        String out = expression;
        for (char axis : new char[] { 'x', 'y', 'z' }) {
            String suffix = ")." + axis;
            int searchFrom = 0;
            while (true) {
                int functionStart = out.indexOf(sourceFunction + "(", searchFrom);
                if (functionStart < 0) {
                    break;
                }
                int argsStart = functionStart + sourceFunction.length() + 1;
                int argsEnd = findMatchingParen(out, argsStart - 1);
                if (argsEnd < 0 || !out.startsWith(suffix, argsEnd)) {
                    searchFrom = argsStart;
                    continue;
                }
                String args = out.substring(argsStart, argsEnd);
                String replacement = targetPrefix + "_" + axis + "(" + args + ")";
                out = out.substring(0, functionStart) + replacement + out.substring(argsEnd + suffix.length());
                searchFrom = functionStart + replacement.length();
            }
        }
        return out;
    }

    private static int findMatchingParen(String expression, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
