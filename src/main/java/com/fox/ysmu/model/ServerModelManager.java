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
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.Tags;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.model.catalog.CatalogAccess;
import com.fox.ysmu.model.catalog.CatalogRootKind;
import com.fox.ysmu.model.format.BuiltinModelIndex;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.ModelCacheWriter;
import com.fox.ysmu.model.format.ModelIndexCache;
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

    /**
     * 受授权保护的模型扫描根：{@code config/ysmu/auth}。它的 {@link CatalogRootKind#accessPolicy()} 是
     * {@link com.fox.ysmu.model.catalog.AccessPolicy#SESSION_AUTHORIZED}，所以这里发现的每个模型都是
     * {@link CatalogAccess#AUTHORIZED}——只有被 {@code /ysm auth} 授权过的玩家才能选用它。
     *
     * <p><b>上游依据。</b>{@code model/catalog/source/CatalogRootKind.java:7} 的
     * {@code AUTH("auth", AccessPolicy.SESSION_AUTHORIZED)} 与
     * {@code model/catalog/source/ModelCatalogSources.reloadableSources():29-33} 把它作为与 {@code custom} 并列的
     * 可重扫来源；{@code AssetPaths.authModelsRoot():100-107} 的落点是 {@code <game-dir>/ysm/auth}。本仓所有模型根
     * 都在 {@code config/ysmu} 下（{@link #CUSTOM} 与 {@link #BUILT} 已经如此），所以这里用同一套前缀。</p>
     *
     * <p><b>默认是空的，因此升级后的服务器什么都不受限。</b>这正是上游的形态：它没有"启用授权"的开关，受限集合
     * 就是 auth 根里的内容；空根 = 无限制。</p>
     */
    public static final Path AUTH = FOLDER.resolve("auth");
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
        // M1：内置模型只有 assets/ysmu/builtin/ 一个来源根；契约指纹未变且契约路径齐全时跳过"清空 + 整树解压"
        // （决策 D7/D8，见 extractBuiltinModels 的 javadoc）。
        extractBuiltinModels();
        // 契约校验刻意放在解压**之后**、且不塞进 extractBuiltinModels 内部：将来"内容未变则跳过整树解压"的优化
        // 落进那个方法时，被跳过的那一轮照样有人报多余/缺失文件（见 verifyBuiltinModelIndex 的 javadoc）。
        verifyBuiltinModelIndex();
        initPassword();
        initOpenYsmServerIndex();
        initBuiltNoticeFile(BUILT);
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

    /**
     * 把三张登记表与来源表恢复成"还没扫描"的状态。{@code reloadPacks()} 每次开局调用它；包内同样可见，因为
     * 同包的回归用例需要一个能和 reload 起点一样的复位点（用例之间共享静态表，不复位就会互相污染）。
     */
    static void clearModelCaches() {
        CACHE_NAME_INFO.clear();
        OPEN_YSM_SYNC_INFO.clear();
        PACKS.clear();
        // 第 6 项：来源表与冲突计数与登记表同生共死，每轮 reload 从零开始（上游每次选记录都重建一份 Selection）。
        MODEL_SOURCES.clear();
        PACK_SOURCES.clear();
        MODEL_CONFLICTS.set(0);
        PACK_CONFLICTS.set(0);
    }

    private static void createConfigDirectories() {
        createFolder(FOLDER);
        createFolder(BUILT);
        createFolder(CUSTOM);
        createFolder(AUTH);
        createFolder(EXPORT);

        createFolder(CACHE);
        createFolder(CACHE_SERVER);
        createFolder(CACHE_CLIENT);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 来源优先级：同名模型/同名包的唯一裁决点（第 6 项）
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 扫描根的显式优先级，数值越小越优先。
     *
     * <p>上游对应物是 {@code ReloadableModelCatalog.rootPriority}
     * （{@code model/catalog/ReloadableModelCatalog.java:636-642}）：{@code BUILTIN 0 / AUTH 1 / CUSTOM 2}。
     * 全部三档现在都有扫描根了：{@link CatalogRootKind#BUILTIN} 是 {@link #BUILT}、
     * {@link CatalogRootKind#AUTH} 是 {@link #AUTH}、{@link CatalogRootKind#CUSTOM} 是 {@link #CUSTOM} 加内置根里
     * 未被契约声明的目录。编号保持上游那张表，不重新编号。</p>
     *
     * <p>内置身份 = 扫描根是 {@link #BUILT} <b>且</b>该模型被随 jar 发布的契约
     * {@code assets/ysmu/builtin-index.json} 声明过（{@link BuiltinModelIndex#declaredInternalIds()}）。契约不可读
     * 时退回"只看扫描根"并 warn 一次：宁可把内置当内置，也不能因为契约缺失让内置输给用户域——那正是本项要固定
     * 的结果。用户手放在 {@code config/ysmu/builtin} 里、契约没声明的目录因此<b>不是</b>内置模型（第 10 项的
     * 要求），它们按 {@link #PRIORITY_CUSTOM} 参与裁决。</p>
     */
    private static final int PRIORITY_BUILTIN = 0;
    /**
     * auth 根排在 custom 之前，于是同相对路径的 auth 候选胜过 custom（上游"同一路径的 auth 候选优先于 custom"，
     * {@code docs/architecture/model-management/catalog-and-sources.md:25}）。权限只影响能否选用，不影响谁能赢这个
     * id：把模型从 custom 移进 auth 不会因为"多了一层限制"而输给留在 custom 里的同名模型。
     */
    private static final int PRIORITY_AUTH = 1;
    private static final int PRIORITY_CUSTOM = 2;

    /** 一个已登记来源：磁盘路径 + 优先级 + 扫描根内相对路径（上游 {@code CatalogRecord} 的 location 半边）。 */
    private static final class RegisteredSource {

        final Path source;
        final int priority;
        /** 排序键的一半：上游 {@code CatalogModelLocation.path()}，相对扫描根、{@code /} 分隔。 */
        final String relativePath;
        /** 这个来源属于哪个扫描根；决定模型是 {@link CatalogAccess#PUBLIC} 还是 {@code AUTHORIZED}。 */
        final CatalogRootKind rootKind;

        RegisteredSource(Path source, int priority, String relativePath, CatalogRootKind rootKind) {
            this.source = source;
            this.priority = priority;
            this.relativePath = relativePath;
            this.rootKind = rootKind;
        }
    }

    /**
     * 登记键 → 生效来源。诊断与冲突日志用它，{@link #isBuiltinModel(ResourceLocation)} 也用它（优先级
     * {@link #PRIORITY_BUILTIN} 即"服务端正在按内置那份提供服务"）。与 {@link #CACHE_NAME_INFO} 一样在 reload
     * 线程重建、被网络线程与命令线程读取，所以是并发容器。
     */
    private static final Map<String, RegisteredSource> MODEL_SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, RegisteredSource> PACK_SOURCES = new ConcurrentHashMap<>();
    private static final AtomicInteger MODEL_CONFLICTS = new AtomicInteger();
    private static final AtomicInteger PACK_CONFLICTS = new AtomicInteger();

    /** 契约不可读只提示一次（契约本身在进程内不会变）。 */
    private static volatile boolean builtinContractWarningLogged;

    /**
     * 登记一个模型。三个扫描器（{@code OpenYsmFormat} / {@code YsmFormat} / {@code FolderFormat}）与索引命中
     * 路径（{@code ModelIndexCache.restoreIfHit}）都必须走这里，取代原先各自的裸 {@code CACHE_NAME_INFO.put}。
     *
     * <h2>裁决规则</h2>
     * <ol>
     * <li>同名冲突按来源优先级裁决：{@code BUILTIN(0)} 胜 {@code CUSTOM(2)}。最终结果与今天一致（内置排在扫描
     * 顺序最后，本来是"后扫的赢"），但从"顺序碰巧"变成"与顺序无关的显式判定"。最典型的是 {@code default}：
     * {@code config/ysmu/custom/default} 是旧安装残留的扁平模型，{@code config/ysmu/builtin/default} 才是新的
     * {@code ysm.json} 包，内置必须赢。</li>
     * <li>同优先级按<b>扫描根内相对路径升序取先者</b>：上游 {@code ReloadableModelCatalog.selectRecords:602-642}
     * 的排序键就是 {@code comparingInt(rootPriority).thenComparing(location.path())}（{@code :607-610}），记录在
     * 前先占位、后面的记冲突丢弃。于是 {@code custom/example/} 排 {@code custom/example.ysm} 之前、目录赢，
     * 与登记顺序无关。</li>
     * <li>同名但来源不同时一律 {@code ysmu.LOG.warn} 一条带两个来源路径与两个优先级的冲突日志（上游记
     * {@code ModelScanError}，消息原文是 {@code "Catalog source conflicts with a selected model"}
     * ，{@code :624-628}）；{@link #modelConflictCount()} 给诊断读本轮条数。</li>
     * <li>同一磁盘来源的第二次登记是"补齐"，不是冲突：{@code OpenYsmFormat} 先登记 legacy 半边、拿到 17 载荷后
     * 再登记一次。上游一个 location 只有一条记录，所以这里也不产生第二条冲突。</li>
     * <li>赢家<b>同时</b>写 {@link #CACHE_NAME_INFO} 与 {@link #OPEN_YSM_SYNC_INFO}：赢了但没有 17 载荷时清掉旧
     * 的，避免两张表指向两个不同的模型（AGENTS.md 记录过这个隐患）。</li>
     * </ol>
     *
     * <p>排序键完全相等（同优先级 + 同相对路径）时保留先登记者：上游一个 rootKind 下不会有两条同路径记录，
     * 本仓的 {@link #PRIORITY_CUSTOM} 同时覆盖 {@code custom} 与内置根里未被契约声明的目录，所以这种情况只在本仓
     * 存在；按上游"先者胜、后者丢弃"的形态处理。</p>
     *
     * @param modelId 登记键（{@code ModelIdUtil.getInternalModelId} 的内部 id）
     * @param info 传统缓存登记；{@code null} 表示这次没产出模型（写缓存失败），整条不登记——与改前一致
     * @param syncInfo 17 通道载荷，可为 {@code null}
     * @param source 磁盘来源（模型目录或 {@code .ysm} 文件）
     * @param scanRoot 来源所属扫描根（{@link #CUSTOM}、{@link #AUTH} 或 {@link #BUILT}）
     */
    public static void registerModel(String modelId, ServerModelInfo info, OpenYsmSyncInfo syncInfo, Path source,
        Path scanRoot) {
        if (modelId == null || info == null || source == null) {
            return;
        }
        CatalogRootKind rootKind = rootKindOf(scanRoot);
        int priority = priorityOf(rootKind, scanRoot, modelId);
        if (!claim("model", modelId, priority, rootKind, source, scanRoot, MODEL_SOURCES, MODEL_CONFLICTS)) {
            return;
        }
        CACHE_NAME_INFO.put(modelId, info);
        if (syncInfo != null) {
            OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
        } else {
            OPEN_YSM_SYNC_INFO.remove(modelId);
        }
    }

    /**
     * 登记一个包根清单（{@code ysm-pack.json} 的名字/描述/封面）。规则与 {@link #registerModel} 同一套，只是包根
     * 不参与内置契约：{@code builtin-index.json} 只列<b>模型</b>路径（上游 {@code BuiltinModelIndex} 的
     * {@code models} 数组同理），所以包根只看扫描根。
     */
    public static void registerPack(String hierarchy, ServerPackData pack, Path source, Path scanRoot) {
        if (hierarchy == null || pack == null || source == null) {
            return;
        }
        CatalogRootKind rootKind = rootKindOf(scanRoot);
        int priority = rootKind == CatalogRootKind.AUTH ? PRIORITY_AUTH
            : rootKind == CatalogRootKind.BUILTIN ? PRIORITY_BUILTIN : PRIORITY_CUSTOM;
        if (claim("model pack", hierarchy, priority, rootKind, source, scanRoot, PACK_SOURCES, PACK_CONFLICTS)) {
            PACKS.put(hierarchy, pack);
        }
    }

    /** 本轮 reload 里被覆盖或丢弃的同名模型条数（上游把每一条记成一个 {@code ModelScanError}）。 */
    public static int modelConflictCount() {
        return MODEL_CONFLICTS.get();
    }

    /** 包清单那半边的计数，口径同 {@link #modelConflictCount()}。 */
    public static int packConflictCount() {
        return PACK_CONFLICTS.get();
    }

    /**
     * 该 id 是否由内置那份提供服务：登记表里生效来源的优先级是 {@link #PRIORITY_BUILTIN}。拼写规范化与
     * {@link #hasModel(ResourceLocation)} 同一套（裸磁盘名、{@code ysmu:} 全名、{@code _name_<hex>} 内部 id），
     * 因为调用方（{@code api/ModelAuthorization}）拿到的可能是其中任一种。
     */
    public static boolean isBuiltinModel(ResourceLocation modelId) {
        if (modelId == null) {
            return false;
        }
        String path = modelId.getResourcePath();
        if (isBuiltinRegistration(path) || isBuiltinRegistration(modelId.toString())) {
            return true;
        }
        return isBuiltinRegistration(ModelIdUtil.getInternalModelId(path))
            || isBuiltinRegistration(ModelIdUtil.getInternalModelId(modelId.toString()));
    }

    private static boolean isBuiltinRegistration(String modelId) {
        RegisteredSource registered = MODEL_SOURCES.get(modelId);
        return registered != null && registered.priority == PRIORITY_BUILTIN;
    }

    /** 该扫描根是不是内置运行时落点 {@link #BUILT}（两边都按绝对规范化路径比）。 */
    public static boolean isBuiltinRoot(Path scanRoot) {
        return scanRoot != null && BUILT.toAbsolutePath()
            .normalize()
            .equals(
                scanRoot.toAbsolutePath()
                    .normalize());
    }

    /**
     * 该 (扫描根, 模型 id) 是否算内置。契约不可读（{@link BuiltinModelIndex#declaredInternalIds()} 为空）时只看
     * 扫描根并 warn 一次；否则必须被契约声明过——这正是"用户手放在 {@code config/ysmu/builtin} 里的多余文件不算
     * 内置"的判定。
     */
    static boolean isBuiltinOrigin(Path scanRoot, String modelId) {
        if (!isBuiltinRoot(scanRoot)) {
            return false;
        }
        Set<String> declared = BuiltinModelIndex.declaredInternalIds();
        if (declared.isEmpty()) {
            if (!builtinContractWarningLogged && BuiltinModelIndex.fingerprintHex() == null) {
                builtinContractWarningLogged = true;
                ysmu.LOG.warn(
                    "Built-in model contract {} is unreadable, so every model under {} is treated as built-in by "
                        + "scan root (the built-in copy keeps winning same-id conflicts)",
                    BuiltinModelIndex.RESOURCE_PATH,
                    BUILT);
            }
            return true;
        }
        return declared.contains(modelId);
    }

    /**
     * 同名来源的裁决点，也是唯一写 {@link #MODEL_SOURCES}/{@link #PACK_SOURCES} 的地方。
     *
     * @return 这次登记是否生效（{@code false} = 保留已登记的那份）
     */
    private static boolean claim(String kind, String key, int priority, CatalogRootKind rootKind, Path source,
        Path scanRoot, Map<String, RegisteredSource> sources, AtomicInteger conflicts) {
        Path normalized = source.toAbsolutePath()
            .normalize();
        String relativePath = relativePathOf(scanRoot, source);
        RegisteredSource existing = sources.get(key);
        if (existing == null || existing.source.equals(normalized)) {
            // 首次登记，或同一磁盘来源的第二次登记（OpenYsmFormat 先 legacy 半边、再补 17 载荷）＝补齐。
            sources.put(key, new RegisteredSource(normalized, priority, relativePath, rootKind));
            return true;
        }
        conflicts.incrementAndGet();
        boolean wins = compareRank(priority, relativePath, existing) < 0;
        // 上游同一句话："Catalog source conflicts with a selected model"（ReloadableModelCatalog:626-627）；
        // 这里连两个来源路径与两个优先级一起打，才能不看代码就定位是哪一个目录赢了。
        ysmu.LOG.warn(
            "Catalog source conflicts with a selected {}: '{}' already comes from {} (priority {}); {} {} (priority {})",
            kind,
            key,
            existing.source,
            existing.priority,
            wins ? "replacing it with" : "discarding",
            normalized,
            priority);
        if (wins) {
            sources.put(key, new RegisteredSource(normalized, priority, relativePath, rootKind));
        }
        return wins;
    }

    /**
     * 来源所属的扫描根，即上游 {@code ModelSourceResolver} 从目录名得出的 {@code CatalogRootKind}：三个已知根按
     * 绝对规范化路径比较，其余（不认识的根、{@code null}）一律当 {@link CatalogRootKind#CUSTOM}——那是最宽松的
     * 一档（PUBLIC），不会因为一个路径没被认出来就意外把模型变成受限。
     */
    static CatalogRootKind rootKindOf(Path scanRoot) {
        if (isBuiltinRoot(scanRoot)) {
            return CatalogRootKind.BUILTIN;
        }
        if (isAuthRoot(scanRoot)) {
            return CatalogRootKind.AUTH;
        }
        return CatalogRootKind.CUSTOM;
    }

    /**
     * 该扫描根的优先级。{@link CatalogRootKind#BUILTIN} 里那些契约没声明的目录退回 {@link #PRIORITY_CUSTOM}
     * （第 10 项："用户手放进内置目录的目录不算内置"），那与权限无关——{@code BUILTIN} 与 {@code CUSTOM} 的
     * {@code accessPolicy} 都是 PUBLIC。
     */
    private static int priorityOf(CatalogRootKind rootKind, Path scanRoot, String modelId) {
        if (rootKind == CatalogRootKind.AUTH) {
            return PRIORITY_AUTH;
        }
        if (rootKind == CatalogRootKind.BUILTIN) {
            return isBuiltinOrigin(scanRoot, modelId) ? PRIORITY_BUILTIN : PRIORITY_CUSTOM;
        }
        return PRIORITY_CUSTOM;
    }

    /** 该扫描根是不是受限根 {@link #AUTH}（两边都按绝对规范化路径比）。 */
    public static boolean isAuthRoot(Path scanRoot) {
        return scanRoot != null && AUTH.toAbsolutePath()
            .normalize()
            .equals(
                scanRoot.toAbsolutePath()
                    .normalize());
    }

    /**
     * 该模型对玩家是公开的还是需要授权——上游
     * {@code model/catalog/snapshot/CatalogRecord.localEntry:32-33}
     * （{@code access = rootKind == AUTH ? AUTHORIZED : PUBLIC}）的对应物，只不过这里的"记录"是
     * {@link #MODEL_SOURCES} 里生效的那条来源。
     *
     * <p>拼写规范化与 {@link #isBuiltinModel(ResourceLocation)} 同一套（裸磁盘名、{@code ysmu:} 全名、
     * {@code _name_<hex>} 内部 id），因为调用方拿到的可能是其中任一种。未知模型返回
     * {@link CatalogAccess#PUBLIC}：服务端没有它的来源，也就无从说它受限；这一条同时保证客户端本地
     * {@code /ysmclient load} 注册的模型不会被授权判定挡住（见 {@code api/ModelAuthorization} 的说明）。</p>
     */
    public static CatalogAccess accessOf(ResourceLocation modelId) {
        if (modelId == null) {
            return CatalogAccess.PUBLIC;
        }
        String path = modelId.getResourcePath();
        CatalogRootKind kind = registeredRootKind(path);
        if (kind == null) {
            kind = registeredRootKind(modelId.toString());
        }
        if (kind == null) {
            kind = registeredRootKind(ModelIdUtil.getInternalModelId(path));
        }
        if (kind == null) {
            kind = registeredRootKind(ModelIdUtil.getInternalModelId(modelId.toString()));
        }
        return kind == CatalogRootKind.AUTH ? CatalogAccess.AUTHORIZED : CatalogAccess.PUBLIC;
    }

    /** 该模型是否由 auth 根提供；{@link #accessOf(ResourceLocation)} 的布尔半边。 */
    public static boolean isAuthModel(ResourceLocation modelId) {
        return accessOf(modelId) == CatalogAccess.AUTHORIZED;
    }

    private static CatalogRootKind registeredRootKind(String modelId) {
        RegisteredSource registered = MODEL_SOURCES.get(modelId);
        return registered == null ? null : registered.rootKind;
    }

    /**
     * 生效来源属于 {@link CatalogRootKind#AUTH} 的每个登记键，即服务端当前受限的模型集合（上游
     * {@code CatalogSnapshot.byModelId} 里 {@code access == AUTHORIZED} 那些条目）。菜单与诊断用；
     * 集合按登记表实时算，所以 reload 之后立刻正确。
     */
    public static Set<String> authModelIds() {
        Set<String> ids = new TreeSet<>();
        for (Map.Entry<String, RegisteredSource> entry : MODEL_SOURCES.entrySet()) {
            if (entry.getValue().rootKind == CatalogRootKind.AUTH) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    /**
     * 上游的排序键：先优先级，再相对路径升序（{@code ReloadableModelCatalog.selectRecords:607-610}）。返回负值表示
     * 新来源排在已登记者前面、应当接替它；相等（同优先级 + 同相对路径）返回 0，由调用方保留先登记者——上游"先者
     * 占位、后者记冲突丢弃"的同一形态。
     */
    private static int compareRank(int priority, String relativePath, RegisteredSource existing) {
        int byPriority = Integer.compare(priority, existing.priority);
        return byPriority != 0 ? byPriority : relativePath.compareTo(existing.relativePath);
    }

    /**
     * 来源在扫描根内的相对路径（{@code /} 分隔）——上游 {@code CatalogModelLocation.path()} 的对应物，同名冲突的
     * 次级排序键。来源不在根下时退回整条绝对路径，保证排序仍然确定（不返回 null）。
     */
    private static String relativePathOf(Path scanRoot, Path source) {
        Path normalized = source.toAbsolutePath()
            .normalize();
        if (scanRoot != null) {
            try {
                return scanRoot.toAbsolutePath()
                    .normalize()
                    .relativize(normalized)
                    .toString()
                    .replace('\\', '/');
            } catch (IllegalArgumentException notRelative) {
                // 不同盘符等情形：落到下面的整条路径。
            }
        }
        return normalized.toString();
    }

    private static void rebuildModelCaches() {
        // M-18:单个模型/单个扫描器失败不得让整次 reload 中断；每一步单独兜底。
        // 包清单先扫：它只读 ysm-pack.json/png，既不影响下面的模型登记，也让 GUI 的文件夹格子在模型到达前
        // 就已有名字与封面。
        //
        // M1/D9:扫描顺序只是<b>执行顺序</b>，不再是重复 modelId 的胜负规则——三个扫描器都从
        // {@link #registerModel} 走，胜负由 {@link #PRIORITY_BUILTIN}/{@link #PRIORITY_AUTH}/{@link #PRIORITY_CUSTOM}
        // 显式裁决（内置赢，auth 胜过同路径的 custom，与顺序无关；同优先级保持"后扫的赢"，见 registerModel 的
        // javadoc 第 2 条）。builtin 仍排在 custom 之后，因为 registerModel 的"同一磁盘来源补齐"与各扫描器的去重
        // （FolderFormat 的 hasOpenYsmRegistration）都建立在"OpenYSM 先于 legacy、custom 先于 builtin"这个既有顺序上。
        // auth 插在 custom 与 builtin 之间，与上游 reloadableSources() 的 [CUSTOM, AUTH] 顺序一致（ReloadableModelCatalog
        // 的优先级表与扫描顺序无关，所以这个位置只影响日志与执行耗时）。
        //
        // 索引侧车（ModelIndexCache）把 cache step 包起来：它为真时各扫描器会跳过已经烘过的源。它任何时候都
        // 只是加速 —— 未命中、索引文件缺失/损坏/版本不符、缓存文件不在，都照旧走下面的完整路径，
        // 所以没有索引时的行为与加它之前逐字节一致。侧车按 (扫描器, 根目录名) 记条目，所以多出来的 auth 根
        // 自动拥有自己的一格，不需要提升 INDEX_VERSION。
        ModelIndexCache.beginReload();
        try {
            cacheRoot("custom", CUSTOM);
            // 第 3 个根：上游的 AUTH（`config/ysmu/auth`），与前两个根走同一套扫描器，只多一层权限分类。
            cacheRoot("auth", AUTH);
            cacheRoot("builtin", BUILT);
        } finally {
            // 与 beginReload 成对：合并本轮记录并原子落盘，失败只 warn。
            ModelIndexCache.commitReload();
        }
    }

    /**
     * 一个扫描根的三个扫描器步骤。顺序仍是"OpenYSM 先于 legacy"——这是同一磁盘来源去重与"补齐"登记的前提
     * （{@code FolderFormat} 的 {@code hasOpenYsmRegistration}），与根的权限分类无关。
     */
    private static void cacheRoot(String label, Path root) {
        runCacheStep("packs (" + label + ")", () -> PackFormat.cacheAllPacks(root));
        runCacheStep("OpenYSM (" + label + ")", () -> {
            OpenYsmFormat.cacheAllModels(root);
            ModelIndexCache.scanCompleted(ModelIndexCache.Scanner.OPEN_YSM, root);
        });
        runCacheStep("legacy (" + label + ")", () -> cacheAllModels(root));
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
     * 把 jar 内 {@code assets/ysmu/builtin/} 的整棵树**按需**镜像到运行时目录 {@link #BUILT}
     * （{@code config/ysmu/builtin}）：随包契约 {@code builtin-index.json} 的指纹
     * （{@link BuiltinModelIndex#fingerprintHex()}）加模组版本与落点里的 stamp 一致、且契约声明的每个模型路径都还在
     * 时，跳过清空与解压；否则保持 SOURCE 的形状——{@code FileUtils.cleanDirectory(target)} 后整树解压（决策 D7）
     * ——再写 stamp。
     *
     * <p><b>为什么是"按需"（移植特有能力，上游没有对应物）。</b>上游的内置模型在 jar 内、由原生层在内存里
     * materialize（{@code model/catalog/builtin/BuiltinModelMaterializer}，没有落盘副本），所以不存在"要不要重写"
     * 这个问题；本仓必须把 {@code ysm.json} 与现代包两种格式摊到磁盘上给扫描器读，每轮 reload 把那 585 个文件、
     * 约 57 MB 重抄一遍纯属浪费。判定与 {@link ModelIndexCache} 同族——用内容指纹决定要不要重建：</p>
     * <ul>
     * <li>指纹 = 契约文件字节的 sha256 + {@link Tags#VERSION}：内置集合或任一模型内容变了、模组换版本了就重写；</li>
     * <li>契约不可读（{@link BuiltinModelIndex#fingerprintHex()} 为 {@code null}）时<b>不跳过</b>：这时连"该有哪些
     * 文件"都不知道，保持整树重写最稳；</li>
     * <li>契约路径有缺失（用户删了/改坏了某个内置模型目录）时<b>不跳过</b>：整树重写顺带修好它。</li>
     * </ul>
     *
     * <p><b>这是一处有意的语义变化。</b>指纹一致时运行时副本不再被每轮覆盖：用户对 {@code config/ysmu/builtin/**}
     * 的改动与添加的文件会保留到下一次契约变化。{@link #BUILT} 仍然不是用户数据（它是模组的落点，
     * {@code notice.txt} 也这么写），但这确实不同于本仓旧行为（每轮清空重写），所以 AGENTS.md 与 notice 文案一起
     * 记着它。契约路径集合同时是"内置身份"（{@link #isBuiltinOrigin}）：契约没声明的目录既不算内置、也不会被清掉。</p>
     *
     * <p>清空（只有真的重写时）仍然安全：{@link #BUILT} 是模组的解压落点，{@link #CUSTOM} 是用户目录、绝不清理。</p>
     *
     * @param target 解压落点（生产是 {@link #BUILT}；参数化是为了用 {@code @TempDir} 验证"跳过 / 用户文件保留 /
     *        缺件自愈"）
     * @param stamp 契约指纹 + 模组版本；{@code null}（契约不可读）时永不跳过
     */
    static void extractBuiltinModels(Path target, String stamp) {
        if (builtinTreeMatches(target, stamp)) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info(
                    "[YSMU-MODEL] Built-in model tree under {} matches the shipped contract; skipping extraction",
                    target);
            }
            return;
        }
        // 只清 target：custom 是用户数据，另一个 built（旧的孤儿 notice 目录）也刻意不动。
        if (Files.isDirectory(target)) {
            try {
                FileUtils.cleanDirectory(target.toFile());
            } catch (IOException e) {
                ysmu.LOG.warn("Failed to clean built-in model directory {}", target, e);
            }
        }
        createFolder(target);
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
                        copyBuiltinPackResource(target, name.substring(entryPrefix.length()));
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
                                    target,
                                    sourceRoot.relativize(source)
                                        .toString()
                                        .replace('\\', '/')));
                    }
                }
            }
            ysmu.LOG.info("Extracted built-in model resources from {} into {}", prefix, target);
            // stamp 只在整棵树真的落地之后写：中途失败就没有 stamp，下次 reload 照旧重写一遍。
            writeBuiltinStamp(target, stamp);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to extract built-in model resources from {}", prefix, e);
        }
    }

    /** 生产入口：落点是 {@link #BUILT}，stamp 由契约指纹与模组版本算出来（见 {@link #builtinStampValue()}）。 */
    private static void extractBuiltinModels() {
        extractBuiltinModels(BUILT, builtinStampValue());
    }

    /** 运行时副本的状态标记文件名；内容 = 契约指纹 + {@link Tags#VERSION}。 */
    static final String BUILTIN_STAMP_FILE_NAME = ".ysmu-builtin-stamp";

    /**
     * stamp 的内容：契约文件字节的 sha256 + 模组版本。契约不可读时返回 {@code null}，调用方据此<b>不</b>跳过解压。
     */
    static String builtinStampValue() {
        String fingerprint = BuiltinModelIndex.fingerprintHex();
        if (fingerprint == null) {
            return null;
        }
        return fingerprint + '\n' + Tags.VERSION + '\n';
    }

    /**
     * {@code target} 是否就是这份 stamp 对应的解压结果：stamp 文件内容一致，<b>且</b>契约声明的每个模型路径都还在。
     * 少了任何一个路径就回答"不匹配"，让整树重写顺带修好它；读不到 stamp 也回答不匹配（重写一次没有代价）。
     */
    static boolean builtinTreeMatches(Path target, String stamp) {
        if (stamp == null || target == null || !Files.isDirectory(target)) {
            return false;
        }
        for (String modelPath : BuiltinModelIndex.declaredModelPaths()) {
            if (!Files.exists(target.resolve(modelPath))) {
                return false;
            }
        }
        Path stampFile = target.resolve(BUILTIN_STAMP_FILE_NAME);
        try {
            return Files.isRegularFile(stampFile)
                && stamp.equals(new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8));
        } catch (Exception | LinkageError unreadable) {
            return false;
        }
    }

    private static void writeBuiltinStamp(Path target, String stamp) {
        if (stamp == null) {
            return;
        }
        try {
            FileUtils.writeStringToFile(target.resolve(BUILTIN_STAMP_FILE_NAME)
                .toFile(), stamp, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to write the built-in extraction stamp under {}", target, e);
        }
    }

    /**
     * 把随 jar 发布的内置契约 {@code assets/ysmu/builtin-index.json} 与 {@link #BUILT} 里**实际**解压出来的内容对照：
     * 路径集合双向覆盖 + 每个模型目录的内容摘要比对，有问题就明确报出来。
     *
     * <h2>上游依据</h2>
     * <ul>
     * <li>{@code model/catalog/source/ModelCatalogSources.builtinIndex():24-27}：上游从模组文件里取同名契约
     * （{@code assets/ysm/builtin-index.json}）。</li>
     * <li>{@code model/catalog/builtin/BuiltinModelIndex.java:283-291} 的 {@code validateCoverage}：双向覆盖
     * ——契约有而实际没有（missing）、实际有而契约没有（unexpected）都算不符。</li>
     * <li>{@code model/catalog/CatalogReconciler.java:101-110}：运行时对内置根照做。</li>
     * </ul>
     *
     * <h2>与上游刻意不同的地方</h2>
     * <p><b>不符时只报，绝不丢根。</b>上游 {@code validateCoverage} 抛错后 {@code CatalogReconciler:105-109}
     * 直接 {@code continue}，整个内置根被跳过——对上游是可接受的（它的内置集合只是"目录的一部分"），对本仓不是：
     * 内置 {@code default} 是客户端启动与兜底渲染的硬依赖，丢根等于所有内置模型消失。所以这里只打
     * {@code LOG.error}/{@code LOG.warn}，**一个模型都不从扫描链里摘掉**。</p>
     * <p>字段用 {@code path}+{@code sha256}（JDK 的 SHA-256，模型目录整棵树的摘要），不是上游那个由
     * {@code ModelHashing.blake3} 算的 {@code modelHash}，也不算上游的 {@code dedupProfileHash}、不含 protobuf。
     * 详见 {@link BuiltinModelIndex} 的类注释。</p>
     * <p>多一个 {@code intentionallyAbsent} 段：上游有、本仓**有意不收录**的模型（当前是三个
     * {@code metadata.license = All Rights Reserved} 的 wine_fox 模型，见 {@code NOTICE.md}）。它们不算缺失；
     * 反过来出现在树里就报 {@code LOG.error}——这条同时是"不要把 ARR 资产拷进来"的守卫。</p>
     */
    private static void verifyBuiltinModelIndex() {
        try {
            BuiltinModelIndex.Report report = BuiltinModelIndex.verifyActual(BUILT);
            if (!report.contractAvailable()) {
                if (report.actualPaths()
                    .isEmpty()) {
                    // 裁剪过的资源包：连内置树都没有，缺契约不是问题（与 extractBuiltinModels 的提前 return 同一情形）。
                    return;
                }
                ysmu.LOG.error(
                    "Built-in model contract {} is unavailable ({}), so the {} built-in models under {} cannot be "
                        + "verified against it",
                    BuiltinModelIndex.RESOURCE_PATH,
                    report.failure(),
                    report.actualPaths()
                        .size(),
                    BUILT);
                return;
            }
            if (report.ok()) {
                ysmu.LOG.info(
                    "Built-in model contract OK: {} models verified against {} (contract fingerprint {})",
                    report.actualPaths()
                        .size(),
                    BuiltinModelIndex.RESOURCE_PATH,
                    BuiltinModelIndex.fingerprintHex());
                return;
            }
            // 以下四项只报不改：扫描链不受影响，模型照常登记。
            if (!report.missing()
                .isEmpty()) {
                ysmu.LOG.error(
                    "Built-in model contract declares {} model(s) that are missing from {}: {} — the jar was built from "
                        + "different built-in assets than the contract; regenerate the contract from the tree",
                    report.missing()
                        .size(),
                    BUILT,
                    report.missing());
            }
            if (!report.hashMismatch()
                .isEmpty()) {
                ysmu.LOG.error(
                    "Built-in model contract hash mismatch for {} model(s) under {}: {} — the model content changed "
                        + "without regenerating the contract",
                    report.hashMismatch()
                        .size(),
                    BUILT,
                    report.hashMismatch());
            }
            if (!report.unexpected()
                .isEmpty()) {
                ysmu.LOG.warn(
                    "{} model(s) under {} are not declared in the built-in contract: {} — either the built-in set grew "
                        + "without regenerating the contract, or a user dropped files into the built-in directory "
                        + "(it is rewritten from the jar every reload)",
                    report.unexpected()
                        .size(),
                    BUILT,
                    report.unexpected());
            }
            if (!report.forbiddenPresent()
                .isEmpty()) {
                Map<String, String> absent = BuiltinModelIndex.intentionallyAbsent();
                for (String path : report.forbiddenPresent()) {
                    ysmu.LOG.error(
                        "Built-in model {} is declared intentionally absent from this repository ({}) but is present "
                            + "under {}; do not ship it",
                        path,
                        absent.get(path),
                        BUILT);
                }
            }
        } catch (Exception | LinkageError e) {
            // 校验本身失败绝不能影响 reload：模型发现与缓存重建都已经在它之前完成了。
            ysmu.LOG.warn("Failed to verify the built-in model contract {}", BuiltinModelIndex.RESOURCE_PATH, e);
        }
    }

    /**
     * 复制 {@code assets/ysmu/builtin/} 下的一个文件，{@code relativePath} 相对该目录；落点是
     * {@code <target>/<relativePath>}，即整棵树的镜像。
     */
    private static void copyBuiltinPackResource(Path target, String relativePath) {
        Path destination = target.resolve(relativePath);
        createFolder(destination.getParent());
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles(BUILTIN_PACK_RESOURCE_ROOT + "/" + relativePath),
            destination.getParent(),
            destination.getFileName()
                .toString());
    }

    private static void cacheAllModels(Path rootPath) {
        YsmFormat.cacheAllModels(rootPath);
        // 这一步里两个扫描器各自有独立的索引命名空间：某个扫描器整棵跑完，才允许 commit 丢掉它在这个根上的
        // 旧条目（上游 ConvertedSourceIndexStore.replaceScopes:102-125 的同一形状）。
        ModelIndexCache.scanCompleted(ModelIndexCache.Scanner.LEGACY_YSM, rootPath);
        FolderFormat.cacheAllModels(rootPath);
        ModelIndexCache.scanCompleted(ModelIndexCache.Scanner.LEGACY_FOLDER, rootPath);
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

    /**
     * 在 {@code builtDir} 里放一份 {@code notice.txt}，说明该目录是模组的落点、用户模型该放 {@link #CUSTOM}。
     *
     * <p>第 ⑩ 项：只在**内容变化**时才写盘。旧版每轮 reload 都重写一遍，而 {@code extractBuiltinModels} 现在在契约
     * 未变时会跳过整树解压，所以这个文件通常已经在那里、内容也一样——重写只会改一次 mtime 并让"运行时副本没被
     * 动过"这件事变得不可观察。文案同步改成实际规则：指纹未变时副本保留（不再声称"每轮被覆盖"）。</p>
     *
     * <p>参数化是为了用 {@code @TempDir} 验证"同内容不写盘"。</p>
     */
    static void initBuiltNoticeFile(Path builtDir) {
        Path noticeFile = builtDir.resolve("notice.txt");
        try {
            String content = "OpenYSM built-in model directory.\n"
                + "It is materialized from assets/ysmu/builtin when the shipped builtin-index.json contract changes;\n"
                + "put your own models in config/ysmu/custom instead.\n";
            if (Files.isRegularFile(noticeFile)
                && content.equals(new String(Files.readAllBytes(noticeFile), StandardCharsets.UTF_8))) {
                return;
            }
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
