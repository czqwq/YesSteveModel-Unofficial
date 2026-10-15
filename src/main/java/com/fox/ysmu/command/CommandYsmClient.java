package com.fox.ysmu.command;

import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.client.animation.molang.MolangReset;
import com.fox.ysmu.client.debug.MolangDebugSnapshot;
import com.fox.ysmu.client.sync.LocalModelLoader;
import com.fox.ysmu.util.ThreadTools;

/**
 * 客户端命令 /ysmclient：纯客户端本地操作，不走服务端。
 *
 * 子命令：
 * - {@code /ysmclient load}：把本地扫描到的模型（config/ysmu/custom + builtin）
 *   注册为仅自己可见的模型（原 /ysmlocal）。
 * - {@code /ysmclient reset}：重置自己的 Molang 变量状态（漫游变量/帧间物理/
 *   v.* 残留）回模型默认值，无需重载模型。
 *
 * 通过 Forge {@code ClientCommandHandler} 注册为纯客户端命令（GuiChat 先尝试
 * 客户端命令处理器，命中则不再发送到服务器）：
 * - 服务端未装 YSMU（纯净服）时也可用；
 * - 服务端装有 YSMU 时，本地独有的模型会追加为仅自己可见，与服务器同名的模型
 *   保持服务端版本（不覆盖，避免混淆）。
 *
 * 不使用 /ysm 子命令：若在客户端注册同名 "ysm" 命令，ClientCommandHandler 会优先
 * 拦截所有 /ysm 调用，导致 reload/play 等服务端子命令无法到达服务器。独立命令名
 * 保证与现有命令完全隔离。
 *
 * <p>整套命令挂在默认关闭的 {@code Config.DEBUG_MOLANG_OVERLAY} 之后：
 * {@code ClientProxy.init} 只在开关打开时注册它。</p>
 */
@SideOnly(Side.CLIENT)
public class CommandYsmClient extends CommandBase {

    @Override
    public String getCommandName() {
        return "ysmclient";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return I18n.format("message.yes_steve_model.debug.ysmclient.usage");
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 纯客户端命令，所有玩家可用
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "load", "reset");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.addChatMessage(
                MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.usage"));
            return;
        }
        if ("load".equalsIgnoreCase(args[0])) {
            processLoad(sender);
        } else if ("reset".equalsIgnoreCase(args[0])) {
            processReset(sender);
        } else {
            sender.addChatMessage(
                MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.ysmclient.unknown", args[0]));
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
            // getMinecraft() 在客户端创建之前是 null（单测 JVM 就是这种状态）；参考分支直接解引用，
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
        if (!(sender instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) sender;
        MolangReset.resetSelf();
        player.addChatMessage(
            MolangDebugSnapshot.prefixed("message.yes_steve_model.debug.reset_done"));
    }
}
