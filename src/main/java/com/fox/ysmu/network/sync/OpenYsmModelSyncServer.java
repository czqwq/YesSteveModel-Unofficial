package com.fox.ysmu.network.sync;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SCompleteFeedback17;
import com.fox.ysmu.network.message.S2CModelSyncPayload17;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import io.netty.buffer.Unpooled;
import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YsmCrypt;

public final class OpenYsmModelSyncServer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_CHUNK_SIZE = 32000;
    private static final int LOW_BANDWIDTH_CHUNK_SIZE = 8192;
    /**
     * N-11: packet 05 "the server has no cache file for this model" marker. The client decrements its pending
     * counter without counting the model as loaded or downloaded, so the round can finish instead of waiting for
     * {@link Config#PLAYER_SYNC_TIMEOUT} to expire.
     */
    static final int SKIP_LENGTH = -1;
    private static final Map<UUID, PlayerSyncState> SYNC_STATES = Maps.newConcurrentMap();

    private OpenYsmModelSyncServer() {}

    /**
     * Starts one 17-protocol round for this player.
     *
     * @return {@code true} when the round was actually started, {@code false} when nothing was sent (17 disabled or
     *         the server_index key is missing). The value is informational only: the legacy channel is driven
     *         independently by {@link ServerModelManager#sendRequestSyncModelMessage}, so a round that never starts
     *         or dies midway never costs the player his models.
     */
    public static boolean startSync(EntityPlayerMP player) {
        if (player == null || !Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) {
            return false;
        }
        byte[] serverKey = ServerModelManager.OPEN_YSM_SERVER_KEY;
        if (serverKey == null || serverKey.length != 56) {
            ysmu.LOG.warn("Skipping OpenYSM model sync because server_index key is not initialized");
            return false;
        }

        UUID playerId = player.getUniqueID();
        // N-11: one fresh session id per attempt. The client learns it from the S2C payload and echoes it in the
        // feedback, so a late payload or a late FAILED feedback from the previous attempt can no longer disturb
        // the attempt that is currently running.
        int sessionId = 1 + RANDOM.nextInt(Integer.MAX_VALUE);
        PlayerSyncState state = new PlayerSyncState(player, sessionId, createClientCacheKey(serverKey));
        state.allowedModels.addAll(snapshotAllowedModels());
        SYNC_STATES.put(playerId, state);
        ysmu.LOG.info(
            "Starting OpenYSM model sync for {}: models={}, session={}",
            player.getCommandSenderName(),
            state.allowedModels.size(),
            state.sessionId);
        ThreadTools.THREAD_POOL.submit(() -> sendPacket01(playerId, state));
        return true;
    }

    /**
     * Snapshot of the model index. {@code OPEN_YSM_SYNC_INFO} is a plain HashMap that {@code /ysm reload} clears and
     * rebuilds, and an exception thrown while iterating it would reach FML's packet handling and disconnect the
     * player, so the copy is defensive: on a concurrent reload this round simply syncs nothing.
     */
    private static List<OpenYsmSyncInfo> snapshotAllowedModels() {
        try {
            return new ArrayList<>(ServerModelManager.OPEN_YSM_SYNC_INFO.values());
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Skipping OpenYSM model sync because the server model index is being reloaded", e);
            return new ArrayList<>();
        }
    }

    public static void handlePayload(int sessionId, UUID playerId, byte[] data) {
        if (playerId == null) {
            return;
        }
        if (data == null || data.length == 0) {
            clear(playerId);
            return;
        }
        ThreadTools.THREAD_POOL.submit(() -> handlePayloadAsync(sessionId, playerId, data));
    }

    public static void complete(int sessionId, UUID playerId, int status, int loaded, int downloaded, int cacheHits,
        String message) {
        PlayerSyncState state = playerId == null ? null : SYNC_STATES.get(playerId);
        if (state == null) {
            return;
        }
        if (sessionId != 0 && sessionId != state.sessionId) {
            // N-11: stale feedback belongs to a previous attempt; it must not clear the current state.
            ysmu.LOG.info(
                "Ignoring stale OpenYSM sync feedback for {} (session {} != current {})",
                state.playerName,
                sessionId,
                state.sessionId);
            return;
        }
        // Two-argument remove: never delete a state that was replaced in the meantime.
        if (!SYNC_STATES.remove(playerId, state)) {
            return;
        }
        if (status == C2SCompleteFeedback17.STATUS_SUCCESS) {
            ysmu.LOG.info(
                "OpenYSM model sync completed for {}: loaded={}, downloaded={}, cacheHits={}, session={}",
                state.playerName,
                loaded,
                downloaded,
                cacheHits,
                sessionId);
        } else {
            ysmu.LOG.warn(
                "OpenYSM model sync failed for {}: loaded={}, downloaded={}, cacheHits={}, message={}, session={}",
                state.playerName,
                loaded,
                downloaded,
                cacheHits,
                message,
                sessionId);
        }
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            SYNC_STATES.remove(playerId);
        }
    }

    static byte[] createClientCacheKey(byte[] serverKey) {
        byte[] key = Arrays.copyOf(serverKey, 56);
        for (int i = 0; i < key.length; i++) {
            key[i] ^= (byte) (0x5A + i * 31);
        }
        return key;
    }

    private static void handlePayloadAsync(int sessionId, UUID playerId, byte[] packetBytes) {
        PlayerSyncState state = SYNC_STATES.get(playerId);
        if (state == null) {
            return;
        }
        if (isTimedOut(state)) {
            // The round died on the server side; drop it so a late payload cannot revive it. Delivery does not
            // depend on this round: the legacy channel runs independently and already carries the model set.
            clear(playerId);
            return;
        }
        if (sessionId != 0 && sessionId != state.sessionId) {
            // N-11: a payload from a previous attempt; drop it without touching the current state.
            return;
        }

        try {
            state.touch();
            if (state.step == 1) {
                handlePacket02(playerId, state, packetBytes);
            } else if (state.step == 2 || state.step == 3) {
                // N-05: the request list can be split over several packet-04 messages when it does not fit the C2S
                // payload budget, so keep accepting them after the first one moved the state to step 3.
                handlePacket04(playerId, state, packetBytes);
            }
        } catch (Exception e) {
            // This round can no longer finish; drop it. The legacy channel is unaffected.
            clear(playerId);
            ysmu.LOG.warn("OpenYSM server sync error for " + state.playerName, e);
        }
    }

    private static void handlePacket02(UUID playerId, PlayerSyncState state, byte[] packetBytes) throws Exception {
        byte[] decrypted = YsmCrypt.decrypt(packetBytes, state.key1);
        if (decrypted == null || decrypted.length < 56) {
            return;
        }

        state.clientNextKey = Arrays.copyOfRange(decrypted, decrypted.length - 56, decrypted.length);
        byte[] payload = Arrays.copyOfRange(decrypted, 0, decrypted.length - 56);
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(payload))) {
            buf.skipGarbageHeader();
            if (buf.getRawBuf().readByte() != 0x02) {
                return;
            }
        }

        state.step = 2;
        sendPacket03(playerId, state);
    }

    private static void handlePacket04(UUID playerId, PlayerSyncState state, byte[] packetBytes) throws Exception {
        byte[] decrypted = YsmCrypt.decrypt(packetBytes, state.key1);
        if (decrypted == null) {
            return;
        }

        List<OpenYsmSyncInfo> requested = new ArrayList<>();
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
            buf.skipGarbageHeader();
            if (buf.getRawBuf().readByte() != 0x04) {
                return;
            }
            int requestCount = buf.readVarInt();
            for (int i = 0; i < requestCount; i++) {
                OpenYsmSyncInfo info = findAllowedModel(state, buf.readVarLong(), buf.readVarLong());
                if (info != null) {
                    requested.add(info);
                }
            }
        }

        state.step = 3;
        sendPacket05(playerId, state, requested);
    }

    private static void sendPacket01(UUID playerId, PlayerSyncState state) {
        if (!isActive(playerId, state)) {
            return;
        }
        try {
            byte[] garbage = randomGarbage();
            try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
                out.writeGarbageHeader(garbage.length, garbage);
                out.writeByte((byte) 0x01);
                YsmCrypt.EncryptedPacket encrypted = YsmCrypt.encrypt(out.toArray(), YsmCrypt.publicKey, true);
                state.key1 = encrypted.nextKey();
                sendPayload(playerId, state, encrypted.data());
            }
        } catch (Exception e) {
            // The very first packet never made it, so this 17 round cannot start; drop it.
            clear(playerId);
            ysmu.LOG.warn("Failed to send OpenYSM packet 01 to " + state.playerName, e);
        }
    }

    private static void sendPacket03(UUID playerId, PlayerSyncState state) {
        if (!isActive(playerId, state)) {
            return;
        }
        try {
            byte[] garbage = randomGarbage();
            try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
                out.writeGarbageHeader(garbage.length, garbage);
                out.writeVarInt(3);
                out.writeVarLong(0L);
                out.getRawBuf().writeBytes(ServerModelManager.OPEN_YSM_SERVER_KEY);
                out.getRawBuf().writeBytes(state.clientCacheKey);

                out.writeVarInt(state.allowedModels.size());
                for (OpenYsmSyncInfo model : state.allowedModels) {
                    out.writeVarLong(model.getHash1());
                    out.writeVarLong(model.getHash2());
                    out.writeString(model.getModelId());
                    out.writeVarInt(model.isCustomSkinModel() ? 1 : 0);
                    out.writeVarInt(model.getFormat());
                }

                out.writeVarInt(0);
                out.writeVarInt(0);

                YsmCrypt.EncryptedPacket encrypted = YsmCrypt.encrypt(out.toArray(), state.clientNextKey, false);
                sendPayload(playerId, state, encrypted.data());
            }
        } catch (Exception e) {
            // The model index never reached the client; this 17 round is dead, so drop it.
            clear(playerId);
            ysmu.LOG.warn("Failed to send OpenYSM packet 03 to " + state.playerName, e);
        }
    }

    private static void sendPacket05(UUID playerId, PlayerSyncState state, List<OpenYsmSyncInfo> requested) {
        ThreadTools.THREAD_POOL.submit(() -> {
            int sentModels = 0;
            try {
                int chunkSize = getChunkSize();
                for (OpenYsmSyncInfo model : requested) {
                    if (!isActive(playerId, state)) {
                        return;
                    }
                    File cacheFile = ServerModelManager.CACHE_SERVER.resolve(model.getCacheFileName())
                        .toFile();
                    if (!cacheFile.isFile()) {
                        ysmu.LOG.warn("Missing OpenYSM server cache file {}", cacheFile);
                        // N-11: tell the client this model is skipped, otherwise its pending counter never reaches
                        // zero and both sides just wait for the sync timeout.
                        sendPayload(playerId, state, createPacket05Skipped(state, model));
                        continue;
                    }
                    byte[] fileData = FileUtils.readFileToByteArray(cacheFile);
                    int offset = 0;
                    while (offset < fileData.length) {
                        if (!isActive(playerId, state)) {
                            return;
                        }
                        int length = Math.min(chunkSize, fileData.length - offset);
                        byte[] packet = createPacket05(state, model, fileData, offset, length);
                        sendPayload(playerId, state, packet);
                        throttle(length);
                        offset += length;
                    }
                    sentModels++;
                }
                ysmu.LOG.info(
                    "OpenYSM server sent {} requested model cache files to {}",
                    sentModels,
                    state.playerName);
            } catch (Exception e) {
                // Chunk delivery blew up; this round cannot complete, so drop it.
                clear(playerId);
                ysmu.LOG.warn("Failed to send OpenYSM model chunks to " + state.playerName, e);
            }
        });
    }

    private static byte[] createPacket05(PlayerSyncState state, OpenYsmSyncInfo model, byte[] fileData, int offset,
        int length) throws Exception {
        byte[] garbage = randomGarbage();
        try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
            out.writeGarbageHeader(garbage.length, garbage);
            out.writeVarInt(5);
            out.writeVarLong(model.getHash1());
            out.writeVarLong(model.getHash2());
            out.writeVarInt(fileData.length);
            out.writeVarInt(offset);
            out.writeVarInt(length);
            out.getRawBuf().writeBytes(fileData, offset, length);
            return YsmCrypt.encrypt(out.toArray(), state.key1, false).data();
        }
    }

    /** N-11: packet 05 with {@link #SKIP_LENGTH} as the chunk length; no payload bytes follow. */
    private static byte[] createPacket05Skipped(PlayerSyncState state, OpenYsmSyncInfo model) throws Exception {
        byte[] garbage = randomGarbage();
        try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
            out.writeGarbageHeader(garbage.length, garbage);
            out.writeVarInt(5);
            out.writeVarLong(model.getHash1());
            out.writeVarLong(model.getHash2());
            out.writeVarInt(0);
            out.writeVarInt(0);
            out.writeVarInt(SKIP_LENGTH);
            return YsmCrypt.encrypt(out.toArray(), state.key1, false).data();
        }
    }

    private static void sendPayload(UUID playerId, PlayerSyncState state, byte[] payload) {
        if (isActive(playerId, state)) {
            NetworkHandler.sendToClientPlayer(new S2CModelSyncPayload17(state.sessionId, payload), state.player);
        }
    }

    private static OpenYsmSyncInfo findAllowedModel(PlayerSyncState state, long hash1, long hash2) {
        for (OpenYsmSyncInfo info : state.allowedModels) {
            if (info.matches(hash1, hash2)) {
                return info;
            }
        }
        return null;
    }

    private static byte[] randomGarbage() {
        byte[] garbage = new byte[16 + RANDOM.nextInt(48)];
        RANDOM.nextBytes(garbage);
        return garbage;
    }

    private static int getChunkSize() {
        return Config.LOW_BANDWIDTH_USAGE ? LOW_BANDWIDTH_CHUNK_SIZE : DEFAULT_CHUNK_SIZE;
    }

    private static void throttle(int bytes) throws InterruptedException {
        int bandwidthLimit = Config.BANDWIDTH_LIMIT;
        if (Config.LOW_BANDWIDTH_USAGE && bandwidthLimit <= 0) {
            bandwidthLimit = 64 * 1024;
        }
        if (bandwidthLimit <= 0) {
            return;
        }
        long sleepMillis = Math.max(1L, bytes * 1000L / bandwidthLimit);
        Thread.sleep(sleepMillis);
    }

    private static boolean isActive(UUID playerId, PlayerSyncState state) {
        return SYNC_STATES.get(playerId) == state;
    }

    private static boolean isTimedOut(PlayerSyncState state) {
        long timeoutMillis = Config.PLAYER_SYNC_TIMEOUT * 1000L;
        return timeoutMillis > 0L && System.currentTimeMillis() - state.lastTouched > timeoutMillis;
    }

    private static final class PlayerSyncState {

        private final EntityPlayerMP player;
        private final String playerName;
        private final int sessionId;
        private final byte[] clientCacheKey;
        private final List<OpenYsmSyncInfo> allowedModels = new ArrayList<>();
        private byte[] key1;
        private byte[] clientNextKey;
        private int step = 1;
        private long lastTouched = System.currentTimeMillis();

        private PlayerSyncState(EntityPlayerMP player, int sessionId, byte[] clientCacheKey) {
            this.player = player;
            this.playerName = player.getCommandSenderName();
            this.sessionId = sessionId;
            this.clientCacheKey = clientCacheKey;
        }

        private void touch() {
            this.lastTouched = System.currentTimeMillis();
        }
    }
}
