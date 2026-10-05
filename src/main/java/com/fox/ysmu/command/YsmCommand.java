package com.fox.ysmu.command;

import static com.fox.ysmu.compat.Utils.isValidResourceLocation;
import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.PacketEvalMolang;
import com.fox.ysmu.network.message.PacketQueryMolangVar;
import com.fox.ysmu.network.message.PacketResetMolang;
import com.fox.ysmu.network.message.S2CPlaySound;
import com.fox.ysmu.network.message.S2CSetDebugOverlay;

public class YsmCommand extends CommandBase {

    /** The permission level {@link #processDebug} re-checks per invocation; see its javadoc. */
    private static final int DEBUG_PERMISSION_LEVEL = 4;

    @Override
    public String getCommandName() {
        return "ysm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ysm <reload|playsound [name]|debug <query|eval|overlay>|reset <selector>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    /**
     * Tab completion, ported from the reference branch's {@code YsmCommand:42-72} and reduced to the subcommands this
     * tree actually implements.
     * <p>
     * The reference reaches {@code MinecraftServer.getServer().getConfigurationManager().getAllUsernames()} with no
     * guard. That call is only safe while a server exists: a dedicated server always has one, but an integrated server
     * that is still starting - and a unit JVM, which is how {@code YsmCommandTest} drives this method - does not, and
     * 1.7.10 command completion runs on the server thread. Degrading to "no suggestions" is a null check, not a
     * behaviour change; the reference's list is returned verbatim whenever a server is present.
     */
    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "reload", "playsound", "debug", "reset");
        }
        if (args.length == 2) {
            if ("debug".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "query", "overlay", "eval");
            }
            if ("reset".equalsIgnoreCase(args[0])) {
                MinecraftServer server = MinecraftServer.getServer();
                if (server == null || server.getConfigurationManager() == null) {
                    return null;
                }
                return getListOfStringsMatchingLastWord(
                    args,
                    server.getConfigurationManager()
                        .getAllUsernames());
            }
        }
        if (args.length == 3 && "debug".equalsIgnoreCase(args[0]) && "overlay".equalsIgnoreCase(args[1])) {
            return getListOfStringsMatchingLastWord(args, "on", "off", "toggle");
        }
        return null;
    }

    /**
     * {@code /ysm reload | playsound | debug | reset}, the four subcommands this tree implements.
     * <p>
     * <b>Deliberately not ported from the reference</b> ({@code YsmCommand:114-135}), with the reason each one is
     * blocked rather than omitted: {@code play} (needs the {@code eep}/{@code GeckoLibCache} animation lookup the
     * reference calls at {@code :220-229}), {@code setgamepath} and {@code welcome} (their packets
     * {@code SyncGamePath} / {@code SetWelcomeConfig} do not exist in this tree - {@code NetworkHandler} registers no
     * id for either), {@code buffer} ({@code ShowBufferInfo}, same - the reference's id 22 is free here but its client
     * screen is not ported), {@code sync} (the reference calls
     * {@code OpenYsmModelSyncServer.requestResync}/{@code RESYNC_*}/{@code resyncRemainingSeconds}, none of which this
     * tree's {@code OpenYsmModelSyncServer} declares), and {@code help} (a whole
     * {@code commands.yes_steve_model.help.*} key family plus the clickable "turn the welcome guide off" line - the
     * reference's own {@code processHelp} exists only to advertise subcommands that are not here). Each is an
     * independent follow-up, not a stub, and none of them is referenced by this method.
     * <p>
     * The framework already enforces {@link #getRequiredPermissionLevel()} for every branch: 1.7.10's
     * {@code CommandHandler.executeCommand} calls
     * {@code ICommand.canCommandSenderUseCommand(sender)} before dispatch, and that inherited
     * {@link CommandBase#canCommandSenderUseCommand(ICommandSender)} compares the level against the sender. The
     * reference's same-named override is not ported: it returns {@code true} for every sender and then re-implements
     * the check inside {@code processCommand} as {@code throw new CommandException("commands.generic.permission")},
     * which is exactly what the framework already does here. Copying a port-specific re-implementation of the vanilla
     * gate would be a second mechanism for one rule.
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            processReload(sender);
        } else if ("playsound".equalsIgnoreCase(args[0])) {
            processPlaySound(sender, args.length >= 2 ? joinCommandArguments(args, 1) : "");
        } else if ("debug".equalsIgnoreCase(args[0])) {
            processDebug(sender, args);
        } else if ("reset".equalsIgnoreCase(args[0])) {
            processReset(sender, args);
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    /**
     * {@code /ysm playsound [name]} - forwards to the issuer's own client.
     * <p>
     * The sound library lives entirely on the client: model sound bytes are only ever decoded there, and playback
     * goes through this client's {@code SoundSystem}. A dedicated server has no {@code Minecraft} instance at all,
     * so running the request here would list nothing and play nothing. The server's job is the permission check
     * ({@link #getRequiredPermissionLevel}) and the forward; the client does the rest, including the reply.
     * <p>
     * No name lists what that client has; a name plays it.
     */
    private void processPlaySound(ICommandSender sender, String soundName) {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        NetworkHandler.CHANNEL.sendTo(new S2CPlaySound(soundName), player);
    }

    /**
     * {@code /ysm debug <query|eval|overlay> [args]} - forwards the request to the issuer's own client, ported from
     * the reference branch's {@code YsmCommand.processDebug} ({@code
     * tmp/YesSteveModel-Unofficial-perf-previewUI/src/main/java/com/fox/ysmu/command/YsmCommand.java:358-431}).
     * <p>
     * All three branches are {@code Side.CLIENT} packet handlers here ({@code NetworkHandler:277-296}), so the
     * direction is server to client by construction - and it has to be, for the reason the reference states at
     * {@code :416-418}: Molang variables, the eval result and {@code DebugOverlay} are all per-client state
     * ({@code MolangParser} is a client singleton and {@code DebugOverlay} touches {@code net.minecraft.client.*} and
     * LWJGL, neither of which a dedicated server has). The server's job is the permission check and the forward; the
     * client answers in its own chat.
     * <p>
     * <b>Permission.</b> The reference re-checks level {@value #DEBUG_PERMISSION_LEVEL} here rather than relying on the
     * command's own default level 2, because {@code eval} can call any {@code ysm} function, including the
     * side-effecting ones ({@code particle}, {@code play_sound}). That gate is kept verbatim, including the vanilla
     * {@code commands.generic.permission} exception it throws. On a default server ({@code op-permission-level=2}) this
     * means only players explicitly granted level 4 can use {@code /ysm debug}; a single-player host always can.
     */
    private void processDebug(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        // 1.7.10 的 EntityPlayerMP.canCommandSenderUseCommand 为双参签名 (permissionLevel, commandName)，
        // 与 CommandBase 内部的调用方式一致（参考分支 :369 的注释）。
        if (!player.canCommandSenderUseCommand(DEBUG_PERMISSION_LEVEL, getCommandName())) {
            throw new CommandException("commands.generic.permission");
        }
        if (args.length < 2) {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.debug.usage"));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if ("query".equals(sub)) {
            if (args.length < 3) {
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.debug.query.usage"));
                return;
            }
            NetworkHandler.CHANNEL.sendTo(new PacketQueryMolangVar(parseQueryNames(args)), player);
        } else if ("eval".equals(sub)) {
            if (args.length < 3) {
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.debug.eval.usage"));
                return;
            }
            NetworkHandler.CHANNEL.sendTo(new PacketEvalMolang(parseEvalExpression(args)), player);
        } else if ("overlay".equals(sub)) {
            // Overlay 是纯客户端状态：服务端只转发切换请求，状态反馈也在客户端输出（服务端不知道结果状态）。
            // 参考分支 :419-425 的判定原样保留：缺参、或第三个参数不是 on/off（含 "toggle" 与任何无法识别的词）
            // 都取 MODE_TOGGLE；无法识别的模式不报错、不新增拒绝分支（本仓不复刻参考分支没有的规则）。
            NetworkHandler.CHANNEL.sendTo(new S2CSetDebugOverlay(parseOverlayMode(args)), player);
        } else {
            player.addChatMessage(
                new ChatComponentTranslation("commands.yes_steve_model.debug.unknown", sub));
        }
    }

    /**
     * {@code /ysm reset <player|@a|@p>} - resets the selected players' Molang variable state, ported from the
     * reference branch's {@code YsmCommand.processReset} ({@code
     * tmp/YesSteveModel-Unofficial-perf-previewUI/src/main/java/com/fox/ysmu/command/YsmCommand.java:439-461}), with
     * {@code PacketResetMolang} replaced by this tree's same-named clientbound packet id 27.
     * <p>
     * Resetting the issuer's own state is the client command {@code /ysmclient reset} (pure local, no round trip);
     * this branch exists for the vanilla selectors. <b>Permission:</b> unchanged from the command's own default, level
     * 2 - only {@code /ysm debug} carries the level-4 re-check, which is the reference's split ({@code :109-113} makes
     * {@code reset} a default-level branch and {@code :369} gates only {@code debug}).
     * <p>
     * The clear itself runs on each target's own client ({@code PacketResetMolang.Handler} calls
     * {@code MolangReset.resetSelf()}), because the state being cleared - the frame scope, the controller runtime and
     * the roaming values - lives in that client's process.
     */
    private void processReset(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.reset.usage"));
            return;
        }
        EntityPlayerMP[] targets = resolveResetTargets(sender, args[1]);
        for (EntityPlayerMP target : targets) {
            NetworkHandler.CHANNEL.sendTo(new PacketResetMolang(), target);
        }
        sender.addChatMessage(
            new ChatComponentTranslation("commands.yes_steve_model.reset.done", targets.length));
    }

    /**
     * The target list of {@code /ysm reset <selector>}.
     * <p>
     * {@link PlayerSelector#matchPlayers} answers {@code null} for a word that is not a selector at all (it does not
     * fall back to a name lookup, {@code PlayerSelector.java:164-167}) and an empty array when the selector matches
     * nobody. The reference collapses both into {@code commands.generic.player.notFound} ({@code :448-454}); both are
     * kept distinct here only long enough to throw the same exception.
     */
    private static EntityPlayerMP[] resolveResetTargets(ICommandSender sender, String selector) {
        EntityPlayerMP[] targets;
        try {
            targets = PlayerSelector.matchPlayers(sender, selector);
        } catch (Exception e) {
            throw new CommandException("commands.generic.player.notFound");
        }
        if (targets == null || targets.length == 0) {
            throw new CommandException("commands.generic.player.notFound");
        }
        return targets;
    }

    /**
     * The variable names of {@code /ysm debug query <variable> [variable2 ...]}: one per argument from index 2, each
     * stripped of one surrounding pair of double quotes (reference {@code :384-391}).
     */
    static List<String> parseQueryNames(String[] args) {
        ArrayList<String> varNames = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String name = args[i];
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1);
            }
            varNames.add(name);
        }
        return varNames;
    }

    /**
     * The expression of {@code /ysm debug eval <expression>}: every argument from index 2 re-joined with single
     * spaces (so the caller may leave the spaces unquoted), trimmed, then stripped of one surrounding pair of double
     * quotes (reference {@code :404-412}).
     */
    static String parseEvalExpression(String[] args) {
        String expression = joinCommandArguments(args, 2).trim();
        if (expression.length() >= 2 && expression.startsWith("\"") && expression.endsWith("\"")) {
            expression = expression.substring(1, expression.length() - 1);
        }
        return expression;
    }

    /**
     * The overlay mode of {@code /ysm debug overlay [on|off|toggle]} - the reference's exact three-way decision
     * (reference {@code :419-425}). Anything that is neither {@code on} nor {@code off} - a missing argument,
     * {@code toggle}, or an unrecognised word - is {@link S2CSetDebugOverlay#MODE_TOGGLE}; the reference adds no
     * rejection branch for a bad mode, so this port does not invent one.
     */
    static byte parseOverlayMode(String[] args) {
        if (args.length >= 3 && "on".equalsIgnoreCase(args[2])) {
            return S2CSetDebugOverlay.MODE_ON;
        }
        if (args.length >= 3 && "off".equalsIgnoreCase(args[2])) {
            return S2CSetDebugOverlay.MODE_OFF;
        }
        return S2CSetDebugOverlay.MODE_TOGGLE;
    }

    /** Joins {@code args[from..]} with single spaces; the reference's inline loops for eval/playsound. */
    private static String joinCommandArguments(String[] args, int from) {
        StringBuilder joined = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(args[i]);
        }
        return joined.toString();
    }

    private void processReload(ICommandSender sender) {
        StopWatch watch = new StopWatch();
        watch.start();
        checkModelFiles(sender, CUSTOM);
        ServerModelManager.reloadPacks();

        // CUI-02: 广播给全服所有玩家，而不是只广播命令执行者所在维度的玩家。
        // N-02（取舍：保留）：这是 /ysm reload 之后唯一的服务端权威重同步入口 —— 每个玩家恰好调用一次，
        // 不存在"同一事件被触发两次"；17 与 legacy 两条通道如何选择（以及协议打开时旧实现会两条都发）
        // 由 model/ServerModelManager.sendRequestSyncModelMessage（:119-124）决定，而它在 model/**，
        // 不在本任务 inScope → 记为跨任务依赖（fix-network 的 N-02 服务端侧 gate）。
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.getConfigurationManager() != null) {
            for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
                ServerModelManager.sendRequestSyncModelMessage(player);
            }
        } else {
            ServerModelManager.sendRequestSyncModelMessage(sender.getEntityWorld().playerEntities);
        }

        watch.stop();
        // CU-02: 语言串只能是 %s（%.2f 会被 ChatComponentTranslation 当作非法格式串并抛异常），
        // 且 %.2f 必须接收 double —— 传 long 会抛 IllegalFormatConversionException。
        sender.addChatMessage(new ChatComponentTranslation(
            "message.yes_steve_model.model.reload.info",
            String.format(Locale.ROOT, "%.2f", (double) watch.getTime())));
    }

    private void checkModelFiles(ICommandSender sender, Path rootPath) {
        Collection<File> dirs = FileUtils.listFiles(rootPath.toFile(), DirectoryFileFilter.INSTANCE, null);
        for (File dir : dirs) {
            String dirName = dir.getName();
            boolean noMainModelFile = true;
            boolean noArmModelFile = true;
            boolean noTextureFile = true;
            Collection<File> files = FileUtils.listFiles(rootPath.resolve(dirName).toFile(), FileFileFilter.FILE, null);
            for (File file : files) {
                String fileName = file.getName();
                if (MAIN_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noMainModelFile = false;
                }
                if (ARM_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noArmModelFile = false;
                }
                if (fileName.endsWith(".png")) {
                    noTextureFile = false;
                    String name = file.getName();
                    name = name.substring(0, name.length() - 4);
                    if (!isValidResourceLocation(name)) {
                        String showName = String.format("%s/%s.png", dirName, name);
                        sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.texture_name", showName));
                    }
                }
            }
            if (noMainModelFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_main_file", dirName));
            }
            if (noArmModelFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_arm_file", dirName));
            }
            if (noTextureFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_texture_file", dirName));
            }
        }
    }

    private static boolean isNotBlankFile(File file) {
        try {
            String fileText = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            return StringUtils.isNoneBlank(fileText);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
