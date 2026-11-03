package com.fox.ysmu.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.fox.ysmu.util.ModAvailability;
import com.fox.ysmu.ysmu;

/**
 * ServerUtilities（1.7.10，{@code tmp/ServerUtilities-master}）权限节点桥：把 {@code /ysm} 的每个子命令注册成
 * 独立的 rank 节点，并把玩家的 rank 判定暴露给 {@code YsmCommand} 的手工分派。
 *
 * <p><b>为什么需要这一层。</b>{@code /ysm} 是<b>一个</b> {@code CommandBase}，子命令在
 * {@code YsmCommand#processCommand} 里手工分派（参考分支同样是手工分派，本仓保留该形态）。而 ServerUtilities
 * 的命令权限是围绕 {@code ICommand} 注册表与 {@code CommandTreeBase} 子节点做的：它在
 * {@code CommandHandler.registerCommand} 时给<b>注册的每个命令</b>生成一个节点；如果命令是
 * {@code CommandTreeBase}，再给它的每个子命令递归生成 {@code 父节点.子命令名}
 * （{@code serverutils/ranks/ICommandWithPermission.java:46-53}）。{@code /ysm} 不是命令树，所以
 * ServerUtilities 只看得到根节点 {@code command.ysmu.ysm}。想让 {@code /ysm manage} 这类子命令能被 rank
 * 单独授权，必须由本仓自己注册子节点、并在手工分派处执行判定 —— 就是这个类 + {@code YsmCommand} 里的一小段
 * gate。</p>
 *
 * <p><b>ServerUtilities 侧的事实</b>（逐条对照 {@code tmp/ServerUtilities-master} 读过）：</p>
 * <ul>
 * <li><b>节点命名</b>：{@code MixinCommandHandler.java:60-69} 在 {@code registerCommand} 的 HEAD 注入，把
 * {@code command.<modid>.<commandName>} 小写化、再把 {@code [^a-zA-Z0-9._]} 每个字符替换为 {@code '_'}。
 * 对 {@code /ysm}（modid {@code ysmu}）得到根节点 {@code command.ysmu.ysm}；本类注册的子节点沿用同一前缀与
 * 字符集（{@link Subcommand#node()}）。</li>
 * <li><b>注册 API</b>：{@code PermissionAPI.registerNode(String, DefaultPermissionLevel, String)}
 * （{@code serverutils/lib/util/permission/PermissionAPI.java:44-54}），要求 loader state 严格晚于
 * PREINITIALIZATION（{@code :49-51}）。它只是写 {@code DefaultPermissionHandler} 的静态表
 * （{@code DefaultPermissionHandler.java:29-41}），因此“注册后出现在 {@code getRegisteredNodes()} 里”由实现
 * 本身保证，不需要运行期验证。</li>
 * <li><b>子命令默认等级</b>：{@code ICommandWithPermission.java:62-70}，{@code getRequiredPermissionLevel() > 0
 * ? OP : ALL}。{@code /ysm} 的级别是 2，所以 ServerUtilities 给根节点登记的默认等级是 OP —— 但默认等级
 * <b>不参与命令执行判定</b>（见下一条），它只用于 rank GUI / {@code /dump permissions} 的展示与
 * {@code DefaultPermissionHandler} 兜底。</li>
 * <li><b>执行判定</b>：{@code MixinCommandHandler.java:32-41}（命令列表）与 {@code :43-58}（执行）两个
 * {@code @ModifyExpressionValue} 把 {@code ICommand#canCommandSenderUseCommand(sender)} 的布尔值换成
 * {@code ((ICommandWithPermission) command).serverutilities$hasPermission(player)}；后者在
 * {@code MixinICommand.java:44-52}：{@code Ranks.INSTANCE.getPermissionResult(player, node, true)} 为
 * DEFAULT 时退回原版 {@code canCommandSenderUseCommand}，ALLOW/DENY 直接决定。这两处 Mixin 只在
 * {@code ranks.enabled && ranks.command_permissions} 时应用（{@code serverutils/mixin/Mixins.java:17-23}，
 * 两者在 {@code ServerUtilitiesConfig.java:496-510} 默认均为 true）。</li>
 * <li><b>为什么被授权的非 OP 玩家能进到 {@code processCommand}</b>：根节点在 rank 里没有条目时
 * {@code getPermissionResult} 返回 DEFAULT，于是回到 {@code YsmCommand#canCommandSenderUseCommand}
 * （它对玩家返回 true），{@code CommandHandler.executeCommand} 的权限门（{@code CommandHandler.java:53}）
 * 放行；这时子命令节点才由 {@code YsmCommand#processCommand} 里的 gate 判定。若管理员给某个 rank 写了
 * 根节点 {@code command.ysmu.ysm: false}，那么整个 {@code /ysm} 在框架层就被拒，子节点无权覆盖。</li>
 * <li><b>继承方向</b>：{@code Rank.getPermission(..., recursive=true)}（{@code Rank.java:193-219}）先查本地
 * 条目、再查父 rank，最后丢掉最后一段点分路径向上查，兜底 {@code *}。所以授权<b>父</b>节点
 * （如 GUI 里可见的 {@code command.ysmu.ysm}）等于授权全部子节点；反过来授权
 * {@code command.ysmu.ysm.manage} 不会授出 {@code command.ysmu.ysm}。</li>
 * <li><b>注册时机</b>：{@code FMLServerStartingEvent} 分发时 loader state 仍是 AVAILABLE
 * （{@code Loader.java:789} 先分发、{@code :790} 才 transition 到 SERVER_STARTING），满足
 * {@code registerNode} 的 “strictly after PREINITIALIZATION” 前置条件；ServerUtilities 自己给根节点登记也发生
 * 在同一事件（它注入的 {@code registerCommand} HEAD）。本仓在 {@code CommonProxy#serverStarting} 里紧接
 * {@code event.registerServerCommand(new YsmCommand())} 之后调用 {@link #install()} 一次，与 ServerUtilities 的
 * 登记点重合，且早于任何 rank GUI 读取。</li>
 * </ul>
 *
 * <p><b>节点表与默认等级</b>：{@link Subcommand} 是注册与 gate 的唯一来源。每个节点带一个
 * {@link VanillaGate}，记录该子命令<b>今天</b>的原版等价门：{@link VanillaGate#OPEN} 对应 help / setgamepath /
 * welcome / buffer / sync（今天对所有玩家开放），{@link VanillaGate#LEVEL_TWO} 对应共享的 2 级门，
 * {@link VanillaGate#LEVEL_FOUR} 对应 debug / upload / manage 在方法体内额外复检的 4 级。注册用的
 * {@code DefaultPermissionLevel} 只有 ALL / OP / NONE 三档：OPEN 的子命令登记 ALL，其余登记 OP（4 级无法表达；
 * OP 与命令自身 {@code getRequiredPermissionLevel()==2} 会被 ServerUtilities 映射出的等级一致，真正的 4 级门
 * 仍留在方法体内，只有 rank 显式 ALLOW 时才跳过）。</p>
 *
 * <p><b>未安装 ServerUtilities 时</b>：{@link #isLoaded()} 为 false，{@link #install()} 什么都不做，
 * {@link #permissionResult(EntityPlayerMP, Subcommand)} 恒返回 {@link #PERMISSION_DEFAULT}，于是
 * {@code YsmCommand} 的 gate 全部退回今天的行为（共享 2 级门 + debug/upload/manage 的 4 级复检），一个字节都
 * 不变。本类对 ServerUtilities 的访问全部是 {@code Class.forName}/{@code Method}/{@code Field} 反射，
 * 没有 import、字段、签名或 catch 子句引用 ServerUtilities 的类型，所以 ServerUtilities 缺席时类照常加载。</p>
 *
 * <p><b>网络侧复检也接在同一节点上</b>：{@code upload}/{@code manage} 的数据包处理器原本各自复检原版 4 级
 * （{@code model/upload/ServerModelUploadManager#acceptChunk}、{@code network/message/RefreshModelManage.Handler}、
 * {@code network/message/HandleFile.Handler}）。只改命令入口会让被授权的非 OP 玩家进得去、随后每个分片/动作仍被
 * 拒绝，所以那三处现在也调用 {@link #isExplicitlyAllowed(EntityPlayerMP, Subcommand)}（upload → {@code UPLOAD}，
 * refresh/delete/move/rename → {@code MANAGE}）：rank 显式 ALLOW 时跳过原版 4 级复检，DEFAULT（未安装或无 rank
 * 条目）时行为与原版逐字节一致。{@code debug} 没有服务端数据包复检。</p>
 *
 * <p><b>rank GUI 可见性</b>：本类注册的节点带非空描述，所以会出现在
 * {@code getRegisteredNodes()}、{@code /dump permissions} 以及 {@code /ranks set_permission} 的补全里
 * （{@code Ranks.getPermissionNodes()} 只对“等级与该前缀默认等级相同且描述为空”的节点做隐藏过滤，
 * {@code Ranks.java:522-558}）。但这两条通道都不够：{@code command.*} 被 rank GUI 的通用权限列表整段跳过
 * （{@code net/MessageRanks.java:120}），而 GUI 的命令组只枚举真实命令对象
 * （{@code CommandUtils.getPermissionCommands():182-213}），{@code /ysm} 是手工分派的一个 {@code CommandBase}，
 * 子命令不是命令对象 —— 所以 15 个节点原先在 GUI 里一个都看不到。ServerUtilities 也没有公开的“往命令组追加节点”
 * 钩子（{@code ServerUtilitiesPermissions.earlyPermissions} 只喂 {@code registerNode}，结局一样是被 {@code :120}
 * 跳过），因此本仓用 {@code mixin/MixinMessageRanks} 注入 {@code MessageRanks#writeData} 的 HEAD，把
 * {@link Subcommand} 的 15 行追加进 {@code MessageRanks.commandPermissions}
 * （{@code net/MessageRanks.java:52}，即 {@code :138-156} 组出来、{@code GuiEditRank} 的黄色“加命令”按钮用的那个
 * {@code ConfigGroup}）。每条都带：节点名（{@code ConfigValueInstance} 的 id，也就是写入 rank 的那个字符串）、
 * 说明（{@link Subcommand#description()}，鼠标悬停显示）与默认等级（{@code ConfigBoolean(默认等级 == ALL)}，
 * 与 ServerUtilities 给真实命令用的写法逐字相同，{@code MessageRanks.java:142-143}）。于是管理员在
 * {@code /ranks} → 选 rank → 黄色“加命令”里勾 {@code command.ysmu.ysm.manage} 就能授出 {@code /ysm manage}，
 * 不用再手打节点名；改动由 GUI 原有的收尾路径写回（{@code MessageRankModify:59-63} →
 * {@code Rank.setPermission(node, value)} → {@code Ranks.save()}），执行端读的仍是同一批节点名，所以 GUI 与
 * {@code /ranks set_permission} / ranks.txt 三条路等价。未装 ServerUtilities（或它改了 GUI 类形状）时
 * {@link #appendRankGuiCommandNodes()} 是空操作/只告警一次，机制见 {@code MixinMessageRanks} 的类注释。
 * 在 GUI 里把根节点 {@code command.ysmu.ysm} 置 true 仍会按点号父级继承开放整族（见上面的继承方向）。</p>
 *
 * <p><b>版本基准</b>：本文所有 ServerUtilities 的 {@code file:line} 都按两处核对过 —— 本仓参考快照
 * {@code tmp/ServerUtilities-master}，与 GTNH 2.9.0 清单钉住的 <b>ServerUtilities 2.4.22</b>
 * （{@code build/elytra_conventions/2.9.0.json}；源码取自 gradle 缓存里的
 * {@code ServerUtilities-2.4.22-sources.jar}）。本文引用到的 13 个类（{@code MessageRanks}、{@code ConfigGroup}、
 * {@code ConfigValueInstance}、{@code ConfigBoolean}、{@code StringUtils}、{@code CommandUtils}、
 * {@code MessageBase}、{@code GuiEditRank}、{@code GuiAddPermission}、{@code MessageRankModify}、{@code Rank}、
 * {@code ICommandWithPermission} 等）在两版之间<b>字节相同</b>（SHA-256 比对），所以行号对两边都成立。
 * GUI 追加的反射句柄在 {@link RankGui} 里独立解析，一处缺失不会波及
 * {@link #permissionResult(EntityPlayerMP, Subcommand)} 的命令判定。开发环境里 ServerUtilities 只是运行期依赖
 * （不在 compileClasspath 上），本文的所有访问因此都是字符串类名 + 反射，编译期从不引用 {@code serverutils.*}。</p>
 */
