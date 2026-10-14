package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.ARM_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.CACHE_SERVER;
import static com.fox.ysmu.model.ServerModelManager.EXTRA_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.MAIN_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SERVER_KEY;
import static com.fox.ysmu.model.ServerModelManager.PASSWORD_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import com.fox.ysmu.Config;
import com.fox.ysmu.Tags;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.ysmu;

/**
 * 服务端模型索引侧车：把「源 → 已经烘好的缓存登记」持久化到 {@code cache/server/model_index.json}，
 * 让下一次 reload 在<b>解密/解析/烘焙/加密之前</b>就判定某个源是否已经烘过，命中则直接重放当时的
 * {@link ServerModelInfo}/{@link OpenYsmSyncInfo}。
 *
 * <p>形状照抄上游的 {@code model/storage/ConvertedSourceIndex.java} 与
 * {@code ConvertedSourceIndexStore.java}（上游是权威，参考分支只作形状参考）：
 * <ul>
 * <li>键是源在<b>扫描根内的相对路径</b>加命名空间前缀，即上游 {@code ModelSourceResolver:63-64} 的
 * {@code <rootKind>/<relativePath>}。本仓有三个扫描器而上游只有一个 resolver，所以键里再带一层扫描器
 * 标识：一次命中只允许重放<b>同一个扫描器</b>当初记下的东西。</li>
 * <li>失效靠<b>源内容指纹</b>，不是 mtime/大小。上游同样只比内容：{@code ModelSourceResolver:66-76} 拿
 * {@code ModelParser.capture} 对源算出的 {@code modelId()} 与索引里的 modelId 比，不相等就重新 convert。</li>
 * <li>整份索引是<b>一个文件</b>：先写临时文件、回读校验、再原子替换（上游
 * {@code ConvertedSourceIndexStore:88-99} 加 {@code AtomicSharedCache.moveCommitted:165-168}）。</li>
 * <li>文件坏了 → 丢掉索引、全部按未命中重建、<b>不抛异常</b>（上游 {@code ConvertedSourceIndexStore:65-68}
 * 连文件都删掉）；版本或密钥对不上 → 保留文件、全部未命中（上游 {@code :59-64}）。</li>
 * </ul>
 *
 * <p><b>索引是加速，不是唯一路径。</b>本类任何一步失败（源读不到、指纹算不出、索引文件坏了、缓存文件
 * 没了或内容不符、JSON 字段缺失）都只是"这一次未命中"：调用方照旧走原来的完整路径，服务端不会因为一个
 * 缓存文件起不来，也不会产出不同的载荷。命中时用的也是 {@link ModelCacheWriter} 自己那套"磁盘缓存还能不能
 * 用"的判定（{@code ModelCacheWriter:133-162}，本类调用的是同一份方法而不是抄一遍），所以"命中"等价于
 * "完整路径在同一份源上也会复用这个缓存文件"，而不是另一套推理。
 *
 * <p>线程模型：{@link #beginReload()}/{@link #commitReload()} 由
 * {@code ServerModelManager.rebuildModelCaches} 在 reload 线程上成对调用，各扫描根 × 各扫描器的 cache step 都是同一
 * 线程上的顺序调用（模型缓存这条路径上没有任何 {@code ThreadTools.THREAD_POOL} 用法），所以这里的容器都是普通
 * {@code LinkedHashMap}/{@code TreeSet}，不引入新的锁。网络线程读的是
 * {@code CACHE_NAME_INFO}/{@code OPEN_YSM_SYNC_INFO}（本来就是并发容器），本类不改变它们的并发语义。
 */
public final class ModelIndexCache {

    /**
     * 侧车文件名，落在 {@code cache/server} 下。{@code ModelCacheWriter.pruneUnregisteredCaches} 只回收
     * 32 位十六进制命名的缓存与 {@code *.tmp} 写入产物（{@code ModelCacheWriter:249-290}），这个名字不在其中，
     * 所以不会被当成陈旧缓存删掉。
     */
    public static final String INDEX_FILE_NAME = "model_index.json";

    /**
     * 文件布局版本：改 JSON 结构、键的形状或指纹配方时 +1，整份索引作废。
     * 对应上游的 {@code "YSMCIDX1"} 幻数（{@code ConvertedSourceIndexStore:30}）。
     */
    private static final int INDEX_VERSION = 1;

    /** schema 名，挡住"把别的 JSON 当索引"。 */
    private static final String SCHEMA = "ysmu-model-index";

    /**
     * 与 {@code ModelCacheWriter.writeAtomically} 同一个后缀：写一半崩掉留下的临时文件会被
     * {@code pruneUnregisteredCaches} 一并回收。
     */
    private static final String TEMP_FILE_SUFFIX = ".tmp";

