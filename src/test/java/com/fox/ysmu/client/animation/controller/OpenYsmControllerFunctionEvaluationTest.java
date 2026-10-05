package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator.Argument;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator.Context;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;

/**
 * 控制器条件路径的**函数表**：{@code Context#functionValue}。
 *
 * <p>这条路径此前只认 {@code ctrl.hold/use/swing/ride} 与两个 {@code ysm.*} 输入查询，其余
 * （{@code math.*} 整个家族、{@code query.position}/{@code query.position_delta}、
 * {@code ysm.perlin_noise}、粒子 / 音效 / 同步）都落到"Unsupported OpenYSM controller function"
 * 并恒返回 0 —— 同一条表达式在动画文件里能算、在控制器条件里算不出来。这个类把新接上的名字
 * 逐个钉住。</p>
 *
 * <p>这些断言**不需要 Minecraft**：一个没有实体的 {@link Context} 就够 —— 数学、柏林噪声的
 * 输入全是实参，位置类查询由 {@link MolangFrameContext} 这个与关键帧路径共用的帧上下文提供
 * （没有帧时按 0 回答）。需要真实玩家（装备标签、音效、同步）的分支只能验"没有实体时不抛异常、
 * 按假回答"，实机行为仍要客户端确认。</p>
 *
 * <p>取值语义按**上游**（{@code tmp/YesSteveModel-dev-1.20} 的
 * {@code geckolib3/core/molang/builtin/MathBinding.java} 与 {@code builtin/math/*}），也就是本仓的
 * 行为权威；与参考分支（SOURCE，
 * {@code OpenYsmControllerExpressionEvaluator.java:678-940}）不同的地方在各自的断言上注明。</p>
 */
class OpenYsmControllerFunctionEvaluationTest {

    /** 没有实体的求值上下文：math.* / perlin / 位置类查询都不需要宿主状态。 */
    private static Context detached() {
        return new Context(null, null, null);
    }

    private static double call(String name, double... values) {
        List<Argument> arguments = new ArrayList<>(values.length);
        for (double value : values) {
            arguments.add(Argument.number(value));
        }
        return detached().functionValue(name, arguments);
    }

    private static double callStrings(String name, String... values) {
        List<Argument> arguments = new ArrayList<>(values.length);
        for (String value : values) {
            arguments.add(Argument.string(value));
        }
        return detached().functionValue(name, arguments);
    }

    /** 走完整的"编译 → 求值"链（与控制器 JSON 里的条件同一条路）。 */
    private static boolean condition(String expression) {
        return OpenYsmControllerExpressionEvaluator.evaluateCondition(expression, detached());
    }

    @Test
    void theMathFamilyAnswersWithUpstreamSemantics() {
        // 取整 / 经典函数（上游 builtin/math/Floor.java 等；参考分支同为 double 语义）
        assertEquals(1.0d, call("math.floor", 1.9d), 0.0d);
        assertEquals(2.0d, call("math.round", 1.5d), 0.0d);
        assertEquals(2.0d, call("math.ceil", 1.1d), 0.0d);
        // 上游 Trunc.java:12-15：向零取整
        assertEquals(1.0d, call("math.trunc", 1.9d), 0.0d);
        assertEquals(-1.0d, call("math.trunc", -1.9d), 0.0d);
        assertEquals(3.0d, call("math.abs", -3.0d), 0.0d);
        assertEquals(3.0d, call("math.sqrt", 9.0d), 0.0d);
        assertEquals(Math.E, call("math.exp", 1.0d), 1.0e-12d);
        assertEquals(0.0d, call("math.ln", 1.0d), 0.0d);
        // 上游 HermitBlend.java:8-11：ceil 后 floor 三次 Hermite（参考分支是 v*v*(3-2v)）
        assertEquals(1.0d, call("math.hermite_blend", 0.2d), 0.0d);
        assertEquals(1.0d, call("math.hermite", 0.2d), 0.0d);
        // 上游 Sin.java / Cos.java：入参是角度
        assertEquals(1.0d, call("math.sin", 90.0d), 1.0e-9d);
        assertEquals(0.0d, call("math.cos", 90.0d), 1.0e-9d);
        // 上游 ACos.java / ASin.java / Atan.java / ATan2.java：结果都是弧度
        // （参考分支把 atan/atan2 转成了角度，这里按上游。）
        assertEquals(0.0d, call("math.acos", 1.0d), 1.0e-9d);
        assertEquals(0.0d, call("math.asin", 0.0d), 1.0e-9d);
        assertEquals(Math.PI / 4.0d, call("math.atan", 1.0d), 1.0e-9d);
        assertEquals(Math.PI / 4.0d, call("math.atan2", 1.0d, 1.0d), 1.0e-9d);
        // 上游 MinAngle.java:4-13 / mclib MinAngle：归一化到 (-180, 180]
        assertEquals(-170.0d, call("math.min_angle", 190.0d), 0.0d);
        assertEquals(170.0d, call("math.min_angle", -190.0d), 0.0d);
        assertEquals(45.0d, call("math.min_angle", 45.0d), 0.0d);
    }