public final class ServerUtilitiesCompat {

    /**
     * ServerUtilities 的 modid（{@code serverutils/ServerUtilities.java:36}）。
     */
    private static final String MOD_ID = "serverutilities";

    /**
     * {@code /ysm} 的根节点，由 ServerUtilities 在 {@code registerCommand} 时自动生成
     * （{@code MixinCommandHandler.java:63-66}）：{@code command.<modid>.<commandName>}，小写。
     */
    public static final String ROOT_NODE = "command." + ysmu.MODID + ".ysm";

    /**
     * {@link #permissionResult(EntityPlayerMP, Subcommand)} 的返回值：rank 显式拒绝。
     */
    public static final int PERMISSION_DENY = 0;

    /**
     * {@link #permissionResult(EntityPlayerMP, Subcommand)} 的返回值：rank 无条目，退回原版判定。
     */
    public static final int PERMISSION_DEFAULT = 1;

    /**
     * {@link #permissionResult(EntityPlayerMP, Subcommand)} 的返回值：rank 显式允许。
     */
    public static final int PERMISSION_ALLOW = 2;

    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    private static final Object LOCK = new Object();

    /**
     * 每条告警只输出一次（{@code /ysm} 每 tick 都可能被调用，失败路径不能刷屏）。
     */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * {@link #install()} 只尝试一次（同一 JVM 内多次进世界/重载服务器也如此）。
     */
    private static volatile boolean installed;

    /**
     * 反射 API 是否已解析；见 {@link #api()}。
     */
    private static volatile boolean apiResolved;

    /**
     * 反射解析结果；ServerUtilities 缺席或版本不匹配时为 null。
     */
    private static volatile @Nullable Api api;

    /**
     * {@link #rankGui()} 是否已尝试解析（与 {@link #api} 分开：GUI 形状变了不该连累命令判定）。
     */
    private static volatile boolean rankGuiResolved;

    /**
     * rank GUI 追加句柄；ServerUtilities 缺席、没有那个 GUI 类或形状不同时为 null。
     */
    private static volatile @Nullable RankGui rankGui;

    private ServerUtilitiesCompat() {
    }

    /**
     * ServerUtilities 是否已安装。走 {@link ModAvailability}，与其它 compat 包装同一探测方式：查询异常 / 没有
     * 游戏启动都按“未安装”处理。
     */
    public static boolean isLoaded() {
        return ModAvailability.isLoaded(MOD_ID);
    }

    /**
     * 注册 {@code /ysm} 的全部子命令节点。必须在 {@code FMLServerStartingEvent} 期间调用一次（本仓在
     * {@code CommonProxy#serverStarting} 里、紧接 {@code event.registerServerCommand(new YsmCommand())} 之后调用），
     * 原因见类注释“注册时机”。ServerUtilities 缺席时是空操作；重复调用只有第一次生效。
     */
    public static void install() {
        if (installed || !isLoaded()) {
            return;
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            // One attempt per JVM: a failed reflection probe must not be retried (and re-logged) on every
            // server start, and the node table is static anyway.
            installed = true;
            Api resolved = api();
            if (resolved == null) {
                return;
            }
            int registered = 0;
            for (Subcommand subcommand : Subcommand.values()) {
                try {
                    Object level = resolved.levelValueOf.invoke(null, subcommand.defaultLevel().name());
                    resolved.registerNode.invoke(null, subcommand.node(), level, subcommand.description());
                    registered++;
                } catch (Throwable t) {
                    warnOnce(
                        "register:" + subcommand.commandName(),
                        "could not register " + subcommand.node() + " with ServerUtilities: " + t);
                }
            }
            if (registered > 0) {
                ysmu.LOG.info(
                    "[YSMU-COMPAT] registered {} /ysm permission nodes under {} with ServerUtilities",
                    registered,
                    ROOT_NODE);
            }
        }
    }

    /**
     * 把 {@link Subcommand} 的 15 行追加进 ServerUtilities rank GUI 的“命令”权限组，让管理员在 GUI 里点选
     * {@code command.ysmu.ysm.<sub>}（见类注释“rank GUI 可见性”）。
     *
     * <p><b>调用点</b>：唯一调用者是 {@code mixin/MixinMessageRanks}，注入在
     * {@code serverutils.net.MessageRanks#writeData(DataOut)} 的 HEAD（{@code net/MessageRanks.java:165-170}）。
     * 选择那个位置的理由写在 mixin 的类注释里：它是 GUI 数据包唯一的序列化入口，且只在服务器一侧执行。</p>
     *
     * <p><b>幂等</b>：{@code MessageRanks.commandPermissions} 是 {@code private static} 且只构建一次
     * （{@code :138} 的 {@code if (commandPermissions == null)}），所以每次 GUI 数据包写出去都会再次进这个方法。
     * 已存在的节点用 {@code ConfigGroup.hasValue(String)}（{@code lib/config/ConfigGroup.java:186-188}）跳过，
     * 重复调用不会产生第二条条目。整个动作在 {@link #LOCK} 下进行：{@code writeData} 在 Netty 线程上跑
     * （{@code lib/net/MessageBase.java:15-17} 的 {@code toBytes} → {@code writeData}），两个 rank 数据包同时
     * 序列化时不能让 {@code ConfigGroup.values} 被并发写。</p>
     *
     * <p><b>失败即降级</b>：ServerUtilities 缺席、{@link RankGui} 解析失败或那个静态字段还不存在（例如 GUI
     * 还没被打开过，字段仍是 null）时直接返回；任何反射异常只告警一次。命令判定
     * （{@link #permissionResult(EntityPlayerMP, Subcommand)}）与本方法无依赖关系。</p>
     */
    public static void appendRankGuiCommandNodes() {
        if (!isLoaded()) {
            return;
        }
        RankGui gui = rankGui();
        if (gui == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Object group = gui.commandGroup.get(null);
                if (group == null) {
                    // GUI 还没请求过（组是按需构建的），或 ServerUtilities 换掉了那个字段的用途。
                    return;
                }
                for (Subcommand subcommand : Subcommand.values()) {
                    if (Boolean.TRUE.equals(gui.hasValue.invoke(group, subcommand.node()))) {
                        continue;
                    }
                    // 与 MessageRanks:142-153 给真实命令的写法逐字对应：值/默认值都是"默认等级 == ALL"的布尔，
                    // id 用 FLAG_ID_PERIOD_DEFAULTS（否则 4 参重载会把节点名里的 '.' 换成 '_'）。
                    Object value = gui.booleanValue.newInstance(subcommand.defaultLevel() == DefaultLevel.ALL);
                    Object entry = gui.add.invoke(
                        group,
                        subcommand.node(),
                        value,
                        value,
                        gui.periodDefaultsFlag);
                    gui.setDisplayName.invoke(entry, new ChatComponentTranslation(subcommand.node()));
                    gui.setInfo.invoke(entry, rankGuiInfo(subcommand));
                }
            } catch (Throwable t) {
                warnOnce("rankgui", "could not add the /ysm subcommand nodes to the ServerUtilities rank GUI: " + t);
            }
        }
    }

