package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator.Argument;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator.ConditionScope;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.RuntimeState;
import com.fox.ysmu.client.animation.molang.PackFunctionScript;

import software.bernie.geckolib3.core.molang.IMolangPhysicsScope;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.processor.AnimationProcessor;

/**
 * 变量名的**大小写**必须在两条路径上一致：写变量的动画文件路径，和读变量的控制器路径。
 * <p>
 * 模型包的门控可以完全由变量名拼写决定成败。一个真实的包在动画文件的时间轴里写
 * {@code "timeline":{"0":["v.is_Idle=1;"]}}，而它的控制器 JSON 用一个字面量读同一个变量：
 * {@code "ctidle":{"transitions":[{"idle":"v.is_Idle"}]}}（{@code v.is_Sit} 是同一对）。
 * 动画那侧的名字会先被 {@code MolangParser.parseExpression} 整体小写化
 * （{@code lowerCaseOutsideStrings}），所以作用域里存的是 {@code v.is_idle}；控制器那侧原来按字面
 * 去查 {@code v.is_Idle}，于是门控恒为假、控制器永远停在 {@code initial_state}、行走动画整条不可达。
 * <p>
 * 上游 1.20 只有一个拼写规则：每个标识符都过 {@code api/molang/MolangNames#identifier:29-35}
 * （小写化）。本仓的对应物是 {@link MolangParser#canonicalVariableName}，它被用在解析器注册表、
 * 线上同步路径（{@code RemoteAnimationVariables.normalize}）、宿主注入的作用域表，以及现在这条
 * 控制器轴和 {@link MolangPhysicsRuntime} 的读写入口上。这个类钉住"两条路径互相看得见"。
 */
class OpenYsmControllerVariableCaseTest {

    /** 控制器路径里那条真实的表达式与变量名。 */
    private static final String CONTROLLER_CONDITION = "v.is_Idle";
    private static final String BARE_NAME = "is_Idle";

    /**
     * 一个脱离玩家的帧作用域：只提供 {@link MolangPhysicsRuntime} 做键所需的两个 id，
     * 与 {@code MolangScopeKeyStabilityTest} 用的桩同一个形状（那里也证明过 null 实体可以工作）。
     */
    private static final class PreviewScope implements IMolangPhysicsScope {

        @Override
        public EntityLivingBase getMolangEntity() {
            return null;
        }

        @Override
        public ResourceLocation getMolangModelId() {
            return new ResourceLocation("ysmu", "a_model/main");
        }

        @Override
        public ResourceLocation getMolangAnimationId() {
            return new ResourceLocation("ysmu", "a_model/main");
        }
    }

    /** {@code begin} 只要求处理器非 null，作用域内的断言不会问它任何问题。 */
    private static final class StubAnimatable implements software.bernie.geckolib3.core.IAnimatable {

        @Override
        public void registerControllers(software.bernie.geckolib3.core.manager.AnimationData data) {}

        @Override
        public software.bernie.geckolib3.core.manager.AnimationFactory getFactory() {
            return new software.bernie.geckolib3.core.manager.AnimationFactory(this);
        }
    }

    @AfterEach
    void releaseScope() {
        MolangPhysicsRuntime.end();
        MolangPhysicsRuntime.clear();
    }

    private static void openFrameScope() {
        MolangPhysicsRuntime.begin(new PreviewScope(), 0.0D, new AnimationProcessor<StubAnimatable>(null));
    }

    /**
     * 控制器轴：与 {@code Context#variableValue} 的 {@code v.} 分支同一个映射
     * （折掉 {@code variable.} 前缀 → 去掉 {@code v.} → 交 {@link RuntimeState}）。
     */
    private static ConditionScope controllerAxis(RuntimeState state) {
        return new ConditionScope() {

            @Override
            public double variableValue(String name) {
                String normalized = name.startsWith("variable.") ? "v." + name.substring("variable.".length())
                    : name;
                return normalized.startsWith("v.") ? state.getVariable(normalized.substring(2), 0.0D) : 0.0D;
            }

            @Override
            public double functionValue(String name, List<Argument> arguments) {
                return 0.0D;
            }
        };
    }

