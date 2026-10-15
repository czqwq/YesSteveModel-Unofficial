package com.fox.ysmu.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import org.junit.jupiter.api.Test;

/**
 * {@code /ysmclient}'s argument dispatch and its refusals.
 * <p>
 * The messages are {@link ChatComponentTranslation}s whose translation happens when the chat box renders them, so the
 * dispatch is observable in a unit JVM: the recorded component's sibling carries the translation <em>key</em>, and no
 * client language table is touched.
 *
 * <p>The command itself never resolves {@code Minecraft} on the {@code reset} path: the client command handler always
 * dispatches with the local player as the sender
 * ({@code build/rfg/minecraft-src/java/net/minecraft/client/gui/GuiChat.java:160} hands {@code mc.thePlayer} to
 * {@code ClientCommandHandler.executeCommand}, which passes it on at {@code ClientCommandHandler.java:72}), so
 * "there is no client" is exactly "the sender is not a player" — the state this test can express.
 */
class CommandYsmClientTest {

    /** Records what the command sends back, without a client. */
    private static final class RecordingSender implements ICommandSender {

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

        /** The translation key of the single message received, or null when nothing was sent. */
        private String singleKey() {
            if (this.messages.size() != 1) {
                return null;
            }
            List<IChatComponent> siblings = this.messages.get(0)
                .getSiblings();
            if (siblings.size() != 1 || !(siblings.get(0) instanceof ChatComponentTranslation)) {
                return null;
            }
            return ((ChatComponentTranslation) siblings.get(0)).getKey();
        }

        private int count() {
            return this.messages.size();
        }
    }

    @Test
    void theCommandIsTheIsolatedClientName() {
        CommandYsmClient command = new CommandYsmClient();

        assertEquals(
            "ysmclient",
            command.getCommandName(),
            "a client-registered \"ysm\" would swallow every /ysm call before it could reach the server");
        assertTrue(command.canCommandSenderUseCommand(new RecordingSender()), "a client command is usable by anyone");
    }

    @Test
    void noArgumentsIsRefusedWithTheUsageMessage() {
        RecordingSender sender = new RecordingSender();

        new CommandYsmClient().processCommand(sender, new String[0]);

        assertEquals(
            "message.yes_steve_model.debug.ysmclient.usage",
            sender.singleKey(),
            "an empty invocation must answer with the usage line and do nothing else");
    }

    @Test
    void anUnknownSubcommandIsRefusedAndNamed() {
        RecordingSender sender = new RecordingSender();

        new CommandYsmClient().processCommand(sender, new String[] { "bogus" });

        assertEquals("message.yes_steve_model.debug.ysmclient.unknown", sender.singleKey());
        ChatComponentTranslation translation = (ChatComponentTranslation) sender.messages.get(0)
            .getSiblings()
            .get(0);
        assertEquals(1, translation.getFormatArgs().length);
        assertEquals("bogus", translation.getFormatArgs()[0], "the offending subcommand is echoed back");
    }

    @Test
    void aSubcommandIsMatchedCaseInsensitively() {
        // "RESET" must reach the reset branch, which sends nothing without a client, and not the unknown-subcommand
        // branch, which always answers. That difference is what makes the dispatch observable here.
        // ("load" is deliberately not driven from a unit test: it hands off to the shared background pool against the
        // live model index, which a test must not make register models for real.)
        RecordingSender sender = new RecordingSender();

        new CommandYsmClient().processCommand(sender, new String[] { "RESET" });

        assertEquals(0, sender.count(), "an upper-case subcommand must not fall through to the unknown answer");
    }

    @Test
    void aSubcommandThatOnlyStartsWithAKnownOneIsRefused() {
        RecordingSender sender = new RecordingSender();

        new CommandYsmClient().processCommand(sender, new String[] { "resetx" });

        assertEquals("message.yes_steve_model.debug.ysmclient.unknown", sender.singleKey());
    }

    @Test
    void resetWithoutAClientIsASilentRefusal() {
        RecordingSender sender = new RecordingSender();

        // A client command is only ever dispatched with the local player as the sender, so this sender (not a
        // player) is the no-client state; the command must return rather than NPE.
        new CommandYsmClient().processCommand(sender, new String[] { "reset" });

        assertEquals(0, sender.count(), "there is nobody to answer when no client exists");
    }

    @Test
    void tabCompletionOffersBothSubcommandsAndOnlyAtTheFirstArgument() {
        CommandYsmClient command = new CommandYsmClient();

        List<?> first = command.addTabCompletionOptions(new RecordingSender(), new String[] { "re" });
        assertEquals(1, first.size());
        assertEquals("reset", first.get(0));

        assertNull(command.addTabCompletionOptions(new RecordingSender(), new String[] { "reset", "x" }));
        assertFalse(command.addTabCompletionOptions(new RecordingSender(), new String[] { "" })
            .isEmpty());
    }
}
