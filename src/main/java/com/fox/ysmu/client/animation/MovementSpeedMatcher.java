package com.fox.ysmu.client.animation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 移动动画防滑步（stride matching）。
 *
 * <p>
 * 本类没有 upstream 对应物：{@code tmp/YesSteveModel-dev-1.20} 全树
 * {@code grep anim_speed|animSpeed|animationSpeed|stride} = 0，它不做播放倍速缩放。
 * 形状照参考分支 {@code client/animation/MovementSpeedMatcher.java}（该文件在本仓不存在），
 * 这是"参考分支有、upstream 没有"的纯移植特性（它自己的 {@code OpenYSM} 参考树也没有），
 * 所以本类的权威是参考分支，不是 upstream。
 *
 * <p>
 * 问题：YSM 的 walk/run/sneak 等移动动画是固定周期循环（动画长度固定），步态周期覆盖的
 * "步幅"固定；玩家实际速度与动画设计速度不一致时，脚就会在冰面上滑（滑步）。
 *
 * <p>
 * 解法：按真实水平速度缩放移动类动画的播放倍速（GeckoLib
 * {@code AnimationController.animationSpeed}）：
 *
 * <pre>
 *     设计速度 = 该步态规范步幅 S / 动画周期 T
 *     倍速     = 基础倍率 × 平滑水平速度 / 设计速度 = 基础倍率 × 平滑水平速度 × T / S
 * </pre>
 *
 * 四种步态参与（要"腿与地面变化接触"）：walk/run 用共享步幅 4.317，sneak 用 1.295，
 * swim 用 1.727；fly 等不参与（不缩放）。每种步态归一到自己的基准步幅后，同一步态下
 * 同一实际速度脚速一致，再套用玩家水平速度随动（药水 buff/debuff 会改变它）。
 * 外部只暴露一个基础倍率 {@link Config#ANIMATION_SPEED_MATCH_BASE}。
 *
 * <p>
 * 名称表（方案 A）用于 1) 判断是否移动类（分类）；2) 周期未知时的回退设计速度。周期来自
 * 模型动画自己的 {@code animation_length}（引擎 {@code Animation.animationLength} 是 tick，
 * 参考 {@code JsonAnimationUtils.java:366} 的 {@code convertSecondsToTicks}），
 * 缺 {@code animation_length} 时本仓的断件解析按 0d 落库
 * （{@code YSMFolderDeserializer.java:693-695}），于是回退名称表——两条路都覆盖。
 */
public final class MovementSpeedMatcher {

    /** 低于此水平速度（blocks/s）视为静止，不缩放（交回状态机切换 idle）。 */
    private static final double MIN_GROUND_SPEED = 0.1;
    /** 倍速下限：防止极端钳制导致动画几乎定格。 */
    private static final double MIN_MULTIPLIER = 0.2;
    /** 倍速上限。 */
    private static final double MAX_MULTIPLIER = 3.0;

    // ---- 方案 A：动画名 → 设计速度 (blocks/s) 名称表（仅作周期未知时的回退） ----
    // 1.7.10 vanilla 玩家速度：行走 4.317、冲刺 5.612（×1.3）、潜行 1.295（×0.3）、
    // 游泳 1.727（×0.4）。fly 无"腿地接触"不参与。
    // 模型自定义动画名（如"行走"/"疾跑"）不在表中 —— 返回 -1 表示不缩放。
    private static final String[] LOCOMOTION_NAMES = {
        "walk", "walking", "walk_loop",
        "run", "running", "run_loop",
        "sprint", "sprinting",
        "sneak", "sneaking", "crouch", "crouching",
        "swim", "swimming",
    };
    private static final double[] LOCOMOTION_SPEEDS = {
        4.317, 4.317, 4.317,
        5.612, 5.612, 5.612,
        5.612, 5.612,
        1.295, 1.295, 1.295, 1.295,
        1.727, 1.727,
    };

    // ---- 各步态规范步幅（blocks/完整步幅周期，内部常量，不暴露给配置）----
    // 源自 vanilla 速度 × 默认模型对应动画周期（参考分支仅对 walk/run 共享步幅）：
    //   walk/run = 4.317、sneak = 1.295、swim = 1.727
    private static final double WALK_RUN_STRIDE = 4.317;
    private static final double SNEAK_STRIDE = 1.295;
    private static final double SWIM_STRIDE = 1.727;

