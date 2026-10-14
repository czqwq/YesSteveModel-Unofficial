package com.eliotlash.mclib.math;

import software.bernie.geckolib3.core.molang.MolangException;

import java.util.HashSet;
import java.util.Set;

public enum Operation {

    // 操作符
    ADD("+", 1) {

        @Override

        public double calculate(double a, double b) {
            return a + b;
        }
    },
    SUB("-", 1) {

        @Override

        public double calculate(double a, double b) {
            return a - b;
        }
    },
    MUL("*", 2) {

        @Override

        public double calculate(double a, double b) {
            return a * b;
        }
    },
    DIV("/", 2) {

        @Override

        public double calculate(double a, double b) {
            /* To avoid any exceptions */
            return a / (b == 0 ? 1 : b);
        }
    },
    MOD("%", 2) {

        @Override

        public double calculate(double a, double b) {
            return a % b;
        }
    },
    POW("^", 3) {

        @Override

        public double calculate(double a, double b) {
            return Math.pow(a, b);
        }
    },
    AND("&&", 5) {

        @Override

        public double calculate(double a, double b) {
            return a != 0 && b != 0 ? 1 : 0;
        }
    },
    OR("||", 5) {

        @Override

        public double calculate(double a, double b) {
            return a != 0 || b != 0 ? 1 : 0;
        }
    },
    LESS("<", 5) {

        @Override

        public double calculate(double a, double b) {
            return a < b ? 1 : 0;
        }
    },
    LESS_THAN("<=", 5) {

        @Override

        public double calculate(double a, double b) {
            return a <= b ? 1 : 0;
        }
    },
    GREATER_THAN(">=", 5) {

        @Override

        public double calculate(double a, double b) {
            return a >= b ? 1 : 0;
        }
    },
    GREATER(">", 5) {

        @Override

        public double calculate(double a, double b) {
            return a > b ? 1 : 0;
        }
    },
    EQUALS("==", 5) {

        @Override

        public double calculate(double a, double b) {
            return equals(a, b) ? 1 : 0;
        }
    },
    NOT_EQUALS("!=", 5) {

        @Override

        public double calculate(double a, double b) {
            return !equals(a, b) ? 1 : 0;
        }
    },
    ASSIGN("=", 5) {
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
    ARROW("->", 6) {
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
    NULL_COALESCE("??", 4) {
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
    CONDITIONAL("?", 0) {
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
        return Math.abs(a - b) < 0.00001;
    }

    /**
     * 根据给定的两个 double 计算值
     */
    public abstract double calculate(double a, double b);
}
