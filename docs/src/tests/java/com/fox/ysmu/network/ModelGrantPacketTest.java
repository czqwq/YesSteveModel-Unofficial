package com.fox.ysmu.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelAccess;
import com.fox.ysmu.network.message.S2CModelGrants;
import com.fox.ysmu.util.ModelIdUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;

/**
 * The authorization packet ({@code S2CModelGrants}, batch 4 of {@code .agent/phase53-model-authorization.md}) and the
 * client holder it feeds. Two properties are worth pinning without a game:
 * <ul>
 * <li>the payload survives a round trip, and a truncated or overstated payload is <b>dropped</b> rather than thrown -
 * a decoder that throws is a kick, not an ignore ({@code network/NetworkHandler.java:71-80}, N-10);</li>
 * <li>the lock predicate follows upstream's
 * {@code entry.authorizationRequired() && !grants.contains(...)} ({@code client/gui/PlayerModelScreen.java:255-257})
 * and answers "do not lock" while no packet has arrived, which is upstream's
 * {@code Optional.empty()} session case ({@code network/forge/ClientSessionRuntime.java:96-105}).</li>
 * </ul>
 */
class ModelGrantPacketTest {

    private static final String RESTRICTED_ID = "wine_fox/01_taisho_maid";
    private static final String GRANTED_ID = "example";

    @AfterEach
    void resetClientState() {
        ClientModelAccess.clear();
    }

