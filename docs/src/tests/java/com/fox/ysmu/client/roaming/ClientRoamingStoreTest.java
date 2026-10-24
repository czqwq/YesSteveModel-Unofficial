package com.fox.ysmu.client.roaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A roaming variable is a named float that belongs to one model of one player (see
 * {@code .agent/phase15-roaming-variables.md}). The store decides what a model reads and what the server is asked to
 * keep, so its bounds and its "a full state replaces the namespace" rule are worth pinning down.
 */
class ClientRoamingStoreTest {

    private static final UUID LOCAL = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final int MODEL = 0x01234567;

    @BeforeEach
    void reset() {
        ClientRoamingStore.clear();
    }

    @Test
    void localChangesAreVisibleAndReportedOnce() {
        ClientRoamingStore.setLocal(LOCAL, MODEL, "v.roaming.player_size", 2.5d);

        assertEquals(2.5d, ClientRoamingStore.valuesFor(LOCAL, MODEL).get("v.roaming.player_size"));
        assertEquals(2.5d, ClientRoamingStore.localValuesFor(LOCAL, MODEL).get("v.roaming.player_size"));
        assertTrue(ClientRoamingStore.hasPending());

        Map<Integer, Map<String, Double>> pending = ClientRoamingStore.takePending();
        assertEquals(1, pending.size());
        assertEquals(2.5d, pending.get(MODEL).get("v.roaming.player_size"));
        // Taken once, so the next flush does not report the same change again.
        assertFalse(ClientRoamingStore.hasPending());
        assertTrue(ClientRoamingStore.takePending().isEmpty());
        // The value itself survives the flush.
        assertEquals(2.5d, ClientRoamingStore.valuesFor(LOCAL, MODEL).get("v.roaming.player_size"));
    }

    @Test
    void aFullStateReplacesTheNamespaceInsteadOfMerging() {
        ClientRoamingStore.acceptState(OTHER, MODEL, map("v.roaming.a", 1d, "v.roaming.b", 2d));
        assertEquals(2, ClientRoamingStore.valuesFor(OTHER, MODEL).size());

        // Upstream's rule: a full state that omits a variable means the variable is gone.
        ClientRoamingStore.acceptState(OTHER, MODEL, map("v.roaming.a", 3d));
        assertEquals(1, ClientRoamingStore.valuesFor(OTHER, MODEL).size());
        assertEquals(3d, ClientRoamingStore.valuesFor(OTHER, MODEL).get("v.roaming.a"));

        // An empty state drops the namespace entirely rather than leaving the previous values behind.
        ClientRoamingStore.acceptState(OTHER, MODEL, null);
        assertTrue(ClientRoamingStore.valuesFor(OTHER, MODEL).isEmpty());
    }

    @Test
    void playersAndModelsDoNotLeakIntoEachOther() {
        ClientRoamingStore.acceptState(OTHER, MODEL, map("v.roaming.a", 1d));
        ClientRoamingStore.setLocal(LOCAL, MODEL, "v.roaming.a", 9d);

        assertEquals(1d, ClientRoamingStore.valuesFor(OTHER, MODEL).get("v.roaming.a"));
        assertEquals(9d, ClientRoamingStore.valuesFor(LOCAL, MODEL).get("v.roaming.a"));
        // A different model of the same player is a different namespace.
        assertTrue(ClientRoamingStore.valuesFor(LOCAL, MODEL + 1).isEmpty());
        // An unknown player reads as empty rather than throwing.
        assertTrue(ClientRoamingStore.valuesFor(null, MODEL).isEmpty());

        ClientRoamingStore.clear(OTHER);
        assertTrue(ClientRoamingStore.valuesFor(OTHER, MODEL).isEmpty());
        assertEquals(9d, ClientRoamingStore.valuesFor(LOCAL, MODEL).get("v.roaming.a"));
    }