    /** 条目数上限，对标上游的 {@code MAX_ENTRIES}（{@code ConvertedSourceIndexStore:31}）。 */
    private static final int MAX_ENTRIES = 100_000;

    /** reload 期间装上的索引；{@link #commitReload()} 摘掉。只在 reload 线程上读写，见类注释的线程模型。 */
    private static ModelIndexCache active;

    private final Path cacheServerDir;
    private final Path indexFile;
    private final String modVersion;
    private final String serverKeyFingerprint;
    private final String passwordFingerprint;

    /** 上次落盘的条目，{@link #load()} 读入、{@link #save()} 作为基线做命名空间级替换。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** 本轮重新记下的条目。 */
    private final Map<String, Entry> recorded = new LinkedHashMap<>();
    /** 本轮完整跑完的 (扫描器, 命名空间) 对：只有它们的旧条目才允许在 {@link #save()} 时被丢弃。 */
    private final Set<String> completedScans = new TreeSet<>();
    /** 本轮算过的指纹，避免 lookup 与 remember 各算一遍。 */
    private final Map<String, String> fingerprints = new LinkedHashMap<>();

    private int restored;
    private String cachedDefaultAnimationsFingerprint;

    /**
     * @param cacheServerDir       缓存目录（生产是 {@link ServerModelManager#CACHE_SERVER}，测试用临时目录）
     * @param modVersion           模组版本：任何一个序列化器/解析器改了都会变（上游 {@code fullModVersion} 的围栏）
     * @param serverKeyFingerprint OpenYSM server key 的指纹；OpenYSM 缓存文件名由它派生
     * @param passwordFingerprint  传统缓存密码文件的指纹；传统缓存的 MD5 由这把 AES 钥匙决定
     */
    public ModelIndexCache(Path cacheServerDir, String modVersion, String serverKeyFingerprint,
        String passwordFingerprint) {
        if (cacheServerDir == null) {
            throw new IllegalArgumentException("Model index cache directory is required");
        }
        this.cacheServerDir = cacheServerDir;
        this.indexFile = cacheServerDir.resolve(INDEX_FILE_NAME);
        this.modVersion = modVersion == null ? "" : modVersion;
        this.serverKeyFingerprint = serverKeyFingerprint;
        this.passwordFingerprint = passwordFingerprint;
    }

    // ---------------------------------------------------------------- reload 生命周期

    /**
     * 开始一次 reload：读侧车、清空本轮状态。索引不可用（server key 或 PASSWORD 拿不到）时直接不装索引，
     * 于是这一轮所有源都走完整路径 —— 与没有这个类时一致。
     */
    public static void beginReload() {
        String serverKey = sha256OfBytes(OPEN_YSM_SERVER_KEY);
        String password = sha256OfFile(PASSWORD_FILE);
        if (serverKey == null || password == null) {
            uninstall();
            return;
        }
        ModelIndexCache cache = new ModelIndexCache(CACHE_SERVER, Tags.VERSION, serverKey, password);
        cache.load();
        install(cache);
    }

    /** 结束一次 reload：清理已消失的条目并原子落盘。任何异常都只 warn，不影响这次 reload 的结果。 */
    public static void commitReload() {
        ModelIndexCache cache = active;
        uninstall();
        if (cache != null) {
            cache.save();
        }
    }

    /** 装上本轮的索引。{@link #beginReload()} 的生产路径就是"{@code new} + {@link #load()} + 这里"。 */
    static void install(ModelIndexCache cache) {
        active = cache;
    }

    /** 摘掉本轮的索引：之后的 {@link #restoreIfHit} 一律未命中、{@link #remember} 一律不记。 */
    static void uninstall() {
        active = null;
    }

    /**
     * 某个扫描器在某个扫描根上完整跑完了。只有跑完的 (扫描器, 命名空间) 的旧条目才允许被替换掉，
     * 这样一次失败的扫描只会让它自己那些条目下次重建，而不会把别的命名空间一起清掉
     * （上游 {@code ConvertedSourceIndexStore.replaceScopes:102-125} 的同一形状）。
     */
    public static void scanCompleted(Scanner scanner, Path root) {
        ModelIndexCache cache = active;
        if (cache != null && scanner != null) {
            cache.complete(scanner, Source.namespaceOf(root));
        }
    }

