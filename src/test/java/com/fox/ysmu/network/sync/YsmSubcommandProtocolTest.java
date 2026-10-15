package com.fox.ysmu.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.network.message.SetWelcomeConfig;
import com.fox.ysmu.network.message.ShowBufferInfo;
import com.fox.ysmu.network.message.SyncGamePath;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The three {@code /ysm} subcommand packets of {@code .agent/phase39-ysm-subcommands.md}
 * ({@code NetworkHandler} ids 32 / 33 / 34) and the pure decisions of the resync rate limiter behind {@code /ysm sync}.
 * <p>
 * Follows {@link MolangDebugProtocolTest}'s shape: a payload FML cannot decode is a disconnect rather than a dropped
 * packet, so each packet has to survive a real encode/decode round trip on a {@code Unpooled} buffer, including a
 * truncated one. No handler is invoked - each of them touches the client (config write, asset provider, chat), so
 * this class pins the wire shape and the command's decision helpers only, and says so rather than skipping either.
 * <p>
 * The handler <em>direction</em> is pinned in {@code YsmCommandTest.everyPortedCommandPacketIsClientboundSoTheServerIsTheSender},
 * not here, so one rule has one test.
 */
class YsmSubcommandProtocolTest {

    @Test
    void theGamePathPacketRoundTripsBothStrings() {
        ByteBuf buf = Unpooled.buffer();
        new SyncGamePath("C:/Users/x/.minecraft", "1.21").toBytes(buf);

        SyncGamePath decoded = new SyncGamePath();
        decoded.fromBytes(buf);

        assertEquals("C:/Users/x/.minecraft", decoded.getGamePath());
        assertEquals("1.21", decoded.getAssetVersion());
    }

    @Test
    void theGamePathPacketRoundTripsANonAsciiPathAndAnEmptyVersion() {
        ByteBuf buf = Unpooled.buffer();
        new SyncGamePath("E:/\u6e38\u620f/.minecraft", "").toBytes(buf);

        SyncGamePath decoded = new SyncGamePath();
        decoded.fromBytes(buf);

        assertEquals("E:/\u6e38\u620f/.minecraft", decoded.getGamePath(), "the length prefix is a UTF-8 byte count");
        assertEquals("", decoded.getAssetVersion());
    }

    @Test
    void theGamePathPacketDropsATruncatedPayloadInsteadOfThrowing() {
        // A decoder that throws here is turned into a disconnect by FMLProxyPacket.processPacket, so a truncated
        // payload has to decode into something harmless (the repository's N-10 rule).
        SyncGamePath empty = new SyncGamePath("C:/x", "1.21");
        empty.fromBytes(Unpooled.buffer());
        assertEquals("", empty.getGamePath());
        assertEquals("", empty.getAssetVersion());

        // A length prefix that claims more bytes than the buffer holds is refused the same way, and the following
        // field is still read defensively.
        ByteBuf lying = Unpooled.buffer();
        lying.writeInt(100);
        lying.writeByte('a');
        SyncGamePath decoded = new SyncGamePath("C:/x", "1.21");
        decoded.fromBytes(lying);
        assertEquals("", decoded.getGamePath());
        assertEquals("", decoded.getAssetVersion());
    }

    @Test
    void theWelcomePacketRoundTripsBothValues() {
        for (boolean show : new boolean[] { true, false }) {
            ByteBuf buf = Unpooled.buffer();
            new SetWelcomeConfig(show).toBytes(buf);

            SetWelcomeConfig decoded = new SetWelcomeConfig();
            decoded.fromBytes(buf);

            assertEquals(show, decoded.isShow());
        }
    }

    @Test
    void theWelcomePacketDefaultsToEnabledOnATruncatedPayload() {
        // The zero-argument instance is what FML builds when decoding, and its default must match the field's default
        // (Config.SHOW_WELCOME_MESSAGE starts true).
        assertTrue(new SetWelcomeConfig().isShow());
        SetWelcomeConfig decoded = new SetWelcomeConfig(false);
        decoded.fromBytes(Unpooled.buffer());
        assertTrue(decoded.isShow(), "a truncated payload reads as enabled rather than throwing");
    }

    @Test
    void theBufferPacketIsEmptyOnTheWireAndStillDecodes() {
        ByteBuf buf = Unpooled.buffer();
        new ShowBufferInfo().toBytes(buf);

        assertEquals(
            0,
            buf.readableBytes(),
            "the buffer request carries no payload; adding one would change what the new id means");

        ShowBufferInfo decoded = new ShowBufferInfo();
        decoded.fromBytes(buf);
        assertNotNull(decoded);
    }

    @Test
    void aNullPlayerIsRefusedByTheResyncEntryPoint() {
        // The command cannot be driven with a real EntityPlayerMP in a unit JVM, so the one decision that needs no
        // player is pinned instead: the null guard in front of the cooldown and of Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL.
        assertEquals(OpenYsmModelSyncServer.RESYNC_FAILED, OpenYsmModelSyncServer.requestResync(null));
    }

    @Test
    void aPlayerWithoutARecordHasNoCooldownLeft() {
        // No player object is needed for this decision either: an unknown UUID means "never resynced", i.e. 0 seconds.
        assertEquals(0L, OpenYsmModelSyncServer.resyncRemainingSeconds(null));
        assertEquals(0L, OpenYsmModelSyncServer.resyncRemainingSeconds(UUID.randomUUID()));
    }

    @Test
    void clearingAnUnknownCooldownIsHarmless() {
        UUID playerId = UUID.randomUUID();

        OpenYsmModelSyncServer.clearResyncCooldown(playerId);
        OpenYsmModelSyncServer.clearResyncCooldown(null);

        assertEquals(
            0L,
            OpenYsmModelSyncServer.resyncRemainingSeconds(playerId),
            "clearing a cooldown that does not exist must not create one");
    }
}