    private static final String[] WALK_RUN_NAMES = {
        "walk", "walking", "walk_loop",
        "run", "running", "run_loop",
        "sprint", "sprinting",
    };
    private static final String[] SNEAK_NAMES = {
        "sneak", "sneaking", "crouch", "crouching",
    };
    private static final String[] SWIM_NAMES = {
        "swim", "swimming",
    };

    /**
     * 动画设计速度提供者：返回该动画名对应的设计速度（blocks/s）；<= 0 表示不是移动类
     * 动画（idle/jump/swing 等），不做倍速缩放。
     */
    public interface SpeedProvider {
        double designSpeedFor(String animationName);
    }

    /** 默认名称表实现（方案 A）。 */
    public static final SpeedProvider DEFAULT_PROVIDER = name -> {
        if (name != null) {
            for (int i = 0; i < LOCOMOTION_NAMES.length; i++) {
                if (LOCOMOTION_NAMES[i].equalsIgnoreCase(name)) {
                    return LOCOMOTION_SPEEDS[i];
                }
            }
        }
        return -1.0d;
    };

    /** 每玩家平滑倍速状态（有状态版本；切断/换世界时由 clearAll 释放）。 */
    private static final Map<UUID, Double> SMOOTHED_MULTIPLIER = new ConcurrentHashMap<>();
    /**
     * {@code anim_speed} 表达式解析缓存（按表达式原文）。
     * <p>
     * 键用<b>原文</b>而不是参考分支的小写文本：MoLang 表达式里的字符串字面量大小写敏感
     * （{@code MolangParser.lowerCaseOutsideStrings} 只小写字符串外的部分），参考分支把键
     * {@code toLowerCase} 后，两个只差字符串字面量大小写的表达式会共用一次解析结果。
     * 标识符大小写不同时多出一条缓存项，代价可忽略。
     */
    private static final Map<String, MolangExpression> ANIM_SPEED_CACHE = new ConcurrentHashMap<>();

    private MovementSpeedMatcher() {}

    /**
     * 总开关（{@link Config#ANIMATION_SPEED_MATCH}）。两条播放路径的入口先用它短路，避免默认关闭时
     * 每帧、每个控制器都做一次玩家/动画文件查询；权威判定仍在 {@link #applyPlaybackSpeed} 里。
     */
    public static boolean isEnabled() {
        return Config.ANIMATION_SPEED_MATCH;
    }

    /**
     * 计算当前应施加的动画播放倍速（1.0 = 不缩放）。
     *
     * @param player               玩家（非 null）
     * @param primaryAnimationName 正在播放的主动画名
     * @param provider             设计速度提供者（null 时用默认名称表）
     * @param cycleSeconds         该动画的周期时长（秒）；<=0 表示未知，回退名称表
     * @return 播放倍速
     */
    public static double computeMultiplier(EntityPlayer player, String primaryAnimationName,
        SpeedProvider provider, double cycleSeconds) {
        SpeedProvider prov = provider != null ? provider : DEFAULT_PROVIDER;
        double design = designSpeed(primaryAnimationName, prov, cycleSeconds);
        if (design <= 0.0d) {
            // 非移动类动画：立即恢复 1.0，不留平滑残留
            clearSmoothing(player);
            return 1.0d;
        }
        double groundSpeed = groundSpeed(player);
        double target;
        if (groundSpeed < MIN_GROUND_SPEED) {
            // 静止：缓慢回到 1.0（状态机即将切到 idle，避免动画突然定格）
            target = 1.0d;
        } else {
            // 基础倍率 × 实际速度 / 设计速度。玩家速度每帧实时读取，倍速随之自适应。
            target = Config.ANIMATION_SPEED_MATCH_BASE * (groundSpeed / design);
        }
        target = MathHelper.clamp_double(target, MIN_MULTIPLIER, MAX_MULTIPLIER);

        // 诊断（DEBUG_CONTROLLER 开启时同一标签每秒最多一条）
        if (Config.DEBUG_CONTROLLER && allowDebugLog("SPEED-MATCH")) {
            ysmu.LOG.info("[YSMU-SPEED] anim='{}' cycle={}s design={} groundSpeed={} target={}x (BASE={})",
                primaryAnimationName, cycleSeconds, design, groundSpeed, target, Config.ANIMATION_SPEED_MATCH_BASE);
        }

        double response = Config.ANIMATION_SPEED_MATCH_RESPONSE;
        UUID id = player.getUniqueID();
        Double prev = SMOOTHED_MULTIPLIER.get(id);
        double smoothed = prev == null ? target : prev + (target - prev) * response;
        SMOOTHED_MULTIPLIER.put(id, smoothed);
        return smoothed;
    }