    /**
     * rank GUI 里那一条的悬停说明，与 {@code MessageRanks:144-151} 给真实命令的写法同形：第一行是蓝色的
     * {@code [模组名]}，第二行是说明。{@code GuiAddPermission.PermissionsEntry#addMouseOverText} 按 {@code \n}
     * 拆行显示（{@code client/gui/ranks/GuiAddPermission.java:68-77}）。
     */
    private static IChatComponent rankGuiInfo(Subcommand subcommand) {
        return new ChatComponentText(
            EnumChatFormatting.BLUE + "[" + ysmu.MODID + "]\n" + subcommand.description());
    }

    /**
     * 解析（一次）rank GUI 追加用的反射句柄。整组一起成功才算成功：缺一个句柄就整体为 null 并告警一次，
     * 这样 {@link #appendRankGuiCommandNodes()} 只需要判一次 null，也不存在"半可用"的状态。
     */
    @Nullable
    private static RankGui rankGui() {
        if (rankGuiResolved) {
            return rankGui;
        }
        synchronized (LOCK) {
            if (rankGuiResolved) {
                return rankGui;
            }
            try {
                rankGui = new RankGui();
            } catch (Throwable t) {
                warnOnce(
                    "rankgui-api",
                    "ServerUtilities is installed but its rank GUI classes could not be reflected ("
                        + t.getClass()
                            .getSimpleName()
                        + "); the /ysm subcommand nodes stay invisible in the rank GUI "
                        + "(they still work via /ranks set_permission and ranks.txt)");
            } finally {
                rankGuiResolved = true;
            }
            return rankGui;
        }
    }

