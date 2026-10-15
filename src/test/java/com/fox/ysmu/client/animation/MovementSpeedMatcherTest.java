package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * {@link MovementSpeedMatcher}（防滑步倍速）里不依赖客户端的部分。
 *
 * <p><b>能测到</b>：总开关的短路语义（关闭时一个字段都不碰）、模型作者写的 {@code anim_speed}
 * 的数值钳制与 {@code finite()} 回退（NaN/Infinity 绝不能进 {@code AnimationController.animationSpeed}，
 * 那会把整条播放时钟变成 NaN）、动画周期换算、以及默认名称表。
 *
 * <p><b>测不到</b>（记录，不近似）：{@code computeMultiplier} 与 {@code clearSmoothing}/{@code clearAll}
 * 的平滑状态。它们的入参是 {@code EntityPlayer}，而 1.7.10 的
 * {@code EntityPlayer(World, GameProfile)} 一上来就读 {@code World.isRemote} 与
 * {@code World.getSpawnPoint()}（{@code build/rfg/minecraft-src/java/net/minecraft/entity/player/EntityPlayer.java:163-176}），
 * 单测 JVM 里造不出玩家 —— 这也是 {@code YsmCommandTest} 已经记录过的同一条限制。要覆盖那三条路径，
 * 需要的是一条"按倍速/速度入参"的生产接缝，而本任务不允许为此改生产结构，所以这里不猜。
 */
class MovementSpeedMatcherTest {

    private boolean savedEnabled;
    private boolean savedDebugController;

    @BeforeEach
    void saveConfig() {
        this.savedEnabled = Config.ANIMATION_SPEED_MATCH;
        this.savedDebugController = Config.DEBUG_CONTROLLER;
        Config.DEBUG_CONTROLLER = false;
    }

    @AfterEach
    void restoreConfig() {
        MovementSpeedMatcher.clearAll();
        Config.ANIMATION_SPEED_MATCH = this.savedEnabled;
        Config.DEBUG_CONTROLLER = this.savedDebugController;
    }

    private static final class Animatable implements IAnimatable {

        @Override
        public void registerControllers(AnimationData data) {
            // no controllers: this test only needs a controller it builds by hand
        }

        @Override
        public AnimationFactory getFactory() {
            return null;
        }
    }

    private static AnimationController<Animatable> controller(double playbackSpeed) {
        AnimationController<Animatable> controller =
            new AnimationController<>(new Animatable(), "test_controller", 0.0f, event -> PlayState.STOP);
        controller.animationSpeed = playbackSpeed;
        return controller;
    }

    private static AnimationFile fileWith(String animationName, String animSpeed, Double lengthTicks) {
        AnimationFile file = new AnimationFile();
        Animation animation = new Animation();
        animation.animationName = animationName;
        animation.animSpeed = animSpeed;
        animation.animationLength = lengthTicks;
        file.putAnimation(animationName, animation);
        return file;
    }

    /** 总开关关闭是本次移植的验收硬条件：一个字段都不能碰，连 null 参数也不许 NPE。 */
    @Test
    void theDisabledSwitchNeverTouchesTheController() {
        Config.ANIMATION_SPEED_MATCH = false;
        assertFalse(MovementSpeedMatcher.isEnabled());

        AnimationController<Animatable> controller = controller(2.5d);
        MovementSpeedMatcher.applyPlaybackSpeed(controller, null, "walk", true, fileWith("walk", "3", 40.0d));
        assertEquals(2.5d, controller.animationSpeed, 0.0d, "关闭时必须保持引擎默认/调用方给的值");

        // 全 null 也不许 NPE：短路发生在任何解引用之前。
        MovementSpeedMatcher.applyPlaybackSpeed(null, null, null, true, null);
        assertEquals(2.5d, controller.animationSpeed, 0.0d);
    }

    /** 打开总开关也只对"有玩家"的调用生效：GUI 预览实体（player == null）不参与防滑步。 */
    @Test
    void theEnabledSwitchStillRefusesToActWithoutAPlayer() {
        Config.ANIMATION_SPEED_MATCH = true;
        assertTrue(MovementSpeedMatcher.isEnabled());

        AnimationController<Animatable> controller = controller(1.5d);
        MovementSpeedMatcher.applyPlaybackSpeed(controller, null, "walk", true, fileWith("walk", "3", 40.0d));
        assertEquals(1.5d, controller.animationSpeed, 0.0d, "没有玩家时一个字段都不碰");

        MovementSpeedMatcher.applyPlaybackSpeed(controller, null, "walk", false, fileWith("walk", "3", 40.0d));
        assertEquals(1.5d, controller.animationSpeed, 0.0d);

        MovementSpeedMatcher.applyPlaybackSpeed(controller, null, "   ", true, fileWith("walk", "3", 40.0d));
        assertEquals(1.5d, controller.animationSpeed, 0.0d);

        MovementSpeedMatcher.applyPlaybackSpeed(controller, null, null, true, null);
        assertEquals(1.5d, controller.animationSpeed, 0.0d, "null 动画名不参与，也不许 NPE");
    }

