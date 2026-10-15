package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;

/**
 * The session-scoped state of {@code /ysm}: the join-world welcome message's once-per-session rule and the
 * {@code /ysm sync} cooldown the logout path drops.
 * <p>
 * Both are normally driven by FML events, so the rule itself is what is pinned here rather than the event:
 * <ul>
 * <li>the welcome decision is {@code Config.SHOW_WELCOME_MESSAGE && !welcomeShown}, exposed as
 * {@link ClientEventHandler#shouldShowWelcome()} with {@link ClientEventHandler#setWelcomeShown(boolean)} as its
 * single writer. The whole lifecycle - fresh session, shown, reset by the disconnect cleanup - is driven through
 * those two seams, which is the only way to reach it without a client: the real entry points are
 * {@code onClientPlayerJoinWorld(EntityJoinWorldEvent)} and {@code clearClientRuntimeState()}, and the latter calls
 * {@code YSMSoundManager.clear()} and other client-only cleanups that a unit JVM cannot link.</li>
 * <li>the logout caller of {@code OpenYsmModelSyncServer.clearResyncCooldown(UUID)} is pinned by the compiled name
 * being present in {@code CommonEventHandler}'s constant pool - the same shape as
 * {@code YsmCommandTest.everyPortedCommandPacketIsClientboundSoTheServerIsTheSender}. It fails if the call is
 * deleted, but it is deliberately <b>not</b> claimed to prove the branch runs: reaching it needs an
 * {@code EntityPlayerMP}, which cannot be fabricated here. The method's own behaviour (null, unknown UUID, clear)
 * is covered by {@code YsmSubcommandProtocolTest}.</li>
 * </ul>
 * Nothing in this class is weakened by the other: the two are one writer/reader pair and one call site.
 */
class ClientSessionStateResetTest {

    private boolean originalShowWelcome;

    @BeforeEach
    void rememberTheConfigSwitch() {
        originalShowWelcome = Config.SHOW_WELCOME_MESSAGE;
    }

    @AfterEach
    void restoreTheConfigSwitchAndTheFlag() {
        Config.SHOW_WELCOME_MESSAGE = originalShowWelcome;
        // Leave the shared flag as this class found it, so another test class in the same JVM is not affected.
        ClientEventHandler.setWelcomeShown(false);
    }

    @Test
    void theWelcomeMessageIsShownOnceAndTheDisconnectCleanupResetsIt() {
        ClientEventHandler.setWelcomeShown(false);
        Config.SHOW_WELCOME_MESSAGE = true;
        assertTrue(ClientEventHandler.shouldShowWelcome(), "a fresh session with the switch on shows the message");

        // What the join block does right after showing it (reference client/ClientEventHandler.java:193-194).
        ClientEventHandler.setWelcomeShown(true);
        assertFalse(ClientEventHandler.shouldShowWelcome(), "a second join in the same session must not repeat it");

        // What clearClientRuntimeState() does at the disconnect/exit boundary, which is the one write the reference
        // does not have (it never resets its flag; see the field's javadoc).
        ClientEventHandler.setWelcomeShown(false);
        assertTrue(ClientEventHandler.shouldShowWelcome(), "the session after that shows it again");
    }

    @Test
    void theConfigSwitchWinsInBothStates() {
        Config.SHOW_WELCOME_MESSAGE = false;

        ClientEventHandler.setWelcomeShown(false);
        assertFalse(ClientEventHandler.shouldShowWelcome(), "switched off, a fresh session stays silent");

        ClientEventHandler.setWelcomeShown(true);
        assertFalse(ClientEventHandler.shouldShowWelcome(), "switched off after it was shown stays silent");
    }

    @Test
    void theLogoutPathStillNamesTheResyncCooldownReset() throws IOException {
        String pool = new String(
            readClassBytes("com/fox/ysmu/event/CommonEventHandler.class"),
            StandardCharsets.ISO_8859_1);

        assertTrue(
            pool.contains("clearResyncCooldown"),
            "event/CommonEventHandler.onPlayerLoggedOut must still call"
                + " OpenYsmModelSyncServer.clearResyncCooldown(UUID), or a relog inside RESYNC_COOLDOWN_SECONDS"
                + " inherits the cooldown of the session that ended");
        assertTrue(
            pool.contains("OpenYsmModelSyncServer"),
            "the same logout path must still reference OpenYsmModelSyncServer at all");
    }

    /** The compiled bytes of the resource at {@code path}, straight from the test classpath (no class loading). */
    private static byte[] readClassBytes(String path) throws IOException {
        try (InputStream in = ClientSessionStateResetTest.class.getClassLoader()
            .getResourceAsStream(path)) {
            assertTrue(in != null, path + " must be readable from the classpath");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