    /**
     * 交叉路径：动画文件写进去的名字，控制器必须读得到。
     * <p>
     * 修复前：动画路径把 {@code v.is_Idle=1;} 存成 {@code v.is_idle}，控制器拿 {@code v.is_Idle}
     * 去查表，落空 → 条件为假（{@code RuntimeState.getVariable} 读到 0）。
     * 修复后：两条路径过同一条规则，条件为真、{@code getVariable} 读到 1。
     */
    @Test
    void controllerAxisSeesTheCaseTheAnimationPathWrote() throws Exception {
        openFrameScope();
        MolangParser parser = new MolangParser();

        // 动画文件路径：时间轴指令 "v.is_Idle=1;"（模型包里的真实写法）。
        parser.parseExpression("v.is_Idle=1;")
            .get();
        assertEquals(
            1.0D,
            MolangPhysicsRuntime.getVariable("v.is_idle", -1.0D),
            0.0D,
            "the animation path stores the lower-cased name, because parseExpression lower-cases the statement");

        // 控制器路径：过渡条件里的字面量 "v.is_Idle"。
        RuntimeState state = new RuntimeState();
        assertEquals(
            1.0D,
            state.getVariable(BARE_NAME, 0.0D),
            0.0D,
            "the controller axis must find what the animation path wrote, or the gate is false forever");
        assertEquals(1.0D, MolangPhysicsRuntime.getVariable(CONTROLLER_CONDITION, -1.0D), 0.0D,
            "the scope's own entry point answers to either spelling");
        assertTrue(
            OpenYsmControllerExpressionEvaluator.evaluateCondition(CONTROLLER_CONDITION, controllerAxis(state)),
            "evaluateCondition('v.is_Idle') must be true once the animation timeline assigned it");
    }

    /**
     * 同一条断裂的另一半：控制器（{@code on_entry} 语句、{@code ctrl.set_*} 之前的准备变量）写下的名字，
     * 动画路径必须读得到。{@code executeStatement} 把 {@code v.is_Idle=1} 变成写入一个**裸名**
     * {@code is_Idle}，所以这里同样按裸名写。
     */
    @Test
    void theAnimationPathReadsTheCaseTheControllerAxisWrote() throws Exception {
        openFrameScope();
        MolangParser parser = new MolangParser();

        RuntimeState state = new RuntimeState();
        state.setVariable(BARE_NAME, 1.0D);
        assertEquals(
            1.0D,
            MolangPhysicsRuntime.getVariable("v.is_idle", -1.0D),
            0.0D,
            "the controller axis must write the canonical key, or nothing the animation reads can find it");

        // 动画文件/关键帧里的两种拼写读的是同一个变量（Molang 大小写不敏感）。
        assertEquals(1.0D, parser.parseExpression("v.is_idle")
            .get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("v.is_Idle")
            .get(), 0.0D);
    }

    /**
     * 修复把同一条规则用在两层（控制器状态机自己的兜底表 + {@link MolangPhysicsRuntime} 的作用域表），
     * 所以规则必须幂等：已经规范的名字再规范一次不能被改坏，否则"再折一次"就会把能工作的名字弄丢。
     */
    @Test
    void theRuleIsIdempotentSoCanonicalisingTwiceCannotChangeAWorkingName() {
        for (String name : new String[] { "v.is_idle", "v.roaming.c", "v.x", "query.life_time", "q.life_time",
            "variable.x", "V.Roaming.C", "v.show_car" }) {
            String once = MolangParser.canonicalVariableName(name);
            assertEquals(once, MolangParser.canonicalVariableName(once), "not idempotent for " + name);
        }
    }

