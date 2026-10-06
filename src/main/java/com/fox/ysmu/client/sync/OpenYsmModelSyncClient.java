package com.fox.ysmu.client.sync;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.gui.ModelConfigRegistry;
import com.fox.ysmu.client.gui.ModelPreviewRegistry;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.ClientPackInfo;
import com.fox.ysmu.client.ClientPackRegistry;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SCompleteFeedback17;
import com.fox.ysmu.network.message.C2SModelSyncPayload17;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.util.ModelIdUtil;
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
    /**
     * 行 46 SHOW_MODEL_LOAD_CHAT：本轮同步的聊天统计。{@code syncStartMs} 在 packet03 索引到达时打点，
     * {@code syncTotalModels} 是本轮索引条目数（packet03 的 serverModelCount）。参考分支把这两个值放在
     * {@code ClientModelManager}（{@code SYNC_TOTAL} / 会话级 {@code syncStartTimeMs}）；它们只被这条聊天消息
     * 读，所以本仓按最小改动留在本类，不动 ClientModelManager。
     */
    private static volatile long syncStartMs;
    private static volatile int syncTotalModels;
    /** N-11: session of the round in progress, learned from the server payload. */
    private static volatile int currentSessionId;
    /** N-11: session that already ended, used to drop payloads that arrive after the round finished. */
    private static volatile int lastCompletedSessionId;
    private static byte[] key1;
    private static byte[] lastKey;
    // volatile: serverKey/clientKey are written by the packet-processing (pool) thread and read back from other
    // threads - clientKey by readClientCacheToClearBytes (ClientModelManager.loadRawModelFromCache, playback
    // thread), the two folder/key fields by resetConnectionState on the disconnect thread. The publish path itself
    // reads neither any more: it carries the values in a CacheContext. Same field modifiers as the reference branch.
    private static volatile byte[] serverKey;
    private static volatile byte[] clientKey;
    private static volatile String currentCacheFolderName;

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
        syncStartMs = 0L;
        syncTotalModels = 0;
        currentSessionId = 0;
        key1 = null;
        lastKey = null;
        serverKey = null;
        // NOTE: clientKey is intentionally KEPT — the model's own sound bytes are
        // re-read from the encrypted client cache after the sync teardown (which also
        // calls resetConnectionState) and are decrypted with it
        // (ClientModelManager.loadRawModelFromCache → readClientCacheToClearBytes).
        // It is only cleared on disconnect (clearConnectionState). Same split as the
        // reference branch's resetConnectionState/clearConnectionState.
        currentCacheFolderName = null;
        SERVER_MODELS.clear();
    }

    public static synchronized void clearConnectionState() {
        resetConnectionState();
        clientKey = null;
    }

    /**
     * 行 45 {@code SHOW_LOADING_PROGRESS}: the read-only view the HUD progress overlay draws
     * ({@code com.fox.ysmu.client.ClientEventHandler#renderSyncProgress}).
     * <p>
     * The reference keeps this state on {@code ClientModelManager} ({@code SYNC_IN_PROGRESS} / {@code SYNC_TOTAL} /
     * {@code SYNC_LOADED} / {@code SYNC_CURRENT_MODEL}, reference {@code client/ClientModelManager.java:85-93},
     * written from reference {@code client/sync/OpenYsmModelSyncClient.java:236-239}), but this port's counters
     * already live in this class ({@code syncStep} / {@code syncTotalModels} / {@code loadedModelsCount}, above).
     * Copying the reference's declaration site would produce a second copy of the same state, so only these
     * read-only outlets were added and the sync logic itself is untouched.
     * <p>
     * "A round is in flight" is {@code syncStep >= 2}: {@code syncStep} starts at 1, becomes 2 in
     * {@code handlePacket01}, becomes 3 in {@code sendPacket04}, and {@code sendComplete} ->
     * {@code resetConnectionState} puts it back to 1 - so 1 is idle (including just-finished) and 2/3 are a round in
     * flight. It is <b>not</b> a percentage; the overlay uses it only as the gate, exactly as the reference uses
     * {@code SYNC_IN_PROGRESS}.
     */
    public static boolean isSyncInProgress() {
        return syncStep >= 2;
    }

    /**
     * 行 45: the model count this round's index declared ({@code handlePacket03}'s {@code serverModelCount}), or 0
     * while it is still unknown - the state the reference spells {@code SYNC_TOTAL = -1} and the overlay draws as an
     * indeterminate bar. It is the total, not the remaining count: {@code pendingModelsCount} only covers the cache
     * misses still downloading, while the cache hits were already registered inside {@code handlePacket03}.
     */
    public static int syncTotal() {
        return syncTotalModels;
    }

    /**
     * 行 45: models registered by this round so far (cache hits in {@code handlePacket03} plus downloads completed
     * in {@code handlePacket05}), i.e. the same counter {@code sendComplete} reports as {@code loaded}.
     * <p>
     * A model the client cannot bridge ({@code parseAndRegisterModel} answers false) is not counted, so the bar can
     * disappear below 100% - the reference's {@code SYNC_LOADED} has the same property, and the completion message
     * is where the failure count is reported (row 46).
     * <p>
     * <b>What is deliberately absent:</b> the reference's fourth item, the name of the model being loaded
     * ({@code SYNC_CURRENT_MODEL}). This port has no such field - {@code handlePacket03}/{@code handlePacket05} only
     * put {@code context.modelId} in log lines - and the owner's ruling was to <b>not</b> add tracking for it, so the
     * overlay degrades to the counter without a name (see {@code ClientEventHandler#renderSyncProgress}).
     */
    public static int syncLoaded() {
        return loadedModelsCount;
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
        // 行 46：完成时聊天统计的两个输入（参考分支 :236 / :245 在同一步骤打点）。
        syncTotalModels = serverModelCount;
        syncStartMs = System.currentTimeMillis();

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
                if (parseAndRegisterModel(clearBytes, context, currentCacheContext())) {
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
            if (parseAndRegisterModel(clearBytes, context, currentCacheContext())) {
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

    private static boolean parseAndRegisterModel(byte[] clearBytes, ServerModelContext context,
        CacheContext cacheContext) {
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
            // The id the playback path asks under (CustomPlayerEntity.getMainModel()), and therefore the id a
            // model sound has to be registered against.
            ResourceLocation mainModelId = ModelIdUtil.getMainId(modelId);
            List<RawYsmModel.RawMetadata.Author> authors = raw.metadata == null
                ? Collections.emptyList() : new ArrayList<>(raw.metadata.authors);
            // The pack's sounds/*.ogg bytes are in this payload too, and they are the only place a model sound can
            // come from in 1.7.10 - there is no resource pack declaring them. The parsed model is still in hand
            // here, so its sound *names* are filed now (name → this main model id); the bytes are not kept, because
            // `raw` is a local that is gone the moment this method returns. YSMSoundManager re-reads and re-decrypts
            // them from the encrypted client cache on first play (ClientModelManager.loadRawModelFromCache). This is
            // the reference branch's ClientModelManager.registerExtraWheel → YSMSoundManager.registerModelSounds
            // call, moved here because this port has no method that sees a parsed RawYsmModel at registration.
            com.fox.ysmu.client.audio.YSMSoundManager
                .registerModelSounds(mainModelId, raw);
            long generation = ClientModelManager.currentGeneration();
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    // This payload was parsed off the client thread, which takes long enough to outlive a disconnect
                    // and the join that follows. Registering after that would put the previous server's model into the
                    // new server's catalogue, where a build requested from it carries the new generation and passes
                    // every guard, so the generation it was parsed on is checked first.
                    if (generation != ClientModelManager.currentGeneration()) {
                        return;
                    }
                    ClientModelManager.registerAll(data);
                    ClientModelMetadataRegistry.acceptStructuredAuthors(modelId, authors);
                    // Record the encrypted client cache file this payload was parsed from (relative to
                    // CACHE_CLIENT), so the model's sound bytes stay reachable after `raw` is gone:
                    // YSMSoundManager re-decrypts them from it on first play
                    // (ClientModelManager.loadRawModelFromCache). The name is the one handlePacket03/
                    // handlePacket05 wrote this payload's file under. The reference branch keeps the same
                    // record (rememberOpenYsmModelCache) for its lazy geo/anim/texture reload; here it is
                    // what makes the sound path work at all.
                    String cacheFileName = YSMClientCache
                        .generateCacheFileName(context.hash1, context.hash2, cacheContext.contextClientKey);
                    if (cacheFileName != null) {
                        String folder = cacheContext.cacheFolderName == null ? "0" : cacheContext.cacheFolderName;
                        ClientModelManager.rememberOpenYsmModelCache(modelId, folder + "/" + cacheFileName);
                    }
                    // The pack's functions/*.molang bodies travel in this same payload; register them so the model's
                    // own scripts (fn.<name>) can be evaluated while it renders.
                    PackUserFunctions.register(modelId, raw.functionFiles);
                    // The same bodies are also what AnimationManager reads as *animation control scripts*
                    // (Config.MOLANG_CONTROL_SCRIPTS -> AnimationManager.applyControlScript ->
                    // AnimationControlScripts.evaluate -> MolangScriptRegistry.controlScript), and until this
                    // call MolangScriptRegistry had no writer at all in src/main, so hasScripts(animId) was
                    // always false and that path was never reached. This is the reference branch's routing,
                    // copied from ClientModelManager.collectMolangScript
                    // (tmp/YesSteveModel-Unofficial-perf-previewUI .../client/ClientModelManager.java:967-994):
                    // it decides the bucket by file name. `<anything>@player_ctrl_<slot>.molang` is the control
                    // script for that slot; `name@<event>` is the function `name` subscribed to that event;
                    // anything else is a plain `fn.<name>` function. The reference registers under the *main*
                    // model id, which is the id CustomPlayerEntity.getAnimation() answers with - and therefore
                    // the id AnimationManager.applyControlScript looks the registry up by.
                    Map<String, String> scriptFunctions = new LinkedHashMap<>();
                    Map<String, List<String>> scriptEvents = new LinkedHashMap<>();
                    Map<String, String> scriptControls = new LinkedHashMap<>();
                    for (Map.Entry<String, RawYsmModel.RawDataFile> functionFile : raw.functionFiles.entrySet()) {
                        RawYsmModel.RawDataFile functionData = functionFile.getValue();
                        if (functionData == null || functionData.data == null) {
                            continue;
                        }
                        String controlSlot = MolangScriptRegistry.controlSlotOf(functionFile.getKey());
                        if (controlSlot != null) {
                            // @player_ctrl_<槽位>.molang：动画控制脚本，按槽位登记（每帧由求值器执行）。
                            String controlBody = MolangScriptRegistry.decode(functionData.data);
                            if (controlBody != null && !controlBody.trim()
                                .isEmpty()) {
                                scriptControls.put(controlSlot, controlBody);
                            }
                            continue;
                        }
                        String functionName = MolangScriptRegistry.functionNameOf(functionFile.getKey());
                        if (functionName == null) {
                            continue;
                        }
                        String functionBody = MolangScriptRegistry.decode(functionData.data);
                        if (functionBody == null || functionBody.trim()
                            .isEmpty()) {
                            continue;
                        }
                        scriptFunctions.put(functionName, functionBody);
                        String event = MolangScriptRegistry.eventOf(functionFile.getKey());
                        if (event != null) {
                            scriptEvents.computeIfAbsent(event, key -> new ArrayList<>())
                                .add(functionName);
                        }
                    }
                    MolangScriptRegistry.register(mainModelId, scriptFunctions, scriptEvents, scriptControls);
                    // The model's preview_animation (the animation the selection GUI plays) and its
                    // disable_preview_rotation flag are in this payload too; without them the GUI could only show a
                    // frozen pose framed with the wrong transform.
                    ModelPreviewRegistry.accept(
                        modelId,
                        raw.properties == null ? null : raw.properties.previewAnimation,
                        raw.properties != null && raw.properties.disablePreviewRotation);
                    // The model's own settings panel (extra_animation_buttons[].config_forms, each form naming a
                    // v.roaming.* variable) travels in the same payload. Remembering it here is what lets the
                    // selection screen offer that panel; nothing else on the client reads it.
                    ModelConfigRegistry.accept(
                        modelId,
                        raw.properties == null ? null : raw.properties.extraAnimationButtons,
                        raw.properties == null ? null : raw.properties.extraAnimationClassifies);
                    // The roaming-variable namespace of this model is derived from its content hash, which only this
                    // payload carries; remember it so the settings panel and the preview address the same namespace
                    // the server will use.
                    ClientRoamingKeys.accept(modelId, raw.properties == null ? null : raw.properties.sha256);
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

    /** 发布点这一次同步的目录名 + 客户端密钥，**按值**捕获：包处理路径在设置这两个静态字段之后
     *  立即调用它，所以取到的就是这批字节所属那一轮的值；{@code registerLocalModel} 用自己那一族
     *  值构造 {@link CacheContext}。见该类注释。 */
    private static CacheContext currentCacheContext() {
        return new CacheContext(currentCacheFolderName, clientKey);
    }

    /** Decrypts a client cache file written by this session's OpenYSM sync into
     *  clear bytes using the session client key. Returns null on failure. Used by
     *  {@code ClientModelManager.loadRawModelFromCache} to re-read a model's own
     *  sound bytes on first play, after the parsed payload that carried them is gone.
     *  <p>Falls back to the client's own local key so locally-registered models
     *  ({@link LocalModelLoader}) stay readable even after a real server sync
     *  overwrote {@link #clientKey}: those files live under folder {@code "0"} and are
     *  written with the key {@link #localClientKey()} derives from the local
     *  server_index key ({@link #registerLocalModel}). A file the session key cannot
     *  open makes {@link YsmCrypt#read} throw, which is what selects the fallback -
     *  the same rule the reference branch uses
     *  ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/sync/OpenYsmModelSyncClient.java:1094-1111}). */
    public static byte[] readClientCacheToClearBytes(byte[] cacheBytes) {
        if (cacheBytes == null) return null;
        if (clientKey != null) {
            try {
                return YsmCrypt.read(cacheBytes, clientKey);
            } catch (Exception ignored) {
                // fall through to the local key below
            }
        }
        byte[] localKey = localClientKey();
        if (localKey != null && localKey != clientKey) {
            try {
                return YsmCrypt.read(cacheBytes, localKey);
            } catch (Exception ignored) {
                // fall through: neither key opens this file
            }
        }
        return null;
    }

    /** The client's own local cache key (derived from the local server_index key),
     *  used for local model fallback registration. */
    private static byte[] localClientKey() {
        byte[] serverKey = ServerModelManager.OPEN_YSM_SERVER_KEY;
        if (serverKey == null || serverKey.length != 56) return null;
        return OpenYsmModelSyncServer.createClientCacheKey(serverKey);
    }

    /**
     * 注册单个本地模型（仅自己可见），由 {@link LocalModelLoader} 调用。
     * 复用真实同步的「服务端缓存 → transcode → 写客户端缓存 → 解密 → 解析 → apply」
     * 路径，不经过网络。synchronized 与真实同步（processServerData 等）共用类锁，
     * 避免本地注册与包处理交错；本地注册期间真实同步会等待，反之亦然（每个模型粒度）。
     *
     * <p>本地注册取自己那一族状态**值**：目录 {@code "0"}、由本地 server_index 密钥派生的
     * {@link #localClientKey()}、以及 {@link ServerModelManager#OPEN_YSM_SERVER_KEY} 作服务端密钥。
     * 它们随 {@link CacheContext} 传给解析路径，**不改写** {@code clientKey}/
     * {@code currentCacheFolderName}：这两个字段是「最近一次同步发布的会话上下文」，懒加载重解密
     * （{@link #readClientCacheToClearBytes}）还要用；而模型真正的注册是在
     * {@code Minecraft.func_152344_a} 里稍后于客户端线程执行的，类锁覆盖不到那一刻，临时改写
     * 只会把本地目录/密钥混进真实同步的 {@code CACHED_MODEL_MD5} 记录里（跨同步后再读静态字段
     * 更是已被 reset 成 null）。</p>
     *
     * @return 模型主体注册成功（不可桥接的模型也会注册投射物/轮盘，返回 false）
     */
    public static synchronized boolean registerLocalModel(OpenYsmSyncInfo info, byte[] serverCacheBytes) {
        if (info == null || serverCacheBytes == null || serverCacheBytes.length == 0) {
            return false;
        }
        byte[] localKey = localClientKey();
        if (localKey == null) {
            return false;
        }
        byte[] localServerKey = ServerModelManager.OPEN_YSM_SERVER_KEY;
        if (localServerKey == null || localServerKey.length != 56) {
            return false;
        }
        final CacheContext ctx = new CacheContext("0", localKey);
        try {
            byte[] clientCacheBytes = YsmCrypt.transcodeServerDataToClientCache(
                serverCacheBytes, localServerKey, ctx.contextClientKey, info.getHash1(), info.getHash2());
            File outFile = new File(ctx.cacheDir(), ctx.cacheFileName(info.getHash1(), info.getHash2()));
            FileUtils.writeByteArrayToFile(outFile, clientCacheBytes);
            byte[] clearBytes = YsmCrypt.read(clientCacheBytes, ctx.contextClientKey);
            ServerModelContext context = new ServerModelContext(
                info.getHash1(), info.getHash2(), info.getModelId(),
                info.isCustomSkinModel() ? 1 : 0, info.getFormat());
            return parseAndRegisterModel(clearBytes, context, ctx);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register local model {}: {}", info.getModelId(), e.getMessage());
            return false;
        }
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
            // 行 46 SHOW_MODEL_LOAD_CHAT（参考分支 client/sync/OpenYsmModelSyncClient.java:539-559）：完成时在
            // 聊天栏报告本次加载的模型数量与耗时；开关关闭时只在确实有模型没加载出来时才报告。
            // 两处与参考分支不同，都由本仓结构决定：
            // 1) 成功数用本类已有的 loadedModelsCount，而不是参考分支的 ClientModelManager.MODELS.size()——
            //    参考分支发完成信号前会等 apply 管线排空（isApplyPipelineDrained），本仓在 pendingModelsCount
            //    归零时立刻发，此刻 func_152344_a 里的 registerAll 可能还没跑，MODELS.size() 会少报；
            // 2) 总数用 packet03 的 serverModelCount（本仓协议头部没有参考分支的 progressTotal 字段），因此
            //    failed = total - loaded 在完成时刻恰好是解析失败 / 无法桥接 / 服务端跳过的模型数。
            long elapsed = syncStartMs == 0L ? 0L : System.currentTimeMillis() - syncStartMs;
            int total = syncTotalModels;
            int loaded = loadedModelsCount;
            int failed = Math.max(0, total - loaded);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                // 1.7.10 的 ChatComponentTranslation 只认 %s（ChatComponentTranslation.java:121-124 对其它的
                // 格式串直接抛 ChatComponentTranslationFormatException），所以耗时先格式化成字符串，语言串用 %s
                // 而不是参考分支的 %d——本仓既有做法见 YsmCommand.java:697-701。
                if (Config.SHOW_MODEL_LOAD_CHAT) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "message.yes_steve_model.sync.complete",
                        String.format(Locale.ROOT, "%.2f", (double) elapsed)));
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "message.yes_steve_model.sync.complete_models",
                        String.valueOf(loaded),
                        String.valueOf(failed),
                        String.valueOf(total)));
                } else if (failed > 0) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "message.yes_steve_model.sync.complete_models",
                        String.valueOf(loaded),
                        String.valueOf(failed),
                        String.valueOf(total)));
                }
            }
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

    /**
     * 一次同步 / 一次本地注册的**不可变**缓存上下文：客户端缓存目录名 + 这批字节所用的客户端密钥。
     *
     * <p>为什么必须随工作项传递而不是在发布点读静态字段：{@link #parseAndRegisterModel} 把模型真正的
     * 注册交给 {@code Minecraft.func_152344_a}，稍后在客户端线程上执行；而目录名/密钥的所有者可能已经
     * 不是这一轮了 —— 同一轮里 {@code sendComplete} → {@code resetConnectionState} 会把
     * {@code currentCacheFolderName} 置 null，下一轮同步会换掉 {@code clientKey}，本地注册则用的是
     * 另一族值（目录 {@code "0"} 与 {@link #localClientKey()}）。读静态字段就会把别人的目录/密钥写进
     * 模型自己的 {@code CACHED_MODEL_MD5} 记录，懒加载（{@link #readClientCacheToClearBytes}）随后
     * 找不到这个文件。</p>
     *
     * <p>较参考分支的 {@code CacheContext}
     * （{@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/sync/OpenYsmModelSyncClient.java:76-107}）
     * 少两个字段：这一树的发布闸门是 {@code ClientModelManager.currentGeneration()} 在
     * {@code parseAndRegisterModel} 内部就地采样的（{@code :417-426}），服务端密钥也由
     * {@link #registerLocalModel} 在类锁内直接读取，所以这里只需要目录名与客户端密钥。</p>
     */
    private static final class CacheContext {

        final String cacheFolderName;
        final byte[] contextClientKey;

        CacheContext(String cacheFolderName, byte[] contextClientKey) {
            this.cacheFolderName = cacheFolderName;
            this.contextClientKey = contextClientKey;
        }

        File cacheDir() {
            return ServerModelManager.CACHE_CLIENT.resolve(cacheFolderName == null ? "0" : cacheFolderName)
                .toFile();
        }

        String cacheFileName(long hash1, long hash2) {
            return YSMClientCache.generateCacheFileName(hash1, hash2, contextClientKey);
        }
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