    @Test
    void rejectsNamesAndValuesThatWouldPoisonTheSave() {
        ClientRoamingStore.setLocal(LOCAL, MODEL, "player_size", 1d);
        assertTrue(
            ClientRoamingStore.valuesFor(LOCAL, MODEL)
                .isEmpty(),
            "a bare name is normalised elsewhere, not silently accepted here");

        // The bound is on the full name, which is upstream's 32 character suffix plus the "v.roaming." prefix.
        StringBuilder longName = new StringBuilder("v.roaming.");
        while (longName.length() <= ClientRoamingStore.MAX_VARIABLE_NAME_LENGTH) {
            longName.append('x');
        }
        ClientRoamingStore.setLocal(LOCAL, MODEL, longName.toString(), 1d);
        assertTrue(ClientRoamingStore.valuesFor(LOCAL, MODEL).isEmpty(), "a name over the bound is rejected");

        ClientRoamingStore.setLocal(LOCAL, MODEL, "v.roaming.nan", Double.NaN);
        ClientRoamingStore.setLocal(LOCAL, MODEL, "v.roaming.inf", Double.POSITIVE_INFINITY);
        assertTrue(ClientRoamingStore.valuesFor(LOCAL, MODEL).isEmpty(), "a non-finite value is rejected");

        // A name exactly at the bound is accepted, so the limit cannot be off by one.
        StringBuilder atBound = new StringBuilder("v.roaming.");
        while (atBound.length() < ClientRoamingStore.MAX_VARIABLE_NAME_LENGTH) {
            atBound.append('y');
        }
        assertEquals(ClientRoamingStore.MAX_VARIABLE_NAME_LENGTH, atBound.length());
        ClientRoamingStore.setLocal(LOCAL, MODEL, atBound.toString(), 1d);
        assertEquals(1, ClientRoamingStore.valuesFor(LOCAL, MODEL).size());
        ClientRoamingStore.clear(LOCAL);

        // The same rules apply to what arrives from the server.
        ClientRoamingStore.acceptState(
            OTHER,
            MODEL,
            map("player_size", 1d, "v.roaming.ok", 1d, "v.roaming.bad", Double.NaN));
        assertEquals(1, ClientRoamingStore.valuesFor(OTHER, MODEL).size());
        assertEquals(1d, ClientRoamingStore.valuesFor(OTHER, MODEL).get("v.roaming.ok"));
    }

    @Test
    void aModelCannotGrowPastTheVariableBound() {        for (int i = 0; i < ClientRoamingStore.MAX_VARIABLES_PER_MODEL + 10; i++) {
            ClientRoamingStore.setLocal(LOCAL, MODEL, "v.roaming.v" + i, i);
        }
        assertEquals(
            ClientRoamingStore.MAX_VARIABLES_PER_MODEL,
            ClientRoamingStore.valuesFor(LOCAL, MODEL).size());

        // Overwriting an existing name is still allowed at the bound.
        ClientRoamingStore.setLocal(LOCAL, MODEL, "v.roaming.v0", 123d);
        assertEquals(123d, ClientRoamingStore.valuesFor(LOCAL, MODEL).get("v.roaming.v0"));
    }

    @Test
    void aPlayerCannotGrowPastTheModelBoundFromTheServer() {
        // The values arrive from a server, so an endless stream of unknown model keys must not grow the map.
        for (int i = 0; i < ClientRoamingStore.MAX_MODELS_PER_PLAYER + 10; i++) {
            ClientRoamingStore.acceptState(OTHER, MODEL + i, map("v.roaming.v", (double) i));
        }
        int accepted = 0;
        for (int i = 0; i < ClientRoamingStore.MAX_MODELS_PER_PLAYER + 10; i++) {
            if (!ClientRoamingStore.valuesFor(OTHER, MODEL + i)
                .isEmpty()) {
                accepted++;
            }
        }
        assertEquals(ClientRoamingStore.MAX_MODELS_PER_PLAYER, accepted);

        // Updating a model that is already known still works at the bound.
        ClientRoamingStore.acceptState(OTHER, MODEL, map("v.roaming.v", 99d));
        assertEquals(99d, ClientRoamingStore.valuesFor(OTHER, MODEL).get("v.roaming.v"));
    }

    private static Map<String, Double> map(Object... pairs) {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return out;
    }
}