    /** 没有帧作用域时 {@link RuntimeState} 用自己的兜底表；那张表也必须按同一个拼写存。 */
    @Test
    void theFallbackStoreUsesTheSameSpellingAsTheFrameScope() {
        RuntimeState state = new RuntimeState();

        state.setVariable(BARE_NAME, 1.0D);
        assertEquals(1.0D, state.getVariable(BARE_NAME, 0.0D), 0.0D);
        assertEquals(1.0D, state.getVariable("is_idle", 0.0D), 0.0D, "Molang is case-insensitive");
        assertEquals(1.0D, state.getVariable("IS_IDLE", 0.0D), 0.0D, "Molang is case-insensitive");
    }

    /**
     * 第三条路径 {@code .molang} 包脚本：{@code PackFunctionScript#registerVariables} 注册脚本自己的
     * {@code t.*} / {@code v.*} 之前先查重，而它查的是**原样**从脚本源码里抠出来的名字
     * （{@code collectAssignedVariables} 不折大小写），解析器注册表的键却是规范名。
     * <p>
     * 于是脚本写 {@code v.pack_ScripT_ProbE=1;} 时，{@code containsKey} 永远查不到已有的
     * {@code v.pack_script_probe}，那句"宿主已经注册过的名字不要动"形同不存在
     * （{@code MolangParser#register} 把不同拼写都落到同一个规范键上，所以那次注册是原地替换）。
     * 这里钉住修好之后的行为：规范名的注册表条目**没有被换掉**。
     */
    @Test
    void packScriptKeepsTheCanonicalEntryInsteadOfReRegisteringOverIt() {
        // 宿主先注册（真实场景：脚本变量表 / 漫游变量）。解析器把 v.* 一律包成 ScopedMolangVariable，
        // 所以"这一条"要从注册表里取，而不是拿传进去的那个对象。
        new MolangParser().register(new LazyVariable("v.pack_script_probe", 5.0D));
        LazyVariable hosted = MolangParser.VARIABLES.get("v.pack_script_probe");
        assertNotNull(hosted, "precondition: the host's variable is in the parser registry");

        PackFunctionScript script = PackFunctionScript.parse("v.pack_ScripT_ProbE=1; return 1;");
        assertNotNull(script, "the probe script must parse");
        assertTrue(
            script.assignedVariables()
                .contains("v.pack_ScripT_ProbE"),
            "collectAssignedVariables keeps the source spelling; registerVariables is what canonicalises it");
        script.registerVariables(new MolangParser());

        assertSame(
            hosted,
            MolangParser.VARIABLES.get("v.pack_script_probe"),
            "a script's non-canonical spelling must not re-register over the canonical entry "
                + "(the pre-fix containsKey() checked the as-written name, so it never matched)");
    }

    /**
     * 同一处修复的另外一半：脚本里混合大小写的名字，赋值与读取必须命中同一个变量。
     * <p>
     * 修好之前这里也是对的 —— 解析器自己 {@code parseExpression} 就小写化了，{@code v.Foo=1} 与
     * {@code v.foo} 都落到 {@code v.foo}；所以这一条钉的是"修复没有把原来能工作的行为弄坏"，
     * 而不是缺陷本身。名字带探测前缀，避免与别的测试共用静态 {@code MolangParser.VARIABLES} 时互撞。
     */
    @Test
    void packScriptAssignmentAndReadAgreeOnTheCanonicalName() throws Exception {
        MolangParser parser = new MolangParser();
        PackFunctionScript script = PackFunctionScript
            .parse("v.case_probe=1; v.case_Probe ? { return 7; } : { return 0; };");
        assertNotNull(script, "the probe script must parse");

        script.registerVariables(parser);

        assertEquals(7.0D, script.evaluate(parser), 0.0D, "Molang is case-insensitive for a script's own names");
        assertEquals(1.0D, MolangParser.VARIABLES.get("v.case_probe")
            .get(), 0.0D, "the assignment landed on the canonical entry");
    }
}
