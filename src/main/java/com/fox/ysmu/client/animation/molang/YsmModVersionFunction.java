package com.fox.ysmu.client.animation.molang;

import javax.annotation.Nullable;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.mod_version(modid)} - the version string of the named client-side mod
 * (YSM-wiki: molang/ref「ysm 实体部分」: {@code ysm.mod_version(modid)} 返回客户端安装的指定模组的版本).
 *
 * <h3>字符串怎么落成 double：池化 id</h3>
 * <p>上游返回的是**字符串**：{@code ModVersion.java:14-23} 取 {@code arguments.getAsString(context, 0)} 作为 modId，
 * 再返回该模组的 Maven 版本字符串，找不到时返回 {@code null}；注册在
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/animation/molang/YSMBinding.java:73}
 * 的 {@code function("mod_version", new ModVersion())}，参数个数校验为 1（{@code ModVersion.java:26-28}）。
 * 本引擎的 {@code com.eliotlash.mclib.math.functions.Function.get()} 只有 {@code double}，
 * 所以字符串按本仓已有的字符串机制返回：{@link MolangStringPool} 的池化 id ——
 * 与 {@code ysm.relative_block_name} 完全同一条路
 * （{@code YsmRelativeBlockNameFunction.java:50} 的 {@code MolangStringPool.intern(name)}）。
 * {@code MolangParser} 在解析期把 {@code '2.6.5'} 这类字面量也池化成同一个 id
 * （{@code MolangParser.java:963} 的 {@code out.append(MolangStringPool.intern(literal.toString()))}），
 * 于是包里的 {@code ysm.mod_version('ysm')=='2.6.5'} 两侧落在同一个 id 上，比较成立。</p>
 *
 * <h3>为什么不学参考分支注册恒 0 桩</h3>
 * <p>参考分支把 {@code ysm.mod_version} 绑到恒 0 的 {@code CtrlHoldFunction}，并注明
 * "1.7.10 模组版本是字符串、无统一数字语义"
 * （{@code tmp/YesSteveModel-Unofficial-perf-previewUI/src/main/java/com/fox/ysmu/client/animation/AnimationRegister.java:160-161}）。
 * 该理由只说明"没有**数字**语义"，而上面那条池化机制正是本仓为此准备的：wiki 与上游对该函数的唯一用法就是
 * 与一个版本字符串比较，恒 0 会让这个比较永远为假（0 是空串 id，不是任何版本），
 * 而这正是本仓对 {@code ysm.relative_block_name} 已经解决过的问题。所以这里是池化 id，不是桩。</p>
 *
 * <p><b>1.7.10 缺口</b>：上游对版本做 Maven 规范化
 * （{@code ModVersion.java:21} 的 {@code MavenVersionStringHelper.artifactVersionToString(...)}）；1.7.10 的
 * {@code ModContainer#getVersion()} 已经是模组自己声明的版本字符串，FML 没有对应的 Maven 助手，
 * 所以这里直接返回该字符串（原样，仅去首尾空白）。</p>
 */
public class YsmModVersionFunction extends Function {

    public YsmModVersionFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    /** 上游 {@code validateArgumentSize} 是 1（{@code ModVersion.java:26-28}）。 */
    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        return versionId(modVersion(CtrlScriptBinding.stringArgument(this.args, 0, "")));
    }

    /**
     * 版本字符串 → 池化 id；空串/缺失（上游的 {@code null}）→ 0。
     * <p>拆出来是为了让"返回什么"这一半可以在没有 FML 的 JVM 里单测。</p>
     */
    static double versionId(@Nullable String version) {
        if (version == null) {
            return 0.0d;
        }
        String trimmed = version.trim();
        return trimmed.isEmpty() ? 0.0d : MolangStringPool.intern(trimmed);
    }

    /**
     * 上游 {@code ModVersion.java:20-22}：按 modId 取该模组的版本字符串，找不到返回 {@code null}。
     * <p>1.7.10 的等价取数是 {@code Loader.instance().getIndexedModList().get(modId).getVersion()}
     * （{@code cpw.mods.fml.common.Loader:728-731}、{@code ModContainer:59}）—— 与
     * {@code YsmDebugDumpFunction.dumpMods()} 读的是同一张表。
     * <p>捕捉异常是因为这张表只有 FML 启动后才填好：单测 JVM 里 {@code Loader.instance()} 会抛，
     * 如实回答"这个 mod 的版本未知"（返回 null → 0），而不是让整条关键帧表达式崩掉。
     */
    @Nullable
    static String modVersion(@Nullable String modId) {
        if (modId == null || modId.trim()
            .isEmpty()) {
            return null;
        }
        try {
            ModContainer container = Loader.instance()
                .getIndexedModList()
                .get(modId.trim());
            return container == null ? null : container.getVersion();
        } catch (Exception e) {
            return null;
        }
    }
}
