package com.fox.ysmu.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.network.message.PacketEvalMolang;
import com.fox.ysmu.network.message.PacketQueryMolangVar;
import com.fox.ysmu.network.message.PacketResetMolang;
import com.fox.ysmu.network.message.S2CSetDebugOverlay;
import com.fox.ysmu.network.message.SetWelcomeConfig;
import com.fox.ysmu.network.message.ShowBufferInfo;
import com.fox.ysmu.network.message.SyncGamePath;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;

/**
 * The server-side {@code /ysm} surface: argument parsing, the refusal paths, tab completion, the permission model and
 * the packet directions - {@code /ysm help}, {@code play}, {@code setgamepath}, {@code buffer}, {@code welcome},
 * {@code sync} and the already-ported {@code debug}/{@code reset}/{@code playsound}.
 * <p>
 * Every message the command emits is a {@link ChatComponentTranslation} whose translation happens when the chat box
 * renders it, so the dispatch is observable in a unit JVM through the translation <em>key</em> and its format args -
 * no client language table is touched. The pure decisions (which names a query carries, which expression an eval
 * carries, which overlay mode a third argument selects, which animation name a play carries, which value a welcome
 * carries, which feedback key a resync code maps to) are driven directly, because their effects leave through
 * {@code NetworkHandler.CHANNEL.sendTo} and a unit JVM has no channel.
 * <p>
 * <b>What a unit JVM cannot drive</b>, recorded rather than approximated:
 * <ul>
 * <li>the happy path that actually sends a packet: it needs an {@code EntityPlayerMP}, and
 * {@code EntityPlayerMP(MinecraftServer, WorldServer, GameProfile, ItemInWorldManager)} dereferences a live
 * {@code WorldServer.provider} and {@code MinecraftServer.getConfigurationManager()} in its constructor, so a player
 * cannot be fabricated here (and neither can a plain {@code EntityPlayer}: its constructor dereferences
 * {@code World.isRemote} and {@code World.getSpawnPoint()}, {@code EntityPlayer.java:163-176}). The tests below
 * therefore pin every step up to the send and the {@code instanceof EntityPlayerMP} refusals (which a plain sender
 * does reach).</li>
 * <li>the level-4 re-check inside {@code processDebug}: it is a call on the {@code EntityPlayerMP}, i.e. on the same
 * unfabricatable object. Only the command's level-2 baseline and the in-body per-subcommand gate are asserted here.</li>
 * <li>the player clause of {@code canCommandSenderUseCommand}: it tests {@code instanceof EntityPlayer}, and no
 * {@code EntityPlayer} can be constructed without a {@code World}. Its other clause - the inherited level-2 rule for
 * non-player senders - is asserted.</li>
 * </ul>
 */
class YsmCommandTest {

    /** Records what the command sends back, without a client or a world. */
    private static class RecordingSender implements ICommandSender {

        private final List<IChatComponent> messages = new ArrayList<>();

        @Override
        public String getCommandSenderName() {
            return "ysmu-test";
        }

        @Override
        public IChatComponent func_145748_c_() {
            return null;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            this.messages.add(message);
        }

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return true;
        }

        @Override
        public World getEntityWorld() {
            return null;
        }

