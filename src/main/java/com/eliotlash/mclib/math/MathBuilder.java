package com.eliotlash.mclib.math;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eliotlash.mclib.math.functions.Function;
import com.eliotlash.mclib.math.functions.classic.*;
import com.eliotlash.mclib.math.functions.limit.Clamp;
import com.eliotlash.mclib.math.functions.limit.Max;
import com.eliotlash.mclib.math.functions.limit.Min;
import com.eliotlash.mclib.math.functions.rounding.Ceil;
import com.eliotlash.mclib.math.functions.rounding.Floor;
import com.eliotlash.mclib.math.functions.rounding.Round;
import com.eliotlash.mclib.math.functions.rounding.Trunc;
import com.eliotlash.mclib.math.functions.utility.*;

public class MathBuilder {

    public Map<String, Variable> variables = new HashMap<String, Variable>();

    public Map<String, Class<? extends Function>> functions = new HashMap<String, Class<? extends Function>>();

    public MathBuilder() {
        /* 默认值 */
        this.register(new Variable("PI", Math.PI));
        this.register(new Variable("E", Math.E));

        /* 取整函数 */
        this.functions.put("floor", Floor.class);
        this.functions.put("round", Round.class);
        this.functions.put("ceil", Ceil.class);
        this.functions.put("trunc", Trunc.class);

        /* 比较函数 */
        this.functions.put("clamp", Clamp.class);
        this.functions.put("max", Max.class);
        this.functions.put("min", Min.class);

        /* 经典数学函数 */
        this.functions.put("abs", Abs.class);
        this.functions.put("exp", Exp.class);
        this.functions.put("ln", Ln.class);
        this.functions.put("sqrt", Sqrt.class);
        this.functions.put("mod", Mod.class);
        this.functions.put("pow", Pow.class);

        /* 三角函数 */
        this.functions.put("cos", Cos.class);
        this.functions.put("sin", Sin.class);
        this.functions.put("acos", ACos.class);
        this.functions.put("asin", ASin.class);
        this.functions.put("atan", ATan.class);
        this.functions.put("atan2", ATan2.class);

        /* 实用工具 */
        this.functions.put("lerp", Lerp.class);
        this.functions.put("lerprotate", LerpRotate.class);
        this.functions.put("random", Random.class);
        this.functions.put("randomi", RandomInteger.class);
        this.functions.put("roll", DieRoll.class);
        this.functions.put("rolli", DieRollInteger.class);
        this.functions.put("hermite", HermiteBlend.class);
    }

    /**
     * 注册变量
     */
    public void register(Variable variable) {
        this.variables.put(variable.getName(), variable);
    }

    /**
     * 将给定的数学表达式解析为可用于执行数学运算的 {@link IValue}
     */
    public IValue parse(String expression) throws Exception {
        return this.parseSymbols(this.breakdownChars(this.breakdown(expression)));
    }

    /**
     * 分解表达式
     */
    public String[] breakdown(String expression) throws Exception {
        // 如果给定的字符串包含非法字符，则无法解析。方括号是 args[i] 需要的语法。
        // 字符串字面量在 MolangParser#breakdown 里已经被替换成字符串池 id，不需要在这里放行引号。
        if (!expression.matches("^[\\w\\d\\s_+-/*%^&|<>=!?:.,()\\[\\]]+$")) {
            throw new Exception("Given expression '" + expression + "' contains illegal characters!");
        }
        // 删除所有空格以及前导和尾随括号
        expression = expression.replaceAll("\\s+", "");
        String[] chars = expression.split("(?!^)");
        int left = 0;
        int right = 0;
        for (String s : chars) {
            if ("(".equals(s)) {
                left++;
            } else if (")".equals(s)) {
                right++;
            }
        }
        // 左括号和右括号的数量应相同
        if (left != right) {
            throw new Exception(
                "Given expression '" + expression
                    + "' has more uneven amount of parenthesis, there are "
                    + left
                    + " open and "
                    + right
                    + " closed!");
        }
        return chars;
    }