    /**
     * 设计速度（blocks/s）。walk/run/sneak/swim：周期已知时按 该步态基准步幅 / 周期 计算
     * （归一化）；周期未知时回退名称表 vanilla 速度。非移动类（含 fly）返回 <= 0（不缩放）。
     */
    private static double designSpeed(String animationName, SpeedProvider provider, double cycleSeconds) {
        double stride = gaitStride(animationName);
        if (stride <= 0.0d) {
            // 非移动类，不缩放
            return -1.0d;
        }
        if (cycleSeconds > 0.0d) {
            // 各步态归一到自己的基准步幅：设计速度 = 步幅 / T。
            // 短周期动画设计速度更高，同实际速度下倍速更低 → 同一步态下脚速一致。
            return stride / cycleSeconds;
        }
        return provider.designSpeedFor(animationName);
    }

    /** 返回该动画所属步态的基准步幅（blocks/完整步幅周期）；非移动类返回 <= 0。 */
    private static double gaitStride(String animationName) {
        if (animationName == null) {
            return -1.0d;
        }
        for (String n : WALK_RUN_NAMES) {
            if (n.equalsIgnoreCase(animationName)) {
                return WALK_RUN_STRIDE;
            }
        }
        for (String n : SNEAK_NAMES) {
            if (n.equalsIgnoreCase(animationName)) {
                return SNEAK_STRIDE;
            }
        }
        for (String n : SWIM_NAMES) {
            if (n.equalsIgnoreCase(animationName)) {
                return SWIM_STRIDE;
            }
        }
        return -1.0d;
    }

    /**
     * 从模型动画文件读取指定动画的周期时长（秒）。返回 <= 0 表示未知（动画缺失或没有长度）。
     *
     * @param file          模型动画文件（可为 null）
     * @param animationName 动画名
     * @return 周期秒数（GeckoLib animationLength 是 tick，÷20 转秒）
     */
    public static double cycleSeconds(AnimationFile file, String animationName) {
        if (file != null && animationName != null) {
            Animation anim = file.getAnimation(animationName);
            if (anim != null && anim.animationLength != null && anim.animationLength > 0.0d) {
                return anim.animationLength / 20.0d;
            }
        }
        return -1.0d;
    }

    /**
     * 求值当前动画的 {@code anim_speed}（逐动画播放倍率，数字或 MoLang 表达式）。
     * 无该字段/求值失败返回 1.0（不缩放）；负值钳制为 0（冻结）。
     * <p>
     * 非有限结果按 1.0 处理：{@code AnimationController.animationSpeed} 参与
     * {@code tickOffset = actualTick - wrappedTick / animationSpeed}
     * （{@code AnimationController.java:1118}）与动画位置计算（{@code :1285}），
     * 模型作者写出 {@code 0/0} 这类表达式时 NaN/Infinity 会让整条时钟变成 NaN。
     * 参考分支没有这一步（它直接 {@code Math.max(0d, value)}，NaN 会穿透）。
     */
    public static double animSpeedFor(AnimationFile file, String animationName, MolangParser parser) {
        if (file == null || animationName == null) {
            return 1.0d;
        }
        Animation anim = file.getAnimation(animationName);
        if (anim == null || anim.animSpeed == null || anim.animSpeed.isEmpty()) {
            return 1.0d;
        }
        String raw = anim.animSpeed.trim();
        try {
            return finite(Math.max(0.0d, Double.parseDouble(raw)));
        } catch (NumberFormatException e) {
            // MoLang 表达式：每帧求值（依赖 query 变量，如 query.ground_speed）
            try {
                MolangExpression expr = ANIM_SPEED_CACHE.computeIfAbsent(raw, s -> {
                    try {
                        return parser.parseExpression(s);
                    } catch (Exception ex) {
                        return null;
                    }
                });
                if (expr != null) {
                    return finite(Math.max(0.0d, expr.get()));
                }
            } catch (Exception ignored) {
                // 求值失败回退 1.0
            }
            return 1.0d;
        }
    }

    /** 非有限值统一回退 1.0；见 {@link #animSpeedFor} 的说明。 */
    private static double finite(double value) {
        return Double.isFinite(value) ? value : 1.0d;
    }

