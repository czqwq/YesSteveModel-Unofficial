package com.fox.ysmu.client.roaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Compiles a config form's read/write/label program with the actual animation-file MoLang parser.
 * <p>
 * 上游在加载模型时就把每个 form 编译成固定 role 的类型化 program，并放进 AOT program inventory
 * （{@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/format/parser/ModelInfoBuilder.java:152-177}、
 * {@code expression/inventory/ModelProgramInventory.java:437-459}）；本仓引擎没有那套 inventory，模型载荷里只带源码
 * （{@link com.fox.ysmu.model.resource.pojo.RawYsmModel.ConfigForm#defaultValue} 与 {@code labels}），所以各端在第一次
 * 用到某个 program 时现场编译，并按源码缓存结果（{@link #compileCached}）。这是移植特有的执行层差别，程序语义与上游
 * 一致：读 = {@code value} 表达式；写 = {@code value + "=t.value"} 形状（本仓把这次要写的值直接内联成字面量，见
 * {@link ConfigActionRuntime}）；radio 的 label 动作 = {@code labels} 里那条完整语句。
 * <p>
 * 求值必须发生在属主实体真实的 MoLang 作用域内（{@code MolangPhysicsRuntime.pushScope}），不能对着替身变量表执行；
 * 该规则由 {@link ConfigActionRuntime} 与 {@link ModelFormExecution} 的调用点保证，本类只负责编译。
 */
public final class RoamingProgram {

    /**
     * 缓存上限。表单数量是模型内声明量（一个包几十个），而每个可见表单每帧读一次，所以按源码缓存编译结果是必要的
     * （删掉了本仓旧实现"每帧重新 parse 可见表单"的热路径）；超过上限整表清空而不是做 LRU：缓存键只是源码字符串，
     * 重建代价低，而引入淘汰表就是上游没有的机制。
     */
    private static final int MAX_CACHED_PROGRAMS = 512;

    /** 源码 -> {@link Compiled}；或 {@link #FAILED} 表示这行源码已知编译不过（不再每帧重复 parse/刷日志）。 */
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    /** {@link #CACHE} 里"编译失败"的占位值；不用 null，因为 CHM 不接受 null。 */
    private static final Object FAILED = new Object();

    private RoamingProgram() {}

    /**
     * Compile the complete program, just as upstream AnimationRouletteScreen.java:524-530 does. Evaluation must
     * happen in the owning entity's live MoLang scope, not against a replacement variable map.
     */
    public static Compiled compile(MolangParser parser, String source) throws MolangException {
        return new Compiled(source, parser.parseExpression(source));
    }

    /**
     * {@link #compile} 的缓存版本：同一个源码字符串只解析一次；编译失败只报告一次并返回 {@code null}
     * （调用方按"这个 program 不可用"处理，与上游 per-form parse error 的行为一致：
     * {@code AnimationRouletteScreen.java:524-530} 报告后不执行）。
     */
    @Nullable
    public static Compiled compileCached(@Nullable String source) {
        if (source == null || source.trim()
            .isEmpty()) {
            return null;
        }
        Object cached = CACHE.get(source);
        if (cached != null) {
            return cached instanceof Compiled compiled ? compiled : null;
        }
        try {
            Compiled compiled = compile(GeckoLibCache.getInstance().parser, source);
            if (CACHE.size() >= MAX_CACHED_PROGRAMS) {
                CACHE.clear();
            }
            CACHE.put(source, compiled);
            return compiled;
        } catch (Exception failure) {
            if (CACHE.size() >= MAX_CACHED_PROGRAMS) {
                CACHE.clear();
            }
            CACHE.put(source, FAILED);
            ysmu.LOG.warn("Failed to parse model form Molang '{}': {}", source, failure.getMessage());
            return null;
        }
    }

    public static final class Compiled {
        private final String source;
        private final MolangExpression expression;

        private Compiled(String source, MolangExpression expression) {
            this.source = source;
            this.expression = expression;
        }

        public String source() {
            return this.source;
        }

        public double evaluate() {
            return this.expression.get();
        }
    }
}