    /**
     * 将分解字符转换为数学表达式符号列表
     */
    public List<Object> breakdownChars(String[] chars) {
        List<Object> symbols = new ArrayList<>();
        String buffer = "";
        int len = chars.length;
        for (int i = 0; i < len; i++) {
            String s = chars[i];
            boolean longOperator = i > 0 && this.isOperator(chars[i - 1] + s);
            // 双字符运算符（== != <= >= && ||）的第一个字符也可能自己就是一个完整运算符：'=' 就是
            // （Operation.ASSIGN）。那样它在上一轮已经被单独入栈，这一轮的长运算符分支会拿空 buffer 去
            // 做 substring(0, -1)，于是 `a==b` 直接抛 StringIndexOutOfBoundsException —— 旧解析器因此
            // 完全无法解析相等比较。把这一位先放进 buffer，下一次迭代自然会认出两位运算符。
            if (!longOperator && i + 1 < len && this.isOperator(s + chars[i + 1])) {
                buffer += s;
                continue;
            }
            if (this.isOperator(s) || longOperator || ",".equals(s)) {
                // 使用负号翻转数值的用法判定
                if ("-".equals(s)) {
                    int size = symbols.size();
                    boolean isFirst = size == 0 && buffer.isEmpty();
                    boolean isOperatorBehind = size > 0
                        && (this.isOperator(symbols.get(size - 1)) || ",".equals(symbols.get(size - 1)))
                        && buffer.isEmpty();
                    if (isFirst || isOperatorBehind) {
                        buffer += s;
                        continue;
                    }
                }
                if (longOperator) {
                    s = chars[i - 1] + s;
                    // 前一个字符可能在 buffer 中（标准情况），也可能已经被推入 symbols
                    // （当 isOperator 对单字符返回 true 时，如 MolangParser 的 "="）。
                    // YSMU 移植偏差（vendored com.eliotlash 树）：这两条清理是"或"的关系，不能先后都跑。
                    // 当上一个符号恰好等于前一个字符时——赋值 "=" 后面紧跟 "=="，即
                    // `v.x = v.y==0?1:v.y`——第二条会把**不属于本次合并**的赋值号从 symbols 里删掉，
                    // MolangParser#parseOneLine:393-396 于是认不出赋值（symbols.get(1) 不再是 "="），
                    // 整条落到 tryTernary，条件被读成 `[v.x, v.y, ==, 0]`：两个相邻操作数之间没有运算符，
                    // parseSymbols 走到 operationForOperator(symbols.get(lastOp))（lastOp == -1，本文件 :283）
                    // 抛 IndexOutOfBoundsException，外层报 "Couldn't parse '<lower-cased>' expression!"，
                    // pack 的整条 timeline 指令失效。实测形状与此吻合：失败的指令都是顶层紧跟 "=" 的三元
                    // （`v.Right_body_x_rotation = v.Right_handsmove == 0 ?query.body_x_rotation:...`），
                    // 而同一条 timeline 里括号内的三元正常；`=` 后面跟 `!=`/`<=`/`>=`/`??` 的也不受影响
                    // （它们合并时前一个字符不是 "="，本条 if 不会命中）。
                    // 上游基准没有这个形状可抄：tmp/YesSteveModel-dev-1.20 只 vendor 了 mclib 的 utils
                    // （NOTICE.md:20），它的 MoLang 走词法分析 + Pratt 解析，`=`/`==` 是不同的 token
                    // （molang/parser/MolangParserImpl.java:310-311），`?` 有独立优先级（:256-271），
                    // 不存在字符级合并；所以这里只做最小修正——让两条清理互斥。
                    if (buffer.length() > 0) {
                        buffer = buffer.substring(0, buffer.length() - 1);
                    } else if (!symbols.isEmpty() && symbols.get(symbols.size() - 1).equals(chars[i - 1])) {
                        symbols.remove(symbols.size() - 1);
                    }
                }
                // 推送 buffer 和操作符
                if (!buffer.isEmpty()) {
                    symbols.add(buffer);
                    buffer = "";
                }
                symbols.add(s);
            } else if ("(".equals(s)) {
                if (!buffer.isEmpty()) {
                    symbols.add(buffer);
                    buffer = "";
                }
                int counter = 1;
                for (int j = i + 1; j < len; j++) {
                    String c = chars[j];
                    if ("(".equals(c)) {
                        counter++;
                    } else if (")".equals(c)) {
                        counter--;
                    }
                    if (counter == 0) {
                        symbols.add(this.breakdownChars(buffer.split("(?!^)")));
                        i = j;
                        buffer = "";
                        break;
                    } else {
                        buffer += c;
                    }
                }
            } else if ("[".equals(s)) {
                // args[i]：把下标整段收成一个 Access 标记，交给 parseSymbols 决定它属于哪个名字
                if (!buffer.isEmpty()) {
                    symbols.add(buffer);
                    buffer = "";
                }
                StringBuilder inner = new StringBuilder();
                int counter = 1;
                for (int j = i + 1; j < len; j++) {
                    String c = chars[j];
                    if ("[".equals(c)) {
                        counter++;
                    } else if ("]".equals(c)) {
                        counter--;
                    }
                    if (counter == 0) {
                        symbols.add(new IndexValue.Access(this.breakdownChars(inner.toString().split("(?!^)"))));
                        i = j;
                        inner.setLength(0);
                        break;
                    }
                    inner.append(c);
                }
            } else {
                buffer += s;
            }
        }
        if (!buffer.isEmpty()) {
            symbols.add(buffer);
        }
        return symbols;
    }

