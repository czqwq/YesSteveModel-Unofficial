package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 变量名的**大小写**必须在弹射物轴的两条路径上一致：写变量的那条（动画文件时间轴 /
 * 渲染器发布的 {@code ysm.*}）和读变量的那条（{@code ProjectileControllerRuntime} 求值控制器条件）。
 * <p>
 * 与 {@link OpenYsmControllerVariableCaseTest} 钉住的玩家轴是同一个缺陷的两半。读侧
 * {@code ProjectileControllerRuntime.java:638-649} 用 {@code parseIdentifier}（{@code :803-814}）
 * 从控制器 JSON 的字面条件里取出标识符，**原样**拿去查 {@code MolangParser.VARIABLES}
 * （条件字符串由 {@code OpenYsmAnimationControllerRegistry.java:621} 的 {@code getElementString} 直接
 * 存下，没有任何小写化）。而那张表按构造只有规范拼写：{@code AnimationRegister.java:382-385} 注册的
 * {@code ysm.in_ground} 等四个名字是小写，渲染器 {@code ArrowProjectileRenderer.java:313-334} 也按小写发表，
 * 时间轴写入则先被 {@code MolangParser.parseExpression}（{@code :294} {@code lowerCaseOutsideStrings}，
 * {@code :398}/{@code :441} 再规范一次）小写化。弹射物时间轴的写路径正是
 * {@code ProjectileTimelineRuntime.java:71 → MolangInstructionExecutor.execute}。
 * <p>
 * 所以：包在时间轴里写 {@code v.is_Idle=1;}、条件里读同一个字面量 {@code v.is_Idle}（两侧拼写完全一致，
 * 错只错在引擎），读侧就会拿到 0、过渡恒不触发、目标状态整条不可达。
 */
class ProjectileControllerVariableCaseTest {

    /** 弹射物动画 id：控制器与动画文件都挂在这个 id 上。 */
    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_proj_case");

    /** 本测试自己的实体 id，避免与别的测试的状态机互撞。 */
    private static final int ENTITY_ID = 0x5EED0001;

    /** 渲染器发表、{@code AnimationRegister} 注册的弹射物名字（两者都是小写）。 */
    private static final String PUBLISHED = "ysm.in_ground";

    /** 包可能写下的拼写。Molang 变量名大小写不敏感。 */
    private static final String PUBLISHED_AS_WRITTEN = "ysm.in_Ground";

    /** {@code v.} 命名空间里的探针，让"两条引擎路径互相看不见"这一半不碰任何共享名字。 */
    private static final String PROBE_CANONICAL = "v.proj_case_probe";
    private static final String PROBE_AS_WRITTEN = "v.proj_Case_Probe";

    private static final String FLIGHT_CLIP = "flight_clip";
    private static final String GROUND_CLIP = "ground_clip";

    /** 本测试替换掉的共享表项，跑完放回去，免得污染别的测试。 */
    private LazyVariable previousPublished;

    @BeforeEach
    void installProjectileState() {
        // 与 ArrowProjectileRenderer.setMolangVariable 首帧做的事完全一样：先把名字登记进共享表，
        // 时间轴对它的赋值才会落进表里而不是变成一个语句局部量。AnimationRegister 注册的也是这四个名字。
        previousPublished = MolangParser.VARIABLES.get(PUBLISHED);
        MolangParser.VARIABLES.put(PUBLISHED, new LazyVariable(PUBLISHED, () -> 0.0D));

        // 状态机选片用的动画文件。
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
        MolangParser.VARIABLES.remove(PROBE_CANONICAL);
        if (previousPublished == null) {
            MolangParser.VARIABLES.remove(PUBLISHED);
        } else {
            MolangParser.VARIABLES.put(PUBLISHED, previousPublished);
        }
    }