    @Test
    void theTwoArgumentMathFunctionsAnswer() {
        // 上游 Mod.java:12-14：Java 取余（参考分支把负数结果折成了正数）
        assertEquals(1.0d, call("math.mod", 7.0d, 3.0d), 0.0d);
        assertEquals(-1.0d, call("math.mod", -7.0d, 3.0d), 0.0d);
        assertEquals(3.0d, call("math.max", 3.0d, 2.0d), 0.0d);
        assertEquals(2.0d, call("math.min", 3.0d, 2.0d), 0.0d);
        assertEquals(8.0d, call("math.pow", 2.0d, 3.0d), 0.0d);
        // 区间类函数只钉"落在区间内"（它们本来就随机）
        double random = call("math.random", 5.0d, 7.0d);
        assertTrue(random >= 5.0d && random < 7.0d, "math.random(5,7) was " + random);
        double integer = call("math.random_integer", 2.0d, 5.0d);
        assertTrue(integer >= 2.0d && integer <= 4.0d, "math.random_integer(2,5) was " + integer);
        assertEquals(Math.rint(integer), integer, 0.0d, "math.random_integer must answer an integer");
        // 退化区间不再抛异常（上游 nextInt(0) 会抛 IllegalArgumentException）
        assertEquals(3.0d, call("math.random_integer", 3.0d, 3.0d), 0.0d);
        // 上游把 randomi 注册成 2 参的 RandomInteger；参考分支是 1 参"小于 n 的随机整数"——
        // 两种形态都接（互不冲突）。
        double randomi = call("math.randomi", 4.0d);
        assertTrue(randomi >= 0.0d && randomi <= 3.0d, "math.randomi(4) was " + randomi);
        double randomiTwo = call("math.randomi", 1.0d, 4.0d);
        assertTrue(randomiTwo >= 1.0d && randomiTwo <= 3.0d, "math.randomi(1,4) was " + randomiTwo);
    }

    @Test
    void theThreeArgumentMathFunctionsAnswer() {
        assertEquals(5.0d, call("math.clamp", 9.0d, 0.0d, 5.0d), 0.0d);
        assertEquals(0.0d, call("math.clamp", -1.0d, 0.0d, 5.0d), 0.0d);
        assertEquals(3.0d, call("math.clamp", 3.0d, 0.0d, 5.0d), 0.0d);
        // 复用 mclib 的 Interpolations（上游 Lerp.java 调的是同一份）
        assertEquals(15.0d, call("math.lerp", 10.0d, 20.0d, 0.5d), 0.0d);
        assertEquals(45.0d, call("math.lerprotate", 0.0d, 90.0d, 0.5d), 0.0d);
        // 350° → 10° 走短弧，中点是 0°
        assertEquals(0.0d, call("math.lerprotate", 350.0d, 10.0d, 0.5d), 0.0d);
        // 骰子：n 次 [min, max) 之和
        double rolled = call("math.die_roll", 3.0d, 1.0d, 7.0d);
        assertTrue(rolled >= 3.0d && rolled < 21.0d, "math.die_roll(3,1,7) was " + rolled);
        assertEquals(0.0d, call("math.die_roll", 0.0d, 1.0d, 7.0d), 0.0d);
        double rolledInteger = call("math.die_roll_integer", 3.0d, 1.0d, 7.0d);
        assertEquals(Math.rint(rolledInteger), rolledInteger, 0.0d, "die_roll_integer must sum integers");
        assertTrue(rolledInteger >= 3.0d && rolledInteger <= 18.0d, "die_roll_integer was " + rolledInteger);
        // 别名（上游 MathBinding.java:55-56 把 roll/rolli 指向同一对实现）
        assertTrue(call("math.roll", 2.0d, 0.0d, 1.0d) >= 0.0d);
        assertEquals(0.0d, call("math.rolli", 0.0d, 0.0d, 1.0d), 0.0d);
    }

    @Test
    void mathConstantsAnswerInBothForms() {
        // 函数形态（上游 MathBinding.java:11-12 constValue）
        assertEquals(Math.PI, call("math.pi"), 0.0d);
        assertEquals(Math.E, call("math.e"), 0.0d);
        // 变量形态（参考分支 SOURCE:665-670；关键帧路径由 MolangParser:158-159 注册）
        assertTrue(condition("math.pi>3.14159&&math.pi<3.1416"));
        assertTrue(condition("math.e>2.71&&math.e<2.72"));
    }

