package com.eliotlash.mclib.math.functions;

import java.util.ArrayDeque;
import java.util.Deque;

import com.eliotlash.mclib.math.IValue;

/**
 * The argument frames of user function calls, one stack per thread.
 * <p>
 * OpenYSM pack scripts ({@code functions/*.molang}) are called as {@code fn.<name>(a, b)} and read their parameters as
 * {@code args[0]}, {@code args[1]}, ... (see {@code MathBuilder}'s {@code IndexValue}). Whoever evaluates such a call
 * pushes the argument values, evaluates the body, and pops in a {@code finally} block, so a body always sees its own
 * call's arguments - including from a nested call, because the stack is per thread and the render thread evaluates one
 * frame at a time.
 * <p>
 * Both accessors answer 0/empty outside a call and for an out-of-range index, the same "no context means no value"
 * rule the host's per-frame MoLang context follows.
 */
public final class UserFunctionArguments {

    /** The only array name this runtime supports in {@code name[index]}. */
    public static final String ARRAY_NAME = "args";

    private static final IValue[] NONE = new IValue[0];
    private static final ThreadLocal<Deque<IValue[]>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private UserFunctionArguments() {}

    public static void push(IValue[] arguments) {
        FRAMES.get()
            .push(arguments == null ? NONE : arguments);
    }

    /** Pops the innermost frame; safe to call when the stack is empty. */
    public static void pop() {
        Deque<IValue[]> frames = FRAMES.get();
        if (!frames.isEmpty()) {
            frames.pop();
        }
    }

    public static double get(int index) {
        IValue[] frame = FRAMES.get()
            .peek();
        if (frame == null || index < 0 || index >= frame.length) {
            return 0;
        }
        return frame[index].get();
    }
}
