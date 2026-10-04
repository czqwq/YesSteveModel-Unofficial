package com.eliotlash.mclib.math;

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
