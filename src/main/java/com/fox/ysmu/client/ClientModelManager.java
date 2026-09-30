package com.fox.ysmu.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.animation.molang.PackUserFunctions;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.resource.YsmControllerResources;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncModelFiles;
import com.fox.ysmu.util.GsonHelper;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.Pair;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.FormatVersion;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

public class ClientModelManager {

    public static Map<ResourceLocation, List<ResourceLocation>> MODELS = Maps.newHashMap();
    public static Map<ResourceLocation, Pair<Double, Double>> SCALE_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, List<IChatComponent>> EXTRA_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, String[]> EXTRA_ANIMATION_NAME = Maps.newHashMap();
    public static AnimationFile DEFAULT_ANIMATION_FILE = new AnimationFile();
    public static List<String> CACHE_MD5 = Collections.synchronizedList(Lists.newArrayList());
    public static volatile byte[] PASSWORD;
    public static volatile UUID PASSWORD_UUID;
    /** Layout versions already reported, so an unsupported geometry format is logged once instead of 150 times. */
    private static final Map<String, Boolean> WARNED_UNSUPPORTED_LAYOUTS = Maps.newConcurrentMap();
    /**
     * Content signature of every model currently installed by {@link #registerAll}, so the same model arriving on
     * both sync channels is registered once instead of twice. Cleared together with the runtime caches it
     * describes.
     */
    private static final Map<ResourceLocation, String> REGISTERED_MODEL_CONTENT = Maps.newConcurrentMap();
    /** Bedrock cache files are named after the hex MD5 of their bytes, which is always 32 characters long. */
    private static final int MD5_HEX_LENGTH = 32;
    private static final int CACHE_HASH_BUFFER_BYTES = 8192;
    /** How long a model sync waits for the client world before it gives up instead of blocking a thread. */
    private static final long SYNC_WORLD_WAIT_MILLIS = 30000L;
    /** Bumped on every sync request and on disconnect, so a superseded verification never sends its reply. */
    private static volatile long SYNC_GENERATION;
    /**
     * The reply waiting for {@code theWorld} to exist. Only the client thread writes it; a disconnect or a newer
     * sync request clears it, which cancels the waiting reply.
     */
    private static volatile PendingModelSync pendingModelSync;
    /** At most one retry hop off the client thread, so a stalled client tick cannot pile up submissions. */
    private static final AtomicBoolean SYNC_RETRY_QUEUED = new AtomicBoolean();

    public static void registerAll(ModelData data) {
        ResourceLocation modelId = getModelId(data);
        // N-02: the legacy channel and the OpenYSM channel both end here, and a second registration of identical
        // content rebuilds every GeoModel and re-uploads every texture for no gain.
        String signature = contentSignature(data);
        if (signature != null && signature.equals(REGISTERED_MODEL_CONTENT.get(modelId))) {
            ysmu.LOG.info("YSM client skipping model {}: identical content is already registered", modelId);
            return;
        }
        ysmu.LOG.info(
            "YSM client registering model {}: geometry={}, textures={}, animations={}",
            modelId,
            data.getModel().keySet(),
            data.getTexture().keySet(),
            data.getAnimation().keySet());
        registerGeometry(modelId, data);
        registerModelTextures(modelId, data);
        try {
            registerModelAnimations(modelId, data);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register animations for model {}", modelId, e);
        }
        ysmu.LOG.info(
            "YSM client registered model {}: totalModelEntries={}, textureCount={}",
            modelId,
            MODELS.size(),
            MODELS.get(modelId) == null ? 0 : MODELS.get(modelId).size());
        if (signature != null) {
            REGISTERED_MODEL_CONTENT.put(modelId, signature);
        }
    }

    private static ResourceLocation getModelId(ModelData data) {
        return new ResourceLocation(ysmu.MODID, data.getModelId());
    }