    /**
     * 一个 {@code /ysm} 子命令的 rank 判定，语义与 ServerUtilities 自己的命令判定一致
     * （{@code MixinICommand.java:44-52}）：ALLOW/DENY 来自 rank 条目，DEFAULT 表示没有条目。
     *
     * <p>这里直接调用 {@code Ranks.INSTANCE.getPermissionResult(player, node, true)}，而不是
     * {@code PermissionAPI.hasPermission}：后者会把 DEFAULT 折叠成默认等级（OP 档 = 看是不是 OP），丢掉调用方
     * 需要的三态。反射解析与方法选择见 {@link Api}。任何反射失败都降级为 {@link #PERMISSION_DEFAULT}（即调用方
     * 退回原版判定），绝不让权限查询打断命令执行。</p>
     *
     * <p><b>线程。</b>命令 gate 在服务端线程；两个管理包处理器（{@code RefreshModelManage} / {@code HandleFile}）
     * 的判定与原文件操作一起放进 {@code DeferredWork.server}，因此也在服务端线程。<b>唯一还留在 Netty 线程上的
     * 调用点是上传分片路径</b>（{@code ServerModelUploadManager#acceptChunk}，由 {@code C2SUploadModelFile.Handler}
     * 在 Netty 线程调用；本任务没有重构该分片累加器）。ServerUtilities 的 rank 表不是为跨线程设计的，所以那里
     * 存在残余风险：合法上传一定先经 {@code /ysm upload} 命令 gate 在服务端线程预热同一 (玩家, UPLOAD) 的缓存，
     * 之后查询只读；但“绕过命令直接发分片”的客户端可能让首次 rank 查询落在 Netty 线程上，与 ServerUtilities
     * 服务端线程上的 rank 写入并发。要清零该风险的后续动作是把分片权限判定也挪到服务端线程（未做，记录在此）。</p>
     */
    public static int permissionResult(@Nullable EntityPlayerMP player, @Nullable Subcommand subcommand) {
        if (player == null || subcommand == null || !isLoaded()) {
            return PERMISSION_DEFAULT;
        }
        Api resolved = api();
        if (resolved == null || resolved.permissionResult == null) {
            return PERMISSION_DEFAULT;
        }
        try {
            Object ranks = resolved.ranksInstance.get(null);
            if (ranks == null) {
                // ServerUtilities 的 Universe 还没建立（或已销毁）：没有 rank 表，等价于“没有条目”。
                return PERMISSION_DEFAULT;
            }
            Object firstArgument = resolved.playerArgument ? player : player.getGameProfile();
            Object result = resolved.permissionResult
                .invoke(ranks, firstArgument, subcommand.node(), Boolean.TRUE);
            if (result instanceof Enum<?>) {
                String verdict = ((Enum<?>) result).name();
                if (ALLOW.equals(verdict)) {
                    return PERMISSION_ALLOW;
                }
                if (DENY.equals(verdict)) {
                    return PERMISSION_DENY;
                }
            }
            return PERMISSION_DEFAULT;
        } catch (Throwable t) {
            warnOnce("query", "ServerUtilities permission query for " + subcommand.node() + " failed: " + t);
            return PERMISSION_DEFAULT;
        }
    }

