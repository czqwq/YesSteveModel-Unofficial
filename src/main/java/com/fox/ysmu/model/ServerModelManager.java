package com.fox.ysmu.model;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.ModelCacheWriter;
import com.fox.ysmu.model.format.OpenYsmFormat;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.model.format.PackFormat;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.ServerPackData;
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
    public static final Path FOLDER = Path.of("config", ysmu.MODID);

    /**
     * 自定义模型所放置的文件夹
     */
    public static final Path CUSTOM = FOLDER.resolve("custom");

    /**
     * 内置模型的运行时落点：jar 内 {@code assets/ysmu/builtin/} 的整棵树在每次 reload 时先清空本目录、再解压到这里，
     * 随后作为与 {@link #CUSTOM} 并列的扫描根被扫描。名字与值和 SOURCE 的
     * {@code ServerModelManager.BUILT}（{@code config/ysmu/builtin}）一致。
     */
    public static final Path BUILT = FOLDER.resolve("builtin");
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
    /**
     * 包根（含 {@code ysm-pack.json} 的目录）的清单与封面，键是带尾斜杠的层级（例如 {@code wine_fox/}）。
     * 它让客户端的文件夹格子能显示包自己的名字、描述和封面，而不是目录名 + 通用图标；和
     * {@link #CACHE_NAME_INFO} 一样在 reload 期间重建、被网络线程读取，所以用并发容器。
     */
    public static final Map<String, ServerPackData> PACKS = new ConcurrentHashMap<>();
    public static volatile byte[] OPEN_YSM_SERVER_KEY;
    /**
     * 上一次 {@link #reloadPacks()} 的耗时（毫秒），0 表示还没跑过。
     *
     * <p>{@code Config.SHOW_MODEL_LOAD_CHAT} 的登录提示读它（{@code event/CommonEventHandler} 的
     * {@code onPlayerLoggedIn}）。上游/reference 的同一个字段是
     * {@code ServerModelManager.LAST_LOAD_TIME_MS}（参考分支 `model/ServerModelManager.java:95` 声明、
     * `:170` 在 {@code reloadPacks()} 末尾由 StopWatch 赋值），所以这是照抄的接缝，不是新机制。
     */
    public static volatile long LAST_LOAD_TIME_MS;

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
    /**
     * OpenYSM 模型包（pack）清单。带有它的目录是一个**包根目录**，里面放的是若干模型目录，
     * 它自己不是模型 —— 否则 {@code wine_fox/} 这种"包根 + 残留 main.json/arm.json"的目录会被
     * 注册成一个和包同名的模型，并把包封面 {@link #PACK_ICON_FILE_NAME} 当成模型贴图。
     */
    public static final String PACK_INFO_FILE_NAME = "ysm-pack.json";
    /** 包封面图，和清单同级；它永远不是模型贴图。 */
    public static final String PACK_ICON_FILE_NAME = "ysm-pack.png";
    /**
     * jar 内"包根格式"内置资源的目录名：{@code assets/ysmu/builtin/}。按决策 D8 它是内置资源的**唯一**
     * 来源根——旧位置 {@code assets/ysmu/custom/} 的逐名复制已退役，jar 里那份拷贝不再被读取。
     * 注意它和运行时目录 {@link #BUILT}（{@code config/ysmu/builtin}）不是一回事。
     */
    private static final String BUILTIN_PACK_RESOURCE_ROOT = "builtin";

    /**
     * 缺省动画文件（{@code main/arm/extra.animation.json}）的实际位置，供所有"模型自己没带这份动画"的回退使用。
     * <p>
     * M1/D7：内置 default 从 {@code config/ysmu/custom/default} 搬到 {@code config/ysmu/builtin/default}，而且它是
     * 现代 {@code ysm.json} 包——动画在包的 {@code animations/} 子目录里（{@code ysm.json} 的
     * {@code files.player.animation.*} 就是这么指的）。所以老位置在新装机器上不存在，而
     * {@code builtin/default} 的**根目录**下也没有任何 {@code *.animation.json}。
     * <p>
     * 这与 SOURCE 的差别值得写下来：SOURCE 只在 {@code RawYsmModelAdapter.readDefaultAnimation}
     * （{@code :1238-1249}）加了一条 {@code BUILT.resolve("default").resolve(fileName)} 的回退，那条路径在本仓
     * 永远不命中——{@code builtin/default} 下没有根级动画文件；它的 {@code FolderFormat.getBytes} 更是完全没有
     * 内置回退。本方法把这条规则收在一处，两个 format 类与 adapter 共用，避免三份各写各的路径。
     * <p>
     * 顺序仍是"用户目录优先"：旧安装（或在 {@code custom/default} 放了自己那份的玩家）继续用自己的，只有它不
     * 存在时才落到内置包。返回的 Path 不保证存在，调用方各自决定"读不到就抛"还是"读不到就用空动画"。
     */
    public static Path defaultAnimationFile(String fileName) {
        Path legacy = CUSTOM.resolve("default").resolve(fileName);
        if (Files.isRegularFile(legacy)) {
            return legacy;
        }
        Path builtin = BUILT.resolve("default").resolve("animations").resolve(fileName);
        return Files.isRegularFile(builtin) ? builtin : legacy;
    }

    /** 该目录是否是 OpenYSM 包根（含 {@code ysm-pack.json}），即"装模型的文件夹"而不是模型本身。 */
    public static boolean isPackRoot(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(PACK_INFO_FILE_NAME));
    }

    public static void sendRequestSyncModelMessage(List<EntityPlayer> playerList) {
        for (EntityPlayer player : playerList) {
            sendRequestSyncModelMessage(player);
        }
    }

    /**
     * 请求一次模型同步。
     *
     * 两条通道各自负责一部分模型，合起来覆盖全部：17 索引（{@link #OPEN_YSM_SYNC_INFO}）只登记
     * {@code OpenYsmFormat} 能产出二进制负载的模型，这些模型由 17 通道投递，{@code SyncModelFiles} 不再重复投递
     * （按模型、且仅在 17 协议启用时抑制，见该处注释）；内置模型与 {@code OpenYsmFormat} 产不出负载的传统模型
     * 永远不在 17 索引里，只能走 legacy，因此这里仍然**无条件**发送 legacy 请求：
     *
     * <ul>
     * <li>抑制条件是"该模型确实在 17 索引里且 17 协议已启用"，不是"17 索引非空就抑制"，所以不会有模型两条通道
     * 都收不到；</li>
     * <li>legacy 这一轮的固定代价只有一次握手，加上客户端缺失清单里真正缺的文件
     * （{@code SyncModelFiles} 按客户端自报的 md5 求差集），不是整批重传；</li>
     * <li>两条通道都登记同一模型时，{@code ClientModelManager.registerAll} 的内容签名去重仍然兜底——但它对
     * OpenYSM folder 模型失效（两条通道把同一模型序列化成不同字节），这正是改成按模型抑制投递的原因。</li>
     * </ul>
     */
    public static void sendRequestSyncModelMessage(EntityPlayer player) {
        if (player == null) {
            return;
        }
        if (Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) {
            NetworkHandler.sendToClientPlayer(new S2CVersionCheck17(NetworkHandler.PROTOCOL_VERSION), player);
        }
        sendLegacySyncModel(player);
    }

    private static void sendLegacySyncModel(EntityPlayer player) {
        NetworkHandler.sendToClientPlayer(new RequestSyncModel(), player);
    }

    public static void reloadPacks() {
        org.apache.commons.lang3.time.StopWatch watch = new org.apache.commons.lang3.time.StopWatch();
        watch.start();
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
            ysmu.LOG.info("[YSMU-MODEL] ===== Starting model reload =====");
            ysmu.LOG.info("[YSMU-MODEL] CUSTOM dir: {} (exists={})", CUSTOM, Files.isDirectory(CUSTOM));
            ysmu.LOG.info("[YSMU-MODEL] BUILT dir: {} (exists={})", BUILT, Files.isDirectory(BUILT));
        }
        clearModelCaches();
        createConfigDirectories();
        // 先补齐 blacklist.txt(首次运行时创建)。
        initBlacklistFile();
        // M1：内置模型只有 assets/ysmu/builtin/ 一个来源根，每次 reload 先清空 config/ysmu/builtin 再整树解压（决策 D7/D8）。
        extractBuiltinModels();
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
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
            ysmu.LOG.info("[YSMU-MODEL] ===== Model reload complete =====");
            ysmu.LOG.info("[YSMU-MODEL] CACHE_NAME_INFO size: {}", CACHE_NAME_INFO.size());
            ysmu.LOG.info("[YSMU-MODEL] OPEN_YSM_SYNC_INFO size: {}", OPEN_YSM_SYNC_INFO.size());
            ysmu.LOG.info("[YSMU-MODEL] PACKS size: {}", PACKS.size());
            if (!CACHE_NAME_INFO.isEmpty()) {
                ysmu.LOG.info("[YSMU-MODEL] Cached model IDs: {}", CACHE_NAME_INFO.keySet());
            }
        }
        watch.stop();
        // 耗时在**最后**取：prune 也算这次 reload 的一部分（参考分支把同一行放在它自己的 reloadPacks 末尾）。
        LAST_LOAD_TIME_MS = watch.getTime();
    }

    private static void clearModelCaches() {
        CACHE_NAME_INFO.clear();
        OPEN_YSM_SYNC_INFO.clear();
        PACKS.clear();
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

    private static void rebuildModelCaches() {
        // M-18:单个模型/单个扫描器失败不得让整次 reload 中断；每一步单独兜底。
        // 包清单先扫：它只读 ysm-pack.json/png，既不影响下面的模型登记，也让 GUI 的文件夹格子在模型到达前
        // 就已有名字与封面。
        //
        // M1/D9:扫描顺序就是重复 modelId 的胜负规则——三个扫描器登记同一个 id 时都是无条件 put，所以
        // "后扫的赢"。builtin 排在 custom 之后，于是同名模型最终登记的一定是内置那份。最典型的是 default：
        // config/ysmu/custom/default 是旧安装残留的扁平模型（它没有 ysm.json，M-21 那条"目录带 ysm.json
        // 且 id 已在 CACHE_NAME_INFO 里就整棵跳过"的豁免对它不成立，FolderFormat 会照常登记它），
        // config/ysmu/builtin/default 才是新的 ysm.json 包。顺序反过来会让残留的 legacy 版本覆盖内置版本，
        // 使 CACHE_NAME_INFO["default"] 与 OPEN_YSM_SYNC_INFO["default"] 指向两个不同的模型。
        runCacheStep("packs (custom)", () -> PackFormat.cacheAllPacks(CUSTOM, PACKS));
        runCacheStep("OpenYSM (custom)", () -> OpenYsmFormat.cacheAllModels(CUSTOM));
        runCacheStep("legacy (custom)", () -> cacheAllModels(CUSTOM));
        runCacheStep("packs (builtin)", () -> PackFormat.cacheAllPacks(BUILT, PACKS));
        runCacheStep("OpenYSM (builtin)", () -> OpenYsmFormat.cacheAllModels(BUILT));
        runCacheStep("legacy (builtin)", () -> cacheAllModels(BUILT));
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

    /**
     * 清空运行时目录 {@link #BUILT}（{@code config/ysmu/builtin}）后，把 jar 内 {@code assets/ysmu/builtin/} 的
     * 整棵树解压进去，与 SOURCE 的 {@code ServerModelManager.extractBuiltinModels} 同一形状：解压整棵树，而不是
     * 逐个模型、逐个文件列举（决策 D7）。
     *
     * <p>树里两类顶层条目都在这里落地：{@code builtin/misc} 是包根（{@code ysm-pack.json} 加六个旧扁平模型
     * 目录 {@code 1_alex} … {@code 6_wine_fox}），{@code builtin/default} 是现代 {@code ysm.json} 包。
     * 它们随后由 {@link #rebuildModelCaches} 的三个扫描器分别认领。目标目录不区分"包根"与"模型目录"，
     * 也没有"用户替换过的目录"这种例外：{@code config/ysmu/builtin} 的内容每轮都由模组自己重写。</p>
     *
     * <p>与 SOURCE 同一形状：SOURCE 先 {@code FileUtils.cleanDirectory(BUILT)} 再解压（{@code :440-446}），这里也
     * 先清空目标目录，避免 jar 里被删除的内置文件以残留形式留在 {@code config/ysmu/builtin} 下、下次 reload 仍被
     * 扫到并登记一个已经不再随 jar 发布的模型。</p>
     *
     * <p>清空是安全的，因为 {@link #BUILT} 不是用户数据：它是模组自己的解压落点（迁到这里的目录，早前版本不
     * 存在），它的 {@code notice.txt} 也明确写着内容每轮都会被 {@code assets/ysmu/builtin} 覆盖、玩家自己的模型
     * 应放 {@link #CUSTOM}。{@link #CUSTOM} 是用户目录，绝不清理。</p>
     */
    private static void extractBuiltinModels() {
        // 只清 BUILT：custom 是用户数据，另一个 built（旧的孤儿 notice 目录）也刻意不动。
        if (Files.isDirectory(BUILT)) {
            try {
                FileUtils.cleanDirectory(BUILT.toFile());
            } catch (IOException e) {
                ysmu.LOG.warn("Failed to clean built-in model directory {}", BUILT, e);
            }
        }
        createFolder(BUILT);
        // 尾斜杠与 SOURCE 一致：jar 里以目录条目（"assets/ysmu/builtin/"）存在，带斜杠才能解析成目录 URL。
        String prefix = getCustomFiles(BUILTIN_PACK_RESOURCE_ROOT) + "/";
        URL url = ysmu.class.getResource(prefix);
        if (url == null) {
            // 裁剪过的资源：没有内置树可解压，不是错误。
            return;
        }
        try {
            if ("jar".equals(url.getProtocol())) {
                // 条目归属的 jar 从 JarURLConnection 取：SOURCE 切 url.getPath() 的 "file:" 前缀，
                // 在 Windows 上会得到 "/E:/..." 这种非法盘符路径。
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    String entryPrefix = prefix.substring(1);
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith(entryPrefix)) {
                            continue;
                        }
                        copyBuiltinPackResource(name.substring(entryPrefix.length()));
                    }
                }
            } else if ("file".equals(url.getProtocol())) {
                // 开发环境：资源就在文件系统上。
                Path sourceRoot = new File(url.toURI()).toPath();
                if (Files.isDirectory(sourceRoot)) {
                    try (Stream<Path> stream = Files.walk(sourceRoot)) {
                        stream.filter(Files::isRegularFile)
                            .forEach(
                                source -> copyBuiltinPackResource(
                                    sourceRoot.relativize(source)
                                        .toString()
                                        .replace('\\', '/')));
                    }
                }
            }
            ysmu.LOG.info("Extracted built-in model resources from {} into {}", prefix, BUILT);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to extract built-in model resources from {}", prefix, e);
        }
    }

    /**
     * 复制 {@code assets/ysmu/builtin/} 下的一个文件，{@code relativePath} 相对该目录；落点是
     * {@code config/ysmu/builtin/<relativePath>}，即整棵树的镜像。
     */
    private static void copyBuiltinPackResource(String relativePath) {
        Path target = BUILT.resolve(relativePath);
        createFolder(target.getParent());
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles(BUILTIN_PACK_RESOURCE_ROOT + "/" + relativePath),
            target.getParent(),
            target.getFileName()
                .toString());
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
                + "# NOT CONSULTED YET: built-in models now come from assets/ysmu/builtin and are extracted\n"
                + "# into config/ysmu/builtin on every reload, so there is no per-name copy left to skip.\n"
                + "# Your own models live in config/ysmu/custom and are never touched.\n"
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
            String content = "OpenYSM built-in model directory.\n"
                + "Its contents are overwritten from assets/ysmu/builtin on every reload;\n"
                + "put your own models in config/ysmu/custom instead.\n";
            FileUtils.writeStringToFile(noticeFile.toFile(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to write OpenYSM builtin directory notice", e);
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
