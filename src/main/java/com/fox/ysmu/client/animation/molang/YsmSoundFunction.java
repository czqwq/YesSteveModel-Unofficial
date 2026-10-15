package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import net.minecraft.entity.Entity;
import com.fox.ysmu.client.audio.MolangSoundContext;
import com.fox.ysmu.client.audio.MolangSoundPlayback;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.play_sound / ysm.stop_sound / ysm.stop_all_sounds} 的 mclib 实现
 * （动画关键帧 / {@code .molang} 指令路径）。
 *
 * <p>参数布局对齐参考树 {@code OpenYSM/} 的 {@code SoundFunction}（2.6.5 树，
 * {@code play} 2~5 参 / {@code stop} 1~2 参 / {@code stop_all} 0~1 参）：</p>
 * <pre>
 *   ysm.play_sound('id', 'sound_name', flags?, volume?, pitch?)   // 2~5 参数
 *   ysm.stop_sound('id', global?)                                 // 1~2 参数
 *   ysm.stop_all_sounds(global?)                                  // 0~1 参数
 * </pre>
 * <p>{@code id} 是本次播放的逻辑标识；{@code sound_name} 写法与声音关键帧一致
 * （模型音效名 / {@code namespace:path} / 本地高版本资产路径）。{@code flags}
 * 位标志（1=强制替换、2=当前 animatable 的全局 manager、4=循环）按上游执行。
 * bit 2 不是无衰减，只有 GUI 上下文使用 UI 音效。数字 id 使用负数命名空间，0 匿名；
 * {@code volume}/{@code pitch} 缺省 1.0，显式值限制 0.001~1000。
 * 依据当前上游 {@code SoundFunction.java:70-123} 与 {@code MolangContext.java:220-235}。</p>
 *
 * <p>字符串参数经 {@link MolangStringPool} 池化为整数 id，求值时刻还原；
 * 实体上下文由 {@link ParticleEffectUtil#setCurrentEntity} 每帧写入，
 * 当前模型 id 由 {@link MolangPhysicsRuntime#getCurrentModelId} 提供。</p>
 *
 * <h3>为什么按名字拆成三个子类</h3>
 * <p>{@link Function} 的构造器**在子类字段赋值之前**就调用 {@code getRequiredArguments()}
 * 校验实参个数（{@code Function:11}）。所以"最少几个参数"只能由**类**决定，不能像原来那样
 * 从构造器里才赋值的 {@code stop}/{@code stopAll} 字段推导 —— 旧实现读到的永远是未初始化的
 * {@code false}，三个名字一律要求 2 个参数，于是 1 参的
 * {@code ysm.stop_sound('id')}（文档允许的写法）和 0 参的 {@code ysm.stop_all_sounds()}
 * 直接抛 "requires at least 2 arguments"，被 mclib 包成
 * {@code MolangException: Couldn't parse '…' expression!}。</p>
 * <p>后果远不止这一条语句：{@code .molang} 脚本是**整份先解析再执行**的
 * （{@link MolangScriptInterpreter}），一条表达式解析失败会让整个文件永不执行 ——
 * 某个模型的 {@code @player_update} 文件里同时放着按键鸣笛、随机鸣笛与轮胎旋转，
 * 一处 1 参 {@code stop_sound} 就把这一整批逻辑全部废掉（实机日志：
 * {@code [YSMU-MOLANG-SCRIPT] … player_update failed to execute}）。</p>
 */
public abstract class YsmSoundFunction extends Function {

    private final boolean stopAll;
    private final boolean stop;

    YsmSoundFunction(IValue[] values, String name, boolean stop, boolean stopAll) throws Exception {
        super(values, name);
        this.stop = stop;
        this.stopAll = stopAll;
    }

    /** {@code ysm.play_sound(id, sound_name, flags?, volume?, pitch?)}：至少 2 个参数。 */
    public static final class Play extends YsmSoundFunction {

        public Play(IValue[] values, String name) throws Exception {
            super(values, name, false, false);
        }

        @Override
        public int getRequiredArguments() {
            return 2;
        }
    }

    /** {@code ysm.stop_sound(id, global?)}：至少 1 个参数。 */
    public static final class Stop extends YsmSoundFunction {

        public Stop(IValue[] values, String name) throws Exception {
            super(values, name, true, false);
        }

        @Override
        public int getRequiredArguments() {
            return 1;
        }
    }

    /** {@code ysm.stop_all_sounds(global?)}：可以 0 个参数。 */
    public static final class StopAll extends YsmSoundFunction {

        public StopAll(IValue[] values, String name) throws Exception {
            super(values, name, false, true);
        }

        @Override
        public int getRequiredArguments() {
            return 0;
        }
    }

    /** Legacy double values encode strings with the existing pool; numeric ids occupy the negative namespace. */
    static Integer soundId(double value) {
        int raw = (int) value;
        if (MolangStringPool.get(raw) != null) return raw;
        int numeric = -raw;
        return numeric > 0 ? null : numeric;
    }

    static float configuredValue(double value) {
        // Upstream ValueConversions.asFloat:46-55 converts NaN to zero before Mth.clamp.
        float converted = (float) value;
        return Math.max(0.001f, Math.min(1000f, Float.isNaN(converted) ? 0f : converted));
    }

    @Override
    public double get() {
        try {
            if (args.length > (stopAll ? 1 : stop ? 2 : 5)) return 0;
            MolangSoundContext context = MolangSoundContext.current();
            if (context != null && !context.allowsEmitting()) return 0;
            Entity entity = context == null ? MolangFrameContext.getFrameEntity() : context.entity();
            if (entity == null && context == null) entity = ParticleEffectUtil.getCurrentEntity();
            Object owner = context == null ? entity : context.owner();
            if (owner == null) return 0;
            if (stopAll) {
                boolean global = args.length > 0 && getArg(0) != 0;
                MolangSoundPlayback.stopAll(context == null ? owner : context.manager(global));
                return 1;
            }
            Integer id = soundId(getArg(0));
            if (id == null) return 0;
            if (stop) {
                boolean global = args.length == 2 && getArg(1) != 0;
                return MolangSoundPlayback.stop(context == null ? owner : context.manager(global), id) ? 1 : 0;
            }
            String soundName = MolangStringPool.get((int) getArg(1));
            if (soundName == null || soundName.trim().isEmpty()) return 0;
            int mode = args.length >= 3 ? (int) getArg(2) : 0;
            if (mode < 0 || mode > 7) return 0;
            float volume = args.length >= 4 ? configuredValue(getArg(3)) : 1f;
            float pitch = args.length >= 5 ? configuredValue(getArg(4)) : 1f;
            ResourceLocation modelId = context == null ? MolangFrameContext.getMainModelId() : context.model();
            if (modelId == null) modelId = MolangPhysicsRuntime.getCurrentModelId();
            Object manager = context == null ? owner : context.manager((mode & 2) != 0);
            return MolangSoundPlayback.play(manager, entity, context != null && context.isUI(),
                id, soundName, modelId, mode, volume, pitch) ? 1 : 0;
        } catch (Exception e) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.warn("[YSMU-SOUND] molang sound function error: {}", e.toString());
            }
            return 0.0d;
        }
    }
}