    /** 状态清理入口的 null 契约；真正的状态清空见类注释（没有 EntityPlayer 无法观察）。 */
    @Test
    void theClearingEntryPointsTolerateNullAndRepeatCalls() {
        MovementSpeedMatcher.clearSmoothing(null);
        MovementSpeedMatcher.clearAll();
        MovementSpeedMatcher.clearAll();
    }

    /** 默认名称表：vanilla 的四条步态速度 + 大小写不敏感；非移动类返回 -1（不缩放）。 */
    @Test
    void theDefaultProviderKnowsTheVanillaGaitSpeeds() {
        assertEquals(4.317d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("walk"), 1.0e-9d);
        assertEquals(4.317d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("walking"), 1.0e-9d);
        assertEquals(5.612d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("run"), 1.0e-9d);
        assertEquals(5.612d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("sprint"), 1.0e-9d);
        assertEquals(1.295d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("sneak"), 1.0e-9d);
        assertEquals(1.295d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("crouching"), 1.0e-9d);
        assertEquals(1.727d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("swim"), 1.0e-9d);
        assertEquals(1.727d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("swim_stand"), 1.0e-9d);
        assertEquals(4.317d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("WALK"), 1.0e-9d,
            "名称表大小写不敏感");

        assertEquals(-1.0d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("idle"), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor("fly"), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor(""), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.DEFAULT_PROVIDER.designSpeedFor(null), 1.0e-9d);
    }

    /** 周期来自动画自己的 {@code animation_length}（tick），秒 = tick / 20；缺失/非正值一律 -1（未知）。 */
    @Test
    void cycleSecondsConvertsTicksAndRejectsMissingLengths() {
        assertEquals(2.0d, MovementSpeedMatcher.cycleSeconds(fileWith("walk", null, 40.0d), "walk"), 1.0e-9d);

        assertEquals(-1.0d, MovementSpeedMatcher.cycleSeconds(null, "walk"), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.cycleSeconds(fileWith("walk", null, 40.0d), null), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.cycleSeconds(fileWith("walk", null, 40.0d), "missing"), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.cycleSeconds(fileWith("walk", null, null), "walk"), 1.0e-9d,
            "没有 animation_length = 周期未知");
        assertEquals(-1.0d, MovementSpeedMatcher.cycleSeconds(fileWith("walk", null, 0.0d), "walk"), 1.0e-9d);
        assertEquals(-1.0d, MovementSpeedMatcher.cycleSeconds(fileWith("walk", null, -20.0d), "walk"), 1.0e-9d);
    }

    /** 数字形式的 {@code anim_speed}：负值钳到 0（冻结），正常值原样，缺失/空白一律 1.0（不缩放）。 */
    @Test
    void numericAnimSpeedIsClampedToZeroAtTheBottom() {
        MolangParser parser = new MolangParser();

        assertEquals(2.5d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "2.5", null), "walk", parser), 1.0e-9d);
        assertEquals(0.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "-3", null), "walk", parser), 1.0e-9d);
        assertEquals(0.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "0", null), "walk", parser), 1.0e-9d);

        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(null, "walk", parser), 1.0e-9d);
        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "2.5", null), null, parser), 1.0e-9d);
        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "2.5", null), "missing", parser), 1.0e-9d);
        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", null, null), "walk", parser), 1.0e-9d);
        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "", null), "walk", parser), 1.0e-9d);
    }

    /**
     * {@code finite()} 回退：NaN/Infinity 必须变成 1.0，绝不能写进
     * {@code AnimationController.animationSpeed}（{@code tickOffset = actualTick - wrappedTick / animationSpeed}
     * 会把整条时钟变成 NaN）。{@code -Infinity} 先被 {@code Math.max(0, …)} 钳成 0，两者都要钉住。
     */
    @Test
    void nonFiniteAnimSpeedFallsBackToOne() {
        MolangParser parser = new MolangParser();

        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "NaN", null), "walk", parser), 1.0e-9d);
        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "Infinity", null), "walk", parser), 1.0e-9d);
        assertEquals(1.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "1e400", null), "walk", parser), 1.0e-9d,
            "溢出成 Infinity 的数字字面量同样回退");
        assertEquals(0.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "-Infinity", null), "walk", parser), 1.0e-9d,
            "负无穷先被下钳到 0（冻结），不是 1.0");
    }

    /** 表达式形式：能算的算出来；算出 NaN（{@code math.sqrt(-1)}）或根本解析不了的，回退 1.0。 */
    @Test
    void expressionAnimSpeedEvaluatesAndNeverLeaksNaN() {
        MolangParser parser = new MolangParser();

        assertEquals(5.0d, MovementSpeedMatcher.animSpeedFor(fileWith("walk", "2+3", null), "walk", parser), 1.0e-9d);
        assertEquals(
            1.0d,
            MovementSpeedMatcher.animSpeedFor(fileWith("walk", "math.sqrt(0-1)", null), "walk", parser),
            1.0e-9d,
            "表达式算出 NaN 时必须回退 1.0");
        assertEquals(
            1.0d,
            MovementSpeedMatcher.animSpeedFor(fileWith("walk", "1 + (2", null), "walk", parser),
            1.0e-9d,
            "解析失败按 1.0 处理，不许把异常抛给渲染路径");
    }
}