    @Test
    void roundTripCarriesBothLists() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new S2CModelGrants(List.of(RESTRICTED_ID), List.of(GRANTED_ID)).toBytes(bytes);
            S2CModelGrants decoded = new S2CModelGrants();
            decoded.fromBytes(bytes);
            assertTrue(decoded.isValid());
            assertEquals(List.of(RESTRICTED_ID), decoded.getRestrictedIds());
            assertEquals(List.of(GRANTED_ID), decoded.getGrantedIds());
            assertEquals(0, bytes.readableBytes());
        } finally {
            bytes.release();
        }
    }

    @Test
    void aTruncatedPayloadIsDroppedInsteadOfThrown() {
        ByteBuf full = Unpooled.buffer();
        ByteBuf truncated = Unpooled.buffer();
        try {
            new S2CModelGrants(List.of(RESTRICTED_ID), List.of()).toBytes(full);
            // The payload ends with the (empty) grant list's four-byte count; cutting two bytes off it leaves a
            // payload whose second list cannot be read.
            truncated.writeBytes(full, 0, full.writerIndex() - 2);

            S2CModelGrants decoded = new S2CModelGrants();
            decoded.fromBytes(truncated);

            assertFalse(decoded.isValid());
            assertNull(decoded.getRestrictedIds());
            assertNull(decoded.getGrantedIds());
        } finally {
            full.release();
            truncated.release();
        }
    }

    @Test
    void anOverstatedCountIsRejectedWithoutAllocating() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            // N-06: the count comes from the peer and must be compared with readableBytes() before any allocation -
            // every entry needs at least one byte (the length varint).
            bytes.writeInt(Integer.MAX_VALUE);
            S2CModelGrants decoded = new S2CModelGrants();
            decoded.fromBytes(bytes);
            assertFalse(decoded.isValid());
            assertNull(decoded.getRestrictedIds());
            assertNull(decoded.getGrantedIds());
        } finally {
            bytes.release();
        }
    }

    @Test
    void anEmptyBufferIsDropped() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            S2CModelGrants decoded = new S2CModelGrants();
            decoded.fromBytes(bytes);
            assertFalse(decoded.isValid());
        } finally {
            bytes.release();
        }
    }

    @Test
    void aFullStateReplacesThePreviousOne() {
        ByteBuf bytes = Unpooled.buffer();
        try {
            new S2CModelGrants(List.of("first"), List.of()).toBytes(bytes);
            S2CModelGrants decoded = new S2CModelGrants();
            decoded.fromBytes(bytes);
            ClientModelAccess.update(decoded.getRestrictedIds(), decoded.getGrantedIds());
            assertTrue(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", "first")));
        } finally {
            bytes.release();
        }

        // Upstream's authority delta is "clear, then add every grant" (SessionCollectionPublication.java:184-196):
        // the second full state is adopted wholesale, so a model that stopped being restricted is unlocked again.
        ClientModelAccess.update(List.of(), List.of());
        assertFalse(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", "first")));
        assertFalse(ClientModelAccess.isEmpty());
    }

    @Test
    void onlyARestrictedAndUngrantedModelNeedsAuth() {
        ClientModelAccess.update(List.of(RESTRICTED_ID), List.of(GRANTED_ID));

        assertFalse(ClientModelAccess.isEmpty());
        assertTrue(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", RESTRICTED_ID)));
        assertTrue(ClientModelAccess.isGranted(new ResourceLocation("ysmu", GRANTED_ID)));
        assertTrue(ClientModelAccess.needsAuth(new ResourceLocation("ysmu", RESTRICTED_ID)));
        // Public (not in the restricted set) and granted models are both selectable.
        assertFalse(ClientModelAccess.needsAuth(new ResourceLocation("ysmu", GRANTED_ID)));
        assertFalse(ClientModelAccess.needsAuth(new ResourceLocation("ysmu", "public_model")));
        assertFalse(ClientModelAccess.needsAuth(null));
        assertFalse(ClientModelAccess.requiresAuth(null));
    }

    @Test
    void aRestrictedModelThatIsAlsoGrantedIsNotLocked() {
        ClientModelAccess.update(List.of(RESTRICTED_ID), List.of(RESTRICTED_ID));
        assertTrue(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", RESTRICTED_ID)));
        assertFalse(ClientModelAccess.needsAuth(new ResourceLocation("ysmu", RESTRICTED_ID)));
    }

    @Test
    void aClientThatNeverReceivedThePacketLocksNothing() {
        // No update at all, or a disconnect clear: upstream's "no session" case, where the caller has no grant set to
        // test against and therefore draws no lock.
        ClientModelAccess.clear();
        assertTrue(ClientModelAccess.isEmpty());
        assertFalse(ClientModelAccess.needsAuth(new ResourceLocation("ysmu", RESTRICTED_ID)));
        assertFalse(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", RESTRICTED_ID)));
        assertFalse(ClientModelAccess.isGranted(new ResourceLocation("ysmu", RESTRICTED_ID)));
    }

    @Test
    void identityMatchesTheEncodedInternalIdSpelling() {
        // A model directory whose relative path needs encoding is registered under "_name_<hex>" (misc/2_steve ->
        // ysmu:_name_6d6973632f325f7374657665). The wire carries that internal id; the GUI asks with the registered
        // ysmu:<internal id> ResourceLocation, whose path is the same string.
        String internalId = ModelIdUtil.getInternalModelId("test/restricted");
        assertTrue(internalId.startsWith("_name_"));

        ClientModelAccess.update(List.of(internalId), List.of());
        assertTrue(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", internalId)));
        assertTrue(ClientModelAccess.needsAuth(new ResourceLocation("ysmu", internalId)));
        // getInternalModelId is not idempotent for an already-encoded id (ModelIdUtil.java:68-71), which is why the
        // raw path is compared first; asking with the disk name instead still resolves through the normalization.
        assertTrue(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", "test/restricted")));
    }

    @Test
    void identityNormalizesADiskNameLikeTheServerDoes() {
        // ServerModelManager.accessOf:592-602 falls back to ModelIdUtil.getInternalModelId, so a caller that still
        // holds the on-disk spelling gets the same answer.
        String internalId = ModelIdUtil.getInternalModelId("Test Model");
        ClientModelAccess.update(List.of(internalId), List.of());
        assertTrue(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", "Test Model")));
        assertFalse(ClientModelAccess.requiresAuth(new ResourceLocation("ysmu", "another model")));
    }
}
