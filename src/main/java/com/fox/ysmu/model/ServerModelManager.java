package com.fox.ysmu.model;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.ModelCacheWriter;
import com.fox.ysmu.model.format.OpenYsmFormat;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.YsmFormat;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.RequestSyncModel;
import com.fox.ysmu.network.message.S2CVersionCheck17;
import com.fox.ysmu.util.GetJarResources;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ServerModelManager {

    /**
     * 配置相关文件夹
     */
    public static final Path FOLDER = Paths.get("config", ysmu.MODID);

    /**
     * 自定义模型所放置的文件夹
     */
    public static final Path BUILT = FOLDER.resolve("built");
    public static final Path CUSTOM = FOLDER.resolve("custom");
    public static final Path EXPORT = FOLDER.resolve("export");

    /**
     * 生成缓存文件的文件夹
     */
    public static final Path CACHE = FOLDER.resolve("cache");
    public static final Path CACHE_SERVER_INDEX_FILE = CACHE.resolve("server_index");
    public static final Path CACHE_SERVER = CACHE.resolve("server");
    /**
     * 存储密码的文件
     */
    public static final Path PASSWORD_FILE = CACHE_SERVER.resolve("PASSWORD");
    public static final Path CACHE_CLIENT = CACHE.resolve("client");
    /**
     * 模型内部 ID -> 模型额外信息缓存
     * 非安全磁盘名称会先编码为内部 ID，再写入此缓存。
     * 可以方便的通过此缓存，来判断客户端发来的 MD5 在不在服务端
     * 从而将服务器文件发送给玩家
     * 还可以获取其他服务端模型信息
     *
     * S-01：这两个表在 reload 期间被重建，同时会被网络线程(收包/发包)读取，
     * 所以必须用并发容器；{@code Maps.newHashMap()} 在并发 put/iterate 下会
     * 抛 ConcurrentModificationException 或读到半成品。原先还有一个"只写不读"的
     * raw 模型表，已按 M-23 删除。
     */
    public static final Map<String, ServerModelInfo> CACHE_NAME_INFO = new ConcurrentHashMap<>();
    public static final Map<String, OpenYsmSyncInfo> OPEN_YSM_SYNC_INFO = new ConcurrentHashMap<>();
    public static volatile byte[] OPEN_YSM_SERVER_KEY;

    /**
     * N-02:等待客户端回应 {@code C2SVersionCheck17} 的宽限期(秒)。超过它仍未见 17 通道启动,
     * 就把客户端当成不支持 17 并补发 legacy 同步;上限受 {@link Config#PLAYER_SYNC_TIMEOUT} 约束。
     */
    private static final int HANDSHAKE_REPLY_GRACE_SECONDS = 10;

    /** N-02:玩家 UUID -> 待兜底的 legacy 同步登记。 */
    private static final Map<UUID, PendingLegacyFallback> PENDING_SYNC_FALLBACKS = new ConcurrentHashMap<>();

    /** N-02:兜底定时器，首次使用时惰性创建（见 {@link #fallbackScheduler()}）。 */
    private static volatile ScheduledExecutorService fallbackScheduler;

    /**
     * Whether the server has a renderable model under this id, used to reject model overrides that no client
     * could draw.
     *
     * D-02：调用方拿到的可能是**磁盘名**（例如 {@code ysmu:example model}），而登记键是
     * {@link ModelIdUtil#getInternalModelId} 编码后的内部 ID；先规范化再查，避免合法模型被拒。
     */
    public static boolean hasModel(ResourceLocation modelId) {
        if (modelId == null) {
            return false;
        }
        String path = modelId.getResourcePath();
        if (CACHE_NAME_INFO.containsKey(path) || CACHE_NAME_INFO.containsKey(modelId.toString())) {
            return true;
        }
        String internalId = ModelIdUtil.getInternalModelId(path);
        if (CACHE_NAME_INFO.containsKey(internalId)) {
            return true;
        }
        String internalFullId = ModelIdUtil.getInternalModelId(modelId.toString());
        return CACHE_NAME_INFO.containsKey(internalFullId);
    }

    /**
     * 特定文件名
     */
    public static final String MAIN_MODEL_FILE_NAME = "main.json";
    public static final String ARM_MODEL_FILE_NAME = "arm.json";
    public static final String MAIN_ANIMATION_FILE_NAME = "main.animation.json";
    public static final String ARM_ANIMATION_FILE_NAME = "arm.animation.json";
    public static final String EXTRA_ANIMATION_FILE_NAME = "extra.animation.json";

    public static void sendRequestSyncModelMessage(List<EntityPlayer> playerList) {
        for (EntityPlayer player : playerList) {
            sendRequestSyncModelMessage(player);
        }
    }

    /**
     * N-02 gate:请求一次模型同步。
     *
     * dev 基线（{@code ServerModelManager.java:119-124}）在 17 通道打开时**无条件**同时发送
     * {@code S2CVersionCheck17} 与 {@code RequestSyncModel}，同一批模型会被两条通道各传一遍。
     * 现在改为 gate：
     * <ul>
     * <li>17 关闭 -> 立刻走 legacy（这是唯一通道，语义与 dev 相同）；</li>
     * <li>17 打开 -> 只发版本校验，并把 legacy 兜底登记为"待命"；只有
     * {@link #onOpenYsmSyncFailed(EntityPlayer)}（版本不一致/反馈 FAILED/17 无法启动）或
     * {@link #onOpenYsmSyncStarted(EntityPlayer) 握手回复}在宽限期内始终没到（客户端不支持 17）时才发
     * {@code RequestSyncModel}。</li>
     * </ul>
     * legacy 仍是 gate，不是删除：17 失败或超时后照旧可用。
     */
    public static void sendRequestSyncModelMessage(EntityPlayer player) {
        if (player == null) {
            return;
        }
        if (!Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) {
            sendLegacySyncModel(player);
            return;
        }
        UUID playerId = player.getUniqueID();
        long graceMillis = handshakeGraceMillis();
        pruneStaleFallbacks();
        PendingLegacyFallback pending = new PendingLegacyFallback(player);
        // 先登记再发包：客户端的回应可能在同一 tick 内到达。
        PENDING_SYNC_FALLBACKS.put(playerId, pending);
        NetworkHandler.sendToClientPlayer(new S2CVersionCheck17(NetworkHandler.PROTOCOL_VERSION), player);
        fallbackScheduler().schedule(
            () -> fallbackToLegacySyncIfHandshakeMissing(playerId, pending),
            graceMillis,
            TimeUnit.MILLISECONDS);
    }

    /**
     * N-02:17 通道已经接管（客户端回应了版本校验、{@code OpenYsmModelSyncServer.startSync} 已开始）。
     * 调用方：{@code network/message/C2SVersionCheck17.Handler}（**必须接线**，否则健康会话也会在宽限期
     * 结束后多发一次 legacy 同步）。
     */
    public static void onOpenYsmSyncStarted(EntityPlayer player) {
        if (player == null) {
            return;
        }
        PendingLegacyFallback pending = PENDING_SYNC_FALLBACKS.get(player.getUniqueID());
        if (pending != null) {
            pending.handshakeStarted = true;
        }
    }

    /**
     * N-02:17 通道成功结束，释放兜底登记。调用方：{@code OpenYsmModelSyncServer.complete} 的
     * {@code STATUS_SUCCESS} 分支。
     */
    public static void onOpenYsmSyncSucceeded(EntityPlayer player) {
        if (player != null) {
            PENDING_SYNC_FALLBACKS.remove(player.getUniqueID());
        }
    }

    /**
     * N-02:17 通道明确失败 -> 立即发 legacy。调用方：{@code C2SVersionCheck17.Handler} 的版本不一致分支与
     * {@code startSync} 的提前返回分支、{@code OpenYsmModelSyncServer.complete} 的非成功分支。
     * 只有在"仍在等 17"或"17 已开始但未成功"时才补发一次，避免与超时兜底重复。
     */
    public static void onOpenYsmSyncFailed(EntityPlayer player) {
        if (player == null) {
            return;
        }
        if (PENDING_SYNC_FALLBACKS.remove(player.getUniqueID()) == null) {
            return;
        }
        ysmu.LOG.info(
            "OpenYSM 17 sync failed for {}; falling back to the legacy model sync",
            player.getCommandSenderName());
        sendLegacySyncModel(player);
    }

    private static void sendLegacySyncModel(EntityPlayer player) {
        NetworkHandler.sendToClientPlayer(new RequestSyncModel(), player);
    }

    private static void fallbackToLegacySyncIfHandshakeMissing(UUID playerId, PendingLegacyFallback pending) {
        if (pending.handshakeStarted) {
            // 17 已接管本回合；后续成功/失败由 onOpenYsmSyncSucceeded/Failed 收口。
            return;
        }
        if (!PENDING_SYNC_FALLBACKS.remove(playerId, pending)) {
            // 已被更新（例如 /ysm reload 重新登记）或被成功/失败信号处理过。
            return;
        }
        EntityPlayer player = pending.player.get();
        if (player == null || player.isDead || !NetworkHandler.isChannelOpen(player)) {
            return;
        }
        ysmu.LOG.info(
            "No OpenYSM 17 handshake from {} within {} ms; falling back to the legacy model sync",
            player.getCommandSenderName(),
            handshakeGraceMillis());
        sendLegacySyncModel(player);
    }

    private static long handshakeGraceMillis() {
        long seconds = Math.min(Math.max(1, Config.PLAYER_SYNC_TIMEOUT), HANDSHAKE_REPLY_GRACE_SECONDS);
        return seconds * 1000L;
    }

    /**
     * 回收已下线/已被 GC 的登记。正常路径由 {@link #onOpenYsmSyncSucceeded(EntityPlayer)} 或
     * {@link #onOpenYsmSyncFailed(EntityPlayer)} 收口；这里只是兜底，防止"没人调用成功回调"时登记表随登录次数增长。
     */
    private static void pruneStaleFallbacks() {
        for (Map.Entry<UUID, PendingLegacyFallback> entry : PENDING_SYNC_FALLBACKS.entrySet()) {
            EntityPlayer pendingPlayer = entry.getValue().player.get();
            if (pendingPlayer == null || pendingPlayer.isDead || !NetworkHandler.isChannelOpen(pendingPlayer)) {
                PENDING_SYNC_FALLBACKS.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 兜底定时器。这里用独立的单线程 daemon 调度器，而不是往 {@code ThreadTools.THREAD_POOL} 里塞一个
     * {@code Thread.sleep}（那正是 M-06 记录的缺陷：池内 sleep 会占住 worker）。
     */
    private static ScheduledExecutorService fallbackScheduler() {
        ScheduledExecutorService scheduler = fallbackScheduler;
        if (scheduler != null) {
            return scheduler;
        }
        synchronized (ServerModelManager.class) {
            if (fallbackScheduler == null) {
                fallbackScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ysmu-legacy-sync-fallback");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return fallbackScheduler;
        }
    }

    /** N-02 的待兜底登记：只持玩家的弱引用，避免把实体留在静态表里。 */
    private static final class PendingLegacyFallback {

        private final WeakReference<EntityPlayer> player;
        private volatile boolean handshakeStarted;

        private PendingLegacyFallback(EntityPlayer player) {
            this.player = new WeakReference<>(player);
        }
    }

    public static void reloadPacks() {
        clearModelCaches();
        createConfigDirectories();
        // 先补齐 blacklist.txt(首次运行时创建),再复制内置模型,这样用户预先放好的规则在第一次
        // reload 就能生效。
        initBlacklistFile();
        copyBuiltInModels();
        initPassword();
        initOpenYsmServerIndex();
        initBuiltNoticeFile();
        rebuildModelCaches();
        // M-09:reloadPacks 末尾按本次登记清理 cache/server（保留 PASSWORD 与所有非缓存命名文件）。
        try {
            ModelCacheWriter.pruneUnregisteredCaches(CACHE_NAME_INFO, OPEN_YSM_SYNC_INFO);
        } catch (Exception | LinkageError e) {
            ysmu.LOG.warn("Failed to prune stale model caches; keeping every existing cache file", e);
        }
    }

    private static void clearModelCaches() {
        CACHE_NAME_INFO.clear();
        OPEN_YSM_SYNC_INFO.clear();
    }

    private static void createConfigDirectories() {
        createFolder(FOLDER);
        createFolder(BUILT);
        createFolder(CUSTOM);
        createFolder(EXPORT);

        createFolder(CACHE);
        createFolder(CACHE_SERVER);
        createFolder(CACHE_CLIENT);
    }

    private static void copyBuiltInModels() {
        // M-04:blacklist.txt 现在真的生效（此前只创建文件、没有任何读取点）。
        List<Pattern> blacklist = readBlacklistPatterns();
        if (!blacklist.isEmpty()) {
            ysmu.LOG.info("Loaded {} built-in model blacklist rule(s) from blacklist.txt", blacklist.size());
        }
        // 内置模型仍然强行覆盖 config/ysmu/custom（AGENTS.md 的 Model and Resource Rules 明确
        // 要求"runtime reload path intentionally overwrites the built-in copies"）；本轮只加黑名单，
        // 不改变覆盖策略，也不迁移到 built/（那需要同步改 AGENTS.md 并动 client 侧的默认模型加载）。
        copyDefaultModel(blacklist);
        copyWineFoxModel(blacklist);
        copyVanillaModel(blacklist);
    }

    /**
     * M-04:读取 {@code config/ysmu/blacklist.txt} 的正则规则，口径与参考实现一致：
     * 每行一个正则，{@code #} 开头为注释，匹配 {@code assets/ysmu/custom/<模型目录>/}。
     */
    private static List<Pattern> readBlacklistPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        Path blacklistFile = FOLDER.resolve("blacklist.txt");
        if (!Files.isRegularFile(blacklistFile)) {
            return patterns;
        }
        try {
            for (String line : Files.readAllLines(blacklistFile, StandardCharsets.UTF_8)) {
                String rule = line.trim();
                if (rule.isEmpty() || rule.startsWith("#")) {
                    continue;
                }
                try {
                    patterns.add(Pattern.compile(rule));
                } catch (PatternSyntaxException e) {
                    ysmu.LOG.warn("Ignoring invalid regular expression in blacklist.txt: {}", rule, e);
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to read {}", blacklistFile, e);
        }
        return patterns;
    }

    /**
     * 与参考实现 {@code OpenYSM/.../ServerModelManager.processBlacklist} 相同的路径口径。
     * {@code default} 模型不可被禁用：本模组的兜底路径直接依赖它
     * （{@code FolderFormat}/{@code YsmFormat}/{@code RawYsmModelAdapter.readDefaultAnimation}、
     * {@code ClientModelManager:389}）。
     */
    private static boolean isBlacklisted(List<Pattern> blacklist, String modelDirectory) {
        if (blacklist.isEmpty() || "default".equals(modelDirectory)) {
            return false;
        }
        String matchPath = "assets/" + ysmu.MODID + "/custom/" + modelDirectory + "/";
        for (Pattern rule : blacklist) {
            if (rule.matcher(matchPath)
                .find()) {
                ysmu.LOG.info("Skipping built-in model {} because blacklist rule '{}' matched", modelDirectory, rule);
                return true;
            }
        }
        return false;
    }

    private static void rebuildModelCaches() {
        // M-18:单个模型/单个扫描器失败不得让整次 reload 中断；每一步单独兜底。
        runCacheStep("OpenYSM (built)", () -> OpenYsmFormat.cacheAllModels(BUILT));
        runCacheStep("OpenYSM (custom)", () -> OpenYsmFormat.cacheAllModels(CUSTOM));
        runCacheStep("legacy (custom)", () -> cacheAllModels(CUSTOM));
    }

    private static void runCacheStep(String name, Runnable step) {
        try {
            step.run();
        } catch (Exception | LinkageError e) {
            // LinkageError 覆盖类加载/引擎版本不匹配（例如旧 jar 缺方法），此时跳过本步而不是让
            // 整个 preInit 失败。
            ysmu.LOG.warn("Model cache step '{}' failed; continuing with the remaining steps", name, e);
        }
    }

    private static void copyDefaultModel(List<Pattern> blacklist) {
        Path defaultPath = CUSTOM.resolve("default");
        createFolder(defaultPath);

        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default/main.json"), defaultPath, MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default/arm.json"), defaultPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/default/default.png"), defaultPath, "default.png");
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/default/blue.png"), defaultPath, "blue.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default/main.animation.json"),
            defaultPath,
            MAIN_ANIMATION_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default/arm.animation.json"),
            defaultPath,
            ARM_ANIMATION_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default/extra.animation.json"),
            defaultPath,
            EXTRA_ANIMATION_FILE_NAME);

        if (isBlacklisted(blacklist, "default_boy")) {
            return;
        }
        Path defaultBoyPath = CUSTOM.resolve("default_boy");
        createFolder(defaultBoyPath);

        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default_boy/main.json"),
            defaultBoyPath,
            MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default_boy/arm.json"), defaultBoyPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/default_boy/red.png"), defaultBoyPath, "red.png");
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default_boy/blue.png"), defaultBoyPath, "blue.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default_boy/main.animation.json"),
            defaultBoyPath,
            MAIN_ANIMATION_FILE_NAME);
    }

    private static void copyVanillaModel(List<Pattern> blacklist) {
        copySteveModel(blacklist);
        copyAlexModel(blacklist);
        copyQinglukaModel(blacklist);
    }

    private static void copySteveModel(List<Pattern> blacklist) {
        if (isBlacklisted(blacklist, "steve")) {
            return;
        }
        Path stevePath = CUSTOM.resolve("steve");
        createFolder(stevePath);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/steve/main.json"), stevePath, MAIN_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/steve/arm.json"), stevePath, ARM_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/steve/tartaric_acid.png"), stevePath, "tartaric_acid.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/steve/main.animation.json"),
            stevePath,
            MAIN_ANIMATION_FILE_NAME);
    }

    private static void copyAlexModel(List<Pattern> blacklist) {
        if (isBlacklisted(blacklist, "alex")) {
            return;
        }
        Path alexPath = CUSTOM.resolve("alex");
        createFolder(alexPath);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/alex/main.json"), alexPath, MAIN_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/alex/arm.json"), alexPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/alex/gsl.png"), alexPath, "gsl.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/alex/main.animation.json"),
            alexPath,
            MAIN_ANIMATION_FILE_NAME);
    }

    private static void copyQinglukaModel(List<Pattern> blacklist) {
        if (isBlacklisted(blacklist, "qingluka")) {
            return;
        }
        Path qinglukaPath = CUSTOM.resolve("qingluka");
        createFolder(qinglukaPath);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/qingluka/main.json"), qinglukaPath, MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/qingluka/arm.json"), qinglukaPath, ARM_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/qingluka/texture.png"), qinglukaPath, "texture.png");
    }

    private static void copyWineFoxModel(List<Pattern> blacklist) {
        if (isBlacklisted(blacklist, "wine_fox")) {
            return;
        }
        Path wineFoxPath = CUSTOM.resolve("wine_fox");
        createFolder(wineFoxPath);

        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/wine_fox/main.json"), wineFoxPath, MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/wine_fox/arm.json"), wineFoxPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/wine_fox/skin.png"), wineFoxPath, "skin.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/wine_fox/main.animation.json"),
            wineFoxPath,
            MAIN_ANIMATION_FILE_NAME);
    }

    private static void cacheAllModels(Path rootPath) {
        YsmFormat.cacheAllModels(rootPath);
        FolderFormat.cacheAllModels(rootPath);
    }

    private static void initPassword() {
        try {
            EncryptTools.createRandomPassword();
            File passwordFile = PASSWORD_FILE.toFile();
            if (passwordFile.isFile()) {
                boolean validPassword = EncryptTools.readPassword(FileUtils.readFileToByteArray(passwordFile));
                if (!validPassword) {
                    FileUtils.writeByteArrayToFile(passwordFile, EncryptTools.writePassword());
                }
            } else {
                FileUtils.writeByteArrayToFile(passwordFile, EncryptTools.writePassword());
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to initialize legacy model password", e);
        }
    }

    private static void initOpenYsmServerIndex() {
        try {
            byte[] serverKey = readOpenYsmServerKey();
            if (serverKey == null) {
                serverKey = new byte[56];
                new SecureRandom().nextBytes(serverKey);
                JsonObject root = new JsonObject();
                root.addProperty("server_key", Base64.getEncoder().encodeToString(serverKey));
                FileUtils.writeStringToFile(CACHE_SERVER_INDEX_FILE.toFile(), ysmu.GSON.toJson(root), StandardCharsets.UTF_8);
            }
            OPEN_YSM_SERVER_KEY = serverKey;
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to initialize OpenYSM server_index", e);
            byte[] fallbackKey = new byte[56];
            new SecureRandom().nextBytes(fallbackKey);
            OPEN_YSM_SERVER_KEY = fallbackKey;
        }
    }

    private static byte[] readOpenYsmServerKey() {
        try {
            File serverIndexFile = CACHE_SERVER_INDEX_FILE.toFile();
            if (!serverIndexFile.isFile()) {
                return null;
            }
            String json = FileUtils.readFileToString(serverIndexFile, StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonElement serverKeyElement = root.get("server_key");
            if (serverKeyElement == null || !serverKeyElement.isJsonPrimitive()) {
                return null;
            }
            byte[] decoded = Base64.getDecoder().decode(serverKeyElement.getAsString());
            return decoded.length == 56 ? decoded : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void initBlacklistFile() {
        Path blacklistFile = FOLDER.resolve("blacklist.txt");
        if (Files.isRegularFile(blacklistFile)) {
            return;
        }
        try {
            String content = "# Yes Steve Model built-in model blacklist\n"
                + "# One Java regular expression per line. Lines starting with # are comments.\n"
                + "# Rules are matched with find() against assets/ysmu/custom/<model directory>/ ,\n"
                + "# for example: assets/ysmu/custom/(steve|alex)/\n"
                + "# A matching built-in model is not copied into config/ysmu/custom on reload.\n"
                + "# Already extracted copies are left alone: delete the directory by hand if you blacklist\n"
                + "# a model that was extracted earlier.\n"
                + "# The default model cannot be disabled: the mod's fallback paths load their animations\n"
                + "# and geometry from it.\n";
            FileUtils.writeStringToFile(blacklistFile.toFile(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to create OpenYSM blacklist file", e);
        }
    }

    private static void initBuiltNoticeFile() {
        Path noticeFile = BUILT.resolve("notice.txt");
        try {
            String content = "OpenYSM built-in model staging directory.\n"
                + "This phase creates the directory for the new scanner; legacy built-ins are still copied to custom.\n";
            FileUtils.writeStringToFile(noticeFile.toFile(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to write OpenYSM built directory notice", e);
        }
    }

    private static String getCustomFiles(String path) {
        return String.format("/assets/%s/%s", ysmu.MODID, path);
    }

    private static void createFolder(Path path) {
        File folder = path.toFile();
        if (!folder.isDirectory()) {
            try {
                Files.createDirectories(folder.toPath());
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to create directory {}", path, e);
            }
        }
    }

    public static String removeExtension(String fileName) {
        int lastIndex = fileName.lastIndexOf('.');
        if (lastIndex != -1) {
            fileName = fileName.substring(0, lastIndex);
        }
        return fileName;
    }
}