    @Test
    void controllerConditionsCanUseTheMathFamily() {
        // 与控制器 JSON 里的条件同一条链：编译 → 求值。参考分支的注释举的就是
        // math.sqrt(math.pow(q.position_delta(0),2)+...) 这种写法（SOURCE:796-805）。
        assertTrue(condition("math.floor(1.6)==1"));
        assertFalse(condition("math.floor(1.6)==2"));
        assertTrue(condition("math.max(3,math.min(9,5))==5"));
        assertTrue(condition("math.sqrt(math.pow(3,2))==3"));
        assertTrue(condition("math.lerp(0,10,0.25)==2.5"));
        assertTrue(condition("math.clamp(math.pi,0,4)>3"));
        // 短写 q.*：值与 query.* 相同（没有帧上下文时两者都是 0），这一条钉的是"能解析、能求值、
        // 不落到不支持分支"；识别与否由 theHostStateFunctionsAreRoutedToRealBranches 反证。
        assertTrue(condition("q.position_delta(0)==query.position_delta(0)"));
    }

    @Test
    void perlinNoiseIsSeededBoundedAndDeterministic() {
        // 坐标必须**非整数**。整数格点上每个轴的三线性权重都是 fade(0)=0，插值只剩下 (0,0,0) 那个角，
        // 而 grad 的 16 个分支在 (0,0,0) 上全得 0 —— 于是任何 seed 都恰好返回 0.5。上游的 STB 同理
        // （stb_perlin_noise3_seed 在整数格点也是 0），所以"用整数坐标证明 seed 选出不同噪声场"
        // 对任何正确实现都不可能成立，与这一版实现无关。
        double noise = call("ysm.perlin_noise", 1.0d, 2.3d, 3.7d, 4.1d);
        assertTrue(noise >= 0.0d && noise <= 1.0d, "ysm.perlin_noise must stay in [0,1], was " + noise);
        assertEquals(noise, call("ysm.perlin_noise", 1.0d, 2.3d, 3.7d, 4.1d), 0.0d,
            "the same seed and coordinates must answer the same value");
        // seed 选的是噪声场：五个种子里至少有一个给出不同值（单看一对可能巧合相等）
        boolean differs = false;
        for (int seed = 2; seed <= 6; seed++) {
            differs |= call("ysm.perlin_noise", (double) seed, 2.3d, 3.7d, 4.1d) != noise;
        }
        assertTrue(differs, "the seed must select a different noise field");
        assertTrue(condition("ysm.perlin_noise(7,2.3,3.7,4.1)>=0&&ysm.perlin_noise(7,2.3,3.7,4.1)<=1"));
    }

    @Test
    void queryPositionAndDeltaForwardToTheSharedFrameContext() {
        // 没有帧上下文时按 0 回答（MolangFrameContext 由 AnimationRegister#setParserValue /
        // AnimationManager 每帧写入），不会拿上一个实体的值。
        MolangFrameContext.end();
        assertEquals(0.0d, call("query.position", 0.0d), 0.0d);
        assertEquals(0.0d, call("query.position", 1.0d), 0.0d);
        assertEquals(0.0d, call("query.position", 2.0d), 0.0d);
        assertEquals(0.0d, call("query.position_delta", 0.0d), 0.0d);
        assertEquals(0.0d, call("query.position_delta", 1.0d), 0.0d);
        assertEquals(0.0d, call("query.position_delta", 2.0d), 0.0d);
        // 未知轴 → 0，不是异常
        assertEquals(0.0d, call("query.position", 7.0d), 0.0d);
        assertEquals(0.0d, call("query.position_delta", 7.0d), 0.0d);
        assertTrue(condition("query.position_delta(0)==0"));
    }

