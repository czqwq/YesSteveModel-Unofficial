package com.fox.ysmu.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ModelIdUtil;

/**
 * The {@code /ysm auth} surface: the dispatch, the refusals, the extractable decisions and the help/tab wiring.
 * <p>
 * The port of upstream's {@code command/sub/AuthCommand.java:28-102}. As in {@link YsmCommandTest}, every message is
 * observable as a {@link ChatComponentTranslation} key without a client language table.
 * <p>
 * <b>What a unit JVM cannot drive</b>, recorded rather than approximated: the mutations themselves. They target an
 * {@code EntityPlayerMP}, which cannot be fabricated here ({@code EntityPlayerMP}'s constructor dereferences a live
 * {@code WorldServer.provider} and {@code MinecraftServer.getConfigurationManager()}), and the per-player table lives
 * in an EEP that only exists on such an entity. The storage and policy halves are therefore pinned directly
 * ({@code ExtendedAuthModelsTest}, {@code ModelAuthorizationTest}); what is pinned here is that the command reaches
 * its target-resolution step and refuses before it, that the argument decisions are right, and that the new
 * subcommand is wired into help and completion.
 */
class YsmAuthCommandTest {

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

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }

        private int count() {
            return this.messages.size();
        }

        private String singleKey() {
            if (this.messages.size() != 1) {
                return null;
            }
            IChatComponent message = this.messages.get(0);
            return message instanceof ChatComponentTranslation ? ((ChatComponentTranslation) message).getKey() : null;
        }
    }

    /** A sender that denies every permission level; pins which subcommands the in-body gate exempts. */
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
    void authWithoutATargetOrActionPrintsTheUsageInsteadOfGrantingAnything() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "auth" });

        assertEquals(1, sender.count());
        assertEquals("commands.yes_steve_model.auth.usage", sender.singleKey());
    }

    @Test
    void anUnknownActionIsRefusedBeforeAnyTargetIsResolved() {
        // "Grant"/"list"/a typo must not be treated as one of upstream's four literals. The refusal is the usage line
        // and it happens before resolveAuthTargets (a unit JVM has no server, so reaching that step would instead
        // throw commands.generic.player.notFound - the difference is what proves the order).
        for (String action : new String[] { "grant", "list", "", "ALL " }) {
            RecordingSender sender = new RecordingSender();

            new YsmCommand().processCommand(sender, new String[] { "auth", "Notch", action });

            assertEquals(
                "commands.yes_steve_model.auth.usage",
                sender.singleKey(),
                "action '" + action + "' is not one of add/remove/all/clear");
        }
    }

    @Test
    void addWithoutAModelIsAUsageErrorRatherThanAPlayerLookup() {
        RecordingSender sender = new RecordingSender();

        new YsmCommand().processCommand(sender, new String[] { "auth", "Notch", "add" });

        assertEquals(1, sender.count());
        assertEquals("commands.yes_steve_model.auth.usage", sender.singleKey());
    }

    @Test
    void theActionWordIsMatchedCaseInsensitively() {
        // "ADD" must reach the model branch (which then fails on the missing argument), not the usage line.
        RecordingSender sender = new RecordingSender();

        assertThrowsPlayerRefusal(() -> new YsmCommand().processCommand(sender, new String[] { "auth", "@a", "ADD", "default" }));
    }

    @Test
    void authStaysBehindTheCommandsLevelTwoGate() {
        // /ysm auth is not in LEVEL_ZERO_SUBCOMMANDS: upstream gates it at permission 2
        // (AuthCommand.java:29), and this port's inherited gate is that same level. A denied sender never reaches the
        // target resolution.
        for (String[] args : new String[][] { { "auth" }, { "auth", "@a", "all" }, { "auth", "Notch", "add", "x" } }) {
            CommandException exception = assertThrows(
                CommandException.class,
                () -> new YsmCommand().processCommand(new DenyingSender(), args));
            assertEquals("commands.generic.permission", exception.getMessage(), Arrays.toString(args));
        }
    }

    @Test
    void aValidActionRefusesWithoutAServerInsteadOfThrowingAnNpe() {
        // The selector/name resolution dereferences MinecraftServer.getServer() (CommandBase.java:198); a unit JVM has
        // none, and the port's answer is the same refusal the vanilla helper would give for an unknown player.
        RecordingSender sender = new RecordingSender();

        assertThrowsPlayerRefusal(() -> new YsmCommand().processCommand(sender, new String[] { "auth", "@a", "all" }));
        assertThrowsPlayerRefusal(
            () -> new YsmCommand().processCommand(sender, new String[] { "auth", "Notch", "clear" }));
        assertEquals(0, sender.count(), "a refusal must not also print a receipt");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the extractable decisions
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theActionIsTheThirdArgumentLowerCasedAndEmptyWhenMissing() {
        assertEquals("add", YsmCommand.parseAuthAction(new String[] { "auth", "@a", "add", "x" }));
        assertEquals("clear", YsmCommand.parseAuthAction(new String[] { "auth", "@a", "CLEAR" }));
        assertEquals("", YsmCommand.parseAuthAction(new String[] { "auth", "@a" }));
        assertEquals("", YsmCommand.parseAuthAction(new String[] { "auth" }));
    }

    @Test
    void onlyUpstreamsFourLiteralsAreKnownActions() {
        for (String known : new String[] { "add", "remove", "all", "clear" }) {
            assertTrue(YsmCommand.isKnownAuthAction(known), known);
        }
        for (String unknown : new String[] { "", "ADD", "grant", "all ", "clearx" }) {
            assertFalse(YsmCommand.isKnownAuthAction(unknown), unknown);
        }
    }

    @Test
    void aModelArgumentIsTheRemainderRejoinedAndOneQuotePairIsStripped() {
        assertEquals("default", YsmCommand.parseAuthModelArgument(new String[] { "auth", "@a", "add", "default" }));
        assertEquals(
            "wine_fox/01 taisho",
            YsmCommand.parseAuthModelArgument(new String[] { "auth", "@a", "add", "wine_fox/01", "taisho" }),
            "a model name the dispatcher split at spaces is re-joined");
        assertEquals(
            "example model",
            YsmCommand.parseAuthModelArgument(new String[] { "auth", "@a", "add", "\"example model\"" }));
    }

    @Test
    void theFeedbackKeysFollowTheActions() {
        assertEquals("commands.yes_steve_model.auth.add.done", YsmCommand.authFeedbackKey("add"));
        assertEquals("commands.yes_steve_model.auth.remove.done", YsmCommand.authFeedbackKey("remove"));
        assertEquals("commands.yes_steve_model.auth.all.done", YsmCommand.authFeedbackKey("all"));
        assertEquals("commands.yes_steve_model.auth.clear.done", YsmCommand.authFeedbackKey("clear"));
    }

    @Test
    void modelSuggestionsAreTheReadableNamesOfTheRegisteredModelsInAStableOrder() {
        String encoded = ModelIdUtil.getInternalModelId("example model");
        Set<String> registered = new HashSet<>(Arrays.asList("wine_fox/01_x", encoded, "default"));

        List<String> suggestions = YsmCommand.authModelSuggestions(registered);

        assertEquals(3, suggestions.size());
        assertEquals("default", suggestions.get(0), "sorted, so the completion list does not jump between calls");
        assertEquals("example model", suggestions.get(1), "an encoded id is offered as the name an operator typed");
        assertEquals("wine_fox/01_x", suggestions.get(2));
        assertNotNull(YsmCommand.authModelSuggestions());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // help and tab completion
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void authHasAHelpTopicWithUsageAndDescription() {
        assertTrue(YsmCommand.isKnownHelpTopic("auth"), "the new subcommand must be documented by /ysm help");

        RecordingSender sender = new RecordingSender();
        new YsmCommand().processCommand(sender, new String[] { "help", "auth" });

        assertEquals(3, sender.count());
        assertEquals("commands.yes_steve_model.help.detail.title", ((ChatComponentTranslation) sender.messages.get(0)).getKey());
        assertEquals(
            "commands.yes_steve_model.help.detail.auth.usage",
            ((ChatComponentTranslation) sender.messages.get(1)).getKey());
        assertEquals(
            "commands.yes_steve_model.help.detail.auth.desc",
            ((ChatComponentTranslation) sender.messages.get(2)).getKey());
    }

    @Test
    void tabCompletionOffersAuthAndItsActions() {
        List<?> top = new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "au" });
        assertEquals(1, top.size());
        assertEquals("auth", top.get(0));

        List<?> actions = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "@a", "" });
        assertEquals(4, actions.size());
        for (String action : new String[] { "add", "remove", "all", "clear" }) {
            assertTrue(actions.contains(action), action + " must be offered");
        }

        List<?> filtered = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "@a", "re" });
        assertEquals(1, filtered.size());
        assertEquals("remove", filtered.get(0));

        // The model slot: one suggestion per registered model (the registry is empty in a clean unit JVM).
        List<?> models = new YsmCommand()
            .addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "@a", "add", "" });
        assertNotNull(models);
        assertEquals(com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO.size(), models.size());
    }

    @Test
    void tabCompletionForAuthTargetsDegradesWithoutAServerInsteadOfFailing() {
        // Same guard as /ysm reset: completion runs on the server thread and MinecraftServer.getServer() is null in a
        // unit JVM, so the port returns null (no suggestions) instead of dereferencing it.
        assertNull(new YsmCommand().addTabCompletionOptions(new RecordingSender(), new String[] { "auth", "N" }));
    }

    /** Asserts the command refused with the port's shared "no such player/selector" refusal. */
    private static void assertThrowsPlayerRefusal(Runnable command) {
        CommandException exception = assertThrows(CommandException.class, command::run);
        assertEquals("commands.generic.player.notFound", exception.getMessage());
    }
}
