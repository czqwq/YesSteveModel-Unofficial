package com.fox.ysmu.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.compat.ServerUtilitiesCompat;

/**
 * 把 {@code /ysm} 的 15 个子命令节点塞进 ServerUtilities rank GUI 的“命令”权限组 —— 这是
 * {@code compat/ServerUtilitiesCompat} 那些节点的<b>图形入口</b>（类注释“rank GUI 可见性”一节）。
 *
 * <p><b>为什么需要注入。</b>ServerUtilities 的 rank GUI 有两个权限来源：通用列表
 * （{@code PermissionAPI.getRegisteredNodes()}，{@code net/MessageRanks.java:115-135}）和命令组
 * （{@code CommandUtils.getPermissionCommands()}，{@code net/MessageRanks.java:138-156}）。本仓注册的节点名都以
 * {@code command.} 开头，通用列表在 {@code MessageRanks.java:120} 用
 * {@code if (node.startsWith(Rank.NODE_COMMAND)) continue;} 把它们全部跳过；命令组只枚举真实的
 * {@code ICommand} / {@code CommandTreeBase} 子命令 / Brigadier 节点，而 {@code /ysm} 是手工分派的一个
 * {@code CommandBase}，子命令不是命令对象。两者都不覆盖 → 15 个节点只出现在 {@code /dump permissions} 与
 * {@code /ranks set_permission} 的补全里，GUI 里看不到。ServerUtilities 没有“追加自定义节点到命令组”的公开钩子
 * （{@code ServerUtilitiesPermissions.earlyPermissions} 只喂 {@code registerNode}，最终还是被 {@code :120} 跳过），
 * 所以只能注入它自己组装命令组的那个方法。</p>
 *
 * <p><b>注入点。</b>{@code serverutils.net.MessageRanks#writeData(DataOut)}（{@code net/MessageRanks.java:165-170}）
 * 的 HEAD。这个方法是服务器把这个 GUI 数据包序列化出去的唯一入口
 * （{@code lib/net/MessageBase.java:15-17} 的 {@code toBytes} 调它），此时
 * {@code MessageRanks.commandPermissions}（{@code :52} 的 {@code private static ConfigGroup}）已经建好，
 * 我们的 15 条正好在 {@code :168-169} 写出去之前进去。选它而不是构造函数，是因为构造函数有两个重载
 * （{@code :54} 的无参重载在<b>客户端</b>反序列化时也跑），而 {@code writeData} 只会在服务器这一侧执行 ——
 * 客户端不该往自己收到的那份组里加节点（服务端没装 YSMU 时那会造出 15 个点了也没用的条目）。
 * 追加动作本身全部在 {@link ServerUtilitiesCompat#appendRankGuiCommandNodes()} 里，用反射完成：本类没有
 * {@code @Shadow}、没有 {@code serverutils.*} 的类型，所以 ServerUtilities 缺席时本类照样能加载。</p>
 *
 * <p><b>ServerUtilities 缺席时（交付要求的一部分）。</b>本类用 {@link Pseudo} +
 * {@code @Mixin(targets = "serverutils.net.MessageRanks")}（字符串目标，编译期不需要那个类），
 * 所以：</p>
 * <ul>
 * <li><b>编译期</b>：{@code @Pseudo} 让注解处理器把目标当作 simulated handle 处理
 * （{@code org/spongepowered/tools/obfuscation/AnnotatedMixin.java:230-241}：virtual 时走
 * {@code getSimulatedHandle}，而不是 {@code MIXIN_SOFT_TARGET_NOT_FOUND(Kind.ERROR)}；后者才是没有
 * {@code @Pseudo} 时碰字符串目标的下场），因此目标不在编译类路径上也能编译。本类同时声明
 * {@code remap = false}（{@code @Mixin} 与 {@code @Inject} 各一处），{@code AnnotatedMixin.java:185} 的
 * {@code remap} 因此为 false、{@code shouldRemap()} 为 false，注解处理器连重映射分支都不进，连
 * “{@code @Pseudo} mixin will not be obfuscated”那条 {@code MISSING_INJECTOR_DESC_SIMULATED(Kind.OTHER)}
 * 提示（{@code AnnotatedMixinElementHandlerInjector.java:229-231}）都不会有 —— 两种写法都不是错误。</li>
 * <li><b>运行期</b>：Mixin 在配置装载时逐个 mixin 解析目标类。{@code ClassInfo.forName:2077-2102} 用字节码提供者
 * 读不到这个类时先打一条 <b>WARN</b>（{@code Error loading class: serverutils/net/MessageRanks
 * (java.lang.ClassNotFoundException: …)}）并把 null 缓存起来；接着 {@code MixinInfo.getTargetClass:1002-1014}
 * 看到 null 先问 {@code isVirtual()}（=有 {@code @Pseudo}，{@code :1044-1046}），是则只打一条 debug 日志
 * {@code Skipping virtual target …} 并返回 null；随后
 * {@code MixinConfig.selectMixins:948-961} 只在 {@code getTargetClasses().size() > 0} 时才把这个 mixin 登记进
 * {@code this.mixins}，目标缺失的 mixin 就此被跳过，不注入任何东西。{@code mixins.ysmu.json} 的
 * {@code "required": true} 只把<b>异常</b>升级成致命错误（{@code MixinConfig.java:936-945,962-972}），而目标缺失这条
 * 路径在默认配置下根本不抛异常 —— 抛异常的那条路在 {@code MixinInfo.handleTargetError:1016-1022} 里以
 * {@code strict} 为条件，{@code strict = parent.getEnvironment().getOption(Option.DEBUG_TARGETS)}
 * （{@code MixinInfo.java:838}），即只有显式开 {@code -Dmixin.debug.strict.targets=true} 才变成错误。
 * 结论：没装 ServerUtilities 时启动照常、功能只是不出现，代价是启动日志里多<b>一条 WARN</b>（上面那条
 * “Error loading class”）+ 一条 debug 跳过日志。这个代价是 {@code @Pseudo} 的固有形态，不是配置写错：
 * {@code tmp/Angelica-2.2.37} 用完全相同的组合（{@code @Pseudo} + {@code @Mixin(targets="Reika/DragonAPI/…")}
 * + 其 {@code mixins.angelica.late.json} 的 {@code "required": true}，且全仓没有 MixinConfigPlugin）处理
 * DragonAPI / Chisel / Railcraft / Starminer 这些可选模组。若所有者要求连这条 WARN 都消失，办法是给本模组加一个
 * {@code IMixinConfigPlugin} 在 {@code shouldApplyMixin} 里按 {@code Loader.isModLoaded("serverutilities")} 过滤，
 * 但那需要改 {@code gradle.properties} 的 {@code mixinPlugin}（构建级改动，需 Lead 裁决，本任务未做）。</li>
 * </ul>
 *
 * <p><b>ServerUtilities 改了形状时。</b>注入点用方法名 {@code writeData} 定位（它是
 * {@code MessageBase#writeData} 的覆写，名字被 ServerUtilities 自己的类层次钉住），并声明 {@code require = 0}
 * （{@code InjectionInfo.parseRequirements:400-405}：{@code require = 0} 表示“不要求命中数”，
 * 于是选择器一条也匹配不上时不会抛 {@code InjectionError}，见 {@code :529-533}）。类名或字段/方法名变了则
 * {@link ServerUtilitiesCompat#appendRankGuiCommandNodes()} 的反射解析失败，只告警一次并放弃这个 GUI 入口，
 * 已注册的节点与命令判定完全不受影响（那条路径是 {@code ServerUtilitiesCompat.Api}，与这里分开解析）。
 * {@code remap = false}：ServerUtilities 的类从不混淆，注入的 {@code writeData} 也没有 refmap 需求。</p>
 *
 * <p><b>版本基准。</b>被注入的 {@code MessageRanks} 与另外 12 个被引用的 ServerUtilities 类在两个版本里
 * <b>字节相同</b>（SHA-256 逐个比对过）：本仓的参考快照 {@code tmp/ServerUtilities-master}，与 GTNH 2.9.0 清单
 * 钉住的 <b>ServerUtilities 2.4.22</b>（{@code build/elytra_conventions/2.9.0.json}，来源
 * {@code ServerUtilities-2.4.22-sources.jar}，gradle 缓存里就有）。所以本文引用的每一个
 * {@code file:line} 在两个版本上都成立。开发环境里 ServerUtilities 是<b>只在运行期</b>的依赖
 * （{@code dependencies.gradle} 的 {@code runtimeOnly...("ServerUtilities")}，不在 compileClasspath 上），
 * 因此 dev {@code runClient}/{@code runServer} 会真的解析到目标、这个 mixin 会真的应用 —— 也就是说 dev 跑起来
 * 验证的是“装了 ServerUtilities”这一支；<b>缺席那一支只能在生产服务器上体现</b>，上面的证明来自读 Mixin 实现，
 * 不是来自某次 dev 启动。编译期仍然看不到 {@code serverutils.*}（本类一个类型都没引用），这条约束由
 * {@code @Pseudo} + 字符串目标 + 反射保证，与运行期依赖无关。</p>
 */
@Pseudo
@Mixin(targets = "serverutils.net.MessageRanks", remap = false)
public abstract class MixinMessageRanks {

    @Inject(method = "writeData", at = @At("HEAD"), require = 0, remap = false)
    private void ysmu$appendYsmSubcommandNodes(CallbackInfo ci) {
        ServerUtilitiesCompat.appendRankGuiCommandNodes();
    }
}