    /**
     * Content fingerprint of everything {@link #registerAll} installs for a model: the geometry, texture and
     * animation bytes plus the digest the server published as this model's metadata.
     * <p>
     * Returns {@code null} when no digest is available, in which case the caller registers unconditionally rather
     * than assuming that two models are equal.
     */
    @Nullable
    private static String contentSignature(ModelData data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            updateDigest(digest, data.getModel());
            updateDigest(digest, data.getTexture());
            updateDigest(digest, data.getAnimation());
            ServerModelInfo info = data.getInfo();
            String md5 = info == null ? null : info.getMd5();
            if (md5 != null) {
                digest.update(md5.getBytes(StandardCharsets.UTF_8));
            }
            return Md5Utils.toHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            ysmu.LOG.warn("Cannot fingerprint YSM model {} for duplicate detection", data.getModelId(), e);
            return null;
        }
    }

    /**
     * Hashes the files of one {@code name -> bytes} map in name order. The name and the length are part of the
     * digest so that two different maps cannot hash to the same value.
     */
    private static void updateDigest(MessageDigest digest, Map<String, byte[]> files) {
        if (files == null) {
            digest.update((byte) -2);
            return;
        }
        List<String> names = new ArrayList<>(files.keySet());
        Collections.sort(names);
        for (String name : names) {
            byte[] bytes = files.get(name);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (bytes == null) {
                digest.update((byte) -1);
                continue;
            }
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }

    private static void registerGeometry(ResourceLocation modelId, ModelData data) {
        registerGeo(modelId, data.getModel());
    }

    private static void registerModelAnimations(ResourceLocation modelId, ModelData data) {
        registerAnimations(ModelIdUtil.getMainId(modelId), data.getAnimation());
    }

    private static void registerModelTextures(ResourceLocation modelId, ModelData data) {
        registerTexture(modelId, data.getTexture());
    }

    public static void registerGeo(ResourceLocation id, Map<String, byte[]> mapData) {
        for (String name : mapData.keySet()) {
            byte[] data = mapData.get(name);
            registerGeo(ModelIdUtil.getSubModelId(id, name), data);
        }
    }

    private static void registerGeo(ResourceLocation id, byte[] data) {
        Map<ResourceLocation, GeoModel> geoModels = GeckoLibCache.getInstance()
            .getGeoModels();
        try {
            // 直接从字节数组解析JSON，而不是尝试反序列化对象
            String modelJson = new String(data, StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);

            // MON-01: whether this port attempts a layout is the engine's decision, not a second table kept here.
            // The previous local whitelist is what made every 1.8.0/1.10.0 model disappear.
            FormatVersion formatVersion = rawModel.getFormatVersion();
            if (formatVersion != null && formatVersion.isSupportedLayout()) {
                RawGeometryTree rawGeometryTree = RawGeometryTree.parseHierarchy(rawModel);
                GeoModel geoModel = GeoBuilder.getGeoBuilder(id.getResourceDomain())
                    .constructGeoModel(rawGeometryTree);
                SCALE_INFO.put(
                    id,
                    Pair.of(rawGeometryTree.properties.getHeightScale(), rawGeometryTree.properties.getWidthScale()));
                ExtraInfo extraInfo = rawGeometryTree.properties.getExtraInfo();
                EXTRA_INFO.put(id, handleExtraInfo(id, extraInfo));
                // The information screen wants the same block, keyed by the model rather than by its main/arm
                // sub-model. The richer author list (with avatars) arrives separately over the OpenYSM channel.
                ClientModelMetadataRegistry.acceptExtraInfo(ModelIdUtil.getParentModelId(id), extraInfo);
                if (extraInfo != null && extraInfo.getExtraAnimationNames() != null
                    && extraInfo.getExtraAnimationNames().length > 0) {
                    EXTRA_ANIMATION_NAME.put(id, extraInfo.getExtraAnimationNames());
                }
                geoModels.put(id, geoModel);
                ysmu.LOG.info(
                    "YSM client registered geometry {}: heightScale={}, widthScale={}, hasExtraInfo={}, extraAnimationNames={}",
                    id,
                    rawGeometryTree.properties.getHeightScale(),
                    rawGeometryTree.properties.getWidthScale(),
                    extraInfo != null,
                    extraInfo != null && extraInfo.getExtraAnimationNames() != null
                        ? extraInfo.getExtraAnimationNames().length
                        : 0);
            } else {
                warnUnsupportedLayout(id, formatVersion);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register geometry " + id, e);
            e.printStackTrace();
        }
    }

    /**
     * Reports a geometry file this port cannot build, once per declared version.
     * <p>
     * The version gate itself delegates to {@link FormatVersion#isSupportedLayout()}, so this only fires for a
     * file that declares no usable {@code format_version} at all: a value the enum cannot represent is already
     * rejected inside the engine's converter and logged by the caller's catch block.
     * <p>
     * Note that the gate only decides whether a layout is attempted. Whether the engine's geometry tree can parse
     * a newer Bedrock layout is a separate engine-side question, so passing this gate is not a promise that such a
     * model builds - it only stops this port from silently skipping a compatible one.
     */
    private static void warnUnsupportedLayout(ResourceLocation id, @Nullable FormatVersion version) {
        String key = String.valueOf(version);
        if (WARNED_UNSUPPORTED_LAYOUTS.putIfAbsent(key, Boolean.TRUE) == null) {
            ysmu.LOG.warn(
                "YSM client cannot build geometry {}: the engine does not support its declared layout ({}); skipping",
                id,
                version == null ? "no format_version" : version);
        }
    }

    public static void registerTexture(ResourceLocation id, Map<String, byte[]> mapData) {
        List<ResourceLocation> textures = Lists.newArrayList();
        for (String name : mapData.keySet()) {
            ResourceLocation textureId = ModelIdUtil.getSubModelId(id, name);
            textures.add(textureId);
        }
        MODELS.put(id, textures);
        for (String name : mapData.keySet()) {
            byte[] data = mapData.get(name);
            ResourceLocation textureId = ModelIdUtil.getSubModelId(id, name);
            try {
                registerTexture(textureId, data);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to register texture {} for model {}", textureId, id, e);
            }
        }
        ysmu.LOG.info("YSM client registered textures for {}: {}", id, textures);
    }

    private static void registerTexture(ResourceLocation id, byte[] data) {
        TextureManager textureManager = Minecraft.getMinecraft()
            .getTextureManager();
        // R-01: loadTexture only replaces the map entry, so registering an id twice would leak the GL texture the
        // previous OuterFileTexture uploaded and nothing would ever free it. deleteTexture is a no-op for an
        // unknown id, which keeps this idempotent and safe as the single registration point. Both calls touch GL
        // state and therefore have to run on the client thread, as every registerAll caller does.
        textureManager.deleteTexture(id);
        textureManager.loadTexture(id, new OuterFileTexture(data));
    }

    private static void registerAnimations(ResourceLocation id, Map<String, byte[]> mapData) {
        Map<ResourceLocation, AnimationFile> animations = GeckoLibCache.getInstance()
            .getAnimations();
        AnimationFile main = new AnimationFile();
        Map<String, byte[]> controllerFiles = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            if (isControllerResource(entry.getKey(), entry.getValue())) {
                controllerFiles.put(entry.getKey(), entry.getValue());
                continue;
            }
            try {
                AnimationFile other = getAnimationFile(new String(entry.getValue(), StandardCharsets.UTF_8));
                mergeAnimationFile(main, other);
            } catch (Exception e) {
                ysmu.LOG.warn(
                    "Failed to parse animation file {} for model {}: {}: {}",
                    entry.getKey(),
                    id,
                    e.getClass().getSimpleName(),
                    StringUtils.defaultString(e.getMessage()));
            }
        }
        DEFAULT_ANIMATION_FILE.animations.forEach((name, action) -> {
            if (!main.animations.containsKey(name)) {
                main.putAnimation(name, action);
            }
        });
        main.animations.forEach((name, animation) -> {
            try {
                ConditionManager.addTest(id, name);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to register animation condition {} for model {}", name, id, e);
            }
        });
        animations.put(id, main);
        OpenYsmAnimationControllerRegistry.register(id, controllerFiles.values());
        ysmu.LOG.info("YSM client registered animations for {}: count={}", id, main.animations.size());
    }

    private static boolean isControllerResource(String name, byte[] data) {
        if (YsmControllerResources.isControllerResource(name)) {
            return true;
        }
        if (data == null || data.length == 0) {
            return false;
        }
        try {
            JsonObject jsonObject = GsonHelper.fromJson(
                ysmu.GSON,
                new String(data, StandardCharsets.UTF_8),
                JsonObject.class);
            return jsonObject != null && jsonObject.has("animation_controllers");
        } catch (Exception e) {
            return false;
        }
    }

    private static AnimationFile getAnimationFile(String file) {
        AnimationFile animationFile = new AnimationFile();
        MolangParser parser = GeckoLibCache.getInstance().parser;
        JsonObject jsonObject = GsonHelper.fromJson(ysmu.GSON, file, JsonObject.class);
        if (jsonObject != null) {
            for (Map.Entry<String, JsonElement> entry : JsonAnimationUtils.getAnimations(jsonObject)) {
                String animationName = entry.getKey();
                Animation animation;
                try {
                    animation = JsonAnimationUtils
                        .deserializeJsonToAnimation(JsonAnimationUtils.getAnimation(jsonObject, animationName), parser);
                    animationFile.putAnimation(animationName, animation);
                } catch (Exception e) {
                    ysmu.LOG.warn(
                        "Failed to register animation {}: {}: {}",
                        animationName,
                        e.getClass().getSimpleName(),
                        StringUtils.defaultString(e.getMessage()));
                }
            }
        }
        return animationFile;
    }

    private static AnimationFile mergeAnimationFile(AnimationFile main, AnimationFile other) {
        other.animations.forEach(main::putAnimation);
        return main;
    }

    public static void loadDefaultModel() {
        try {
            ModelData data = FolderFormat.getModelData(ServerModelManager.CUSTOM, "default");
            data.getAnimation()
                .forEach((name, bytes) -> {
                    AnimationFile animationFile = getAnimationFile(new String(bytes, StandardCharsets.UTF_8));
                    mergeAnimationFile(DEFAULT_ANIMATION_FILE, animationFile);
                });
            ClientModelManager.registerAll(data);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to load default model", e);
            e.printStackTrace();
        }
    }

    /**
     * Asks the server which of its models this client already has cached.
     * <p>
     * CUI-01: the packet handler that reaches this method runs on a network thread, while {@code theWorld}, the
     * runtime caches and the cache directory all belong to the client thread, so the work is scheduled there.
     */
    public static void sendSyncModelMessage() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.func_152345_ab()) {
            // Already on the client thread; func_152344_a would run this inline anyway.
            ClientModelManager.prepareModelSync();
            return;
        }
        mc.func_152344_a(ClientModelManager::prepareModelSync);
    }

    /** Client thread: resets the sync state, clears the runtime caches, then reads the cache directory. */
    private static void prepareModelSync() {
        long generation = SYNC_GENERATION + 1L;
        SYNC_GENERATION = generation;
        pendingModelSync = null;
        ysmu.LOG.info(
            "YSM client starting model sync: currentModels={}, rememberedCachedModels={}",
            MODELS.size(),
            CACHE_MD5.size());
        PASSWORD = null;
        PASSWORD_UUID = null;
        clearCachedModelMd5();
        // CUI-01: the cache reset and the cache-directory listing run in this one client task, in this order, so
        // the reply describes the state the client is actually in when it is built.
        clearRuntimeModelCaches();
        String[] candidates = getMd5Info();
        long deadline = System.currentTimeMillis() + SYNC_WORLD_WAIT_MILLIS;
        // M-10: a name is only a claim until the bytes behind it are checked, and that is file IO. Verify on the
        // model pool - without ever waiting there - and reschedule the reply instead of blocking the client thread.
        Runnable verification = () -> verifyCachedModelFiles(candidates, generation, deadline);
        ThreadTools.THREAD_POOL.submit(verification);
    }

    /** Pool thread: keeps the names whose file content really hashes to them, then reschedules the reply. */
    private static void verifyCachedModelFiles(String[] candidates, long generation, long deadline) {
        List<String> verified = new ArrayList<>(candidates.length);
        for (String name : candidates) {
            if (generation != SYNC_GENERATION || Thread.currentThread().isInterrupted()) {
                return;
            }
            File file = ServerModelManager.CACHE_CLIENT.resolve(name)
                .toFile();
            if (contentMatchesFileName(file, name)) {
                verified.add(name);
            } else {
                // A crash or a disconnect can leave a half-written file under a full MD5 name; reporting it would
                // make the server grant a load request that can never succeed, and it would never resend the file.
                ysmu.LOG.warn("YSM client discarding stale model cache file {}: its content is not {}", file, name);
            }
        }
        String[] md5Info = verified.toArray(new String[0]);
        // Called from the pool, so func_152344_a queues and the client thread runs it on its next tick.
        Minecraft.getMinecraft()
            .func_152344_a(() -> armModelSyncReply(md5Info, generation, deadline));
    }

    /** Client thread: makes this reply the current one and tries to send it. */
    private static void armModelSyncReply(String[] md5Info, long generation, long deadline) {
        if (generation != SYNC_GENERATION) {
            return;
        }
        PendingModelSync send = new PendingModelSync(md5Info, deadline);
        pendingModelSync = send;
        trySendModelSync(send);
    }

    /** Client thread: sends the reply once a world exists, or re-arms the bounded, tick-driven wait below. */
    private static void trySendModelSync(PendingModelSync send) {
        if (send == null || send != pendingModelSync) {
            return;
        }
        if (Minecraft.getMinecraft().theWorld != null) {
            pendingModelSync = null;
            ysmu.LOG.info(
                "YSM client sending model sync md5 list: count={}, values={}",
                send.md5Info.length,
                Lists.newArrayList(send.md5Info));
            NetworkHandler.CHANNEL.sendToServer(new SyncModelFiles(send.md5Info));
            return;
        }
        // N-12/M-06: this used to sleep on the model pool until the world was ready. Waiting is now free instead of
        // occupying the pool: each attempt is one client tick, and it is abandoned after a deadline.
        if (Thread.currentThread().isInterrupted()) {
            pendingModelSync = null;
            ysmu.LOG.warn("YSM client model sync cancelled before a client world existed");
            return;
        }
        if (System.currentTimeMillis() >= send.deadline) {
            pendingModelSync = null;
            ysmu.LOG.warn(
                "YSM client gave up sending its model sync after {} ms without a client world",
                SYNC_WORLD_WAIT_MILLIS);
            return;
        }
        scheduleModelSyncRetry();
    }

    /**
     * Re-arms {@link #trySendModelSync} once through the client task queue. {@code func_152344_a} runs its runnable
     * inline when the caller already is the client thread, so this hop goes through the model pool: the queued
     * runnable is then executed by the client thread on its next tick, which is what makes the wait tick-driven.
     */
    private static void scheduleModelSyncRetry() {
        if (!SYNC_RETRY_QUEUED.compareAndSet(false, true)) {
            return;
        }
        Runnable hop = () -> Minecraft.getMinecraft()
            .func_152344_a(() -> {
                SYNC_RETRY_QUEUED.set(false);
                // Re-read the field: a newer sync request may have replaced the reply this hop was armed for.
                PendingModelSync pending = pendingModelSync;
                if (pending != null) {
                    trySendModelSync(pending);
                }
            });
        ThreadTools.THREAD_POOL.submit(hop);
    }

    /** A model sync reply that is waiting for {@code theWorld}, with the point in time it stops being worth sending. */
    private static final class PendingModelSync {

        private final String[] md5Info;
        private final long deadline;

        private PendingModelSync(String[] md5Info, long deadline) {
            this.md5Info = md5Info;
            this.deadline = deadline;
        }
    }

    /**
     * Names of the cache files this client may claim to have.
     * <p>
     * N-07/M-10: a cache file is named after the uppercase hex MD5 that {@code ModelCacheWriter} and
     * {@code SendModelFile} computed over its bytes, so anything that is not 32 hex characters - above all a
     * {@code <md5>.part} file left behind by an interrupted chunked upload - is not a cache hit and must not be
     * reported as one. Whether the content really matches the name is checked separately, off the client thread.
     */
    private static String[] getMd5Info() {
        File cacheDir = ServerModelManager.CACHE_CLIENT.toFile();
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            ysmu.LOG.warn("Failed to create YSM client model cache directory: {}", cacheDir);
            return new String[0];
        }
        Collection<File> files = FileUtils.listFiles(cacheDir, FileFileFilter.FILE, null);
        List<String> output = new ArrayList<>(files.size());
        List<String> ignored = Lists.newArrayList();
        for (File file : files) {
            String name = file.getName();
            if (isCacheMd5Name(name)) {
                output.add(name);
            } else {
                ignored.add(name);
            }
        }
        if (!ignored.isEmpty()) {
            ysmu.LOG.info(
                "YSM client ignoring {} file(s) in the model cache that are not named after an MD5: {}",
                ignored.size(),
                ignored);
        }
        return output.toArray(new String[0]);
    }

    /** Whether a file name is a plain 32 character hex MD5, the only shape a cache file can legitimately have. */
    private static boolean isCacheMd5Name(String name) {
        if (name == null || name.length() != MD5_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < MD5_HEX_LENGTH; i++) {
            char c = name.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Whether the file's bytes hash to the name they are stored under, which is the criterion the writer used. */
    private static boolean contentMatchesFileName(File file, String name) {
        if (!file.isFile() || file.length() == 0L) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream input = FileUtils.openInputStream(file)) {
                byte[] buffer = new byte[CACHE_HASH_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return Md5Utils.toHexString(digest.digest())
                .equalsIgnoreCase(name);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to hash YSM client model cache file " + file, e);
            return false;
        }
    }

    private static void clearRuntimeModelCaches() {
        ysmu.LOG.info(
            "YSM client clearing runtime model caches: models={}, scales={}, extraInfo={}, extraAnimations={}",
            MODELS.size(),
            SCALE_INFO.size(),
            EXTRA_INFO.size(),
            EXTRA_ANIMATION_NAME.size());
        MODELS.clear();
        SCALE_INFO.clear();
        EXTRA_INFO.clear();
        EXTRA_ANIMATION_NAME.clear();
        // The content signatures describe exactly the models being dropped here, so they go with them; otherwise a
        // model that is still identical to the previous server's copy would be skipped into an empty cache.
        REGISTERED_MODEL_CONTENT.clear();
        // The pack table belongs to the server whose models are being dropped; keeping it would label the next
        // server's folders with the previous server's names and cover art.
        ClientPackRegistry.clear();
        ClientModelMetadataRegistry.clear();
        PackUserFunctions.clear();
        // The preview animations belong to the models being dropped; keeping them would make the selection GUI ask the
        // next server's models for animations they never declared.
        com.fox.ysmu.client.gui.ModelPreviewRegistry.clear();
        com.fox.ysmu.client.gui.ModelPreviewAnimationState.resetAll();
        ConditionManager.clear();
        OpenYsmAnimationControllerRegistry.clear();
        MolangPhysicsRuntime.clear();
        MolangInstructionExecutor.clearWarnings();
    }

    public static void rememberCachedModel(String md5) {
        synchronized (CACHE_MD5) {
            if (!CACHE_MD5.contains(md5)) {
                CACHE_MD5.add(md5);
            }
        }
    }

    public static List<String> getCachedModelSnapshot() {
        synchronized (CACHE_MD5) {
            return new ArrayList<>(CACHE_MD5);
        }
    }

    public static void clearConnectionState() {
        PASSWORD = null;
        PASSWORD_UUID = null;
        // A reply still waiting for a world belongs to the connection that just ended, so drop it and invalidate the
        // in-flight verification with it.
        SYNC_GENERATION++;
        pendingModelSync = null;
        clearCachedModelMd5();
        OpenYsmModelSyncClient.clearConnectionState();
    }

    private static void clearCachedModelMd5() {
        synchronized (CACHE_MD5) {
            CACHE_MD5.clear();
        }
    }

    private static byte[] getBytes(Path root, String fileName) throws IOException {
        return FileUtils.readFileToByteArray(
            root.resolve(fileName)
                .toFile());
    }

    @Nullable
    private static List<IChatComponent> handleExtraInfo(ResourceLocation id, @Nullable ExtraInfo extraInfo) {
        if (extraInfo == null || StringUtils.isBlank(extraInfo.getName())) {
            return null;
        }
        List<IChatComponent> component = Lists.newArrayList();
        IChatComponent textComponent = new ChatComponentText(extraInfo.getName());
        textComponent.getChatStyle()
            .setColor(EnumChatFormatting.GOLD);
        component.add(textComponent);
        if (StringUtils.isNoneBlank(extraInfo.getTips())) {
            String[] split = extraInfo.getTips()
                .split("\n");
            for (String s : split) {
                IChatComponent lineComponent = new ChatComponentText(s);
                lineComponent.getChatStyle()
                    .setColor(EnumChatFormatting.GRAY);
                component.add(lineComponent);
            }
        }
        if (extraInfo.getAuthors() != null && extraInfo.getAuthors().length != 0) {
            component.add(
                new ChatComponentTranslation(
                    "gui.yes_steve_model.model.authors",
                    StringUtils.join(extraInfo.getAuthors(), "丨")));
        }
        if (StringUtils.isNoneBlank(extraInfo.getLicense())) {
            component.add(new ChatComponentTranslation("gui.yes_steve_model.model.license", extraInfo.getLicense()));
        }
        return component;
    }
}