    /**
     * 命中则把当时登记的两份信息重新放进 {@code CACHE_NAME_INFO}/{@code OPEN_YSM_SYNC_INFO} 并返回 true，
     * 调用方直接跳过该模型的解密/解析/烘焙/加密。未命中或索引不可用返回 false，调用方走原路。
     *
     * <p>第 6 项：重放也走 {@code ServerModelManager#registerModel} 这个唯一入口，否则"索引命中"与"完整路径"
     * 会有两套同名裁决规则。来源就是索引条目记下的 (扫描器, 扫描根, 相对路径)，与当初完整路径登记时同一份，
     * 所以优先级一致（{@link Source#getRoot()} 就是为此保留的扫描根）。</p>
     */
    public static boolean restoreIfHit(Source source, String modelId) {
        ModelIndexCache cache = active;
        if (cache == null || source == null || modelId == null) {
            return false;
        }
        Optional<Entry> hit = cache.hit(source, modelId);
        if (!hit.isPresent()) {
            return false;
        }
        Entry entry = hit.get();
        ServerModelManager.registerModel(modelId, entry.toServerModelInfo(), entry.toOpenYsmSyncInfo(),
            source.getAbsolute(), source.getRoot());
        return true;
    }

    /**
     * 记录一次完整路径的结果。{@code legacy == null}（这次什么都没登记）时不记，下一次照旧重建；
     * {@code syncInfo == null} 表示这次没有登记 OpenYSM 同步缓存，命中时就照原样不登记。
     */
    public static void remember(Source source, String modelId, ServerModelInfo legacy, OpenYsmSyncInfo syncInfo) {
        ModelIndexCache cache = active;
        if (cache != null && source != null) {
            cache.record(source, modelId, legacy, syncInfo);
        }
    }

    // ---------------------------------------------------------------- 实例核心（测试直接用它）

