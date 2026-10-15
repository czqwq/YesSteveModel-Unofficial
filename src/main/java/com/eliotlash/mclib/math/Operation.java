package com.eliotlash.mclib.math;

import software.bernie.geckolib3.core.molang.MolangException;

import java.util.HashSet;
import java.util.Set;

public enum Operation {

    // 优先级取自上游 BinaryExpression.Op 的相对顺序（tmp/YesSteveModel-dev-1.20
    // .../molang/parser/ast/BinaryExpression.java:116-132，一元负号见 UnaryExpression.java:101-116）：
    // ASSIGN(1) < NULL_COALESCE(1200) < CONDITIONAL(1400) < OR(1600) < AND(1800) < EQ/NEQ(2000)
    // < LT/LTE/GT/GTE(2200) < ADD/SUB(2400) < MUL/DIV(2600) < 一元负号(2800) < ARROW(3000)。
    // 本表数值越大越紧。POW/MOD 是 mclib 有、上游没有的两个运算符，按标准优先级插在 MUL/DIV 一档（MOD）
    // 与它们之上（POW）。旧的 mclib 原表把所有比较/相等/逻辑挤在同一个值 5（**比算术更紧**），
    // 于是 `0.8+v<=6?-0.9:0` 被读成 `0.8+(v<=6)`——条件恒真，随包 wine_fox/05_magical 的位移因此永远偏。
    ADD("+", 7) {

        @Override

        public double calculate(double a, double b) {
            return a + b;
        }
    },
    SUB("-", 7) {

        @Override

        public double calculate(double a, double b) {
            return a - b;
        }
    },
    MUL("*", 8) {

        @Override

        public double calculate(double a, double b) {
            return a * b;
        }
    },
    DIV("/", 8) {

        @Override

        public double calculate(double a, double b) {
            // 上游：Molang 允许除以零，结果恒为 0（ExpressionEvaluatorImpl.java:50-57
            // `if (divisor == 0) return 0;`）。旧实现是 `a / (b == 0 ? 1 : b)`，即 `5/0` 得 5。
            return b == 0 ? 0 : a / b;
        }
    },
    MOD("%", 8) {

        @Override

        public double calculate(double a, double b) {
            return a % b;
        }
    },
    POW("^", 9) {

        @Override

        public double calculate(double a, double b) {
            return Math.pow(a, b);
        }
    },
    AND("&&", 4) {

        @Override

        public double calculate(double a, double b) {
            // 短路本身在 MathBuilder#binary 的惰性值里（上游是 Supplier，Java `&&` 短路）；
            // 这里只保证"真值判定"与上游 asBoolean 一致。
            return asBoolean(a) && asBoolean(b) ? 1 : 0;
        }
    },
    OR("||", 3) {

        @Override

        public double calculate(double a, double b) {
            return asBoolean(a) || asBoolean(b) ? 1 : 0;
        }
    },
    LESS("<", 6) {

        @Override

        public double calculate(double a, double b) {
            return a < b ? 1 : 0;
        }
    },
    LESS_THAN("<=", 6) {

        @Override

        public double calculate(double a, double b) {
            return a <= b ? 1 : 0;
        }
    },
    GREATER_THAN(">=", 6) {

        @Override

        public double calculate(double a, double b) {
            return a >= b ? 1 : 0;
        }
    },
    GREATER(">", 6) {

        @Override

        public double calculate(double a, double b) {
            return a > b ? 1 : 0;
        }
    },
    EQUALS("==", 5) {

        @Override

        public double calculate(double a, double b) {
            // 上游数字相等是**精确**比较（ExpressionEvaluatorImpl.java:112-126：两侧都过
            // ValueConversions.asFloat 之后用 `==`），不是容差比较；旧实现走 Operation.equals 的 1e-5 容差，
            // 于是 `0.000001==0` 为真。
            return a == b ? 1 : 0;
        }
    },
    NOT_EQUALS("!=", 5) {

        @Override

        public double calculate(double a, double b) {
            return a != b ? 1 : 0;
        }
    },
    ASSIGN("=", 0) {
        @Override
        public double calculate(double a, double b) {
            return b;
        }
    },
    /**
     * Bedrock/YSM 的结构体（箭头）访问 {@code a -> b}：在 {@code a} 指出的**上下文**里求值 {@code b}。
     *
     * <p>上游 Molang 是对对象值做 Pratt 解析：{@code BinaryExpression.Op.ARROW} 是它优先级最高的二元运算符
     * （{@code tmp/YesSteveModel-dev-1.20 .../molang/parser/BinaryExpression.java:127}），求值器先算左边，再把右边
     * 放进一个**以该值为 subject 的子求值器**里跑
     * （{@code .../molang/runtime/ExpressionEvaluatorImpl.java:58-69}）。变量 wiki 记的用例正是这一支：
     * {@code v.flame_level ?? (v.flame_level = ysm.projectile_owner->ysm.equipped_enchantment_level('Mainhand',
     * 'minecraft:flame'))} —— 右边是**关于射击者**的查询（{@code tmp/wiki/molang_misc.md:148}、
     * {@code molang_ref.md:229}：{@code ysm.projectile_owner} 返回发射该箭矢的玩家实体，可用箭头表达式查询其属性）。
     *
     * <p>本引擎的求值器没有对象值——{@code IValue} 就是 {@code double}——所以左边只能是宿主认得的**句柄**，
     * 右边也只有在宿主能替换 subject 的地方才能跑。{@link MathBuilder.StructAccessHost} 就是那个宿主：由 mod 安装
     * （反向控制，与 {@code MolangParser.ysmFunctionRegistrar} 同一形状），两半都归它管。没有宿主时这个运算符
     * **无法求值**，因此解析阶段就带原因失败，而不是悄悄只answer 一侧。
     *
     * <p>优先级取全表最高：上游 ARROW 就是最紧的二元运算符，{@code a->b+1} 必须读成 {@code (a->b)+1}。
     */
    ARROW("->", 10) {
        @Override
        public double calculate(double a, double b) {
            // calculate 的契约是"两个 double 进、一个 double 出"，而箭头的语义是副作用（换 subject），所以它永远
            // 不该走到这里：MathBuilder#binary 会把 ARROW 交给宿主。会走到这里只有一种情形——没有安装宿主，那时
            // 宁可响亮失败也不要悄悄只取一侧。用非受检异常：这张表的签名不允许受检异常。
            throw new IllegalStateException(
                "'->' cannot be evaluated by the numeric operator table; install MathBuilder.structAccessHost");
        }
    },
    /**
     * Bedrock's null-coalescing {@code a ?? b}: use the left operand, or the right one when the left is undefined.
     * <p>
     * The numeric evaluator has no notion of "undefined", so an unset variable - which reads as 0 - takes the
     * fallback. Model packs rely on it for exactly that: {@code "scale": "v.player_size??1"} must scale by 1 until
     * the player picks a size. Without the operator the whole expression failed to tokenise (the two '?' characters
     * stayed in the buffer and the result was an empty symbol), the scale channel evaluated to 0 and the model
     * collapsed to a point. Precedence is below every other binary operator so the fallback applies last.
     */
    NULL_COALESCE("??", 1) {
        @Override
        public double calculate(double a, double b) {
            return a != 0 ? a : b;
        }
    },
    /**
     * YSM/Bedrock's colon-less conditional {@code cond?value}, i.e. {@code cond ? value : 0}.
     * <p>
     * Pack animation files use it as a compact "value, or nothing" switch - {@code "Root": {"position":
     * ["-4+(!v.fm?50)", ...]}, {@code "v.hold?!v.speed"}, {@code "q.ground_speed>0.8?-180"},
     * {@code "(!v.leftbow&&!v.rightbow?(0.2))"} - and they nest it inside a full ternary, for example
     * {@code "v.hold?(v.speed?-0.1:-0.05)"}. {@link MathBuilder#parseSymbols} used to answer any expression
     * that still contained a bare {@code '?'} after {@code tryTernary} with the constant 0, so every one of
     * those channels silently evaluated to 0: the whole model was drawn at a fixed offset and parts that
     * the pack shows conditionally never appeared. Precedence 0 binds looser than every other binary
     * operator, which is what a conditional needs; a well-formed {@code a?b:c} is still claimed by
     * {@link MathBuilder#tryTernary} before this operator is ever considered.
     */
    CONDITIONAL("?", 2) {
        @Override
        public double calculate(double a, double b) {
            return a != 0 ? b : 0;
        }
    };

