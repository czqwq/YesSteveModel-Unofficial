package com.fox.ysmu.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.client.animation.molang.MolangReset;
import com.fox.ysmu.client.debug.MolangDebugSnapshot;
import com.fox.ysmu.client.sync.LocalModelLoader;
import com.fox.ysmu.util.ThreadTools;

/**
 * 客户端命令 {@code /ysmclient}（别名 {@code /ysmc}）：纯客户端本地操作，不走服务端，所有玩家可用。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code /ysmclient load}：把本地扫描到的模型（{@code config/ysmu/custom} + {@code builtin}）注册为
 *   仅自己可见的模型（原 /ysmlocal）。</li>
 *   <li>{@code /ysmclient reset}：重置自己的 Molang 变量状态（漫游变量/帧间物理/{@code v.*} 残留）回模型
 *   默认值，无需重载模型。</li>
 *   <li>{@code /ysmclient help [load|reset]}：打印用法行与每个子命令的一行说明。</li>
 * </ul>
 *
 * <p><b>注册是无条件的。</b>本命令<i>不</i>挂在 {@code Config.DEBUG_MOLANG_OVERLAY} 之后：那个开关管的是
 * Molang 调试叠加层、每帧变量快照和四个 debug 包消费者，而本命令是给每个玩家的客户端功能入口。
 * {@code ClientProxy#init} 在客户端 init 阶段直接注册它，因此服务端未装 YSMU（纯净服）时也可用——
 * 这正是 {@code load} 存在的目的。
 *
 * <p><b>为什么命令名不叫 /ysm。</b>Forge 的 {@code ClientCommandHandler} 对同名命令优先于服务端
 * （其类注释："If there is a command with the same name registered both on the server and client, the client
 * takes precedence!"）：在客户端注册 {@code /ysm} 会拦截所有 {@code /ysm} 调用，reload/play 等服务端子命令
 * 再也到不了服务器。独立命令名保证与服务端命令完全隔离；{@code /ysmc} 只是它的别名。
 *
 * <p><b>1.7.10 的别名机制（读源码确认，非猜测）。</b>{@link ICommand#getCommandAliases()} 被
 * {@code CommandHandler#registerCommand} 读取并逐条放进命令表
 * （{@code build/rfg/minecraft-src/java/net/minecraft/command/CommandHandler.java:140-163}，插入在 :157，
 * 已有同名命令的守卫在 :155-156）；{@code ClientCommandHandler} 继承该实现且没有覆盖注册逻辑
 * （{@code build/rfg/minecraft-src/java/net/minecraftforge/client/ClientCommandHandler.java:27}），
 * 所以客户端命令的别名与主名同表：执行时按别名查表（{@code ClientCommandHandler.java:51}，
 * 由 {@code GuiChat#func_146403_a} 先问客户端命令处理器，{@code GuiChat.java:160}），补全时也按别名查表再
 * 转给本命令的 {@link #addTabCompletionOptions}（{@code CommandHandler.java:209-213}）。
 *
 * <p><b>为什么 load 不需要服务端。</b>它读的是客户端自己在 {@code CommonProxy#preInit} 里调用
 * {@code ServerModelManager#reloadPacks()} 建好的本地扫描索引与本地缓存
 * （{@link LocalModelLoader#registerLocalModels()}）：本地模型目录、加密缓存和本地密钥都在客户端这一侧生成
 * （{@code ServerModelManager#initPassword} / {@code #initOpenYsmServerIndex} 是本地文件 IO），
 * 不依赖任何握手包或服务端模型目录。
 *
 * <p><b>移植注：</b>上游（{@code tmp/YesSteveModel-dev-1.20}）没有对应命令——它的客户端总有本地模型目录，
 * 服务端会话只是在其上追加；1.7.10 移植把"仅自己可见地注册本地模型"做成一个本地入口，因此这是移植特有新增。
 * 与官方 wiki 的 {@code /ysmclient} 同名不同义：wiki 那套是给动画制作者的 {@code watch var|state|clear} /
 * {@code molang execute|watch ...} / {@code debug <selector>} 调试命令族（{@code tmp/wiki/command.md:31-43}），
 * 本仓尚未移植（见 {@code .agent/wiki-parity-audit.md}）；将来移植时子命令表就在 {@link #SUBCOMMANDS} 一处扩展。
 */
@SideOnly(Side.CLIENT)
public class CommandYsmClient extends CommandBase {

    /** 子命令；tab 补全与 help 输出共用这一份，避免两处漂移。 */
    private static final String[] SUBCOMMANDS = { "load", "reset", "help" };

