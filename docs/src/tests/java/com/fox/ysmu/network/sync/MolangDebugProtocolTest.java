package com.fox.ysmu.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.network.message.PacketEvalMolang;
import com.fox.ysmu.network.message.PacketQueryMolangVar;
import com.fox.ysmu.network.message.PacketResetMolang;
import com.fox.ysmu.network.message.S2CSetDebugOverlay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The four clientbound packets of the Molang debug-overlay cluster ({@code NetworkHandler} ids 23 / 26 / 27 / 31,
 * taken from the reference branch; see {@code .agent/phase33-m4-debug-overlay-ported.md}).
 * <p>
 * Follows {@link RoamingProtocolTest}'s shape: a payload FML cannot decode is a disconnect rather than a dropped
 * packet, so each of these has to survive a real encode/decode round trip on a {@code Unpooled} buffer. The framing
 * itself (an 18-byte UTF-8 length prefix for strings, a plain int for the list size) is what these tests pin - no
 * handler is invoked, so no client is needed.
 */
class MolangDebugProtocolTest {

    @Test
    void theQueryPacketRoundTripsEveryNameInOrder() {
        List<String> sent = Arrays.asList("ysm.person_view", "query.health", "v.roaming.bq_eye");

        ByteBuf buf = Unpooled.buffer();
        new PacketQueryMolangVar(sent).toBytes(buf);
        PacketQueryMolangVar decoded = new PacketQueryMolangVar();
        decoded.fromBytes(buf);

        assertEquals(sent, decoded.getVarNames());
    }

    @Test
    void theQueryPacketRoundTripsNamesAwayFromAscii() {
        // Model settings and pack-scoped names are not guaranteed to be ASCII; the length prefix is a byte count.
        List<String> sent = Arrays.asList("v.roaming.\u53d8\u91cf", "query.is_\u98de\u884c");

        ByteBuf buf = Unpooled.buffer();
        new PacketQueryMolangVar(sent).toBytes(buf);
        PacketQueryMolangVar decoded = new PacketQueryMolangVar();
        decoded.fromBytes(buf);

        assertEquals(sent, decoded.getVarNames());
    }

    @Test
    void theQueryPacketKeepsAnEmptyNameListDecodable() {
        ByteBuf buf = Unpooled.buffer();
        new PacketQueryMolangVar(Collections.<String>emptyList()).toBytes(buf);
        PacketQueryMolangVar decoded = new PacketQueryMolangVar();
        decoded.fromBytes(buf);

        assertNotNull(decoded.getVarNames());
        assertTrue(
            decoded.getVarNames()
                .isEmpty());
    }

    @Test
    void theEvalPacketRoundTripsTheWholeExpression() {
        String expression = "math.clamp(v.roaming.player_size, 0, 2) + 1";

        ByteBuf buf = Unpooled.buffer();
        new PacketEvalMolang(expression).toBytes(buf);
        PacketEvalMolang decoded = new PacketEvalMolang();
        decoded.fromBytes(buf);

        assertEquals(expression, decoded.getExpression());
    }

    @Test
    void theEvalPacketRoundTripsAMultiByteExpression() {
        String expression = "ysm.dump_mods('\u6a21\u578b\u5305')";

        ByteBuf buf = Unpooled.buffer();
        new PacketEvalMolang(expression).toBytes(buf);
        PacketEvalMolang decoded = new PacketEvalMolang();
        decoded.fromBytes(buf);

        assertEquals(expression, decoded.getExpression());
    }

    @Test
    void theResetPacketIsEmptyOnTheWireAndStillDecodes() {
        ByteBuf buf = Unpooled.buffer();
        new PacketResetMolang().toBytes(buf);

        assertEquals(
            0,
            buf.readableBytes(),
            "the reset request carries no payload; adding one would change what id 27 means without a protocol bump");

        PacketResetMolang decoded = new PacketResetMolang();
        decoded.fromBytes(buf);
        assertNotNull(decoded);
    }

    @Test
    void theOverlayPacketRoundTripsEveryMode() {
        for (byte mode : new byte[] { S2CSetDebugOverlay.MODE_OFF, S2CSetDebugOverlay.MODE_ON,
            S2CSetDebugOverlay.MODE_TOGGLE }) {
            ByteBuf buf = Unpooled.buffer();
            new S2CSetDebugOverlay(mode).toBytes(buf);
            S2CSetDebugOverlay decoded = new S2CSetDebugOverlay();
            decoded.fromBytes(buf);

            assertEquals(mode, decoded.getMode(), "mode " + mode + " must survive the single-byte payload");
        }
    }

    @Test
    void theOverlayPacketDefaultsToToggle() {
        // The zero-argument instance is what FML builds when decoding a peer's payload; it must mean "toggle".
        assertEquals(S2CSetDebugOverlay.MODE_TOGGLE, new S2CSetDebugOverlay().getMode());
    }
}
