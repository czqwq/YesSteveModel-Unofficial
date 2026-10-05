package com.fox.ysmu.command;

import static com.fox.ysmu.compat.Utils.isValidResourceLocation;
import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.fox.ysmu.Config;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.PacketEvalMolang;
import com.fox.ysmu.network.message.PacketQueryMolangVar;
import com.fox.ysmu.network.message.PacketResetMolang;
import com.fox.ysmu.network.message.S2CPlaySound;
import com.fox.ysmu.network.message.S2CSetDebugOverlay;
import com.fox.ysmu.network.message.SetWelcomeConfig;
import com.fox.ysmu.network.message.ShowBufferInfo;
import com.fox.ysmu.network.message.SyncGamePath;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public class YsmCommand extends CommandBase {

    /** The permission level {@link #processDebug} re-checks per invocation; see its javadoc. */
    private static final int DEBUG_PERMISSION_LEVEL = 4;

    /**
     * The subcommands {@code /ysm help} documents, in the order the reference branch prints them
     * ({@code YsmCommand:173-182}). One list, so the summary lines, the subcommand lookup and the detail keys cannot
     * drift apart; the reference repeats the same names in three separate {@code equals} chains.
     */
    private static final String[] HELP_TOPICS = { "reload", "play", "playsound", "buffer", "setgamepath", "welcome",
        "sync", "reset", "debug", "ysmclient" };

    /**
     * The subcommands exempt from the level-2 re-check inside {@link #processCommand}: every one of them either runs
     * entirely on the issuing client or is handled before the check ({@code help}). Copied from the reference's
     * exclusion list ({@code YsmCommand:109-113}).
     */
    private static final java.util.Set<String> LEVEL_ZERO_SUBCOMMANDS = new java.util.HashSet<>(
        Arrays.asList("help", "setgamepath", "welcome", "buffer", "sync"));

    @Override
    public String getCommandName() {
        return "ysm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        // The reference's own usage line (YsmCommand:38), now that every subcommand it lists exists here.
        return "/ysm <reload|play|playsound|setgamepath|buffer|welcome|debug|reset|sync|help> [args] (type /ysm help for details)";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    /**
     * Who may reach {@link #processCommand} at all. Ported from the reference's override
     * ({@code YsmCommand:83-94}) - the mechanism its level-0 subcommands depend on - with one narrowed clause.
     * <p>
     * 1.7.10's {@code CommandHandler.executeCommand} asks this once, before dispatch
     * ({@code CommandHandler.java:53}) and compares the answer against nothing else; the level itself is only ever
     * consulted inside the inherited {@link CommandBase#canCommandSenderUseCommand(ICommandSender)}. So "help,
     * setgamepath, welcome, buffer and sync are usable by everyone" cannot be expressed by
     * {@link #getRequiredPermissionLevel()} - that number is one gate for the whole command, and it stays 2 - but
     * only by letting those senders in here and denying the rest per subcommand inside {@link #processCommand}
     * ({@link #LEVEL_ZERO_SUBCOMMANDS}).
     * <p>
     * <b>The narrowed clause.</b> The reference returns {@code true} for every sender after a check that cannot
     * fail - its {@code if (sender instanceof net.minecraft.command.ICommandSender)} tests the parameter's own type
     * - so a fake or non-player sender that denies level 2 is let through as well. That is the shape
     * {@code YsmCommandTest.theCommandKeepsItsLevelTwoBaseline} pins against (it asserts a non-player sender that
     * denies level 2 is refused), and letting a non-player sender through would grant level 0 to senders the
     * reference's own subcommands then reject anyway. Here the override grants the same reach to exactly the senders
     * the reference's intent covers - a player (each of setgamepath/welcome/buffer/sync requires one, see
     * {@code getCommandSenderAsPlayer}-style casts below) or a sender the vanilla gate already allows, which
     * includes the server console ({@code MinecraftServer.canCommandSenderUseCommand} returns true,
     * {@code MinecraftServer.java:965-968}). Everything else is refused by the inherited rule, unchanged.
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (super.canCommandSenderUseCommand(sender)) {
            return true;
        }
        return sender instanceof EntityPlayer;
    }

    /**
     * Tab completion, ported from the reference branch's {@code YsmCommand:42-72}.
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
            return getListOfStringsMatchingLastWord(
                args,
                "reload", "play", "playsound", "setgamepath", "buffer", "welcome", "debug", "reset", "sync", "help");
        }
        if (args.length == 2) {
            if ("welcome".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "on", "off");
            }
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
        if (args.length == 2 && "setgamepath".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "<path>");
        }
        if (args.length == 3) {
            if ("setgamepath".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "[version]");
            }
            if ("debug".equalsIgnoreCase(args[0]) && "overlay".equalsIgnoreCase(args[1])) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "toggle");
            }
        }
        return null;
    }

    /**
     * {@code /ysm <reload|play|playsound|setgamepath|buffer|welcome|debug|reset|sync|help>}: the whole command surface
     * of the reference branch's {@code YsmCommand} ({@code
     * tmp/YesSteveModel-Unofficial-perf-previewUI/src/main/java/com/fox/ysmu/command/YsmCommand.java}), which this
     * method now dispatches in the reference's own branch order.
     * <p>
     * <b>Permission, and why the gate is here as well as in the framework.</b> {@code help} is handled before the
     * check (reference {@code :101-105}), and the subcommands in {@link #LEVEL_ZERO_SUBCOMMANDS} - setgamepath,
     * welcome, buffer and sync - are exempt from it (reference {@code :109-113}), because each of them only forwards
     * a request to the issuing player's own client. Everything else - reload, play, playsound, debug, reset - must
     * pass the inherited level-2 gate, re-checked here because {@link #canCommandSenderUseCommand(ICommandSender)}
     * now lets players through to this point. That re-check is the reference's mechanism, not an addition: it is what
     * keeps a non-OP player out of {@code /ysm reload} while {@code /ysm help} still works. {@code /ysm debug} then
     * re-checks level 4 on top of it ({@link #processDebug}).
     */
    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        // help is available to everyone, before any permission check (reference :101-105).
        if ("help".equalsIgnoreCase(args[0])) {
            processHelp(sender, args);
            return;
        }
        // setgamepath, welcome, buffer and sync are player-facing and client-side only; the rest needs the command's
        // own level 2 (reference :106-113).
        if (!LEVEL_ZERO_SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))
            && !super.canCommandSenderUseCommand(sender)) {
            throw new CommandException("commands.generic.permission");
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            processReload(sender);
        } else if ("play".equalsIgnoreCase(args[0])) {
            processPlay(sender, args);
        } else if ("playsound".equalsIgnoreCase(args[0])) {
            processPlaySound(sender, args.length >= 2 ? joinCommandArguments(args, 1) : "");
        } else if ("setgamepath".equalsIgnoreCase(args[0])) {
            processSetGamePath(sender, args);
        } else if ("buffer".equalsIgnoreCase(args[0])) {
            processBuffer(sender);
        } else if ("sync".equalsIgnoreCase(args[0])) {
            processSync(sender);
        } else if ("welcome".equalsIgnoreCase(args[0])) {
            processWelcome(sender, args);
        } else if ("debug".equalsIgnoreCase(args[0])) {
            processDebug(sender, args);
        } else if ("reset".equalsIgnoreCase(args[0])) {
            processReset(sender, args);
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    /**
     * {@code /ysm help [subcommand]} - ported from the reference's {@code processHelp} ({@code :151-189}).
     * <p>
     * Without an argument it prints the ten {@code commands.yes_steve_model.help.line.*} summaries in the reference's
     * order ({@code :172-182}) plus the clickable "turn the welcome guide off" line ({@code :183-188}). With an
     * argument it prints the topic's {@code .usage} and {@code .desc} lines, or
     * {@code commands.yes_steve_model.help.unknown} for a word that is not a subcommand ({@code :154-170}).
     * <p>
     * Server commands cannot know the client's language, so every line is a translation key - the reference does the
     * same through {@code ChatComponentTranslation}. All ten topics exist in this tree; {@code ysmclient} is the
     * client-only {@code CommandYsmClient}.
     */
    private void processHelp(ICommandSender sender, String[] args) {
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (!isKnownHelpTopic(sub)) {
                sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.unknown", sub));
                return;
            }
            sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.detail.title", sub));
            sender.addChatMessage(
                new ChatComponentTranslation("commands.yes_steve_model.help.detail." + sub + ".usage"));
            sender.addChatMessage(
                new ChatComponentTranslation("commands.yes_steve_model.help.detail." + sub + ".desc"));
            return;
        }
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.title"));
        for (String topic : HELP_TOPICS) {
            sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line." + topic));
        }
        // The welcome-off hint: the whole line runs /ysm welcome off when clicked (reference :183-188).
        ChatComponentTranslation off = new ChatComponentTranslation("commands.yes_steve_model.help.welcome_off");
        off.getChatStyle()
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ysm welcome off"));
        sender.addChatMessage(off);
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
     * {@code /ysm play <animation>} - play a named animation of the issuer's model, ported from the reference's
     * {@code processPlay} ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../command/YsmCommand.java:191-232}).
     * <p>
     * The animation state lives in the player's {@link ExtendedModelInfo} EEP, which the server owns: the tick handler
     * broadcasts a dirty EEP to every tracking client ({@code event/CommonEventHandler.java:257-264}) and the client's
     * {@code AnimationManager} consumes the replay request ({@code client/animation/AnimationManager.java:208-209}), so
     * setting it here is the whole feature.
     * <p>
     * The existence check runs only on a side that can answer it: {@code GeckoLibCache}'s animation table is filled by
     * the client while it parses models, so on a dedicated server it is empty. The reference's own comment
     * ({@code :215-219}) says the same. The check therefore rejects a name only when the table has the model at all
     * (integrated server / single player) and otherwise leaves the verdict to the client, which skips a missing
     * animation safely instead of failing.
     */
    private void processPlay(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        String animName = parsePlayAnimationName(args);
        if (StringUtils.isBlank(animName)) {
            sender.addChatMessage(
                new ChatComponentTranslation("message.yes_steve_model.model.animation_roulette.play", "?"));
            return;
        }
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep == null) {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.play.no_data"));
            return;
        }
        ResourceLocation modelId = eep.getModelId();
        if (modelId != null) {
            ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
            AnimationFile animFile = GeckoLibCache.getInstance()
                .getAnimations()
                .get(mainId);
            if (animFile != null && !animFile.animations.containsKey(animName)) {
                player.addChatMessage(
                    new ChatComponentTranslation("commands.yes_steve_model.play.not_found", animName));
                return;
            }
        }
        eep.playAnimation(animName);
        player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.play.done", animName));
    }

    /**
     * {@code /ysm setgamepath [path] [version]} - push a high-version game directory to the issuer's client, ported
     * from the reference's {@code processSetGamePath} ({@code :265-306}).
     * <p>
     * The path is client-local config, so the server only forwards it; the consumer is the client's
     * {@code SyncGamePath.Handler}, whose last step is {@code LocalAssetProvider.reset()}
     * ({@code compat/LocalAssetProvider.java:243}) - without that call the provider would keep the asset index and the
     * version jar it opened for the previous path and the command would look like a no-op.
     */
    private void processSetGamePath(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        if (args.length < 2) {
            // Show the current configuration (reference :271-286).
            String currentPath = Config.HIGH_VERSION_GAME_PATH;
            String currentVer = Config.HIGH_VERSION_ASSET_VERSION;
            if (currentPath == null || currentPath.isEmpty()) {
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.none"));
            } else {
                player.addChatMessage(
                    new ChatComponentTranslation("commands.yes_steve_model.setgamepath.current", currentPath));
                player.addChatMessage(
                    new ChatComponentTranslation("commands.yes_steve_model.setgamepath.version", currentVer));
            }
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.usage"));
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.example"));
            return;
        }
        String newPath = parseGamePath(args);
        String newVersion = parseGamePathVersion(args);
        NetworkHandler.CHANNEL.sendTo(new SyncGamePath(newPath, newVersion), player);
        if (newPath.isEmpty()) {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.cleared"));
        } else {
            player.addChatMessage(
                new ChatComponentTranslation("commands.yes_steve_model.setgamepath.set", newPath));
            player.addChatMessage(
                new ChatComponentTranslation("commands.yes_steve_model.setgamepath.version", newVersion));
            player.addChatMessage(
                new ChatComponentTranslation("commands.yes_steve_model.setgamepath.local_notice"));
        }
    }

    /**
     * {@code /ysm buffer} - ask the issuer's client to print its own direct-buffer, mapped-buffer and heap figures,
     * ported from the reference's {@code processBuffer} ({@code :308-317}).
     * <p>
     * The figures come from the client JVM's platform MBeans, which a dedicated server cannot read meaningfully for
     * the client - hence the round trip rather than a server-side print. The reference calls the shot a "client
     * screen"; its own handler prints three chat lines and no GUI exists in either tree, so the chat output is what
     * is ported.
     */
    private void processBuffer(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        NetworkHandler.CHANNEL.sendTo(new ShowBufferInfo(), player);
        player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.buffer.querying"));
    }

    /**
     * {@code /ysm welcome [on|off]} - toggle the issuer's welcome-message flag on their own client, ported from the
     * reference's {@code processWelcome} ({@code :342-356}).
     * <p>
     * The flag is per-client config, so the command forwards it. <b>Nothing in this tree reads the flag yet</b>: the
     * reference's reader is its join-world welcome block
     * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/ClientEventHandler.java:193}), which this tree
     * has no equivalent of, and {@code client/} is outside this task's files. The command and its packet are ported;
     * the visible effect arrives with that reader. Recorded in {@code .agent/phase39-ysm-subcommands.md}.
     */
    private void processWelcome(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        boolean newValue = parseWelcomeValue(args);
        NetworkHandler.CHANNEL.sendTo(new SetWelcomeConfig(newValue), player);
        player.addChatMessage(new ChatComponentTranslation(welcomeMessageKey(newValue)));
    }

    /**
     * {@code /ysm sync} - the issuer asks the server for a fresh model-sync round, ported from the reference's
     * {@code processSync} ({@code :319-340}).
     * <p>
     * The rate limit is the server's ({@code OpenYsmModelSyncServer.requestResync}): a player can spam the command and
     * every round is server uplink. The reference takes no target argument here - it resyncs the issuer - even though
     * its own help line advertises {@code /ysm sync [player]}; the port follows the code, and the help text in
     * {@code commands.yes_steve_model.help.detail.sync.*} was corrected to match it (see the note).
     */
    private void processSync(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        int result = OpenYsmModelSyncServer.requestResync(player);
        String key = resyncMessageKey(result);
        if (result == OpenYsmModelSyncServer.RESYNC_COOLDOWN) {
            player.addChatMessage(
                new ChatComponentTranslation(key, OpenYsmModelSyncServer.resyncRemainingSeconds(player.getUniqueID())));
        } else {
            player.addChatMessage(new ChatComponentTranslation(key));
        }
    }

    /**
     * Whether {@code /ysm help <sub>} names a topic this command family knows (reference {@code :156-159}). The
     * reference builds the same set as an {@code equals} chain; here it is {@link #HELP_TOPICS}, so a topic cannot
     * have a summary line without also having detail keys.
     */
    static boolean isKnownHelpTopic(String sub) {
        for (String topic : HELP_TOPICS) {
            if (topic.equals(sub)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The animation name of {@code /ysm play}: every argument from index 1 re-joined with single spaces and stripped
     * of every {@code "} and {@code '} (reference {@code :195-204}, including its {@code replaceAll("[\"']", "")} -
     * so an internal quote disappears too, not just one surrounding pair).
     */
    static String parsePlayAnimationName(String[] args) {
        StringBuilder name = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(args[i]);
        }
        return name.toString()
            .replaceAll("[\"']", "");
    }

    /**
     * The path of {@code /ysm setgamepath <path> [version]}: the second argument, with the two spellings of an empty
     * path ({@code ""} and {@code ''}) normalised to a real empty string so the client clears the setting
     * (reference {@code :288-294}).
     */
    static String parseGamePath(String[] args) {
        if (args.length < 2) {
            return "";
        }
        String newPath = args[1];
        if ("\"\"".equals(newPath) || "''".equals(newPath)) {
            return "";
        }
        return newPath;
    }

    /**
     * The asset version of {@code /ysm setgamepath <path> [version]}: the third argument, or the currently configured
     * version when it is absent (reference {@code :289}).
     */
    static String parseGamePathVersion(String[] args) {
        return args.length >= 3 ? args[2] : Config.HIGH_VERSION_ASSET_VERSION;
    }

    /**
     * The flag of {@code /ysm welcome [on|off]}: on unless the second argument is one of the three spellings of "off"
     * (reference {@code :347-352}; a missing argument and any other word - {@code on} included - all mean on).
     */
    static boolean parseWelcomeValue(String[] args) {
        if (args.length >= 2) {
            String value = args[1];
            if ("off".equalsIgnoreCase(value) || "0".equals(value) || "false".equalsIgnoreCase(value)) {
                return false;
            }
        }
        return true;
    }

    /** The feedback key of {@code /ysm welcome}: the reference's {@code key} ternary ({@code :354-355}). */
    static String welcomeMessageKey(boolean show) {
        return show ? "commands.yes_steve_model.welcome.on" : "commands.yes_steve_model.welcome.off";
    }

    /**
     * The feedback key of {@code /ysm sync} for a {@code OpenYsmModelSyncServer} answer code - the reference's
     * four-way if-chain ({@code :329-339}), read without the send so the decision itself is testable.
     */
    static String resyncMessageKey(int result) {
        if (result == OpenYsmModelSyncServer.RESYNC_OK) {
            return "commands.yes_steve_model.sync.resync.started";
        }
        if (result == OpenYsmModelSyncServer.RESYNC_COOLDOWN) {
            return "commands.yes_steve_model.sync.resync.cooldown";
        }
        if (result == OpenYsmModelSyncServer.RESYNC_DISABLED) {
            return "commands.yes_steve_model.sync.resync.disabled";
        }
        return "commands.yes_steve_model.sync.resync.failed";
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