        /** Required by 1.7.10's {@code ICommandSender}; no subcommand under test reads it. */
        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }

        private int count() {
            return this.messages.size();
        }

        /** The single message received as its translation, or null when there was not exactly one translation. */
        private ChatComponentTranslation singleTranslation() {
            if (this.messages.size() != 1) {
                return null;
            }
            IChatComponent message = this.messages.get(0);
            if (message instanceof ChatComponentTranslation) {
                return (ChatComponentTranslation) message;
            }
            return null;
        }

        private String singleKey() {
            ChatComponentTranslation translation = singleTranslation();
            return translation == null ? null : translation.getKey();
        }

        /** The translation key of the n-th message, for the multi-line branches (help's summary). */
        private String keyAt(int index) {
            IChatComponent message = this.messages.get(index);
            if (message instanceof ChatComponentTranslation) {
                return ((ChatComponentTranslation) message).getKey();
            }
            return null;
        }

        /**
         * The message carrying {@code key}, or {@code null} when none does.
         * <p>
         * For assertions about a line's <em>contents</em> rather than its position: the help output grows whenever a
         * subcommand is added ({@code /ysm auth} did exactly that), and an index-based lookup would keep passing while
         * asserting about a different line.
         */
        private ChatComponentTranslation translationWithKey(String key) {
            for (IChatComponent message : this.messages) {
                if (message instanceof ChatComponentTranslation) {
                    ChatComponentTranslation translation = (ChatComponentTranslation) message;
                    if (key.equals(translation.getKey())) {
                        return translation;
                    }
                }
            }
            return null;
        }
    }

    /** A non-player sender that denies every permission level; pins which subcommands the in-body gate exempts. */
    private static final class DenyingSender extends RecordingSender {

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // dispatch and refusals
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void noArgumentsIsRefusedWithTheUsage() {
        RecordingSender sender = new RecordingSender();

        assertThrows(WrongUsageException.class, () -> new YsmCommand().processCommand(sender, new String[0]));
    }

    @Test
    void anUnknownSubcommandIsRefusedAndDoesNotReachTheDebugBranch() {
        RecordingSender sender = new RecordingSender();

        assertThrows(
            WrongUsageException.class,
            () -> new YsmCommand().processCommand(sender, new String[] { "bogus" }));
        assertEquals(0, sender.count(), "an unknown top-level subcommand must not answer with a debug message");
    }

    @Test
    void aSubcommandThatOnlyExtendsAKnownOneIsRefused() {
        assertThrows(
            WrongUsageException.class,
            () -> new YsmCommand().processCommand(new RecordingSender(), new String[] { "reloadx" }));
    }

    @Test
    void theTopLevelSubcommandIsMatchedCaseInsensitively() {
        // "DEBUG" must reach the debug branch, whose first refusal is the player requirement - an unknown subcommand
        // would instead throw WrongUsageException. That difference is what makes the dispatch observable here.
        RecordingSender sender = new RecordingSender();

        CommandException exception = assertThrows(
            CommandException.class,
            () -> new YsmCommand().processCommand(sender, new String[] { "DEBUG" }));

        assertFalse(
            exception instanceof WrongUsageException,
            "an upper-case subcommand must not fall through to the unknown-subcommand usage");
        assertEquals("commands.generic.player.notFound", exception.getMessage());
    }

    @Test
    void debugRequiresAPlayer() {
        RecordingSender sender = new RecordingSender();

        CommandException exception = assertThrows(
            CommandException.class,
            () -> new YsmCommand().processCommand(sender, new String[] { "debug", "query", "v.x" }));

        assertEquals("commands.generic.player.notFound", exception.getMessage());
        assertEquals(0, sender.count(), "nothing is answered when there is no player to forward to");
    }

    @Test
    void resetRequiresAPlayerEvenWithoutASelector() {
        // The reference returns its usage line for a missing selector only after the player cast, so a non-player
        // sender is refused before any message can be produced.
        RecordingSender sender = new RecordingSender();

        CommandException exception = assertThrows(
            CommandException.class,
            () -> new YsmCommand().processCommand(sender, new String[] { "reset" }));

        assertEquals("commands.generic.player.notFound", exception.getMessage());
        assertEquals(0, sender.count());
    }

    @Test
    void resetWithoutASelectorIsNotTheUnknownSubcommandPath() {
        // "reset" is dispatched (it reaches its own player check), unlike a misspelling.
        CommandException exception = assertThrows(
            CommandException.class,
            () -> new YsmCommand().processCommand(new RecordingSender(), new String[] { "reset", "Notch" }));

        assertEquals("commands.generic.player.notFound", exception.getMessage());
    }

    @Test
    void playsoundStillReachesItsOwnPlayerRequirement() {
        // Regression guard for the ported playsound branch: it must keep dispatching rather than becoming unknown.
        //
        // This branch's refusal is *vanilla's own helper*, not a hand-built CommandException like processDebug's:
        // YsmCommand.processPlaySound calls CommandBase.getCommandSenderAsPlayer, whose 1.7.10 body is
        //     throw new PlayerNotFoundException("You must specify which player you wish to perform this action on.",
        //                                         new Object[0])
        // (build/rfg/minecraft-src/java/net/minecraft/command/CommandBase.java:184). PlayerNotFoundException extends
        // CommandException and hands that string straight to RuntimeException
        // (PlayerNotFoundException.java:3-15 with CommandException.java:8-12), so getMessage() is the literal English
        // sentence; CommandHandler then wraps *that* string in a ChatComponentTranslation
        // (CommandHandler.java:101-104), which is why the chat prints it verbatim. The other refusal tests in this
        // class build `new CommandException("commands.generic.player.notFound")` themselves, which is why their
        // getMessage() really is the key - this one must assert the type and the helper's own message.
        // Checked as CommandException (a supertype of both, so the `instanceof` below is legal - the same shape as
        // theTopLevelSubcommandIsMatchedCaseInsensitively), with the concrete type asserted by assertThrows.
        CommandException exception = assertThrows(
            PlayerNotFoundException.class,
            () -> new YsmCommand().processCommand(new RecordingSender(), new String[] { "playsound" }));

        assertFalse(
            exception instanceof WrongUsageException,
            "playsound must stay dispatched, not fall through to the unknown-subcommand usage");
        assertEquals(
            "You must specify which player you wish to perform this action on.",
            exception.getMessage(),
            "the vanilla helper's own message, not a translation key");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the extractable decisions
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aQueryCarriesOneNamePerArgumentFromTheThirdOnwards() {
        List<String> names = YsmCommand.parseQueryNames(new String[] { "debug", "query", "v.a", "v.b", "query.x" });

        assertEquals(3, names.size());
        assertEquals("v.a", names.get(0));
        assertEquals("v.b", names.get(1));
        assertEquals("query.x", names.get(2));
    }

    @Test
    void aQuotedQueryNameIsUnquoted() {
        List<String> names = YsmCommand.parseQueryNames(new String[] { "debug", "query", "\"v.roaming.player_size\"" });

        assertEquals(1, names.size());
        assertEquals("v.roaming.player_size", names.get(0), "one surrounding pair of double quotes is stripped");
    }

    @Test
    void aQueryNameIsNeverNullOrEmpty() {
        List<String> names = YsmCommand.parseQueryNames(new String[] { "debug", "query", "" });

        assertEquals(1, names.size());
        assertEquals("", names.get(0), "an empty name is passed through for the client to answer as unknown");
    }

    @Test
    void anEvalExpressionIsTheRemainderRejoinedWithSingleSpaces() {
        assertEquals(
            "math.clamp(5,0,2)+1",
            YsmCommand.parseEvalExpression(new String[] { "debug", "eval", "math.clamp(5,0,2)+1" }));
        assertEquals(
            "math.floor( 3.7 )",
            YsmCommand.parseEvalExpression(new String[] { "debug", "eval", "math.floor(", "3.7", ")" }),
            "an expression the shell split at spaces is re-joined");
    }

    @Test
    void aQuotedEvalExpressionIsUnquotedAndTrimmed() {
        assertEquals(
            "math.floor(3.7)",
            YsmCommand.parseEvalExpression(new String[] { "debug", "eval", "\"math.floor(3.7)\"" }));
    }

    @Test
    void anOverlayModeIsOnOrOffAndEverythingElseToggles() {
        assertEquals(S2CSetDebugOverlay.MODE_ON, YsmCommand.parseOverlayMode(new String[] { "debug", "overlay", "on" }));
        assertEquals(S2CSetDebugOverlay.MODE_ON, YsmCommand.parseOverlayMode(new String[] { "debug", "overlay", "ON" }));
        assertEquals(
            S2CSetDebugOverlay.MODE_OFF,
            YsmCommand.parseOverlayMode(new String[] { "debug", "overlay", "off" }));
        assertEquals(
            S2CSetDebugOverlay.MODE_TOGGLE,
            YsmCommand.parseOverlayMode(new String[] { "debug", "overlay", "toggle" }));
    }

    @Test
    void aMissingOrUnrecognisedOverlayModeTogglesRatherThanFailing() {
        // The reference's decision has no rejection branch: it defaults to MODE_TOGGLE and only an explicit, exact
        // on/off changes it. Ported as-is, so this pins it rather than inventing a bad-mode error.
        assertEquals(S2CSetDebugOverlay.MODE_TOGGLE, YsmCommand.parseOverlayMode(new String[] { "debug", "overlay" }));
        assertEquals(
            S2CSetDebugOverlay.MODE_TOGGLE,
            YsmCommand.parseOverlayMode(new String[] { "debug", "overlay", "半开" }));
        // The reference reads args[2] and nothing else (reference
        // tmp/YesSteveModel-Unofficial-perf-previewUI/.../command/YsmCommand.java:419-424: `off` first, then `on`,
        // everything else MODE_TOGGLE), so a trailing word cannot change the verdict - "on extra" is MODE_ON.
        assertEquals(
            S2CSetDebugOverlay.MODE_ON,
            YsmCommand.parseOverlayMode(new String[] { "debug", "overlay", "on", "extra" }),
            "only the first mode word is read, so a trailing word is ignored and the leading 'on' still wins");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // tab completion
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void tabCompletionOffersEveryPortedSubcommandAtTheFirstArgument() {
        List<?> all = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "" });
        // The reference's own list and order (reference YsmCommand:44-45), plus /ysm auth and /ysm model - the two
        // subcommands that do not come from the reference tree but from upstream's RootCommand
        // (RootCommand.java:16-24) and are therefore offered here too. Every name is asserted individually, not just
        // counted, so a renamed or dropped subcommand cannot pass as "same size".
        assertEquals(12, all.size());
        String[] expected = { "reload", "play", "playsound", "setgamepath", "buffer", "welcome", "debug", "reset",
            "sync", "auth", "model", "help" };
        for (String name : expected) {
            assertTrue(all.contains(name), name + " must be offered at argument 1");
        }
        assertTrue(all.contains("reload"));
        assertTrue(all.contains("playsound"));
        assertTrue(all.contains("debug"));
        assertTrue(all.contains("reset"));
        assertTrue(all.contains("model"));

        List<?> filtered = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "re" });
        assertEquals(2, filtered.size());
        assertTrue(filtered.contains("reload"));
        assertTrue(filtered.contains("reset"));
    }

    @Test
    void tabCompletionOffersTheDebugSubcommandsAtTheSecondArgument() {
        List<?> modes = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "debug", "" });
        assertEquals(3, modes.size());
        assertTrue(modes.contains("query"));
        assertTrue(modes.contains("eval"));
        assertTrue(modes.contains("overlay"));

        List<?> filtered = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "debug", "o" });
        assertEquals(1, filtered.size());
        assertEquals("overlay", filtered.get(0));

        // Matched case-insensitively, like the dispatch.
        assertEquals(
            1,
            new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "DEBUG", "q" })
                .size());
    }

    @Test
    void tabCompletionOffersTheOverlayModesAtTheThirdArgument() {
        List<?> modes = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "debug", "overlay", "" });
        assertEquals(3, modes.size());
        assertTrue(modes.contains("on"));
        assertTrue(modes.contains("off"));
        assertTrue(modes.contains("toggle"));

        List<?> filtered = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "debug", "overlay", "of" });
        assertEquals(1, filtered.size());
        assertEquals("off", filtered.get(0));
    }

    @Test
    void tabCompletionForResetDegradesWithoutAServerInsteadOfFailing() {
        // MinecraftServer.getServer() is null in a unit JVM. The reference dereferences it; this port returns null so
        // completion simply suggests nothing. The selector word itself is not offered either way.
        assertNull(new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "reset", "N" }));

        // ... and the unrelated third-argument slot stays empty.
        assertNull(
            new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "reload", "x", "y" }));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the permission baseline and the packet direction
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theCommandKeepsItsLevelTwoBaseline() {
        YsmCommand command = new YsmCommand();

        assertEquals(2, command.getRequiredPermissionLevel(), "the framework's dispatch gate stays at level 2");

        // The gate itself is the inherited CommandBase implementation, which compares that level by asking the sender.
        // A sender that denies level 2 must be refused, which is what keeps /ysm reset (and reload and playsound)
        // OP-gated; the per-subcommand level-4 re-check on /ysm debug sits on top of this baseline.
        assertFalse(command.canCommandSenderUseCommand(new ICommandSender() {

            @Override
            public String getCommandSenderName() {
                return "ysmu-denied";
            }

            @Override
            public IChatComponent func_145748_c_() {
                return null;
            }

            @Override
            public void addChatMessage(IChatComponent message) {}

            @Override
            public boolean canCommandSenderUseCommand(int permissionLevel, String name) {
                return false;
            }

            @Override
            public World getEntityWorld() {
                return null;
            }

            @Override
            public ChunkCoordinates getPlayerCoordinates() {
                return new ChunkCoordinates(0, 0, 0);
            }
        }));
        assertTrue(command.canCommandSenderUseCommand(new RecordingSender()));
    }

    @Test
    void everyPortedCommandPacketIsClientboundSoTheServerIsTheSender() throws Exception {
        // Direction, read off each handler rather than assumed: every one of these guards on
        // `if (ctx.side != Side.CLIENT) return null;` (see the sources cited in the ExecPlan), so only a server can
        // have sent one - which is why YsmCommand is its sender. The check reads the compiled handler's constant pool:
        // a class that never mentions the Side enum, or never mentions its CLIENT constant, cannot carry that guard.
        // Pinned here because reversing a registration's side would silently make these subcommands no-ops.
        for (Class<?> packet : new Class<?>[] { PacketQueryMolangVar.class, PacketEvalMolang.class,
            PacketResetMolang.class, S2CSetDebugOverlay.class, SyncGamePath.class, SetWelcomeConfig.class,
            ShowBufferInfo.class }) {
            Class<?> handler = null;
            for (Class<?> nested : packet.getDeclaredClasses()) {
                if (nested.getSimpleName()
                    .equals("Handler")) {
                    handler = nested;
                    break;
                }
            }
            assertTrue(handler != null, packet + " must still declare its Handler");

            byte[] bytecode = readClassBytes(handler);
            String pool = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(
                pool.contains("cpw/mods/fml/relauncher/Side"),
                handler + " must still name the Side enum, i.e. still guard on the receiving side");
            assertTrue(
                pool.contains("CLIENT"),
                handler + " must still reference Side.CLIENT, i.e. still be answered on the client");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // /ysm help
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void helpWithoutAnArgumentListsEverySubcommandAndTheWelcomeOffHint() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "help" });

        // The title, the summaries in HELP_TOPICS' order (the reference's own order, reference YsmCommand:172-182,
        // plus /ysm auth and /ysm model after reset), then the clickable welcome-off line (:183-188).
        assertEquals(14, sender.count());
        assertEquals("commands.yes_steve_model.help.title", sender.keyAt(0));
        String[] topics = { "reload", "play", "playsound", "buffer", "setgamepath", "welcome", "sync", "reset", "auth",
            "model", "debug", "ysmclient" };
        for (int i = 0; i < topics.length; i++) {
            assertEquals("commands.yes_steve_model.help.line." + topics[i], sender.keyAt(1 + i));
        }
        assertEquals("commands.yes_steve_model.help.welcome_off", sender.keyAt(13));
    }

    @Test
    void helpWithAKnownTopicPrintsItsUsageAndDescription() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "help", "reload" });

        assertEquals(3, sender.count());
        assertEquals("commands.yes_steve_model.help.detail.title", sender.keyAt(0));
        assertEquals("commands.yes_steve_model.help.detail.reload.usage", sender.keyAt(1));
        assertEquals("commands.yes_steve_model.help.detail.reload.desc", sender.keyAt(2));
        ChatComponentTranslation title = (ChatComponentTranslation) sender.messages.get(0);
        assertEquals("reload", title.getFormatArgs()[0], "the title names the requested topic");
    }

    @Test
    void helpWithAnUnknownTopicNamesItInsteadOfGuessing() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "help", "bogus" });

        assertEquals(1, sender.count());
        ChatComponentTranslation message = (ChatComponentTranslation) sender.messages.get(0);
        assertEquals("commands.yes_steve_model.help.unknown", message.getKey());
        assertEquals("bogus", message.getFormatArgs()[0]);
    }

    @Test
    void everyAdvertisedTopicHasDetailKeysAndIsMatchedCaseInsensitively() {
        String[] topics = { "reload", "play", "playsound", "buffer", "setgamepath", "welcome", "sync", "reset", "auth",
            "model", "debug", "ysmclient" };
        for (String topic : topics) {
            assertTrue(YsmCommand.isKnownHelpTopic(topic), topic + " must be a known topic");
            RecordingSender sender = new RecordingSender();
            new YsmCommand().processCommand(sender, new String[] { "help", topic.toUpperCase(java.util.Locale.ROOT) });
            assertEquals(3, sender.count(), topic + " in upper case must still resolve to its detail lines");
            assertEquals("commands.yes_steve_model.help.detail." + topic + ".usage", sender.keyAt(1));
        }
        // "help" is not one of its own topics (the reference's list, :156-159), and neither is a non-subcommand.
        assertFalse(YsmCommand.isKnownHelpTopic("help"));
        assertFalse(YsmCommand.isKnownHelpTopic("bogus"));
        assertFalse(YsmCommand.isKnownHelpTopic(""));
    }

    @Test
    void theWelcomeOffHintCarriesTheCommandItAdvertises() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "help" });

        // Looked up by key, not by index: the hint is the last line of a 12-topic list, so a hard index would keep
        // "passing" against a different line after the next subcommand lands. The assertion is unchanged in strength -
        // the line must still exist, and it must still be clickable with the command it advertises.
        ChatComponentTranslation off = sender.translationWithKey("commands.yes_steve_model.help.welcome_off");
        assertNotNull(off, "the welcome-off hint must still be printed");
        ChatStyle style = off.getChatStyle();
        assertNotNull(style.getChatClickEvent(), "the hint line must be clickable, as the reference makes it");
        assertEquals(ClickEvent.Action.RUN_COMMAND, style.getChatClickEvent()
            .getAction());
        assertEquals("/ysm welcome off", style.getChatClickEvent()
            .getValue());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the permission model of the new subcommands
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theLevelZeroSubcommandsReachTheirOwnRefusalInsteadOfThePermissionGate() {
        // help is handled before the gate (reference :101-105) and setgamepath/welcome/buffer/sync are exempt from it
        // (:109-113). Each of the four then demands a player, which is what proves the gate did not swallow the
        // branch: a denied permission would have been "commands.generic.permission" instead.
        RecordingSender helpSender = new DenyingSender();
        new YsmCommand().processCommand(helpSender, new String[] { "help" });
        // One line per HELP_TOPICS entry - 12 since /ysm model joined - plus the title and the welcome-off hint.
        assertEquals(14, helpSender.count(), "help must run for a sender that denies every permission level");

        for (String sub : new String[] { "setgamepath", "welcome", "buffer", "sync" }) {
            CommandException exception = assertThrows(
                CommandException.class,
                () -> new YsmCommand().processCommand(new DenyingSender(), new String[] { sub }),
                sub + " must be exempt from the level-2 gate");
            assertEquals(
                "commands.generic.player.notFound",
                exception.getMessage(),
                sub + " must reach its own player requirement");
        }
    }

    @Test
    void theLevelTwoSubcommandsStillHitTheGateForASenderThatDeniesIt() {
        // reload/play/playsound/debug/reset/auth/model keep the inherited level-2 rule, re-checked inside processCommand
        // because canCommandSenderUseCommand now lets players through to it (reference :109-113; /ysm auth's own gate
        // is upstream's requires(hasPermission(source, 2)), AuthCommand.java:29, and /ysm model's is the same at
        // ModelCommand.java:35). The refusal also proves that none of them ran: with a denied sender processReload never
        // touches the model directory.
        for (String sub : new String[] { "reload", "play", "playsound", "debug", "reset", "auth", "model" }) {
            CommandException exception = assertThrows(
                CommandException.class,
                () -> new YsmCommand().processCommand(new DenyingSender(), new String[] { sub }));
            assertEquals(
                "commands.generic.permission",
                exception.getMessage(),
                sub + " must stay behind the level-2 gate");
        }
    }

    @Test
    void thePortedPlayerSubcommandsRefuseANonPlayer() {
        for (String sub : new String[] { "play", "setgamepath", "buffer", "welcome", "sync" }) {
            CommandException exception = assertThrows(
                CommandException.class,
                () -> new YsmCommand().processCommand(new RecordingSender(), new String[] { sub, "x" }));
            assertEquals(
                "commands.generic.player.notFound",
                exception.getMessage(),
                sub + " must refuse a sender that is not a player");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the extractable decisions of the new subcommands
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void aPlayNameIsTheRemainderRejoinedWithEveryQuoteStripped() {
        assertEquals("wave", YsmCommand.parsePlayAnimationName(new String[] { "play", "wave" }));
        assertEquals(
            "my anim",
            YsmCommand.parsePlayAnimationName(new String[] { "play", "my", "anim" }),
            "an animation name the shell split at spaces is re-joined");
        assertEquals("wave", YsmCommand.parsePlayAnimationName(new String[] { "play", "\"wave\"" }));
        assertEquals(
            "wave",
            YsmCommand.parsePlayAnimationName(new String[] { "play", "wa\"ve" }),
            "the reference removes every quote, not only a surrounding pair (:203)");
        assertEquals("", YsmCommand.parsePlayAnimationName(new String[] { "play" }));
    }

    @Test
    void aGamePathIsTheSecondArgumentAndTheEmptySpellingsClearIt() {
        assertEquals("C:/x/.minecraft", YsmCommand.parseGamePath(new String[] { "setgamepath", "C:/x/.minecraft" }));
        assertEquals(
            "",
            YsmCommand.parseGamePath(new String[] { "setgamepath" }),
            "without a path there is nothing to read");
        assertEquals(
            "",
            YsmCommand.parseGamePath(new String[] { "setgamepath", "\"\"" }),
            "a quoted empty path clears the setting (reference :292-294)");
        assertEquals("", YsmCommand.parseGamePath(new String[] { "setgamepath", "''" }));
        assertEquals("D:/minecraft", YsmCommand.parseGamePath(new String[] { "setgamepath", "D:/minecraft" }));
    }

    @Test
    void aGamePathVersionFallsBackToTheConfiguredOne() {
        assertEquals("1.21", YsmCommand.parseGamePathVersion(new String[] { "setgamepath", "C:/x", "1.21" }));
        assertEquals(
            Config.HIGH_VERSION_ASSET_VERSION,
            YsmCommand.parseGamePathVersion(new String[] { "setgamepath", "C:/x" }),
            "a missing version keeps the configured asset version, as the reference does (:289)");
    }

    @Test
    void aWelcomeValueIsOffOnlyForItsThreeSpellings() {
        assertFalse(YsmCommand.parseWelcomeValue(new String[] { "welcome", "off" }));
        assertFalse(YsmCommand.parseWelcomeValue(new String[] { "welcome", "OFF" }));
        assertFalse(YsmCommand.parseWelcomeValue(new String[] { "welcome", "0" }));
        assertFalse(YsmCommand.parseWelcomeValue(new String[] { "welcome", "false" }));
        // The reference has no rejection branch: a missing argument and any other word - "on" included - mean on.
        assertTrue(YsmCommand.parseWelcomeValue(new String[] { "welcome" }));
        assertTrue(YsmCommand.parseWelcomeValue(new String[] { "welcome", "on" }));
        assertTrue(YsmCommand.parseWelcomeValue(new String[] { "welcome", "bogus" }));
    }

    @Test
    void theWelcomeAndResyncFeedbackKeysFollowTheirDecisions() {
        assertEquals("commands.yes_steve_model.welcome.on", YsmCommand.welcomeMessageKey(true));
        assertEquals("commands.yes_steve_model.welcome.off", YsmCommand.welcomeMessageKey(false));

        assertEquals(
            "commands.yes_steve_model.sync.resync.started",
            YsmCommand.resyncMessageKey(OpenYsmModelSyncServer.RESYNC_OK));
        assertEquals(
            "commands.yes_steve_model.sync.resync.cooldown",
            YsmCommand.resyncMessageKey(OpenYsmModelSyncServer.RESYNC_COOLDOWN));
        assertEquals(
            "commands.yes_steve_model.sync.resync.disabled",
            YsmCommand.resyncMessageKey(OpenYsmModelSyncServer.RESYNC_DISABLED));
        assertEquals(
            "commands.yes_steve_model.sync.resync.failed",
            YsmCommand.resyncMessageKey(OpenYsmModelSyncServer.RESYNC_FAILED));
        assertEquals(
            "commands.yes_steve_model.sync.resync.failed",
            YsmCommand.resyncMessageKey(42),
            "an unknown code takes the reference's else branch, not a fourth message");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // tab completion for the new subcommands
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void tabCompletionOffersTheNewSubcommandsArguments() {
        List<?> path = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "setgamepath", "" });
        assertEquals(1, path.size());
        assertEquals("<path>", path.get(0));

        List<?> version = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "setgamepath", "C:/x", "" });
        assertEquals(1, version.size());
        assertEquals("[version]", version.get(0));

        List<?> modes = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "welcome", "" });
        assertEquals(2, modes.size());
        assertTrue(modes.contains("on"));
        assertTrue(modes.contains("off"));

        List<?> filtered = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "welcome", "of" });
        assertEquals(1, filtered.size());
        assertEquals("off", filtered.get(0));

        // The reference has no completion branch for help's topic argument, so it stays null rather than guessing.
        assertNull(new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "help", "" }));
    }

    /** The compiled bytes of {@code type}, straight from the test classpath. */
    private static byte[] readClassBytes(Class<?> type) throws java.io.IOException {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        try (java.io.InputStream in = type.getClassLoader()
            .getResourceAsStream(resource)) {
            assertTrue(in != null, resource + " must be readable from the classpath");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