    /**
     * 一个控制器：初始状态播 {@link #FLIGHT_CLIP}，{@code guard} 一成立就转到播 {@link #GROUND_CLIP} 的状态——
     * 弹射物自己的控制器就是这个形状。
     */
    private static void registerGroundGuard(String guard) {
        String json = "{\"animation_controllers\":{\"projectile_case\":{\"initial_state\":\"flight\",\"states\":{"
            + "\"flight\":{\"animations\":[\""
            + FLIGHT_CLIP
            + "\"],\"transitions\":[{\"ground\":\""
            + guard
            + "\"}]},"
            + "\"ground\":{\"animations\":[\""
            + GROUND_CLIP
            + "\"]}}}}}";
        byte[] controllerFile = json.getBytes(StandardCharsets.UTF_8);
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerFile),
            Collections.emptyList());
    }

    /** 这一帧弹射物轴选中的动画名。 */
    private static List<String> activeAnimations() {
        List<ProjectileControllerRuntime.ActiveAnimation> entries =
            ProjectileControllerRuntime.getActiveAnimationEntries(
                ENTITY_ID, ID, 0.0d, ProjectileControllerRuntime.ProjectileState.AIRBORNE);
        List<String> names = new ArrayList<>(entries.size());
        for (ProjectileControllerRuntime.ActiveAnimation entry : entries) {
            names.add(entry.name);
        }
        return names;
    }

    /**
     * 发表的 {@code ysm.*} 轴：渲染器按小写登记/发表，条件按包里的字面大小写读。
     * <p>
     * 修复前：{@code VARIABLES.get("ysm.in_Ground")} 落空（表里只有 {@code ysm.in_ground}）→ 条件为假 →
     * 状态机停在 {@code flight}，返回 {@code [flight_clip]}。
     * 修复后：读侧先规范成 {@code ysm.in_ground} → 条件为真 → 返回 {@code [ground_clip]}。
     */
    @Test
    void theProjectileAxisSeesTheCaseTheTimelineWrote() {
        registerGroundGuard(PUBLISHED_AS_WRITTEN);

        // 弹射物真正走的时间轴写路径（ProjectileTimelineRuntime:71 -> MolangInstructionExecutor）。
        MolangInstructionExecutor.execute(PUBLISHED_AS_WRITTEN + "=1;");

        assertEquals(
            1.0D,
            MolangParser.VARIABLES.get(PUBLISHED)
                .get(),
            0.0D,
            "the timeline path stores the lower-cased name, because parseExpression lower-cases the statement");
        assertEquals(
            Collections.singletonList(GROUND_CLIP),
            activeAnimations(),
            "the projectile's condition must find the entry the timeline wrote, or the state is unreachable");
    }

    /**
     * 同一处断裂、且**包没有任何错**的一半：包在时间轴和控制器里把同一个变量拼成一样的
     * {@code v.proj_Case_Probe}，只有引擎两侧不一致——时间轴那句被 {@code parseExpression} 小写化，
     * 条件字面量没有。用探针名是为了不碰 {@code v.is_idle} 这类共享表项。
     */
    @Test
    void theProjectileTimelineAndTheProjectileConditionAgreeOnOneSpelling() {
        registerGroundGuard(PROBE_AS_WRITTEN);
        MolangInstructionExecutor.execute(PROBE_AS_WRITTEN + "=1;");

        assertTrue(
            MolangParser.VARIABLES.containsKey(PROBE_CANONICAL),
            "the timeline path must have keyed the canonical spelling");
        assertEquals(
            Collections.singletonList(GROUND_CLIP),
            activeAnimations(),
            "a pack that spells the variable identically on both sides must not be split by the engine");
    }

    /**
     * 读侧过的是**同一条**共享规则（{@link MolangParser#canonicalVariableName}），所以它也接受
     * {@code variable.} 这种长拼写——与 {@code v.} 在运行时其它任何地方都是同一个变量。
     * 修复前 {@code VARIABLES.get("variable.proj_Case_Probe")} 同样落空。
     */
    @Test
    void theAxisAcceptsEverySpellingTheSharedRuleAccepts() {
        registerGroundGuard("variable.proj_Case_Probe");
        MolangInstructionExecutor.execute(PROBE_AS_WRITTEN + "=1;");

        assertEquals(
            Collections.singletonList(GROUND_CLIP),
            activeAnimations(),
            "variable.<name> is the same variable as v.<name>, as it is everywhere else in the runtime");
    }

    /**
     * 弹射物求值器的 {@code math.*} 分支也要按**上游**取值（{@code tmp/YesSteveModel-dev-1.20
     * .../builtin/math/HermitBlend.java:8-11} 与 {@code Ln.java:8-10}），与玩家轴
     * {@code OpenYsmControllerExpressionEvaluator} 一致。
     *
     * <p>修复前这里带着参考分支的两处不同实现：{@code math.hermite_blend} 是未取整的 {@code v*v*(3-2v)}、
     * {@code math.ln} 对 {@code v<=0} 返回 0，于是同一条控制器条件在弹射物轴上与在玩家轴上取值不同
     * （{@code ProjectileControllerRuntime.evaluateFunction} 与
     * {@code OpenYsmControllerExpressionEvaluator.mathValue}）。</p>
     *
     * <p>判据是状态机的**转移结果**（{@link #activeAnimations()}）：求值器是私有的，条件文本才是它的公开
     * 入口——条件成立则选到 {@code guard} 状态，不成立则停在初始状态。</p>
     */
    @Test
    void theProjectileAxisUsesUpstreamMathSemantics() {
        // 三个子用例各自重新登记控制器，先清掉上一个子用例/上一个用例留下的状态机，判据才只是本次求值的结果。
        ProjectileControllerRuntime.clear();
        // 上游 HermitBlend：ceil(0.2)=1 → floor(3-2)=1，所以 1==1 成立；参考分支的 0.2*0.2*(3-0.4)=0.104 不成立。
        registerGroundGuard("math.hermite_blend(0.2)==1");
        assertEquals(
            Collections.singletonList(GROUND_CLIP),
            activeAnimations(),
            "math.hermite_blend must round like upstream, not evaluate the raw cubic");

        // 上游 Ln：Math.log(1)=0。两条护栏（v<=0→0 与 Math.log）在 1.0 上同值，所以这里另取一个只在
        // "取对数" 与 "恒返回 0" 之间有区别的输入：Math.log(e)=1。
        ProjectileControllerRuntime.clear();
        registerGroundGuard("math.ln(math.e)>0.999");
        assertEquals(
            Collections.singletonList(GROUND_CLIP),
            activeAnimations(),
            "math.ln must be Math.log, not a constant 0");

        // 负入参：上游不给护栏，Math.log(-1) 是 NaN，NaN 的任一比较都是假——所以条件不成立。
        ProjectileControllerRuntime.clear();
        registerGroundGuard("math.ln(-1)<0");
        assertEquals(
            Collections.singletonList(FLIGHT_CLIP),
            activeAnimations(),
            "an upstream math.ln has no v<=0 guard, so Math.log(-1) is NaN and the guard is false");
    }
}