    public final static Set<String> OPERATORS = new HashSet<String>();

    static {
        for (Operation op : values()) {
            OPERATORS.add(op.sign);
        }
    }

    /**
     * 此操作的字符串化名称
     */
    public final String sign;
    /**
     * 此操作相对于其他操作的优先级
     */
    public final int value;

    Operation(String sign, int value) {
        this.sign = sign;
        this.value = value;
    }

    public static boolean equals(double a, double b) {
        // 只服务 MolangExpression#isConstant 的"这是不是字面量 0/1"判定。`==` 运算符本身是精确比较
        // （见 EQUALS 的注释）；这里以前是 1e-5 容差，会让 0.000001 被当成 0。
        return a == b;
    }

    /**
     * 上游 {@code ValueConversions.asDouble(Object)}（molang/runtime/binding/ValueConversions.java:76-91）的
     * double 精度镜像：{@code NaN} 归 0，其余原样。
     *
     * <p>上游在每个"取值边界"都做这件事——二元运算符的操作数、函数实参（{@code Function.ArgumentCollection
     * .getAsDouble:89-91}）、一元运算与条件（{@code asBoolean}）。本引擎的 {@code IValue} 只有 double，
     * 所以这里只保留"NaN 归 0"这一条。上游在二元运算符里用的是 {@code asFloat}（:46-61，会先窄化成 float），
     * 本仓刻意不窄化：这是移植的精度选择（{@code IValue} 就是 double），不是语义差异。
     */
    public static double asDouble(double value) {
        return Double.isNaN(value) ? 0 : value;
    }

    /**
     * 上游 {@code ValueConversions.asBoolean(Object)}（:32-44）的 double 镜像：
     * {@code NaN} → false、0 → false，其余 true。
     */
    public static boolean asBoolean(double value) {
        return !Double.isNaN(value) && value != 0;
    }

    /**
     * 根据给定的两个 double 计算值
     */
    public abstract double calculate(double a, double b);
}