    @Test
    void theEntityBoundFunctionsAreSafeWithoutAnEntity() {
        // 装备标签 / 粒子 / 音效 / 同步都需要玩家实体；没有实体时必须按假回答而不是抛异常
        // （实机行为要客户端确认，单测只能钉这一半）。
        assertEquals(0.0d, callStrings("query.equipped_item_any_tag", "head", "minecraft:swords"), 0.0d);
        assertEquals(0.0d, callStrings("query.equipped_item_all_tags", "head", "minecraft:swords"), 0.0d);
        assertEquals(0.0d, callStrings("ysm.particle", "minecraft:flame"), 0.0d);
        assertEquals(0.0d, callStrings("abs_particle", "minecraft:flame"), 0.0d);
        assertEquals(0.0d, callStrings("particle", "minecraft:flame"), 0.0d);
        assertEquals(0.0d, callStrings("ysm.abs_particle", "minecraft:flame"), 0.0d);
        assertEquals(0.0d, callStrings("ysm.play_sound", "id", "ysmu:test"), 0.0d);
        assertEquals(0.0d, callStrings("ysm.stop_sound", "id"), 0.0d);
        assertEquals(0.0d, call("ysm.stop_all_sounds"), 0.0d);
        assertEquals(0.0d, call("ysm.sync", 1.0d, 2.0d), 0.0d);
        assertEquals(0.0d, callStrings("ctrl.armor", "head", "$minecraft:diamond_helmet"), 0.0d);
        // 同样经由编译链走一遍（字符串实参 + 多参数）
        assertFalse(condition("ysm.abs_particle('minecraft:flame',0,0,0,0,0,0,0,1,20)"));
        assertFalse(condition("query.equipped_item_any_tag('head','minecraft:swords')"));
    }

    /**
     * 识别与否的**反证**：这些函数在没有实体时都回答 0，与"这个名字答不了"的 0 无法在数值上区分。
     * 所以这里查注册表记下的一次性提示 —— 求值之后这个名字不能出现在"Unsupported ... function"里。
     * 最后一条（真的不存在的名字）是反向对照，保证前一组断言不是空的。
     */
    @Test
    void theHostStateFunctionsAreRoutedToRealBranches() {
        // 提示日志是整个 JVM 一份的去重集合，而它把"这个名字答不了"与"名字认识、只是实参个数不对"
        // 记在同一个 func:<name> 键上：本类的 wrongArityAndUnknownFunctionsAnswerZeroInsteadOfThrowing
        // 会为 math.floor（零参）与 ysm.perlin_noise（两参）各记一次，而这两个名字正在下面这张反证表里。
        // 先复位，字段断言的才是**本次**这些调用有没有落到不支持分支，而不是两个用例谁先跑。
        OpenYsmAnimationControllerRegistry.clearWarnings();
        call("math.floor", 1.0d);
        call("math.pi");
        call("query.position", 0.0d);
        call("query.position_delta", 0.0d);
        detached().functionValue("q.position_delta", java.util.Collections.singletonList(Argument.number(0.0d)));
        callStrings("ctrl.armor", "head", "$minecraft:diamond_helmet");
        callStrings("query.equipped_item_any_tag", "head", "minecraft:swords");
        callStrings("query.equipped_item_all_tags", "head", "minecraft:swords");
        callStrings("particle", "minecraft:flame");
        callStrings("abs_particle", "minecraft:flame");
        callStrings("ysm.particle", "minecraft:flame");
        callStrings("ysm.abs_particle", "minecraft:flame");
        callStrings("ysm.play_sound", "id", "ysmu:test");
        callStrings("ysm.stop_sound", "id");
        call("ysm.stop_all_sounds");
        call("ysm.sync", 1.0d);
        call("ysm.perlin_noise", 1.0d, 2.0d, 3.0d, 4.0d);

        String[] routed = { "ctrl.armor", "math.floor", "math.pi", "query.position", "query.position_delta",
            "query.equipped_item_any_tag", "query.equipped_item_all_tags", "particle", "abs_particle",
            "ysm.particle", "ysm.abs_particle", "ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds",
            "ysm.sync", "ysm.perlin_noise" };
        for (String name : routed) {
            assertFalse(
                OpenYsmAnimationControllerRegistry.hasWarned("func:" + name),
                name + " must be routed to a real branch, not the unsupported-function fallback");
        }
        // 反向对照：真不存在的名字必须被记为不支持。
        call("math.this_name_does_not_exist", 1.0d);
        assertTrue(
            OpenYsmAnimationControllerRegistry.hasWarned("func:math.this_name_does_not_exist"),
            "the negative control must be recorded, or the loop above proves nothing");
    }

    @Test
    void wrongArityAndUnknownFunctionsAnswerZeroInsteadOfThrowing() {
        // 上游按 validateArgumentSize 拒绝；这里同样只是 0，不会让整条条件抛异常。
        assertEquals(0.0d, call("math.floor"), 0.0d);
        assertEquals(0.0d, call("math.pow", 2.0d), 0.0d);
        assertEquals(0.0d, call("math.clamp", 1.0d, 2.0d), 0.0d);
        assertEquals(0.0d, call("math.this_name_does_not_exist", 1.0d), 0.0d);
        assertEquals(0.0d, call("ysm.perlin_noise", 1.0d, 2.0d), 0.0d);
        assertFalse(condition("math.this_name_does_not_exist(1)==1"));
    }
}