    /**
     * rank 是否<b>显式</b> ALLOW 了这个子命令。这是 processCommand / 网络侧 in-body 4 级复检的
     * “ServerUtilities 覆盖”判据：true 时跳过原版等级复检，false（含 DEFAULT）时保持原版行为不变。
     */
    public static boolean isExplicitlyAllowed(@Nullable EntityPlayerMP player, @Nullable Subcommand subcommand) {
        return permissionResult(player, subcommand) == PERMISSION_ALLOW;
    }

    /**
     * 今天豁免共享 2 级门的子命令名（{@link VanillaGate#OPEN} 的那些）——{@code YsmCommand} 的
     * {@code LEVEL_ZERO_SUBCOMMANDS} 由此派生，豁免名单与权限表因此不会漂移。
     */
    public static Set<String> openSubcommandNames() {
        Set<String> names = new HashSet<>();
        for (Subcommand subcommand : Subcommand.values()) {
            if (subcommand.vanillaGate() == VanillaGate.OPEN) {
                names.add(subcommand.commandName());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * 子命令名（大小写不敏感）→ 节点表条目；不认识的名字返回 null（调用方按原版规则处理）。
     */
    @Nullable
    public static Subcommand subcommand(@Nullable String commandName) {
        if (commandName == null) {
            return null;
        }
        String normalized = commandName.toLowerCase(Locale.ROOT);
        for (Subcommand subcommand : Subcommand.values()) {
            if (subcommand.commandName().equals(normalized)) {
                return subcommand;
            }
        }
        return null;
    }

    /**
     * 反射解析一次 ServerUtilities 的三处 API，缓存结果。解析失败（未安装之外的版本不匹配）只告警一次并永久降级为
     * DEFAULT —— 这是能力探测，不校验版本号（{@link ModAvailability#isClassPresent} 的同一原则）。
     */
    @Nullable
    private static Api api() {
        if (apiResolved) {
            return api;
        }
        synchronized (LOCK) {
            if (apiResolved) {
                return api;
            }
            try {
                Class<?> permissionApiClass = Class.forName("serverutils.lib.util.permission.PermissionAPI");
                Class<?> levelClass = Class.forName("serverutils.lib.util.permission.DefaultPermissionLevel");
                Method registerNode = permissionApiClass.getMethod("registerNode", String.class, levelClass,
                    String.class);
                Method levelValueOf = levelClass.getMethod("valueOf", String.class);

                Class<?> ranksClass = Class.forName("serverutils.ranks.Ranks");
                Field ranksInstance = ranksClass.getField("INSTANCE");
                Method permissionResult = findPermissionResult(ranksClass);

                api = new Api(registerNode, levelValueOf, ranksInstance, permissionResult);
            } catch (Throwable t) {
                warnOnce(
                    "api",
                    "ServerUtilities is installed but its permission API could not be reflected ("
                        + t.getClass().getSimpleName() + "); /ysm keeps the vanilla permission levels");
            } finally {
                apiResolved = true;
            }
            return api;
        }
    }

    /**
     * {@code Ranks} 上有两个 {@code getPermissionResult} 重载（{@code Ranks.java:513-520}）：一个接收
     * {@code GameProfile}，一个接收 {@code EntityPlayerMP}。取第一个参数能吃下 {@code EntityPlayerMP} 的那个
     * （少读一次 profile），否则退回 {@code GameProfile} 重载 —— 不同 ServerUtilities 构建可能只保留其一。
     */
    @Nullable
    private static Method findPermissionResult(Class<?> ranksClass) {
        Method fallback = null;
        for (Method method : ranksClass.getMethods()) {
            if (!"getPermissionResult".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 3 || parameters[1] != String.class || parameters[2] != boolean.class) {
                continue;
            }
            if (parameters[0].isAssignableFrom(EntityPlayerMP.class)) {
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            ysmu.LOG.warn("[YSMU-COMPAT] {}", message);
        }
    }

    /**
     * 当前 {@code /ysm} 子命令在今天（ServerUtilities 缺席时）对应的原版权限门。
     */
    public enum VanillaGate {

        /**
         * 今天对所有玩家开放：共享 2 级门之后还有一份豁免名单。
         */
        OPEN,

        /**
         * 今天要求命令自身的 2 级门（{@code YsmCommand#getRequiredPermissionLevel()}）。
         */
        LEVEL_TWO,

        /**
         * 今天在命令体内额外复检 4 级（debug / upload / manage）。
         */
        LEVEL_FOUR
    }

    /**
     * 注册用的 {@code DefaultPermissionLevel}；名字与 ServerUtilities 的枚举常量一一对应（ALL / OP）。
     */
    public enum DefaultLevel {
        ALL,
        OP
    }

    /**
     * {@code /ysm} 的子命令节点表 —— 注册（{@link ServerUtilitiesCompat#install()}）与判定
     * （{@code YsmCommand#processCommand}）的唯一来源，新增子命令只在这里加一行。
     */
    public enum Subcommand {

        /**
         * {@code /ysm help}：今天在权限检查之前直接执行。
         */
        HELP("help", VanillaGate.OPEN, DefaultLevel.ALL, "Allow /ysm help"),

        /**
         * {@code /ysm reload}：重扫模型目录并重新同步。
         */
        RELOAD("reload", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm reload (rebuild and resync the model catalog)"),

        /**
         * {@code /ysm play <targets> <animation>}：上游 {@code PlayAnimationCommand} 的 2 级门。
         */
        PLAY("play", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm play (play a model animation)"),

        /**
         * {@code /ysm playsound [name]}：只转发给执行者客户端。
         */
        PLAYSOUND("playsound", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm playsound (play a model sound)"),

        /**
         * {@code /ysm setgamepath [path] [version]}：只转发给执行者客户端。
         */
        SETGAMEPATH("setgamepath", VanillaGate.OPEN, DefaultLevel.ALL, "Allow /ysm setgamepath (set the client's high-version game path)"),

        /**
         * {@code /ysm buffer}：只转发给执行者客户端。
         */
        BUFFER("buffer", VanillaGate.OPEN, DefaultLevel.ALL, "Allow /ysm buffer (print the client's buffer figures)"),

        /**
         * {@code /ysm welcome [on|off]}：只转发给执行者客户端。
         */
        WELCOME("welcome", VanillaGate.OPEN, DefaultLevel.ALL, "Allow /ysm welcome (toggle the client's welcome message)"),

        /**
         * {@code /ysm sync}：请求服务器重新同步，服务端有限流。
         */
        SYNC("sync", VanillaGate.OPEN, DefaultLevel.ALL, "Allow /ysm sync (request a model resync)"),

        /**
         * {@code /ysm reset <player|@a|@p>}：重置目标玩家的 Molang 状态。
         */
        RESET("reset", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm reset (reset a player's Molang state)"),

        /**
         * {@code /ysm auth ...}：模型授权管理（上游 AuthCommand 的 2 级门）。
         */
        AUTH("auth", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm auth (grant or revoke model authorization)"),

        /**
         * {@code /ysm model set ...}：强制选择模型/贴图（上游 ModelCommand 的 2 级门）。
         */
        MODEL("model", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm model (force a player's model and texture)"),

        /**
         * {@code /ysm molang execute <targets> <expression>}：上游 {@code MolangCommand} 的 2 级门。
         */
        MOLANG("molang", VanillaGate.LEVEL_TWO, DefaultLevel.OP, "Allow /ysm molang (execute a Molang expression on players)"),

        /**
         * {@code /ysm debug ...}：今天在命令体内额外复检 4 级。
         */
        DEBUG("debug", VanillaGate.LEVEL_FOUR, DefaultLevel.OP, "Allow /ysm debug (rank grant replaces the vanilla level-4 re-check)"),

        /**
         * {@code /ysm upload <auth|custom> <path>}：今天在命令体内额外复检 4 级。
         */
        UPLOAD("upload", VanillaGate.LEVEL_FOUR, DefaultLevel.OP, "Allow /ysm upload (rank grant replaces the vanilla level-4 re-check)"),

        /**
         * {@code /ysm manage}：今天在命令体内额外复检 4 级。
         */
        MANAGE("manage", VanillaGate.LEVEL_FOUR, DefaultLevel.OP, "Allow /ysm manage (rank grant replaces the vanilla level-4 re-check)");

        private final String commandName;
        private final VanillaGate vanillaGate;
        private final DefaultLevel defaultLevel;
        private final String description;

        Subcommand(String commandName, VanillaGate vanillaGate, DefaultLevel defaultLevel, String description) {
            this.commandName = commandName;
            this.vanillaGate = vanillaGate;
            this.defaultLevel = defaultLevel;
            this.description = description;
        }

        /**
         * 子命令词（{@code args[0]}，全小写）。
         */
        public String commandName() {
            return commandName;
        }

        /**
         * 这个子命令今天的原版权限门，供 {@code YsmCommand} 的 gate 在 DEFAULT 时回落。
         */
        public VanillaGate vanillaGate() {
            return vanillaGate;
        }

        /**
         * 注册到 {@code PermissionAPI.registerNode} 的默认等级（只影响展示 / 兜底，见类注释）。
         */
        public DefaultLevel defaultLevel() {
            return defaultLevel;
        }

        /**
         * rank GUI / {@code /dump permissions} 里显示的说明；非空，否则节点会被通用列表过滤掉。
         */
        public String description() {
            return description;
        }

        /**
         * 完整节点名：{@link ServerUtilitiesCompat#ROOT_NODE} + {@code '.'} + 子命令名。子命令名本身已经满足
         * ServerUtilities 的字符集（全小写、无分隔符），无需再做 {@code [^a-zA-Z0-9._] → '_'} 替换。
         */
        public String node() {
            return ROOT_NODE + '.' + commandName;
        }
    }

    /**
     * 反射解析出的 ServerUtilities API 句柄。任何一处缺失都由 {@link #api()} 整体降级 —— 宁可按未安装处理，也不让
     * 版本差异在命令执行路径上抛异常。
     */
    private static final class Api {

        private final Method registerNode;
        private final Method levelValueOf;
        private final Field ranksInstance;
        private final @Nullable Method permissionResult;
        private final boolean playerArgument;

        private Api(Method registerNode, Method levelValueOf, Field ranksInstance,
                    @Nullable Method permissionResult) {
            this.registerNode = registerNode;
            this.levelValueOf = levelValueOf;
            this.ranksInstance = ranksInstance;
            this.permissionResult = permissionResult;
            this.playerArgument = permissionResult != null
                && permissionResult.getParameterTypes()[0].isAssignableFrom(EntityPlayerMP.class);
        }
    }

    /**
     * rank GUI 追加所需的反射句柄，全部按 {@code tmp/ServerUtilities-master}（1.7.10）的形状解析：
     *
     * <ul>
     * <li>{@code serverutils.net.MessageRanks#commandPermissions} —— GUI 的“命令”权限组，{@code :52} 的
     * {@code private static ConfigGroup}（所以要走 {@code getDeclaredField} + {@code setAccessible}）。</li>
     * <li>{@code serverutils.lib.config.ConfigGroup#hasValue(String)}（{@code :186-188}）—— 幂等判定。</li>
     * <li>{@code ConfigGroup#add(String, ConfigValue, ConfigValue, int)}（{@code :135-144}）—— 4 参重载，
     * 只有它能保留节点名里的 {@code '.'}（3 参重载用 {@code FLAG_ID_DEFAULTS}，会把点号换成下划线，
     * {@code StringUtils.java:40-42,129-145}）。泛型擦除后的参数类型是 {@code ConfigValue}，所以句柄用
     * {@code ConfigValue} 取，而不是 {@code ConfigBoolean}。</li>
     * <li>{@code ConfigBoolean(boolean)}（{@code :50}）—— 值/默认值，与 {@code MessageRanks:143} 同一构造。</li>
     * <li>{@code ConfigValueInstance#setDisplayName(IChatComponent)} / {@code #setInfo(IChatComponent)}
     * （{@code :85-99}）—— 显示名与悬停说明，两者都会被序列化送到客户端（{@code :159-174}）。</li>
     * <li>{@code StringUtils.FLAG_ID_PERIOD_DEFAULTS}（{@code :41-42}）—— 取不到时用 0（{@code getID(id, 0)} 不做
     * 任何规范化，{@code :100-105}；我们的节点名本来就只有小写字母、数字与点）。</li>
     * </ul>
     *
     * <p>这一组与 {@link Api} 分开解析：GUI 换了形状只该让 GUI 入口消失，不该让命令判定回落到原版等级。</p>
     */
    private static final class RankGui {

        private final Field commandGroup;
        private final Method hasValue;
        private final Method add;
        private final Constructor<?> booleanValue;
        private final Method setDisplayName;
        private final Method setInfo;
        private final int periodDefaultsFlag;

        private RankGui() throws ReflectiveOperationException {
            Class<?> configGroup = Class.forName("serverutils.lib.config.ConfigGroup");
            Class<?> configValue = Class.forName("serverutils.lib.config.ConfigValue");
            Class<?> configInstance = Class.forName("serverutils.lib.config.ConfigValueInstance");

            this.commandGroup = Class.forName("serverutils.net.MessageRanks").getDeclaredField("commandPermissions");
            this.commandGroup.setAccessible(true);
            this.hasValue = configGroup.getMethod("hasValue", String.class);
            this.add = configGroup.getMethod("add", String.class, configValue, configValue, int.class);
            this.booleanValue = Class.forName("serverutils.lib.config.ConfigBoolean").getConstructor(boolean.class);
            this.setDisplayName = configInstance.getMethod("setDisplayName", IChatComponent.class);
            this.setInfo = configInstance.getMethod("setInfo", IChatComponent.class);
            this.periodDefaultsFlag = resolvePeriodDefaultsFlag();
        }

        /**
         * {@code StringUtils.FLAG_ID_PERIOD_DEFAULTS} 的数值；取不到时返回 0（见类注释最后一条）。
         */
        private static int resolvePeriodDefaultsFlag() {
            try {
                return Class.forName("serverutils.lib.util.StringUtils")
                    .getField("FLAG_ID_PERIOD_DEFAULTS")
                    .getInt(null);
            } catch (Throwable t) {
                return 0;
            }
        }
    }
}
