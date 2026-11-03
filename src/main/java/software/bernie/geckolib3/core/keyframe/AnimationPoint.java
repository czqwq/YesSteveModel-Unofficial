/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.keyframe;

import java.util.ArrayList;

import com.eliotlash.mclib.math.IValue;

public class AnimationPoint {

    /**
     * The current tick in the animation to lerp from
     * <p>
     * YSMU: 这几个字段原先是 {@code Double}，而它们每帧每条通道都会被
     * {@link #obtain} 重新赋值一次 —— 装箱于是成了动画 tick 路径上最大的一处分配
     * （堆快照里 8,037,400 个 {@code java.lang.Double}，184 MiB，绝大多数就是它）。
     * 改成基本类型后 {@link #obtain} 不再装箱，{@link #recycle} 也不必再逐个置 null：
     * 池复用的是对象本身，字段值每帧都会被覆盖。
     */
    public double currentTick;
    /**
     * The tick that the current animation should end at
     */
    public double animationEndTick;
    /**
     * The Animation start value.
     */
    public double animationStartValue;
    /**
     * The Animation end value.
     */
    public double animationEndValue;

    /**
     * The current keyframe.
     */
    public KeyFrame<IValue> keyframe;

    // ---- catmullrom outer control points ----

    /**
     * The two control points a Catmull-Rom segment needs <em>outside</em> its own endpoints: the value of the
     * keyframe before the segment's start ({@code controlLeft}) and of the keyframe after its end
     * ({@code controlRight}).
     *
     * <p>Upstream keeps them on the keyframe object itself: {@code CatmullRomKeyFrame} holds
     * {@code leftPoint}/{@code beginPoint}/{@code endPoint}/{@code rightPoint} and interpolates with
     * {@code MathUtil.catmullRom(percent, left, begin, end, right)}
     * ({@code tmp/YesSteveModel-dev-1.20 .../geckolib3/core/keyframe/bone/CatmullRomKeyFrame.java:13-34};
     * {@code .../core/util/MathUtil.java:71-77}), picking them as
     * {@code keyFrames[max(0,i-2)].post} / {@code [i-1].post} / {@code [i].pre} / {@code [min(size-1,i+1)].pre}
     * ({@code .../core/keyframe/bone/EasingType.java:45-55}). This engine has one keyframe type for every easing
     * mode, so the two outer values ride on the point that carries the segment, and they are only read when the
     * resolved easing is {@link software.bernie.geckolib3.core.easing.EasingType#Catmullrom}.</p>
     *
     * <p>{@link #obtain} leaves {@link #hasControlPoints} false, so every other easing mode - and every point
     * built for a transition or for the concurrent fold, which carry no segment at all - behaves exactly as
     * before; the two extra doubles live in an object that is pooled anyway.</p>
     */
    public double controlLeft;
    /** See {@link #controlLeft}. */
    public double controlRight;
    /** Whether {@link #controlLeft}/{@link #controlRight} were filled in for a catmullrom segment. */
    public boolean hasControlPoints;

    public AnimationPoint() {}

    public AnimationPoint(KeyFrame<IValue> keyframe, double currentTick, double animationEndTick,
        double animationStartValue, double animationEndValue) {
        this.keyframe = keyframe;
        this.currentTick = currentTick;
        this.animationEndTick = animationEndTick;
        this.animationStartValue = animationStartValue;
        this.animationEndValue = animationEndValue;
    }

    public AnimationPoint(KeyFrame<IValue> keyframe, double tick, double animationEndTick, float animationStartValue,
        double animationEndValue) {
        this.keyframe = keyframe;
        this.currentTick = tick;
        this.animationEndTick = animationEndTick;
        this.animationStartValue = animationStartValue;
        this.animationEndValue = animationEndValue;
    }

    // ---- Object pooling ----

    private static final int POOL_MAX_SIZE = 2048;
    private static final ArrayList<AnimationPoint> POOL = new ArrayList<>(POOL_MAX_SIZE);

    /** Obtain an AnimationPoint from the pool, or create new if pool is empty. */
    public static AnimationPoint obtain(KeyFrame<IValue> keyframe, double currentTick,
        double animationEndTick, double animationStartValue, double animationEndValue) {
        AnimationPoint p = take();
        p.keyframe = keyframe;
        p.currentTick = currentTick;
        p.animationEndTick = animationEndTick;
        p.animationStartValue = animationStartValue;
        p.animationEndValue = animationEndValue;
        p.hasControlPoints = false;
        return p;
    }

    /**
     * Obtain a point for a Catmull-Rom segment: as {@link #obtain}, plus the two outer control values; see
     * {@link #controlLeft}.
     */
    public static AnimationPoint obtainWithControls(KeyFrame<IValue> keyframe, double currentTick,
        double animationEndTick, double animationStartValue, double animationEndValue, double controlLeft,
        double controlRight) {
        AnimationPoint p = obtain(keyframe, currentTick, animationEndTick, animationStartValue, animationEndValue);
        p.controlLeft = controlLeft;
        p.controlRight = controlRight;
        p.hasControlPoints = true;
        return p;
    }

    private static AnimationPoint take() {
        synchronized (POOL) {
            if (!POOL.isEmpty()) {
                return POOL.remove(POOL.size() - 1);
            }
        }
        return new AnimationPoint();
    }

    /** Return this AnimationPoint to the pool for reuse. */
    public void recycle() {
        synchronized (POOL) {
            if (POOL.size() < POOL_MAX_SIZE) {
                // 只需断开引用（值字段每帧都会被 obtain 覆盖）。
                this.keyframe = null;
                POOL.add(this);
            }
        }
    }

    @Override
    public String toString() {
        return "Tick: " + currentTick
            + " | End Tick: "
            + animationEndTick
            + " | Start Value: "
            + animationStartValue
            + " | End Value: "
            + animationEndValue;
    }
}
