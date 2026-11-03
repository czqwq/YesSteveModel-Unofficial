package software.bernie.geckolib3.core.util;

import java.util.function.Function;

import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;

public class MathUtil {

    /**
     * Lerps an AnimationPoint
     *
     * @param animationPoint The animation point
     * @return the resulting lerped value
     */
    public static float lerpValues(AnimationPoint animationPoint, EasingType easingType,
        Function<Double, Double> customEasingMethod) {
        // AnimationPoint 的四个字段现在是基本类型（见 AnimationPoint 的注释），所以不再有
        // "池里拿出来的点还没填值"这种 null 情况：一个没被 obtain 过的点是全 0，
        // 下面第一个判断就会把它送到 animationEndValue（同样是 0），与旧的 null 兜底等价。
        if (animationPoint.currentTick >= animationPoint.animationEndTick) {
            return (float) animationPoint.animationEndValue;
        }

        if (easingType == EasingType.CUSTOM && customEasingMethod != null) {
            return lerpValues(
                customEasingMethod.apply(animationPoint.currentTick / animationPoint.animationEndTick),
                animationPoint.animationStartValue,
                animationPoint.animationEndValue);
        } else if (easingType == EasingType.NONE && animationPoint.keyframe != null) {
            easingType = animationPoint.keyframe.easingType;
        }
        double percent = animationPoint.currentTick / animationPoint.animationEndTick;
        // YSMU perf: Linear 是 KeyFrame 的默认缓动（绝大多数关键帧都是它），NONE 也一样 ——
        // EasingManager.getEasingFuncImpl 对这两者走 default 分支，返回的 in(linear) 里
        // in() 原样返回、linear(t)=t，所以缓动结果就是 percent 本身。直接算掉，省掉每次调用
        // 一次 new EasingFunctionArgs + 两次 Double 装箱（Function<Double,Double> 的参数与返回值）
        // + 一次 Memoizer 查表（它的 key 还会被缓存长期留住：堆快照里 106,851 个）。
        if (easingType == EasingType.Linear || easingType == EasingType.NONE) {
            return lerpValues(percent, animationPoint.animationStartValue, animationPoint.animationEndValue);
        }
        // YSMU perf: Catmullrom 是随包动画里唯一实际出现的缓动（`"lerp_mode": "catmullrom"`，
        // 语料里 397k 处），而它是三种"不需要 easingArgs"的曲线之一 —— getEasingFuncImpl 对
        // 它返回的就是 in(catmullRom)，即原样调用下面这个双参函数。EasingManager.ease 那一路
        // 每次调用要分配 EasingFunctionArgs + 一个绑定方法引用（Memoizer 里的 function::apply）
        // + 两个 Double（Function<Double,Double> 的拆装箱），再做一次 ConcurrentHashMap 查找；
        // 一个 70 骨骼的模型每帧要过 ~630 次这条路径，是渲染线程上的主要分配点之一。
        // 结果与走 ease() 完全一致（Catmullrom 忽略 easingArgs，见 getEasingFuncImpl），只是不再装箱。
        if (easingType == EasingType.Catmullrom) {
            if (animationPoint.hasControlPoints) {
                // 关键帧段：与上游同一个四控制点样条，见下面 catmullRom 重载的注释。
                return (float) catmullRom(percent, animationPoint.controlLeft, animationPoint.animationStartValue,
                    animationPoint.animationEndValue, animationPoint.controlRight);
            }
            // 非关键帧段（transition / concurrent fold 建的点没有相邻关键帧）：保持原来的标量缓动。
            return lerpValues(EasingManager.catmullRom(percent), animationPoint.animationStartValue,
                animationPoint.animationEndValue);
        }
        double ease = EasingManager.ease(
            percent,
            easingType,
            animationPoint.keyframe == null ? null : animationPoint.keyframe.easingArgs);
        return lerpValues(ease, animationPoint.animationStartValue, animationPoint.animationEndValue);
    }

    /**
     * This is the actual function that smoothly interpolates (lerp) between
     * keyframes
     *
     * @param startValue The animation's start value
     * @param endValue   The animation's end value
     * @return The interpolated value
     */
    public static float lerpValues(double percentCompleted, double startValue, double endValue) {
        // current tick / position should be between 0 and 1 and represent the
        // percentage of the lerping that has completed
        return (float) lerp(percentCompleted, startValue, endValue);
    }

    public static double lerp(double pct, double start, double end) {
        return start + pct * (end - start);
    }

    /**
     * A Catmull-Rom spline over four control points, exactly upstream's formula.
     *
     * <p>Copied from {@code tmp/YesSteveModel-dev-1.20 .../geckolib3/core/util/MathUtil.java:71-77}, which is the
     * curve {@code CatmullRomKeyFrame#getLerpPoint} uses ({@code .../core/keyframe/bone/CatmullRomKeyFrame.java:22-34}):
     * <pre>
     *   v0 = (end - left) * 0.5          // tangent at begin
     *   v1 = (right - begin) * 0.5       // tangent at end
     *   f(t) = (2*begin - 2*end + v0 + v1)*t^3 + (-3*begin + 3*end - 2*v0 - v1)*t^2 + v0*t + begin
     * </pre>
     * Endpoint check: {@code t = 0} collapses to {@code begin}; {@code t = 1} gives
     * {@code 2b - 2e + v0 + v1 - 3b + 3e - 2v0 - v1 + v0 + b = e}. Both endpoints are therefore still exact, and
     * two adjacent segments share the same tangent at the keyframe between them (that continuity is the whole
     * point of the curve - the scalar ease this engine used before stops at every keyframe).</p>
     *
     * <p>{@code EasingManager.catmullRom} is a different thing and stays: it shapes a scalar 0..1 progress with
     * virtual control points, which is all a segment without neighbouring values can do.</p>
     */
    public static double catmullRom(double percent, double left, double begin, double end, double right) {
        double v0 = (end - left) * 0.5;
        double v1 = (right - begin) * 0.5;
        double t2 = percent * percent;
        double t3 = percent * t2;
        return (2 * begin - 2 * end + v0 + v1) * t3 + (-3 * begin + 3 * end - 2 * v0 - v1) * t2 + v0 * percent
            + begin;
    }
}