    /**
     * Resolves a unary minus that {@code breakdownChars} left as its own symbol, or {@code null} when there is none.
     * <p>
     * A bare {@code "-"} is at a unary position exactly when it is the first symbol or follows an operator or a
     * comma; every other place it is the binary subtraction and must reach {@link #seekLastOperator}. The operator
     * immediately to the left is replaced along with the minus, so precedence is not reinterpreted: for
     * {@code v.a = -(1)*25} this evaluates {@code (-1)*25}, not {@code -(1*25)}.
     * <p>
     * A bare {@code "-"} can only appear before a parenthesised group, because the character loop folds a minus into
     * the token buffer for every other follower; that is why this one shape covers what the logs show.
     */
    private IValue parseLeadingUnaryMinus(List<Object> symbols) throws Exception {
        int size = symbols.size();
        for (int i = 0; i < size; i++) {
            if (!"-".equals(symbols.get(i))) {
                continue;
            }
            boolean operandPosition = i == 0
                || (this.isOperator(symbols.get(i - 1)) || ",".equals(symbols.get(i - 1)));
            if (!operandPosition || i + 1 >= size) {
                return null;
            }
            IValue value = new Negative(this.parseSymbols(symbols.subList(i + 1, size)));
            if (i == 0) {
                return value;
            }
            IValue left = this.parseSymbols(symbols.subList(0, i - 1));
            return new Operator(this.operationForOperator((String) symbols.get(i - 1)), left, value);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public IValue parseSymbols(List<Object> symbols) throws Exception {
        // 一元负号：`breakdownChars` 只在 token 直接跟在负号后面时把两者合进同一个 buffer
        // （`-1`、`-v.x`、`-!v.x`）；`(` 分支却会把缓冲里的 `-` 先单独入栈，于是
        // `-(1)/25` 的符号表是 `[-, [1], /, 25]`，`10*-(2)` 是 `[10, *, -, [2]]`。这两种形状以前都
        // 解析失败：seekLastOperator 找到二元运算符后，左侧/右侧递归到 `[-, [1]]` 或单个 `-`，
        // lastOp == -1，`operationForOperator(symbols.get(-1))` 抛 IndexOutOfBoundsException，
        // 整条表达式判死。实盘形状：模型粒子的 `-(ysm.bone_pivot_abs('X').z)/25`，整条指令被丢弃。
        // 只处理 `-`：`!` 的分离形式同样会走到这里，但取非在 valueFromObject 里已有路径，而合并形式
        // `-!v.x` / `!-v.x` 是单个 token、不进入本方法。
        // 上游没有这个形状：它有自己的词法器与 Pratt 解析（molang/parser/MolangParserImpl.java），
        // 一元负号是 UnaryExpression（:156-158），不依赖字符级合并。
        IValue leadingMinus = parseLeadingUnaryMinus(symbols);
        if (leadingMinus != null) {
            return leadingMinus;
        }
        IValue ternary = this.tryTernary(symbols);
        if (ternary != null) {
            return ternary;
        }
        // 这里以前是：symbols 里还留着 '?' 就整条返回常量 0，用来避免把非法三元的 '?' 当成二元运算符。
        // 那样会连带废掉 YSM/Bedrock 的另一种写法 cond?value（没有冒号，等价于 cond ? value : 0）——
        // Operation.CONDITIONAL 现在负责它，否则 "-4+(!v.fm?50)"、"v.hold?!v.speed"、
        // "(!v.leftbow&&!v.rightbow?(0.2))" 全部恒为 0。
        // 合法的 a?b:c 在上面已被 tryTernary 取走；这里剩下的 ':' 只可能来自链式三元的错配上，
        // 而 operationForOperator(":") 会抛异常并把整条表达式判死，所以仍兜底为 0。
        if (symbols.contains(":")) {
            return new IValue() {
                @Override
                public double get() {
                    return 0;
                }
            };
        }

        int size = symbols.size();
        // 常量、变量或组（括号）
        if (size == 1) {
            return this.valueFromObject(symbols.get(0));
        }
        // 函数
        if (size == 2) {
            Object first = symbols.get(0);
            Object second = symbols.get(1);
            if (second instanceof IndexValue.Access && first instanceof String) {
                return new IndexValue((String) first, this.parseSymbols(((IndexValue.Access) second).symbols));
            }
            if ((this.isVariable(first) || "-".equals(first)) && second instanceof List) {
                return this.createFunction((String) first, (List<Object>) second);
            }
        }
        // 其他数学表达式
        int lastOp = this.seekLastOperator(symbols);
        int op = lastOp;

        while (op != -1) {
            int leftOp = this.seekLastOperator(symbols, op - 1);
            if (leftOp != -1) {
                Operation left = this.operationForOperator((String) symbols.get(leftOp));
                Operation right = this.operationForOperator((String) symbols.get(op));
                if (right.value > left.value) {
                    IValue leftValue = this.parseSymbols(symbols.subList(0, leftOp));
                    IValue rightValue = this.parseSymbols(symbols.subList(leftOp + 1, size));
                    return new Operator(left, leftValue, rightValue);
                } else if (left.value > right.value) {
                    Operation initial = this.operationForOperator((String) symbols.get(lastOp));
                    if (initial.value < left.value) {
                        IValue leftValue = this.parseSymbols(symbols.subList(0, lastOp));
                        IValue rightValue = this.parseSymbols(symbols.subList(lastOp + 1, size));
                        return new Operator(initial, leftValue, rightValue);
                    }
                    IValue leftValue = this.parseSymbols(symbols.subList(0, op));
                    IValue rightValue = this.parseSymbols(symbols.subList(op + 1, size));
                    return new Operator(right, leftValue, rightValue);
                }
            }
            op = leftOp;
        }
        Operation operation = this.operationForOperator((String) symbols.get(lastOp));
        return new Operator(
            operation,
            this.parseSymbols(symbols.subList(0, lastOp)),
            this.parseSymbols(symbols.subList(lastOp + 1, size)));
    }

    protected int seekLastOperator(List<Object> symbols) {
        return this.seekLastOperator(symbols, symbols.size() - 1);
    }

    protected int seekLastOperator(List<Object> symbols, int offset) {
        for (int i = offset; i >= 0; i--) {
            Object o = symbols.get(i);
            if (this.isOperator(o)) {
                return i;
            }
        }
        return -1;
    }

    protected int seekFirstOperator(List<Object> symbols) {
        return this.seekFirstOperator(symbols, 0);
    }

    protected int seekFirstOperator(List<Object> symbols, int offset) {
        for (int i = offset, size = symbols.size(); i < size; i++) {
            Object o = symbols.get(i);
            if (this.isOperator(o)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 尝试解析三元表达式
     */
    protected IValue tryTernary(List<Object> symbols) throws Exception {
        int question = -1;
        int questions = 0;
        int colon = -1;
        int colons = 0;
        int size = symbols.size();
        for (int i = 0; i < size; i++) {
            Object object = symbols.get(i);
            if (object instanceof String) {
                if ("?".equals(object)) {
                    if (question == -1) {
                        question = i;
                    }
                    questions++;
                } else if (":".equals(object)) {
                    if (colons + 1 == questions && colon == -1) {
                        colon = i;
                    }
                    colons++;
                }
            }
        }
        if (questions == colons && question > 0 && question + 1 < colon && colon < size - 1) {
            return new Ternary(
                this.parseSymbols(symbols.subList(0, question)),
                this.parseSymbols(symbols.subList(question + 1, colon)),
                this.parseSymbols(symbols.subList(colon + 1, size)));
        }

        return null;
    }

    /**
     * 创建函数值
     */
    protected IValue createFunction(String first, List<Object> args) throws Exception {
        // 取非
        if ("!".equals(first)) {
            return new Negate(this.parseSymbols(args));
        }
        if (first.startsWith("!") && first.length() > 1) {
            return new Negate(this.createFunction(first.substring(1), args));
        }
        // 取负
        if ("-".equals(first)) {
            return new Negative(new Group(this.parseSymbols(args)));
        }
        if (first.startsWith("-") && first.length() > 1) {
            return new Negative(this.createFunction(first.substring(1), args));
        }
        List<IValue> values = new ArrayList<>();
        List<Object> buffer = new ArrayList<>();
        for (Object o : args) {
            if (",".equals(o)) {
                values.add(this.parseSymbols(buffer));
                buffer.clear();
            } else {
                buffer.add(o);
            }
        }
        if (!buffer.isEmpty()) {
            values.add(this.parseSymbols(buffer));
        }
        Class<? extends Function> function = this.functions.get(first);
        if (function == null) {
            // 没有静态注册的名字交给宿主解析（pack 的 fn.<name> 就是按模型动态决定函数体的）
            if (this.functionResolver != null) {
                IValue resolved = this.functionResolver.resolve(first, values);
                if (resolved != null) {
                    return resolved;
                }
            }
            throw new Exception("Function '" + first + "' couldn't be found!");
        }
        Constructor<? extends Function> constructor = function.getConstructor(IValue[].class, String.class);
        return constructor.newInstance(values.toArray(new IValue[0]), first);
    }

    /**
     * 动态函数解析：静态 {@link #functions} 表里没有的名字交给它，返回 {@code null} 表示确实不存在。
     * <p>
     * 存在的意义是 OpenYSM 的 pack 函数（{@code functions/*.molang}）：{@code fn.<name>} 的函数体随模型而定，
     * 无法在解析器上静态注册一个类。宿主在解析阶段返回一个惰性 {@link IValue}，求值时再按当前模型取函数体。
     */
    public interface FunctionResolver {

        IValue resolve(String name, List<IValue> arguments) throws Exception;
    }

    /** Installs (or clears, with {@code null}) the dynamic function resolver. */
    public void setFunctionResolver(FunctionResolver resolver) {
        this.functionResolver = resolver;
    }

    private FunctionResolver functionResolver;

    /**
     * 从对象中获取值
     */
    @SuppressWarnings("unchecked")
    public IValue valueFromObject(Object object) throws Exception {
        if (object instanceof String) {
            String symbol = (String) object;
            // 取非
            if (symbol.startsWith("!")) {
                return new Negate(this.valueFromObject(symbol.substring(1)));
            }
            if (this.isDecimal(symbol)) {
                return new Constant(Double.parseDouble(symbol));
            } else if (this.isVariable(symbol)) {
                // 取负
                if (symbol.startsWith("-")) {
                    symbol = symbol.substring(1);
                    // Handle -!prefix: "-!v.xxx" was tokenised as a single
                    // token (since ! is not an operator in breakdownChars).
                    // The ! has higher precedence than unary - per Bedrock
                    // Molang spec, so -!v.x ≡ Negative(Negate(v.x)).
                    if (symbol.startsWith("!")) {
                        return new Negative(
                            new Negate(this.valueFromObject(symbol.substring(1))));
                    }
                    Variable value = this.getVariable(symbol);

                    if (value != null) {
                        return new Negative(value);
                    }
                } else {
                    IValue value = this.getVariable(symbol);
                    // 避免 NPE
                    if (value != null) {
                        return value;
                    }
                }
            }
        } else if (object instanceof List) {
            return new Group(this.parseSymbols((List<Object>) object));
        }
        throw new Exception("Given object couldn't be converted to value! " + object);
    }

    protected Variable getVariable(String name) {
        return this.variables.get(name);
    }

    protected Operation operationForOperator(String op) throws Exception {
        for (Operation operation : Operation.values()) {
            if (operation.sign.equals(op)) {
                return operation;
            }
        }
        throw new Exception("There is no such operator '" + op + "'!");
    }

    protected boolean isVariable(Object o) {
        return o instanceof String && !this.isDecimal((String) o) && !this.isOperator((String) o);
    }

    protected boolean isOperator(Object o) {
        return o instanceof String && this.isOperator((String) o);
    }

    protected boolean isOperator(String s) {
        return Operation.OPERATORS.contains(s) || "?".equals(s) || ":".equals(s);
    }

    protected boolean isDecimal(String s) {
        return s.matches("^-?\\d+(\\.\\d+)?$");
    }
}
