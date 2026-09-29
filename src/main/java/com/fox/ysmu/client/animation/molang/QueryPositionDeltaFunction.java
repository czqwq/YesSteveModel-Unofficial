package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code query.position_delta(axis)} - the current frame entity's movement on one axis.
 *
 * <p>
 * A-05: this function used to read three mutable static fields ({@code dx}/{@code dy}/{@code dz}) that no code ever
 * wrote, so it answered 0 for every model (and the fields were shared across entities, so a writer would have
 * leaked one player's movement into another's animation). It now reads {@link MolangFrameContext}, which is
 * published once per frame by {@code AnimationRegister#setParserValue} (players) or
 * {@code AnimationManager#predicateEntityLocomotion} (non-players) and uses the same
 * {@code posX - prevPosX} definition as the {@code query.position_delta} variable form.
 */
public class QueryPositionDeltaFunction extends Function {

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public QueryPositionDeltaFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    @Override
    public double get() {
        // 安全检查：如果没有传参数，默认返回 0
        if (this.arguments == null || this.arguments.length == 0) {
            return 0.0;
        }

        // 获取模型公式里传进来的第一个参数：query.position_delta(轴)
        int axis = (int) this.arguments[0].get();

        // 逐帧上下文里按轴读取；不在帧内（未 begin）时为 0
        return MolangFrameContext.getPositionDelta(axis);
    }
}
