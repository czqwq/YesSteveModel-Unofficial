package com.fox.ysmu.client.sync;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.ClientPackInfo;
import com.fox.ysmu.client.ClientPackRegistry;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SCompleteFeedback17;
import com.fox.ysmu.network.message.C2SModelSyncPayload17;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.Unpooled;
import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YSMClientCache;
import rip.ysm.security.YsmCrypt;

@SideOnly(Side.CLIENT)
public final class OpenYsmModelSyncClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** N-11: matches {@code OpenYsmModelSyncServer.SKIP_LENGTH}: "the server has no cache file for this model". */
    private static final int SKIP_LENGTH = -1;
    /** N-05: packet-04 header budget: garbage header (<=65) + type byte + count varInt + session id + slack. */
    private static final int PACKET04_HEADER_BUDGET = 96;
    /** N-06: sane upper bound for one model cache file, so a bogus declared size cannot exhaust the heap. */
    private static final int MAX_MODEL_CACHE_BYTES = 64 * 1024 * 1024;
    /** N-06: sane upper bound for the model count advertised in the server index. */
    private static final int MAX_SERVER_MODELS = 100_000;
    /** N-06: sane upper bound for the pack count advertised in the server index. */
    private static final int MAX_SERVER_PACKS = 4096;
    /** N-06: a pack cover is a tile image; anything far larger than a texture atlas is not one. */
    private static final int MAX_PACK_ICON_BYTES = 1024 * 1024;
    private static final int MAX_PACK_LANGUAGES = 64;
    private static final int MAX_PACK_TRANSLATIONS = 512;

    private static final Map<UUID, ServerModelContext> SERVER_MODELS = Maps.newConcurrentMap();

    private static volatile int syncStep = 1;
    private static volatile int pendingModelsCount;
    private static volatile int loadedModelsCount;
    private static volatile int downloadedModelsCount;
    private static volatile int cacheHitCount;
    /** N-11: session of the round in progress, learned from the server payload. */
    private static volatile int currentSessionId;
    /** N-11: session that already ended, used to drop payloads that arrive after the round finished. */
    private static volatile int lastCompletedSessionId;
    private static byte[] key1;
    private static byte[] lastKey;
    private static byte[] serverKey;
    private static byte[] clientKey;
    private static String currentCacheFolderName;

    private OpenYsmModelSyncClient() {}

    public static void handlePayload(int sessionId, byte[] data) {
        ThreadTools.THREAD_POOL.submit(() -> processServerData(sessionId, data));
    }

    public static synchronized void resetConnectionState() {
        syncStep = 1;
        pendingModelsCount = 0;
        loadedModelsCount = 0;
        downloadedModelsCount = 0;
        cacheHitCount = 0;
        currentSessionId = 0;
        key1 = null;
        lastKey = null;
        serverKey = null;
        clientKey = null;
        currentCacheFolderName = null;
        SERVER_MODELS.clear();
    }

    public static synchronized void clearConnectionState() {
        resetConnectionState();
    }

    private static synchronized void processServerData(int sessionId, byte[] packetBytes) {
        if (packetBytes == null || packetBytes.length == 0) {
            resetConnectionState();
            return;
        }
        if (isStaleSession(sessionId)) {
            // N-11: a payload that belongs to a session which already ended (or to a different, still running one)
            // is dropped before decryption. Decrypting it would fail with the wrong key and the failure handler
            // would send a FAILED feedback that tears down the round that is actually in progress.
            return;
        }

        try {
            if (syncStep == 1) {
                byte[] decrypted = YsmCrypt.decrypt(packetBytes, YsmCrypt.publicKey);
                if (decrypted != null) {
                    handlePacket01(decrypted);
                    currentSessionId = sessionId;
                }
            } else if (syncStep == 2) {
                byte[] decrypted = YsmCrypt.decrypt(packetBytes, lastKey);
                if (decrypted != null) {
                    try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
                        handlePacket03(buf);
                    }
                }
            } else if (syncStep == 3) {
                byte[] decrypted = YsmCrypt.decrypt(packetBytes, key1);
                if (decrypted != null) {
                    try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
                        handlePacket05(buf);
                    }
                }
            }
        } catch (Exception e) {
            sendComplete(C2SCompleteFeedback17.STATUS_FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
            ysmu.LOG.warn("OpenYSM client sync error at step " + syncStep, e);
        }
    }

    private static boolean isStaleSession(int sessionId) {
        if (sessionId == 0) {
            // 0 means "unknown" (a peer that does not stamp its payloads); accept it rather than dropping data.
            return false;
        }
        if (sessionId == lastCompletedSessionId) {
            return true;
        }
        return currentSessionId != 0 && sessionId != currentSessionId;
    }

    private static void handlePacket01(byte[] decryptedBuffer) throws Exception {
        resetConnectionState();
        if (decryptedBuffer.length < 56) {
            return;
        }
        key1 = Arrays.copyOfRange(decryptedBuffer, decryptedBuffer.length - 56, decryptedBuffer.length);
        syncStep = 2;

        byte[] garbage = randomGarbage();
        try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
            out.writeGarbageHeader(garbage.length, garbage);
            out.writeByte((byte) 0x02);
            out.writeByte((byte) 0x00);
            YsmCrypt.EncryptedPacket encrypted = YsmCrypt.encrypt(out.toArray(), key1, true);
            lastKey = encrypted.nextKey();
            sendPayload(encrypted.data());
        }
    }

    private static void handlePacket03(YSMByteBuf buf) throws Exception {
        buf.skipGarbageHeader();
        int type = buf.readVarInt();
        if (type != 3) {
            return;
        }

        long folderHash = buf.readVarLong();
        currentCacheFolderName = Long.toHexString(folderHash);
        serverKey = new byte[56];
        buf.getRawBuf()
            .readBytes(serverKey);
        clientKey = new byte[56];
        buf.getRawBuf()
            .readBytes(clientKey);

        SERVER_MODELS.clear();
        File cacheDir = getCacheDir();
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            ysmu.LOG.warn("Failed to create OpenYSM client cache directory {}", cacheDir);
        }

        Map<UUID, File> localCacheMap = YSMClientCache.buildCacheIndex(cacheDir, clientKey);
        List<ModelHash> modelsToRequest = new ArrayList<>();
        int serverModelCount = buf.readVarInt();
        // N-06: the advertised count comes from the peer; bound it before allocating or looping.
        if (serverModelCount < 0 || serverModelCount > MAX_SERVER_MODELS) {
            throw new IllegalStateException("Invalid OpenYSM model index size: " + serverModelCount);
        }
        ysmu.LOG.info("OpenYSM client received sync index: models={}", serverModelCount);

        for (int i = 0; i < serverModelCount; i++) {
            long hash1 = buf.readVarLong();
            long hash2 = buf.readVarLong();
            String modelId = buf.readString();
            int customSkinModel = buf.readVarInt();
            int version = buf.readVarInt();
            ServerModelContext context = new ServerModelContext(hash1, hash2, modelId, customSkinModel, version);
            SERVER_MODELS.put(context.uuid, context);

            File cachedFile = localCacheMap.get(context.uuid);
            if (YSMClientCache.verifyFileContent(cachedFile, hash1, hash2)) {
                cacheHitCount++;
                byte[] cachedBytes = FileUtils.readFileToByteArray(cachedFile);
                byte[] clearBytes = YsmCrypt.read(cachedBytes, clientKey);
                if (parseAndRegisterModel(clearBytes, context)) {
                    loadedModelsCount++;
                }
                ysmu.LOG.info("OpenYSM client cache hit for {} ({})", modelId, context.uuid);
            } else {
                modelsToRequest.add(new ModelHash(hash1, hash2));
                ysmu.LOG.info("OpenYSM client cache miss for {} ({})", modelId, context.uuid);
            }
        }

        List<ClientPackInfo> packs = readPackData(buf);
        sendPacket04(modelsToRequest);
        // Pack covers become GL textures, so publishing the packs has to happen on the client thread. Sending the
        // model requests first keeps the download going while that is queued.
        Minecraft.getMinecraft()
            .func_152344_a(() -> ClientPackRegistry.accept(packs));
    }

    private static void handlePacket05(YSMByteBuf buf) throws Exception {
        buf.skipGarbageHeader();
        int type = buf.readVarInt();
        if (type != 5) {
            return;
        }

        long hash1 = buf.readVarLong();
        long hash2 = buf.readVarLong();
        UUID uuid = new UUID(hash1, hash2);
        ServerModelContext context = SERVER_MODELS.get(uuid);
        if (context == null) {
            ysmu.LOG.warn("OpenYSM client received unexpected chunk for {}", uuid);
            return;
        }

        int totalSize = buf.readVarInt();
        int chunkOffset = buf.readVarInt();
        int chunkLength = buf.readVarInt();
        if (chunkLength == SKIP_LENGTH) {
            // N-11: the server has no cache file for this model. It counts as neither loaded nor downloaded, but
            // the pending counter has to move so the round can finish.
            ysmu.LOG.warn("OpenYSM server skipped model {} ({})", context.modelId, uuid);
            pendingModelsCount--;
            if (pendingModelsCount <= 0) {
                sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
            }
            return;
        }
        // N-06: validate the peer-supplied sizes before allocating or seeking; a malformed chunk is dropped
        // instead of throwing (the round is not aborted because of one bad packet).
        if (totalSize < 0 || chunkOffset < 0 || chunkLength < 0
            || totalSize > MAX_MODEL_CACHE_BYTES
            || chunkOffset > totalSize - chunkLength
            || chunkLength > buf.getRawBuf()
                .readableBytes()) {
            ysmu.LOG.warn(
                "Ignoring malformed OpenYSM model chunk for {}: total={}, offset={}, length={}",
                context.modelId,
                totalSize,
                chunkOffset,
                chunkLength);
            return;
        }
        if (context.fileBuffer == null) {
            context.fileBuffer = new byte[totalSize];
            context.totalSize = totalSize;
            context.bytesReceived = 0;
        }
        buf.getRawBuf()
            .readBytes(context.fileBuffer, chunkOffset, chunkLength);
        context.bytesReceived += chunkLength;

        if (context.bytesReceived >= context.totalSize) {
            byte[] clientCacheBytes = YsmCrypt
                .transcodeServerDataToClientCache(context.fileBuffer, serverKey, clientKey, hash1, hash2);
            File outFile = new File(getCacheDir(), YSMClientCache.generateCacheFileName(hash1, hash2, clientKey));
            FileUtils.writeByteArrayToFile(outFile, clientCacheBytes);
            context.fileBuffer = null;

            byte[] clearBytes = YsmCrypt.read(clientCacheBytes, clientKey);
            if (parseAndRegisterModel(clearBytes, context)) {
                loadedModelsCount++;
                downloadedModelsCount++;
            }
            pendingModelsCount--;
            ysmu.LOG.info("OpenYSM client downloaded and cached {} to {}", context.modelId, outFile);
            if (pendingModelsCount <= 0) {
                sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
            }
        }
    }

    /**
     * N-05: the request list grows by two var-longs per model and one packet may not exceed the C2S budget
     * ({@link C2SModelSyncPayload17#MAX_SYNC_PAYLOAD_BYTES}), so it is split into as many packet-04 messages as
     * needed. The server accepts every one of them (see {@code handlePayloadAsync}).
     */
    private static List<List<ModelHash>> splitRequests(List<ModelHash> modelsToRequest) {
        List<List<ModelHash>> batches = new ArrayList<>();
        List<ModelHash> current = new ArrayList<>();
        int size = PACKET04_HEADER_BUDGET;
        for (ModelHash hash : modelsToRequest) {
            int entrySize = varLongSize(hash.hash1) + varLongSize(hash.hash2);
            if (!current.isEmpty() && size + entrySize > C2SModelSyncPayload17.MAX_SYNC_PAYLOAD_BYTES) {
                batches.add(current);
                current = new ArrayList<>();
                size = PACKET04_HEADER_BUDGET;
            }
            current.add(hash);
            size += entrySize;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private static int varLongSize(long value) {
        int size = 1;
        while ((value & -128L) != 0L) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    private static void sendPacket04(List<ModelHash> modelsToRequest) throws Exception {
        syncStep = 3;
        pendingModelsCount = modelsToRequest.size();
        for (List<ModelHash> batch : splitRequests(modelsToRequest)) {
            sendPacket04Batch(batch);
        }

        if (pendingModelsCount == 0) {
            sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
        }
    }

    private static void sendPacket04Batch(List<ModelHash> batch) throws Exception {
        byte[] garbage = randomGarbage();
        try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
            out.writeGarbageHeader(garbage.length, garbage);
            out.writeByte((byte) 0x04);
            out.writeVarInt(batch.size());
            for (ModelHash hash : batch) {
                out.writeVarLong(hash.hash1);
                out.writeVarLong(hash.hash2);
            }
            sendPayload(
                YsmCrypt.encrypt(out.toArray(), key1, false)
                    .data());
        }
    }

    private static boolean parseAndRegisterModel(byte[] clearBytes, ServerModelContext context) {
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(clearBytes, 32)) {
            RawYsmModel raw = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(raw);
            raw.modelId = context.modelId;
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                ysmu.LOG.warn("OpenYSM synced model {} is not bridgeable to legacy ModelData", context.modelId);
                return false;
            }
            ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, context.modelId);
            // The information screen's author avatars are part of this payload; hand them over together with the
            // model so the screen has them as soon as the model itself is selectable.
            ResourceLocation modelId = new ResourceLocation(ysmu.MODID, context.modelId);
            List<RawYsmModel.RawMetadata.Author> authors = raw.metadata == null
                ? Collections.emptyList() : new ArrayList<>(raw.metadata.authors);
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    ClientModelManager.registerAll(data);
                    ClientModelMetadataRegistry.acceptStructuredAuthors(modelId, authors);
                    // The pack's functions/*.molang bodies travel in this same payload; register them so the model's
                    // own scripts (fn.<name>) can be evaluated while it renders.
                    PackUserFunctions.register(modelId, raw.functionFiles);
                });
            return true;
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to parse OpenYSM synced model " + context.modelId, e);
            return false;
        }
    }

    /**
     * Parses the model pack list that follows the model index: folder hierarchy, optional cover, optional
     * name/description, then the locale table. The layout is the one upstream's {@code ModelPackData} uses; the port
     * used to skip these bytes, which is why folder tiles could only show their directory name.
     * <p>
     * The peer's sizes are treated as untrusted (N-06): the pack count is bounded, and a cover larger than any
     * sensible tile is dropped rather than allocated.
     */
    private static List<ClientPackInfo> readPackData(YSMByteBuf buf) {
        int packCount = buf.readVarInt();
        if (packCount < 0 || packCount > MAX_SERVER_PACKS) {
            throw new IllegalStateException("Invalid OpenYSM pack index size: " + packCount);
        }
        List<ClientPackInfo> packs = new ArrayList<>(packCount);
        for (int i = 0; i < packCount; i++) {
            String hierarchy = buf.readString();

            byte[] icon = null;
            if (buf.readVarInt() != 0) {
                byte[] iconBytes = buf.readByteArray();
                // width, height, image format and one unknown flag; the client only needs the bytes.
                buf.readVarInt();
                buf.readVarInt();
                buf.readVarInt();
                buf.readVarInt();
                if (iconBytes != null && iconBytes.length <= MAX_PACK_ICON_BYTES) {
                    icon = iconBytes;
                } else {
                    ysmu.LOG.warn("Ignoring oversized cover of model pack {} ({} bytes)", hierarchy,
                        iconBytes == null ? -1 : iconBytes.length);
                }
            }

            String name = "";
            String description = "";
            if (buf.readVarInt() != 0) {
                name = buf.readString();
                description = buf.readString();
            }

            Map<String, Map<String, String>> lang = Maps.newHashMap();
            int languageCount = buf.readVarInt();
            if (languageCount < 0 || languageCount > MAX_PACK_LANGUAGES) {
                throw new IllegalStateException("Invalid OpenYSM pack language count: " + languageCount);
            }
            for (int language = 0; language < languageCount; language++) {
                String locale = buf.readString();
                int translationCount = buf.readVarInt();
                if (translationCount < 0 || translationCount > MAX_PACK_TRANSLATIONS) {
                    throw new IllegalStateException("Invalid OpenYSM pack translation count: " + translationCount);
                }
                Map<String, String> translations = Maps.newHashMap();
                for (int entry = 0; entry < translationCount; entry++) {
                    translations.put(buf.readString(), buf.readString());
                }
                lang.put(locale, translations);
            }
            packs.add(new ClientPackInfo(hierarchy, name, description, lang, icon));
        }
        if (buf.getRawBuf()
            .readableBytes() > 0) {
            // Reserved trailing varint written by the server (always 0). The legacy channel no longer needs to be
            // signalled through it: ServerModelManager sends the legacy request unconditionally.
            buf.readVarInt();
        }
        return packs;
    }

    private static File getCacheDir() {
        String folder = currentCacheFolderName == null ? "0" : currentCacheFolderName;
        return ServerModelManager.CACHE_CLIENT.resolve(folder)
            .toFile();
    }

    private static void sendPayload(byte[] payload) {
        NetworkHandler.CHANNEL.sendToServer(new C2SModelSyncPayload17(currentSessionId, payload));
    }

    private static void sendComplete(int status, String message) {
        int finishedSession = currentSessionId;
        NetworkHandler.CHANNEL.sendToServer(
            new C2SCompleteFeedback17(
                finishedSession,
                status,
                loadedModelsCount,
                downloadedModelsCount,
                cacheHitCount,
                message));
        if (status == C2SCompleteFeedback17.STATUS_SUCCESS) {
            ysmu.LOG.info(
                "OpenYSM client sync complete: loaded={}, downloaded={}, cacheHits={}, session={}",
                loadedModelsCount,
                downloadedModelsCount,
                cacheHitCount,
                finishedSession);
        }
        // Any payload of this session that is still in flight is stale from now on.
        lastCompletedSessionId = finishedSession;
        resetConnectionState();
    }

    private static byte[] randomGarbage() {
        byte[] garbage = new byte[16 + RANDOM.nextInt(48)];
        RANDOM.nextBytes(garbage);
        return garbage;
    }

    private static final class ModelHash {

        private final long hash1;
        private final long hash2;

        private ModelHash(long hash1, long hash2) {
            this.hash1 = hash1;
            this.hash2 = hash2;
        }
    }

    private static final class ServerModelContext {

        private final long hash1;
        private final long hash2;
        private final UUID uuid;
        private final String modelId;
        @SuppressWarnings("unused")
        private final int customSkinModel;
        @SuppressWarnings("unused")
        private final int version;
        private byte[] fileBuffer;
        private int totalSize;
        private int bytesReceived;

        private ServerModelContext(long hash1, long hash2, String modelId, int customSkinModel, int version) {
            this.hash1 = hash1;
            this.hash2 = hash2;
            this.uuid = new UUID(hash1, hash2);
            this.modelId = modelId;
            this.customSkinModel = customSkinModel;
            this.version = version;
        }
    }
}