    /**
     * 别名 {@code /ysmc}。返回 {@code List<String>} 即可：{@code CommandHandler#registerCommand} 按
     * {@link ICommand#getCommandAliases()} 逐条登记（见类 javadoc 的 file:line）。
     */
    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("ysmc");
    }

    @Override
    public String getCommandName() {
        return "ysmclient";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        // 纯客户端命令，用法行由客户端自己的语言表格式化（I18n.format 走 String.format），
        // 不是聊天翻译组件，所以不受"聊天键只能 %s"那条 1.7.10 限制的约束。
        return I18n.format("message.yes_steve_model.debug.ysmclient.usage");
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 纯客户端命令，所有玩家可用
    }

    /**
     * Tab 补全。主名与别名都走这里：{@code ClientCommandHandler#autoComplete} 用命令别名查表
     * （{@code CommandHandler.java:209-213}），拿到的就是这个 {@link ICommand} 实例，
     * 所以 {@code /ysmc <tab>} 与 {@code /ysmclient <tab>} 的子命令列表一致。
     */
    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        }
        if (args.length == 2 && "help".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "load", "reset");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1 || "help".equalsIgnoreCase(args[0])) {
            processHelp(sender, args);
        } else if ("load".equalsIgnoreCase(args[0])) {
            processLoad(sender);
        } else if ("reset".equalsIgnoreCase(args[0])) {
            processReset(sender);
        } else {
            sender.addChatMessage(
                MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.unknown", args[0]));
        }
    }

    /**
     * /ysmclient [help [load|reset]] — 用法行 + 每个子命令一行说明。
     * <p>
     * 输出都是 {@link ChatComponentTranslation}（经 {@link MolangDebugSnapshot#prefixed}），翻译发生在聊天框渲染时，
     * 所以构造本身不碰客户端语言表；但聊天键仍只能用 %s——1.7.10 的
     * {@code ChatComponentTranslation#initializeFromFormat} 对其它的格式说明符直接抛异常
     * （{@code ChatComponentTranslation.java:121-124}），且是渲染聊天时才抛。
     */
    private void processHelp(ICommandSender sender, String[] args) {
        if (args.length >= 2) {
            String topic = args[1].toLowerCase(Locale.ROOT);
            if (!"help".equals(topic)) {
                if (Arrays.asList(SUBCOMMANDS).contains(topic)) {
                    sender.addChatMessage(
                        MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.help." + topic));
                } else {
                    sender.addChatMessage(
                        MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.unknown", args[1]));
                }
                return;
            }
        }
        sender.addChatMessage(MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.usage"));
        for (String subcommand : SUBCOMMANDS) {
            if ("help".equals(subcommand)) {
                // help 自己的说明就是上面那行用法，不单列一行
                continue;
            }
            sender.addChatMessage(
                MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.help." + subcommand));
        }
    }

    /** /ysmclient load — 注册本地模型（原 /ysmlocal）。 */
    private void processLoad(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.loadlocal.started"));
        // 解析/注册较重，放到后台线程；完成后回主线程报告。
        ThreadTools.THREAD_POOL.submit(() -> {
            int[] result = LocalModelLoader.registerLocalModels();
            int registered = result[0], skipped = result[1], failed = result[2];
            Minecraft mc = Minecraft.getMinecraft();
            // getMinecraft() 在客户端创建之前是 null；参考分支直接解引用，
            // 这里判空只是让"没有客户端"退化为不回话，而不是 NPE。
            if (mc == null) {
                return;
            }
            mc.func_152344_a(() -> {
                if (mc.thePlayer == null) {
                    return;
                }
                if (registered == 0 && skipped == 0 && failed == 0) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation("commands.yes_steve_model.loadlocal.none"));
                } else {
                    // 语言串只能是 %s:1.7.10 的 ChatComponentTranslation 对 %d 之类的格式串直接抛
                    // ChatComponentTranslationFormatException(ChatComponentTranslation.java:121-124),
                    // 而它是在**渲染聊天栏时**才抛的,所以原来那三个 int 会让这条消息永远显示不出来。
                    // 参考分支的 1.20 允许 %d,照抄语言串就把 1.20 的规则带进了 1.7.10。
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "commands.yes_steve_model.loadlocal.complete",
                        String.valueOf(registered),
                        String.valueOf(skipped),
                        String.valueOf(failed)));
                }
            });
        });
    }

    /**
     * /ysmclient reset — 重置自己的 Molang 变量状态。
     * <p>
     * <b>这里不解析 {@code Minecraft}。</b>客户端命令只会由聊天框以本地玩家为 sender 下发：{@code GuiChat}
     * 把 {@code mc.thePlayer} 交给 {@code ClientCommandHandler.executeCommand}
     * （{@code build/rfg/minecraft-src/java/net/minecraft/client/gui/GuiChat.java:160}），后者在
     * {@code ClientCommandHandler.java:72} 原样转给本命令。所以"没有客户端 / 还没进世界"在这一层就是
     * "sender 不是玩家"—— 它正是原来 {@code mc == null || mc.thePlayer == null} 那个判定的等价形式，而
     * {@code sender} 是方法本来就有的参数。原实现改为解引用 {@code Minecraft.getMinecraft()}，而单元测试
     * JVM 的 runtime classpath 没有 lwjgl：那个类在那里连链接都做不到，第一次触碰就抛
     * {@code NoClassDefFoundError: org/lwjgl/LWJGLException}（链路见
     * {@code .agent/phase37-unit-jvm-test-repair.md}），"没有客户端时静默返回"这条分支反而测不到。
     */
    private void processReset(ICommandSender sender) {
        if (!(sender instanceof EntityPlayer player)) {
            return;
        }
        MolangReset.resetSelf();
        player.addChatMessage(
            MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.reset_done"));
    }
}
