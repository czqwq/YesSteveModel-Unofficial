package com.fox.ysmu.network.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.roaming.ModelRoamingLimits;
import com.fox.ysmu.network.message.C2SRoamingChanges;
import com.fox.ysmu.network.message.S2CRoamingState;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The roaming-variable channel (see {@code .agent/phase15-roaming-variables.md}, Milestone 3).
 * <p>
 * FML turns an exception thrown out of a packet decoder into a disconnect rather than a dropped packet, so a
 * malformed payload has to leave the message empty and let the handler ignore it. These tests pin that down, plus the
 * one invariant that is easy to get wrong: the entry count is written before the entries, so the writer must filter
 * before it counts.
 */
class RoamingProtocolTest {

    private static final int MODEL_KEY = 0x01234567;

    @Test
    void changesRoundTrip() {
        C2SRoamingChanges sent = C2SRoamingChanges.of(
            MODEL_KEY,
            map("v.roaming.player_size", 2.5d, "v.roaming.elytra", 0d));

        ByteBuf buf = Unpooled.buffer();
        sent.toBytes(buf);

        C2SRoamingChanges decoded = new C2SRoamingChanges();
        decoded.fromBytes(buf);

        assertEquals(MODEL_KEY, decoded.getModelKey());
        assertEquals(2, decoded.getChanges().size());
        assertEquals(2.5d, decoded.getChanges().get("v.roaming.player_size"));
        assertEquals(0d, decoded.getChanges().get("v.roaming.elytra"));
    }

    @Test
    void stateRoundTripsThePlayerAndTheWholeNamespace() {
        UUID playerId = UUID.fromString("12345678-1234-5678-1234-567812345678");
        S2CRoamingState sent = S2CRoamingState.of(playerId, MODEL_KEY, map("v.roaming.cloth", 1d));

        ByteBuf buf = Unpooled.buffer();
        sent.toBytes(buf);

        S2CRoamingState decoded = new S2CRoamingState();
        decoded.fromBytes(buf);

        assertEquals(playerId, decoded.getPlayerId());
        assertEquals(MODEL_KEY, decoded.getModelKey());
        assertEquals(1d, decoded.getValues().get("v.roaming.cloth"));
    }

    @Test
    void theWriterFiltersBeforeItCounts() {
        // A bare name is not accepted by the stores, so it must not reach the wire - and, because the count is
        // written first, it must not be counted either or the decoder would read into the next entry.
        C2SRoamingChanges sent = C2SRoamingChanges.of(
            MODEL_KEY,
            map("player_size", 1d, "v.roaming.ok", 2d, "v.roaming.nan", Double.NaN));

        ByteBuf buf = Unpooled.buffer();
        sent.toBytes(buf);
        assertEquals(
            1,
            buf.getUnsignedShort(4),
            "only v.roaming.ok survives the filter, and the count must match the entries actually written");

        C2SRoamingChanges decoded = new C2SRoamingChanges();
        decoded.fromBytes(buf);
        assertEquals(1, decoded.getChanges().size());
        assertEquals(2d, decoded.getChanges().get("v.roaming.ok"));
    }

    @Test
    void anEntryCountBeyondTheBoundIsRejected() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(MODEL_KEY);
        buf.writeShort(0xFFFF);
        // Enough bytes for the decoder to have read something if it trusted the count.
        for (int i = 0; i < 64; i++) {
            buf.writeByte(0);
        }

        C2SRoamingChanges decoded = new C2SRoamingChanges();
        decoded.fromBytes(buf);

        assertTrue(decoded.getChanges().isEmpty());
        assertEquals(0, decoded.getModelKey(), "a rejected payload leaves the message at its empty state");
    }

    @Test
    void aTruncatedPayloadIsDroppedInsteadOfThrowing() {
        C2SRoamingChanges full = C2SRoamingChanges.of(MODEL_KEY, map("v.roaming.player_size", 2.5d));
        ByteBuf buffer = Unpooled.buffer();
        full.toBytes(buffer);
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(0, bytes);

        // Every prefix of a valid payload must decode quietly: FML would disconnect the client otherwise.
        for (int length = 0; length < bytes.length; length++) {
            ByteBuf truncated = Unpooled.wrappedBuffer(bytes, 0, length);
            C2SRoamingChanges decoded = new C2SRoamingChanges();
            decoded.fromBytes(truncated);
            assertTrue(
                decoded.getChanges().isEmpty() || decoded.getChanges().size() == 1,
                "a prefix must decode to nothing or to the whole change, never to a partial one");
        }

        // The same decoder path is used by the state message.
        S2CRoamingState state = S2CRoamingState.of(UUID.randomUUID(), MODEL_KEY, map("v.roaming.a", 1d));
        ByteBuf stateBuffer = Unpooled.buffer();
        state.toBytes(stateBuffer);
        byte[] stateBytes = new byte[stateBuffer.readableBytes()];
        stateBuffer.getBytes(0, stateBytes);
        for (int length = 0; length < stateBytes.length; length++) {
            S2CRoamingState decoded = new S2CRoamingState();
            decoded.fromBytes(Unpooled.wrappedBuffer(stateBytes, 0, length));
        }
    }

    @Test
    void theWriterNeverExceedsTheVariableBound() {
        Map<String, Double> many = new HashMap<>();
        for (int i = 0; i < ModelRoamingLimits.MAX_VARIABLES_PER_MODEL + 20; i++) {
            many.put("v.roaming.v" + i, (double) i);
        }

        ByteBuf buf = Unpooled.buffer();
        C2SRoamingChanges.of(MODEL_KEY, many)
            .toBytes(buf);

        assertEquals(ModelRoamingLimits.MAX_VARIABLES_PER_MODEL, buf.getUnsignedShort(4));
        C2SRoamingChanges decoded = new C2SRoamingChanges();
        decoded.fromBytes(buf);
        assertEquals(ModelRoamingLimits.MAX_VARIABLES_PER_MODEL, decoded.getChanges().size());
    }

    private static Map<String, Double> map(Object... pairs) {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return out;
    }
}