    /** 读侧车。文件缺失/不可读/损坏/版本不符都只是"没有可用条目"，不抛异常。 */
    public void load() {
        entries.clear();
        recorded.clear();
        completedScans.clear();
        fingerprints.clear();
        restored = 0;
        cachedDefaultAnimationsFingerprint = null;
        if (serverKeyFingerprint == null || passwordFingerprint == null || !Files.isRegularFile(indexFile)) {
            return;
        }
        final String text;
        try {
            text = new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8);
        } catch (Exception | LinkageError unreadable) {
            ysmu.LOG.warn("Model index cache {} is unreadable; rebuilding every model this reload", indexFile,
                unreadable);
            return;
        }
        try {
            StoredIndex stored = ysmu.GSON.fromJson(text, StoredIndex.class);
            if (stored == null || !SCHEMA.equals(stored.schema) || stored.version != INDEX_VERSION
                || !modVersion.equals(stored.modVersion) || !serverKeyFingerprint.equals(stored.serverKeyFp)
                || !passwordFingerprint.equals(stored.passwordFp)) {
                // 不是损坏，只是这一代（版本/密钥）的索引不适用：保留文件，全部按未命中处理。
                return;
            }
            entries.putAll(parse(stored));
        } catch (Exception | LinkageError malformed) {
            ysmu.LOG.warn("Model index cache {} is malformed ({}); discarding it and rebuilding every model", indexFile,
                malformed.getMessage());
            discard();
        }
    }

    /**
     * 查一次命中：指纹一致，且磁盘上的缓存文件仍然通过了 {@link ModelCacheWriter} 那套判定。
     *
     * <p>指纹在<b>看条目之前</b>就算：同一次 reload 里 {@link #record} 会复用它，于是索引里记的永远是
     * "解析之前那一刻读到的源"。万一源在解析/烘焙期间被改写，这一轮烘出的载荷与记下的指纹就不再对应，
     * 下一次 reload 会正确地未命中并重烘，而不是把旧载荷当命中发出去。
     *
     * @return 命中的条目；任何不确定的因素（源读不到、缓存文件没了、内容不符）都返回
     *         {@link Optional#empty()}，调用方走完整路径
     */
    public Optional<Entry> hit(Source source, String modelId) {
        if (source == null || modelId == null) {
            return Optional.empty();
        }
        try {
            String fingerprint = fingerprint(source, modelId);
            if (fingerprint == null) {
                return Optional.empty();
            }
            Entry entry = entries.get(source.key());
            if (entry == null || !modelId.equals(entry.getModelId())) {
                return Optional.empty();
            }
            if (!fingerprint.equals(entry.getFingerprint())) {
                return Optional.empty();
            }
            // 与完整路径同一份判定：能复用磁盘缓存才叫命中。缓存文件被 prune、被截断、被改动，
            // 或者 OpenYSM 的签名对不上，都退回完整路径重烘。
            if (!ModelCacheWriter.isStoredLegacyCacheValid(
                cacheServerDir.resolve(entry.getMd5())
                    .toFile(),
                entry.getMd5())) {
                return Optional.empty();
            }
            OpenYsm openYsm = entry.getOpenYsm();
            if (openYsm != null && !ModelCacheWriter.isStoredOpenYsmCacheValid(
                cacheServerDir.resolve(openYsm.getCacheFileName())
                    .toFile(),
                openYsm.getHash1(),
                openYsm.getHash2())) {
                return Optional.empty();
            }
            restored++;
            return Optional.of(entry);
        } catch (Exception | LinkageError failure) {
            ysmu.LOG.warn("Model index lookup failed for {}; rebuilding it", source.getAbsolute(), failure);
            return Optional.empty();
        }
    }

    /** 记下一次完整路径的结果。任何失败都只是"这条不入索引"，绝不打扰已经完成的登记。 */
    public void record(Source source, String modelId, ServerModelInfo legacy, OpenYsmSyncInfo syncInfo) {
        if (source == null || modelId == null || legacy == null) {
            return;
        }
        try {
            String fingerprint = fingerprint(source, modelId);
            if (fingerprint == null) {
                return;
            }
            recorded.put(source.key(),
                new Entry(source.getScanner(), source.getNamespace(), source.getRelativePath(), fingerprint, modelId,
                    legacy.getType(), new ArrayList<>(legacy.getTextures()), legacy.getMd5(),
                    syncInfo == null ? null : new OpenYsm(syncInfo)));
        } catch (Exception | LinkageError failure) {
            ysmu.LOG.warn("Cannot index model cache for {}; the next reload rebuilds it", source.getAbsolute(),
                failure);
        }
    }

    /** 标记某个 (扫描器, 命名空间) 本轮完整扫过。 */
    public void complete(Scanner scanner, String namespace) {
        if (scanner != null && namespace != null && !namespace.isEmpty()) {
            completedScans.add(scanKey(scanner, namespace));
        }
    }

    /**
     * 合并本轮的记录并原子落盘：以磁盘上的条目为基线，丢掉"本轮完整扫过"的 (扫描器, 命名空间) 的旧条目，
     * 再放上本轮记下的条目（上游 {@code ConvertedSourceIndexStore.replace/replaceScopes:71-125}）。
     *
     * @return 是否成功落盘；失败只 warn，磁盘上原来那份索引保持不变
     */
    public boolean save() {
        try {
            if (serverKeyFingerprint == null || passwordFingerprint == null) {
                return false;
            }
            Map<String, Entry> merged = new LinkedHashMap<>(entries);
            Iterator<Map.Entry<String, Entry>> iterator = merged.entrySet()
                .iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next()
                    .getValue();
                if (completedScans.contains(scanKey(entry.getScanner(), entry.getNamespace()))) {
                    iterator.remove();
                }
            }
            merged.putAll(recorded);
            writeAtomically(ysmu.GSON.toJson(toStored(merged)));
            entries.clear();
            entries.putAll(merged);
            if (restored > 0) {
                ysmu.LOG.info("Model index cache: restored {} model cache registration(s) from {}", restored,
                    indexFile);
            }
            return true;
        } catch (Exception | LinkageError failure) {
            ysmu.LOG.warn("Failed to save model index cache {}; the next reload rebuilds every model", indexFile,
                failure);
            return false;
        }
    }

    /** 当前知道的条目数 = 磁盘基线 ∪ 本轮记录，供诊断与测试（不是两个集合的算术和）。 */
    public int entryCount() {
        Map<String, Entry> known = new LinkedHashMap<>(entries);
        known.putAll(recorded);
        return known.size();
    }

    /** 本轮命中的次数。 */
    public int restoredCount() {
        return restored;
    }

    // ---------------------------------------------------------------- 指纹

    /**
     * 源指纹：只要源的字节变了，指纹就变；只要所有输入都一模一样，指纹就一样。
     *
     * <p>输入清单（每项都能改变最终载荷，逐项说明理由）：
     * <ol>
     * <li>源自己的全部文件内容（目录递归，单文件是整个文件），每段带长度前缀、按相对路径排序。
     * <b>只比 mtime/大小是不安全的</b> —— 解压、{@code git checkout}、复制粘贴、图片重压缩都能在不改
     * mtime（或只改 mtime 不改内容）的情况下改变载荷，而这里要回答的是"载荷会一样吗"，所以取内容。</li>
     * <li>{@link Tags#VERSION}：任何一个序列化器/解析器/适配器的代码改了都会变，是上游
     * {@code ConvertedSourceIndex.fullModVersion} 的同一个围栏（{@code ConvertedSourceIndexStore:59-64}）。
     * 传统容器没有自己的烘焙版本常量，这一项是它的兜底。</li>
     * <li>{@code ModelCacheWriter.hashSourceFor}：里面已经带了 {@code OPEN_YSM_BAKE_VERSION} 与
     * {@code OPEN_YSM_SYNC_FORMAT}，直接复用而不是在这里再抄一份常量。</li>
     * <li>{@link EncryptTools#PREVIEW_MODEL_VERSION}：传统缓存容器<b>当前</b>写出的载荷版本（头部第二个字，
     * {@code EncryptTools.assembleEncryptModels} 写它；v7 加了 GUI 预览声明与 {@code lang/*.json} 语言表段，
     * v6 加了 {@code metadata.links} 段，v5 及更早仍可读）。
     * 布局一改，同一份源烘出来的 MD5 也会变。取"当前那一版"而不是某一个历史常量，是为了让这个围栏跟着
     * 容器版本走；v6 加链接段时是 {@code LINKS_MODEL_VERSION}，v7 加预览/语言段时换成
     * {@code PREVIEW_MODEL_VERSION}（{@code Tags.VERSION} 是兜底的那张网）。</li>
     * <li>{@link Config#ACCEPT_SOUND_FX}：会让 {@code ModelCacheWriter} 剥掉音效（{@code :186-194}），
     * 载荷内容随之改变。</li>
     * <li>三份内置缺省动画（模型自己没带时 {@code RawYsmModelAdapter}/{@code FolderFormat}/{@code YsmFormat}
     * 会去读它们，见 {@link ServerModelManager#defaultAnimationFile}）：它们的内容就是载荷的一部分。</li>
     * <li>源键本身（扫描器 + 命名空间 + 相对路径）：路径决定 modelId，也决定 {@code hashSourceFor} 的兜底内容。</li>
     * </ol>
     * server key 与 PASSWORD 的指纹不在这里逐条混入，而是放在索引文件头部做整份围栏（见 {@link #load()}）。
     *
     * @return 十六进制指纹；源读不到时返回 {@code null}（= 这条不入索引 / 不算命中）
     */
    String fingerprint(Source source, String modelId) {
        String cacheKey = source.key() + '\0' + modelId;
        String cached = fingerprints.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            MessageDigest digest = newDigest();
            updateText(digest, SCHEMA);
            updateInt(digest, INDEX_VERSION);
            updateText(digest, modVersion);
            updateText(digest, serverKeyFingerprint);
            updateText(digest, passwordFingerprint);
            updateText(digest, ModelCacheWriter.hashSourceFor(null, modelId));
            updateInt(digest, EncryptTools.PREVIEW_MODEL_VERSION);
            updateText(digest, Boolean.toString(Config.ACCEPT_SOUND_FX));
            updateText(digest, defaultAnimationsFingerprint());
            updateText(digest, source.key());
            updateSourceTree(digest, source.getAbsolute());
            String fingerprint = Md5Utils.toHexString(digest.digest());
            fingerprints.put(cacheKey, fingerprint);
            return fingerprint;
        } catch (Exception | LinkageError failure) {
            ysmu.LOG.warn("Cannot fingerprint model source {}; it is rebuilt instead", source.getAbsolute(), failure);
            return null;
        }
    }

    /**
     * 三份内置缺省动画的内容指纹。记的是"解析到的那份文件的字节"而不是它的路径：换成一个内容相同的文件
     * 不影响载荷，也就不该让指纹变化。
     */
    private String defaultAnimationsFingerprint() {
        if (cachedDefaultAnimationsFingerprint != null) {
            return cachedDefaultAnimationsFingerprint;
        }
        StringBuilder builder = new StringBuilder();
        String[] names = { MAIN_ANIMATION_FILE_NAME, ARM_ANIMATION_FILE_NAME, EXTRA_ANIMATION_FILE_NAME };
        for (String name : names) {
            builder.append(name)
                .append('=');
            Path path = ServerModelManager.defaultAnimationFile(name);
            byte[] bytes = null;
            if (path != null && Files.isRegularFile(path)) {
                try {
                    bytes = Files.readAllBytes(path);
                } catch (Exception | LinkageError unreadable) {
                    bytes = null;
                }
            }
            builder.append(bytes == null ? "missing" : sha256Hex(bytes));
            builder.append('\n');
        }
        cachedDefaultAnimationsFingerprint = builder.toString();
        return cachedDefaultAnimationsFingerprint;
    }

    private static void updateSourceTree(MessageDigest digest, Path source) throws IOException {
        if (Files.isDirectory(source)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(source)) {
                walk.filter(Files::isRegularFile)
                    .forEach(files::add);
            }
            // 排序只为"同一棵树每次得到同一个指纹"；顺序本身不参与任何协议。
            files.sort((left, right) -> relativeName(source, left).compareTo(relativeName(source, right)));
            for (Path file : files) {
                updateText(digest, relativeName(source, file));
                updateBytes(digest, Files.readAllBytes(file));
            }
            return;
        }
        updateBytes(digest, Files.readAllBytes(source));
    }

    private static String relativeName(Path root, Path file) {
        return root.relativize(file)
            .toString()
            .replace('\\', '/');
    }

    private static void updateText(MessageDigest digest, String value) {
        updateBytes(digest, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /** 长度前缀让拼接无歧义（"a"+"bc" 与 "ab"+"c" 不能得到同一个指纹）。 */
    private static void updateBytes(MessageDigest digest, byte[] bytes) {
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value });
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 digest is not available", impossible);
        }
    }

    /** @return SHA-256 十六进制；{@code null}/空输入返回 {@code null}（= 这份状态拿不到，索引整体不可用） */
    private static String sha256OfBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return sha256Hex(bytes);
    }

    /** 不定长输入的 SHA-256 十六进制（空串也有确定的摘要，用于缺省动画的内容指纹）。 */
    private static String sha256Hex(byte[] bytes) {
        return Md5Utils.toHexString(newDigest().digest(bytes));
    }

    private static String sha256OfFile(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            return sha256OfBytes(Files.readAllBytes(file));
        } catch (Exception | LinkageError unreadable) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 落盘

    /**
     * 原子写：临时文件 → 回读校验（写出来的字节必须能解析回同一份内容）→ 原子替换。
     * 因此下次启动绝不会把半截 JSON 当成有效索引；校验或替换失败时磁盘上原来那份索引保持不变。
     */
    private void writeAtomically(String text) throws IOException {
        Path parent = indexFile.getParent();
        if (parent == null) {
            throw new IOException("Model index " + indexFile + " has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, indexFile.getFileName()
            .toString(), TEMP_FILE_SUFFIX);
        try {
            Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
            String readBack = new String(Files.readAllBytes(temporary), StandardCharsets.UTF_8);
            if (!text.equals(ysmu.GSON.toJson(ysmu.GSON.fromJson(readBack, StoredIndex.class)))) {
                throw new IOException("Model index verification failed after writing " + temporary);
            }
            try {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void discard() {
        try {
            Files.deleteIfExists(indexFile);
        } catch (Exception | LinkageError failure) {
            ysmu.LOG.warn("Failed to delete malformed model index cache {}", indexFile, failure);
        }
    }

    private static Map<String, Entry> parse(StoredIndex stored) {
        if (stored.entries == null) {
            throw new MalformedIndexException("no entries array");
        }
        if (stored.entries.size() > MAX_ENTRIES) {
            throw new MalformedIndexException("too many entries: " + stored.entries.size());
        }
        Map<String, Entry> parsed = new LinkedHashMap<>();
        for (StoredEntry entry : stored.entries) {
            if (entry == null) {
                throw new MalformedIndexException("null entry");
            }
            Entry converted = entry.toEntry();
            if (parsed.put(converted.key(), converted) != null) {
                throw new MalformedIndexException("duplicate entry " + converted.key());
            }
        }
        return parsed;
    }

    private StoredIndex toStored(Map<String, Entry> merged) {
        StoredIndex stored = new StoredIndex();
        stored.schema = SCHEMA;
        stored.version = INDEX_VERSION;
        stored.modVersion = modVersion;
        stored.serverKeyFp = serverKeyFingerprint;
        stored.passwordFp = passwordFingerprint;
        List<StoredEntry> list = new ArrayList<>(merged.size());
        for (Entry entry : new TreeMap<>(merged).values()) {
            list.add(StoredEntry.of(entry));
        }
        stored.entries = list;
        return stored;
    }

    private static String scanKey(Scanner scanner, String namespace) {
        return scanner.id() + '/' + namespace;
    }

    // ---------------------------------------------------------------- 类型

    /** 记录这条条目的是哪一个扫描器。上游只有一个 resolver，本仓有三个，所以键里必须区分。 */
    public enum Scanner {

        OPEN_YSM("openysm"),
        LEGACY_FOLDER("folder"),
        LEGACY_YSM("ysm");

        private final String id;

        Scanner(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Scanner byId(String id) {
            for (Scanner scanner : values()) {
                if (scanner.id.equals(id)) {
                    return scanner;
                }
            }
            return null;
        }
    }

    /**
     * 一个源：哪个扫描器、哪个扫描根（命名空间）、根内相对路径。形状取上游的
     * {@code <rootKind>/<relativePath>}（{@code ModelSourceResolver:63-64}）再加一层扫描器。
     */
    public static final class Source {

        private final Scanner scanner;
        private final String namespace;
        private final String relativePath;
        private final Path absolute;
        /**
         * 扫描根（绝对规范化）。{@link #key()} 只带命名空间，所以它不进索引键；但"命中重放"要按来源优先级登记
         * （第 6 项：{@code ServerModelManager.registerModel} 只有同时拿到来源与扫描根才能判定内置），
         * {@code config/ysmu/custom} 与 {@code config/ysmu/builtin} 的命名空间恰好不同名时也不能靠名字猜。
         */
        private final Path root;

        private Source(Scanner scanner, String namespace, String relativePath, Path absolute, Path root) {
            this.scanner = scanner;
            this.namespace = namespace;
            this.relativePath = relativePath;
            this.absolute = absolute;
            this.root = root;
        }

        /**
         * @return 这个源；参数缺失或源不在根下时返回 {@code null}，索引整条链于是都当未命中，
         *         不会把异常抛回扫描器
         */
        public static Source of(Scanner scanner, Path root, Path source) {
            if (scanner == null || root == null || source == null) {
                return null;
            }
            String namespace = namespaceOf(root);
            if (namespace.isEmpty()) {
                return null;
            }
            final Path relative;
            try {
                relative = root.relativize(source);
            } catch (IllegalArgumentException notARelativePath) {
                return null;
            }
            String relativePath = relative.toString()
                .replace('\\', '/');
            if (relativePath.isEmpty() || relativePath.startsWith("../")) {
                return null;
            }
            return new Source(scanner, namespace, relativePath,
                source.toAbsolutePath()
                    .normalize(),
                root.toAbsolutePath()
                    .normalize());
        }

        /** 扫描根的名字就是命名空间（{@code config/ysmu/custom} → {@code custom}）。 */
        public static String namespaceOf(Path root) {
            if (root == null || root.getFileName() == null) {
                return "";
            }
            return root.getFileName()
                .toString();
        }

        public String key() {
            return scanner.id() + '/' + namespace + '/' + relativePath;
        }

        public Scanner getScanner() {
            return scanner;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public Path getAbsolute() {
            return absolute;
        }

        /** 这个源所属扫描根的绝对规范化路径；判内置身份用（见 {@code root} 字段的注释）。 */
        public Path getRoot() {
            return root;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    /** {@link OpenYsmSyncInfo} 的持久化形态（本来就全是不可变标量）。 */
    public static final class OpenYsm {

        private final String modelId;
        private final String cacheFileName;
        private final long hash1;
        private final long hash2;
        private final int format;
        private final boolean customSkinModel;

        public OpenYsm(OpenYsmSyncInfo info) {
            this(modelIdOf(info), cacheFileNameOf(info), info.getHash1(), info.getHash2(), info.getFormat(),
                info.isCustomSkinModel());
        }

        public OpenYsm(String modelId, String cacheFileName, long hash1, long hash2, int format,
            boolean customSkinModel) {
            this.modelId = requireText(modelId, "openYsm.modelId");
            this.cacheFileName = requireText(cacheFileName, "openYsm.cacheFileName");
            this.hash1 = hash1;
            this.hash2 = hash2;
            this.format = format;
            this.customSkinModel = customSkinModel;
        }

        private static String modelIdOf(OpenYsmSyncInfo info) {
            if (info == null) {
                throw new MalformedIndexException("missing openYsm");
            }
            return info.getModelId();
        }

        private static String cacheFileNameOf(OpenYsmSyncInfo info) {
            return info == null ? null : info.getCacheFileName();
        }

        public String getModelId() {
            return modelId;
        }

        public String getCacheFileName() {
            return cacheFileName;
        }

        public long getHash1() {
            return hash1;
        }

        public long getHash2() {
            return hash2;
        }

        public int getFormat() {
            return format;
        }

        public boolean isCustomSkinModel() {
            return customSkinModel;
        }
    }

    /**
     * 一条索引条目：{@link ServerModelInfo} 与可选的 {@link OpenYsmSyncInfo} 的全部可观察字段。
     * 与上游的 {@code ConvertedSourceIndex} 一样，字段缺失/为空一律当作损坏（构造时抛）。
     */
    public static final class Entry {

        private final Scanner scanner;
        private final String namespace;
        private final String relativePath;
        private final String fingerprint;
        private final String modelId;
        private final Type type;
        private final List<String> textures;
        private final String md5;
        private final OpenYsm openYsm;

        public Entry(Scanner scanner, String namespace, String relativePath, String fingerprint, String modelId,
            Type type, List<String> textures, String md5, OpenYsm openYsm) {
            this.scanner = require(scanner, "scanner");
            this.namespace = requireText(namespace, "namespace");
            this.relativePath = requireText(relativePath, "source");
            this.fingerprint = requireText(fingerprint, "fingerprint");
            this.modelId = requireText(modelId, "modelId");
            this.type = require(type, "type");
            this.md5 = requireText(md5, "md5");
            List<String> copy = new ArrayList<>();
            for (String texture : require(textures, "textures")) {
                copy.add(requireText(texture, "texture"));
            }
            this.textures = Collections.unmodifiableList(copy);
            this.openYsm = openYsm;
        }

        public String key() {
            return scanner.id() + '/' + namespace + '/' + relativePath;
        }

        /**
         * 重建完整路径当初放进 {@code CACHE_NAME_INFO} 的那个对象。贴图集合的顺序照旧，
         * 因为 {@code ServerModelInfo.getTexture()} 取的是第一个元素。
         */
        public ServerModelInfo toServerModelInfo() {
            ServerModelInfo info = new ServerModelInfo(new LinkedHashSet<>(textures), type);
            info.setMd5(md5);
            return info;
        }

        /** @return 完整路径当初放进 {@code OPEN_YSM_SYNC_INFO} 的对象；{@code null} 表示当初没登记。 */
        public OpenYsmSyncInfo toOpenYsmSyncInfo() {
            if (openYsm == null) {
                return null;
            }
            return new OpenYsmSyncInfo(openYsm.getModelId(), openYsm.getCacheFileName(), openYsm.getHash1(),
                openYsm.getHash2(), openYsm.getFormat(), openYsm.isCustomSkinModel());
        }

        public Scanner getScanner() {
            return scanner;
        }

        public String getNamespace() {
            return namespace;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public String getModelId() {
            return modelId;
        }

        public Type getType() {
            return type;
        }

        public List<String> getTextures() {
            return textures;
        }

        public String getMd5() {
            return md5;
        }

        public OpenYsm getOpenYsm() {
            return openYsm;
        }
    }

    // ---------------------------------------------------------------- 文件里的 JSON 形状

    /**
     * 磁盘形状的 DTO。刻意不用 record：1.7.10 这边运行时带的 Gson 早于支持 record 的版本，
     * 反序列化走字段反射而不是规范构造器；字段用可空类型，缺失就是 {@code null}，能被判成损坏。
     */
    private static final class StoredIndex {

        String schema;
        int version;
        String modVersion;
        String serverKeyFp;
        String passwordFp;
        List<StoredEntry> entries;
    }

    private static final class StoredEntry {

        String scanner;
        String namespace;
        String source;
        String fingerprint;
        String modelId;
        String type;
        List<String> textures;
        String md5;
        StoredOpenYsm openYsm;

        static StoredEntry of(Entry entry) {
            StoredEntry stored = new StoredEntry();
            stored.scanner = entry.getScanner()
                .id();
            stored.namespace = entry.getNamespace();
            stored.source = entry.getRelativePath();
            stored.fingerprint = entry.getFingerprint();
            stored.modelId = entry.getModelId();
            stored.type = entry.getType()
                .name();
            stored.textures = entry.getTextures();
            stored.md5 = entry.getMd5();
            stored.openYsm = entry.getOpenYsm() == null ? null : StoredOpenYsm.of(entry.getOpenYsm());
            return stored;
        }

        Entry toEntry() {
            Scanner byId = Scanner.byId(scanner);
            if (byId == null) {
                throw new MalformedIndexException("unknown scanner " + scanner);
            }
            if (type == null) {
                throw new MalformedIndexException("missing type");
            }
            return new Entry(byId, namespace, source, fingerprint, modelId, Type.valueOf(type), textures, md5,
                openYsm == null ? null : openYsm.toOpenYsm());
        }
    }

    private static final class StoredOpenYsm {

        String modelId;
        String cacheFileName;
        Long hash1;
        Long hash2;
        Integer format;
        Boolean customSkinModel;

        static StoredOpenYsm of(OpenYsm openYsm) {
            StoredOpenYsm stored = new StoredOpenYsm();
            stored.modelId = openYsm.getModelId();
            stored.cacheFileName = openYsm.getCacheFileName();
            stored.hash1 = openYsm.getHash1();
            stored.hash2 = openYsm.getHash2();
            stored.format = openYsm.getFormat();
            stored.customSkinModel = openYsm.isCustomSkinModel();
            return stored;
        }

        OpenYsm toOpenYsm() {
            return new OpenYsm(requireText(modelId, "openYsm.modelId"),
                requireText(cacheFileName, "openYsm.cacheFileName"), require(hash1, "openYsm.hash1"),
                require(hash2, "openYsm.hash2"), require(format, "openYsm.format"),
                customSkinModel != null && customSkinModel);
        }
    }

    /**
     * 索引数据不合法：文件级损坏（不是"这一代索引"）或某条条目的字段缺失/不认识。
     * 与上游 {@code ConvertedSourceIndexStore.MalformedIndexException} 同一形状，一律按"整份不可用"处理。
     */
    private static final class MalformedIndexException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private MalformedIndexException(String message) {
            super(message);
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new MalformedIndexException("missing " + name);
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new MalformedIndexException("missing " + name);
        }
        return value;
    }
}
