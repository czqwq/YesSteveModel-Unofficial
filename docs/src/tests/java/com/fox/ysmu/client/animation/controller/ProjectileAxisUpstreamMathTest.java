package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 弹射物轴的 {@code math.*} 必须与**上游**和**玩家轴**同解；上游出处逐条见 {@link ControllerMath}。
 *
 * <p>{@code tmp/math-function-divergences.md} §8 与 {@code .agent/phase27-mclib-math-defects.md:171-183} 记录了
 * 六处：{@code math.sqrt} 负入参归 0、{@code math.mod} 欧几里得、{@code math.atan}/{@code math.atan2} 转角度、
 * {@code math.min_angle} 是两参差值（**形状**不同）、{@code math.lerprotate} 折到 {@code [0,360)}，以及
 * {@code math.die_roll_integer} 上界闭区间。本次把两条轴的求值体都收进 {@link ControllerMath} 一份
 * （{@code .agent/phase36-axis-math-repair.md}）：除这六处外，逐条对照两个求值器时又发现
 * {@code math.clamp} 用了另一种 clamp 约定、{@code math.random} 在端点写反时答常量，也一并收进去。
 * 于是**同名函数在两条轴上只有一份实现**，由 {@link #bothAxesAnswerTheSameValue()} 从两条链各自钉住。</p>
 *
 * <p>两层判据：{@link ControllerMath} 的精确值（边界值在求值器里几乎不可判定 —— 例如 NaN 的任一比较都是假，
 * 而"折到 {@code [0,360)}"与"取 {@code [-180,180]}"在多数输入上同值），以及**真正经过两条轴**的用例：
 * 弹射物轴走它唯一的公开入口（{@code registerGroundGuard} + {@code activeAnimations}，见
 * {@link ProjectileControllerVariableCaseTest}），玩家轴走 {@code Context#functionValue}
 * （与 {@link OpenYsmControllerFunctionEvaluationTest} 同一条路）。</p>
 *
 * <p>一条被断言写错、但**仍然要留下**的判据：{@code math.sqrt(-1)==math.sqrt(-1)} 与
 * {@code math.atan(-1)<-1} 必须是 {@code assertFalse} —— NaN 不等于自身、弧度 -0.785 不小于 -1，
 * 而旧体（负数归 0、角度制 -45）会让它们成立，所以把它们写成 {@code assertTrue} 恰好是在断言旧行为。</p>
 */
class ProjectileAxisUpstreamMathTest {

    /** 弹射物动画 id：控制器与它的动画文件都挂在这个 id 上。 */
    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_proj_upstream_math");

    /** 本测试自己的实体 id，避免与别的测试的状态机互撞。 */
    private static final int ENTITY_ID = 0x5EED0029;

    private static final String FLIGHT_CLIP = "flight_clip";
    private static final String GROUND_CLIP = "ground_clip";

    @BeforeEach
    void installClips() {
        AnimationFile file = new AnimationFile();
        file.animations.put(FLIGHT_CLIP, new Animation());
        file.animations.put(GROUND_CLIP, new Animation());
        GeckoLibCache.getInstance()
            .getAnimations()
            .put(ID, file);
    }

    @AfterEach
    void release() {
        GeckoLibCache.getInstance()
            .getAnimations()
            .remove(ID);
        OpenYsmAnimationControllerRegistry.clear();
        ProjectileControllerRuntime.clear();
    }

    /** 一个控制器：初始状态播 {@link #FLIGHT_CLIP}，{@code guard} 一成立就转到播 {@link #GROUND_CLIP} 的状态。 */
    private static void registerGroundGuard(String guard) {
        String json = "{\"animation_controllers\":{\"projectile_upstream_math\":{\"initial_state\":\"flight\",\"states\":{"
            + "\"flight\":{\"animations\":[\""
            + FLIGHT_CLIP
            + "\"],\"transitions\":[{\"ground\":\""
            + guard
            + "\"}]},"
            + "\"ground\":{\"animations\":[\""
            + GROUND_CLIP
            + "\"]}}}}}";
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(json.getBytes(StandardCharsets.UTF_8)),
            Collections.emptyList());
    }

    /** 这一帧弹射物轴选中的动画名。 */
    private static List<String> activeAnimations() {
        List<ProjectileControllerRuntime.ActiveAnimation> entries =
            ProjectileControllerRuntime.getActiveAnimationEntries(
                ENTITY_ID,
                ID,
                0.0d,
                ProjectileControllerRuntime.ProjectileState.AIRBORNE);
        List<String> names = new ArrayList<>(entries.size());
        for (ProjectileControllerRuntime.ActiveAnimation entry : entries) {
            names.add(entry.name);
        }
        return names;
    }

    /** 弹射物轴是否被 {@code guard} 求值为真（状态机选到了 {@link #GROUND_CLIP}）。 */
    private static boolean projectileGuardHolds(String guard) {
        ProjectileControllerRuntime.clear();
        registerGroundGuard(guard);
        return activeAnimations().equals(Collections.singletonList(GROUND_CLIP));
    }

    /** 玩家轴对同一个函数名的取值（与 {@link OpenYsmControllerFunctionEvaluationTest} 同一条求值链）。 */
    private static double playerAxis(String name, double... values) {
        List<OpenYsmControllerExpressionEvaluator.Argument> arguments = new ArrayList<>(values.length);
        for (double value : values) {
            arguments.add(OpenYsmControllerExpressionEvaluator.Argument.number(value));
        }
        return new OpenYsmControllerExpressionEvaluator.Context(null, null, null).functionValue(name, arguments);
    }

    /** 玩家轴对整条条件的求值（编译 → 求值）。 */
    private static boolean playerAxisCondition(String expression) {
        return OpenYsmControllerExpressionEvaluator
            .evaluateBoolean(expression, new OpenYsmControllerExpressionEvaluator.Context(null, null, null));
    }

    // ── 先证明上面那两条求值链是活的，否则本类其余用例可能是空断言 ──────────────────────────────

    @Test
    void theTwoEvaluationChainsAreAlive() {
        // 弹射物轴：条件恒真选 GROUND_CLIP，恒假停 FLIGHT_CLIP。
        assertTrue(projectileGuardHolds("1==1"));
        assertFalse(projectileGuardHolds("1==2"));
        // 玩家轴：functionValue 与整条条件各来一条。
        // 上游 Floor.java:10 是 Mth.floor(getAsFloat(0))，floor(1.9) 就是 1 —— 这里此前写 2.0（round 的值）。
        // 链路本身是活的，见下面两条（floor(1.6) 的恒真/恒假对照）。
        assertEquals(1.0d, playerAxis("math.floor", 1.9d), 0.0d);
        assertEquals(2.0d, playerAxis("math.round", 1.5d), 0.0d);
        assertTrue(playerAxisCondition("math.floor(1.6)==1"));
        assertFalse(playerAxisCondition("math.floor(1.6)==2"));
        // 控制器注册本身成功（注册失败会 remove 掉 id，activeAnimations 就不是上面那两个结果了）。
        assertNotNull(
            OpenYsmAnimationControllerRegistry.get(ID),
            "the controller JSON must parse, or every guard in this class is vacuously false");
    }

    // ── math.sqrt：上游 Sqrt.java:9 无护栏，负数得 NaN（此前弹射物轴归 0） ──────────────────────────

    @Test
    void sqrtHasNoNegativeGuardSoTheResultIsNaN() {
        assertEquals(3.0d, ControllerMath.sqrt(9.0d), 0.0d);
        assertTrue(Double.isNaN(ControllerMath.sqrt(-1.0d)), "upstream Sqrt.java:9 has no v<0 guard");

        // NaN 的任一比较都是假，而护栏后的 0.0 与自身相等 —— 所以**这一条必须为假**才证明是 NaN：
        // 旧护栏（负数归 0）会让 0.0==0.0 成立，这一条就是红灯。此前写成 assertTrue，方向反了。
        assertFalse(
            projectileGuardHolds("math.sqrt(-1)==math.sqrt(-1)"),
            "NaN == NaN is false; the guarded 0.0 body would have made this true, so assertTrue here "
                + "asserted the old behaviour");
        assertTrue(projectileGuardHolds("math.sqrt(-1)!=1"));
        // 反向对照：条件链本身是活的（若上面恒假，这两条就会指出是链路而非语义）。
        assertTrue(projectileGuardHolds("math.sqrt(4)==2"));
        assertFalse(projectileGuardHolds("math.sqrt(4)==5"));
    }

    // ── math.mod：上游 Mod.java:9 是 a % b，符号随被除数（此前弹射物轴欧几里得 + 除零归 0） ──────

    @Test
    void modFollowsJavaRemainderInsteadOfEuclideanFolding() {
        assertEquals(1.0d, ControllerMath.mod(7.0d, 3.0d), 0.0d);
        assertEquals(-1.0d, ControllerMath.mod(-7.0d, 3.0d), 0.0d);
        // 上游 Mod.java:9 是 a % b：余数的符号跟**被除数**，两个负数不变号（-7 % -3 = -1）。
        // 这里此前写 1.0，是欧几里得折叠的值，恰好与它上面那条注释自相矛盾。
        assertEquals(-1.0d, ControllerMath.mod(-7.0d, -3.0d), 0.0d);
        assertTrue(Double.isNaN(ControllerMath.mod(1.0d, 0.0d)), "Java 1.0 % 0.0 is NaN");

        assertTrue(projectileGuardHolds("math.mod(-7,3)==-1"), "the Euclidean body answered +2 here");
        assertTrue(projectileGuardHolds("math.mod(7,3)==1"));
        // 原欧几里得体只在被除数为负时有区别；这一条是它的反向对照。
        assertFalse(projectileGuardHolds("math.mod(-7,3)==2"));
    }

    // ── math.atan / math.atan2：上游 Atan.java:9 / ATan2.java:10 都是**弧度**（此前弹射物轴转角度） ──

    @Test
    void atanAndAtan2AnswerRadians() {
        assertEquals(Math.PI / 4.0d, ControllerMath.atan(1.0d), 1.0e-12d);
        assertEquals(-Math.PI / 4.0d, ControllerMath.atan(-1.0d), 1.0e-12d);
        assertEquals(Math.PI / 4.0d, ControllerMath.atan2(1.0d, 1.0d), 1.0e-12d);
        // 第二象限：atan2(y>0, x<0) 落在 (π/2, π)。
        assertEquals(3.0d * Math.PI / 4.0d, ControllerMath.atan2(1.0d, -1.0d), 1.0e-12d);
        // 第三象限：负角，仍是弧度。
        assertEquals(-3.0d * Math.PI / 4.0d, ControllerMath.atan2(-1.0d, -1.0d), 1.0e-12d);

        // -45° 在前版本（角度制）是 -45；-1 这个阈值把两者分开：弧度 -0.785 < -1 为**假**，
        // 所以这一条必须是 assertFalse —— 角度制的 -45 < -1 为真，旧体会让 assertFalse 红灯。
        // 此前写成 assertTrue，方向反了。
        assertFalse(projectileGuardHolds("math.atan(-1)<-1"), "the degree body answered -45 here");
        assertTrue(projectileGuardHolds("math.atan(-1)>-1"));
        assertTrue(projectileGuardHolds("math.atan(1)>0.7"));
        assertTrue(projectileGuardHolds("math.atan2(1,-1)>2"), "the degree body answered 135 here");
        assertFalse(projectileGuardHolds("math.atan2(1,-1)<-2"));
        // 第三象限的 atan2(-1,-1) 在角度制下是 -135，在弧度制下是 -2.356，两条都 <-2，所以只钉象限。
        assertTrue(projectileGuardHolds("math.atan2(-1,-1)<-2"));
    }

    // ── math.min_angle：上游 MinAngle.java:9-16 是**一参**归一化，不是两参差值 ──────────────────

    @Test
    void minAngleIsUpstreamsOneArgumentNormalisation() {
        assertEquals(-170.0d, ControllerMath.minAngle(190.0d), 0.0d);
        assertEquals(170.0d, ControllerMath.minAngle(-190.0d), 0.0d);
        assertEquals(45.0d, ControllerMath.minAngle(45.0d), 0.0d);
        // 边界：上游判的是 >= 180（mclib 的 classic/MinAngle 判 > 180，把 180 留成 180）。
        assertEquals(-180.0d, ControllerMath.minAngle(180.0d), 0.0d);
        assertEquals(-180.0d, ControllerMath.minAngle(-180.0d), 0.0d);
        assertEquals(-179.0d, ControllerMath.minAngle(-179.0d), 0.0d);

        // 一参形状：此前弹射物轴要求 count >= 2，单个实参落到"答不了"而返回 0。
        assertTrue(projectileGuardHolds("math.min_angle(190)==-170"), "the two-argument shape answered 0 here");
        // 两参差值形状（旧体是 min_angle(1,190) → 189 → -171）：上游的一参归一化对第一个实参回答 1。
        assertTrue(projectileGuardHolds("math.min_angle(1,190)==1"), "the old difference body answered -171 here");
        assertFalse(projectileGuardHolds("math.min_angle(1,190)==-171"));
        // 零参仍按本轴规则回答 0，不抛异常。
        assertFalse(projectileGuardHolds("math.min_angle()==1"));
    }

    // ── math.lerprotate：上游 LerpRotate.java:10-12 → Interpolations.lerpYaw，结果留在 [-180,180] ──

    @Test
    void lerpRotateStaysInMinus180To180InsteadOfFoldingToZeroTo360() {
        // 这里此前写 -170：那是把"新旧只差一次归一到 [0,360)"当成了前提（旧体 190 ≡ -170）。
        // 实测旧体（HEAD 的 (b-a)%360 差值 + 一次 [0,360) 折叠）对这个输入同样答 180，
        // 所以 -170 不是任何一版的值。
        assertEquals(180.0d, ControllerMath.lerpRotate(175.0d, -175.0d, 0.5d), 1.0e-4d);
        // position=2.0 是**外推**：a=-90, b=90, diff=-180 不触发 normalizeYaw 的折返，于是
        // lerp(-90, 90, 2) = 270 —— 上游（float 的 lerpYaw）同样是 270。这里此前写 180，
        // 误以为 position>1 也会留在 [-180,180]。
        assertEquals(270.0d, ControllerMath.lerpRotate(-90.0d, 90.0d, 2.0d), 1.0e-4d);
        assertEquals(0.0d, ControllerMath.lerpRotate(350.0d, 10.0d, 0.5d), 1.0e-4d);
        // 真正把新旧两份实现分开的输入：旧体不 wrap `from`（350 还是 350），差值折成 +20，
        // 结果 350 + 20*0.25 = 355（< 360，不再折）；lerpYaw 先把端点 wrap 成 -10/10，
        // 于是 lerp(-10,10,0.25) = -5 —— 上游 LerpRotate.java:10-12 就是 -5。
        assertEquals(-5.0d, ControllerMath.lerpRotate(350.0d, 10.0d, 0.25d), 1.0e-4d);

        assertTrue(projectileGuardHolds("math.lerprotate(175,-175,0.5)==180"));
        assertTrue(projectileGuardHolds("math.lerprotate(175,-175,0.5)>-180"));
        assertTrue(projectileGuardHolds("math.lerprotate(350,10,0.5)==0"));
        assertTrue(projectileGuardHolds("math.lerprotate(350,10,0.25)<0"), "the [0,360) body answered 355 here");
        assertFalse(projectileGuardHolds("math.lerprotate(175,-175,0.5)==190"));
    }

    // ── math.die_roll_integer：上游 DieRollInteger.java:27-28 的 nextInt(range) → [min, max-1] ────

    @Test
    void dieRollIntegerUsesTheExclusiveUpperBound() {
        // 计数四舍五入（上游 Math.round）、端点截断（上游 getAsInt），每颗骰子是 [min, max-1]，
        // 所以总数落在 [rolls*min, rolls*(max-1)]。
        for (int i = 0; i < 200; i++) {
            double rolled = ControllerMath.dieRollInteger(2.0d, 1.0d, 7.0d);
            assertEquals(Math.rint(rolled), rolled, 0.0d, "the integer form must answer integers");
            assertTrue(rolled >= 2.0d && rolled <= 12.0d, "die_roll_integer(2,1,7) was " + rolled);
        }
        // 端点相等：区间宽度为 0，每颗骰子都答那个唯一值。
        assertEquals(0.0d, ControllerMath.dieRollInteger(0.0d, 5.0d, 5.0d), 0.0d);
        assertEquals(5.0d, ControllerMath.dieRollInteger(1.0d, 5.0d, 5.0d), 0.0d);
        assertEquals(10.0d, ControllerMath.dieRollInteger(2.0d, 5.0d, 5.0d), 0.0d);
        // 端点反序也要排好序（上游 swap 分支，DieRollInteger.java:18-24）。每颗骰子是 [5,6]，两颗
        // 之和因此落在 [10,12]；这里此前写死 10.0，而两颗骰子里有一颗是 6 时结果就是 11/12 —— 断言
        // 写的是一个随机变量不可能永远取到的值。原来的意图（"反序也排好序"）由区间断言保住：
        // 若 swap 分支丢了，一颗骰子会落在 [7,8]，总和立刻超出 [10,12]。
        for (int i = 0; i < 200; i++) {
            double rolled = ControllerMath.dieRollInteger(2.0d, 7.0d, 5.0d);
            assertTrue(rolled >= 10.0d && rolled <= 12.0d, "die_roll_integer(2,7,5) was " + rolled);
        }
        // 2.9 → 3 颗，5.9 → 5，7.9 → 7，每颗是 5 或 6，所以总和落在 [15,18]。
        for (int i = 0; i < 100; i++) {
            double rolled = ControllerMath.dieRollInteger(2.9d, 5.9d, 7.9d);
            assertTrue(rolled >= 15.0d && rolled <= 18.0d, "die_roll_integer(2.9,5.9,7.9) was " + rolled);
        }

        // 弹射物轴：值本身随机（这是整份测试里唯一非确定的输入），所以只钉闭区间的上界。
        // 旧体是 [min, max] 闭区间，`(int)(Math.random()*(max-min+1))` 能答到 6；出现一次就红灯。
        for (int i = 0; i < 300; i++) {
            assertTrue(projectileGuardHolds("math.die_roll_integer(1,0,7)<7"), "the old body could answer 6");
        }
        // 确定性的那一半：0 颗 / 退化区间 / 端点相等，随机源都不参与。
        assertTrue(projectileGuardHolds("math.die_roll_integer(0,5,5)==0"));
        assertTrue(projectileGuardHolds("math.die_roll_integer(2,5,5)==10"));
        // 一次骰子、端点相等：上游 nextInt(0) 会抛 IllegalArgumentException（整条表达式失效），
        // 本实现把退化区间回答成那个唯一值。
        assertTrue(projectileGuardHolds("math.die_roll_integer(1,5,5)==5"));
        // 次数在小数上必须**四舍五入**（上游 DieRollInteger.java:15 的 Math.round），弹射物轴的调用点
        // 此前先 `(int)` 截断再交给求值体，2.9 会变成 2 颗。端点相等让随机源不参与：3 颗 × 5 = 15，
        // 截断成 2 颗则是 10。
        assertTrue(projectileGuardHolds("math.die_roll_integer(2.9,5,5)==15"));
        assertTrue(projectileGuardHolds("math.die_roll(2.9,5,5)==10"), "DieRoll.java:15 truncates: 2 rolls");
    }

    // ── 一条实现：两条轴都走 ControllerMath（同名函数只有一份文本），两侧取值必须一致 ─────────────

    /**
     * 玩家轴与弹射物轴对同名函数必须同解。弹射物轴的值只能从公开入口读（求值器与它内部的分派都是私有的），
     * 所以这里用"条件成立与否"作判据；玩家轴则直接读 {@code Context#functionValue}。
     *
     * <p>玩家轴那一侧的位置（编辑后的行号）：{@code math.sqrt}`:1224`、{@code math.atan}`:1258`、
     * {@code math.min_angle}`:1263`、{@code math.mod}`:1275`、{@code math.atan2}`:1288`、
     * {@code math.random}`:1295`、{@code math.clamp}`:1314`、{@code math.lerprotate}`:1322`、
     * {@code math.die_roll}`:1328`、{@code math.die_roll_integer}`:1331` —— 十条都直接调
     * {@link ControllerMath}，不再是各写一份。{@link ControllerMath} 若改值，下面的断言就红灯。</p>
     */
    @Test
    void bothAxesAnswerTheSameValue() {
        // 玩家轴（值）
        assertEquals(-1.0d, playerAxis("math.mod", -7.0d, 3.0d), 0.0d);
        assertEquals(Math.PI / 4.0d, playerAxis("math.atan", 1.0d), 1.0e-12d);
        assertEquals(Math.PI / 4.0d, playerAxis("math.atan2", 1.0d, 1.0d), 1.0e-12d);
        assertEquals(-170.0d, playerAxis("math.min_angle", 190.0d), 0.0d);
        // lerpYaw 的 position=2.0 是外推，-90 → 90 在 2.0 处是 270（上游同样，见上面那个用例）。
        assertEquals(270.0d, playerAxis("math.lerprotate", -90.0d, 90.0d, 2.0d), 1.0e-4d);
        assertTrue(Double.isNaN(playerAxis("math.sqrt", -1.0d)));
        // clamp：上游 Clamp.java:10-12 = Mth.clamp，端点写反时答的是 Math.min(value, max) 而不是下界。
        assertEquals(1.0d, playerAxis("math.clamp", 7.0d, 5.0d, 1.0d), 0.0d);
        assertEquals(5.0d, playerAxis("math.clamp", 9.0d, 0.0d, 5.0d), 0.0d);

        // 弹射物轴（同一个条件，走状态机）
        assertTrue(projectileGuardHolds("math.mod(-7,3)==-1"));
        assertTrue(projectileGuardHolds("math.atan(1)>0.7"));
        assertTrue(projectileGuardHolds("math.atan2(1,1)>0.7"));
        assertTrue(projectileGuardHolds("math.min_angle(190)==-170"));
        assertTrue(projectileGuardHolds("math.lerprotate(-90,90,2)==270"));
        assertTrue(projectileGuardHolds("math.clamp(7,5,1)==1"), "the other clamp convention answered 5 here");
        // NaN != NaN，所以这一条必须是假；旧护栏（负数归 0）会让它成立。
        assertFalse(projectileGuardHolds("math.sqrt(-1)==math.sqrt(-1)"));
        // random 的两个端点写反时上游/玩家轴仍然排序取 [min,max)：[3,7) 里恒 < 7；旧体返回常量 7。
        assertTrue(projectileGuardHolds("math.random(7,3)<7"), "the old body answered the first argument, 7");
        assertTrue(projectileGuardHolds("math.random(3,7)>=3"));

        // 编译 → 求值链（玩家轴），与上面的值断言是同一条路
        assertTrue(playerAxisCondition("math.mod(-7,3)==-1"));
        assertTrue(playerAxisCondition("math.random(7,3)<7"));
        assertTrue(playerAxisCondition("math.clamp(7,5,1)==1"));
        assertFalse(playerAxisCondition("math.atan(1)<-1"), "the degree body answered -45 here");

        // ControllerMath 与上面两条链取的是同一个值（不是"两边各自对"）
        assertEquals(-1.0d, ControllerMath.mod(-7.0d, 3.0d), 0.0d);
        assertEquals(Math.PI / 4.0d, ControllerMath.atan(1.0d), 1.0e-12d);
        assertEquals(-170.0d, ControllerMath.minAngle(190.0d), 0.0d);
        assertEquals(270.0d, ControllerMath.lerpRotate(-90.0d, 90.0d, 2.0d), 1.0e-4d);
        assertEquals(1.0d, ControllerMath.clamp(7.0d, 5.0d, 1.0d), 0.0d);
    }
}