    /**
     * 计算并写入控制器播放倍速 = anim_speed × 防滑步倍率。legacy（AnimationManager）与
     * OpenYSM（OpenYsmPlayerControllerRuntime）两条播放路径共用此实现，避免重复。
     * <p>
     * 总开关 {@link Config#ANIMATION_SPEED_MATCH} 关闭时<b>完全不碰</b>
     * {@code AnimationController.animationSpeed}（保持引擎默认 1.0），这是本次移植的
     * 验收硬条件：关闭时行为必须与移植前逐字节一致。参考分支把 {@code anim_speed}
     * 放在总开关之外无条件应用（参考 {@code MovementSpeedMatcher.java:294-301}）——
     * 本仓不照抄这一处，见汇报。
     *
     * @param ctrl             目标控制器（非 null）
     * @param player           玩家；null（GUI 预览实体）时不干预
     * @param animationName    正在播放的动画名；空白视为不缩放
     * @param isBodyController 是否主身体控制器——防滑步仅对主身体控制器生效
     * @param file             模型动画文件（可 null）
     */
    public static void applyPlaybackSpeed(AnimationController<?> ctrl, EntityPlayer player,
        String animationName, boolean isBodyController, AnimationFile file) {
        if (!isEnabled()) {
            return;
        }
        if (ctrl == null || player == null || StringUtils.isBlank(animationName)) {
            return;
        }
        double strideMultiplier = 1.0d;
        // 防滑步：仅主身体控制器按真实速度缩放（设计速度 = 步幅 / 周期）
        if (isBodyController) {
            double cycleSeconds = cycleSeconds(file, animationName);
            strideMultiplier = computeMultiplier(player, animationName, DEFAULT_PROVIDER, cycleSeconds);
        }
        // anim_speed：模型作者逐动画播放倍率
        double animSpeed = animSpeedFor(file, animationName, GeckoLibCache.getInstance().parser);
        // 最终倍率 = anim_speed × 防滑步倍率
        ctrl.animationSpeed = finite(strideMultiplier * animSpeed);
    }

    /** 清除指定玩家的平滑状态（例如切换模型/离开世界时）。 */
    public static void clearSmoothing(EntityPlayer player) {
        if (player != null) {
            SMOOTHED_MULTIPLIER.remove(player.getUniqueID());
        }
    }

    /** 清除所有平滑状态与表达式解析缓存（换世界/清模型/断开时）。 */
    public static void clearAll() {
        SMOOTHED_MULTIPLIER.clear();
        // 参考分支只清 SMOOTHED_MULTIPLIER，不清表达式表。这里连它一起丢：条目数只随"见过的不同
        // 表达式"增长（不是按帧泄漏），但换世界/断开本来就是把这一整套运行期状态作废的时机，
        // 而引擎自己的对应清理钩子（AnimationController.clearAnimTimeUpdateCache）在本仓
        // 没有任何调用者，所以这一步是本仓补的清理点，不是照抄。
        ANIM_SPEED_CACHE.clear();
    }

    /**
     * 水平速度（blocks/s）。用位置差值（每 tick 真实位移）× 20 计算，本地/远程一致。
     * <p>
     * 不能对本地玩家用 motionX：1.7.10 客户端本地玩家的 motion 在渲染期数值约为 pos 差值的
     * 1/4.317，会导致 BASE 需要设到 ~4.317 才等效于 1.0。与控制器
     * {@code query.ground_speed} 的现有实现
     * （{@code OpenYsmControllerExpressionEvaluator.java:1873-1877} 的 horizontalSpeed()）
     * 逐字一致，两处必须保持同源。
     */
    private static double groundSpeed(EntityPlayer player) {
        double dx = player.posX - player.prevPosX;
        double dz = player.posZ - player.prevPosZ;
        return MathHelper.sqrt_double(dx * dx + dz * dz) * 20.0d;
    }

    /** 调试日志限流：同一 tag 每秒最多一次。 */
    private static final Map<String, Long> DEBUG_LOG_LAST_TIME = new ConcurrentHashMap<>();

    private static boolean allowDebugLog(String tag) {
        long now = System.currentTimeMillis();
        Long last = DEBUG_LOG_LAST_TIME.get(tag);
        if (last != null && now - last < 1000) {
            return false;
        }
        DEBUG_LOG_LAST_TIME.put(tag, now);
        return true;
    }
}
