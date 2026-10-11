package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code ysm.defer(name, args...)} - upstream's {@code function("defer", new Defer())}
 * ({@code tmp/YesSteveModel-dev-1.20 .../client/animation/molang/YSMBinding.java:191}), body at
 * {@code .../functions/Defer.java:8-21}.
 *
 * <p><b>What upstream does with it.</b> The call requires a non-empty first argument (the name), then hands the rest
 * to {@code AnimationContext#defer} ({@code geckolib3/core/molang/context/AnimationContext.java:50-66}), which records
 * their <em>values</em> in a per-animation-context list, and returns {@code null}. At the end of the animation context
 * ({@code AnimationContext#reset:68-89}) every recorded argument list is replayed through
 * {@code CustomEntity#getMolangDeferHandler()} with emitting enabled.
 *
 * <p><b>Why the port registers it without a deferral mechanism.</b> That handler list is the only thing the replay
 * calls, and in upstream's tree the field behind it has no writer at all: {@code getMolangDeferHandler()} is its only
 * mention outside {@code AnimationContext} (a grep of the upstream sources for {@code deferHandler} finds the field,
 * the getter and that one use). So upstream's deferral performs no observable work; what a pack observes is that the
 * call <em>parses</em> and its expression survives. The port's equivalent mechanism
 * ({@code ModelFormExecution}, the timeline scheduler) is for other jobs - config forms and timeline events - and
 * wiring this name to it would be inventing a mechanism upstream does not run. Registering the name and answering 0 is
 * the faithful port: before it, a whole keyframe channel containing {@code ysm.defer(...)} failed to parse.
 */
public class YsmDeferFunction extends Function {

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public YsmDeferFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        // Upstream's Defer returns null (no value) whichever branch it takes; the legacy parser has no null, so 0.
        return 0.0D;
    }
}
