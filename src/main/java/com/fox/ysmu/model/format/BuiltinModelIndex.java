package com.fox.ysmu.model.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 内置模型契约：{@code assets/ysmu/builtin-index.json} 声明"随本 jar 发布的内置模型有哪些、每个的内容哈希是多少"，
 * 本类负责解析它、把它与 {@code config/ysmu/builtin} 里实际解压出来的内容对照，并把对照结果交给调用方去报。
 *
 * <h2>上游依据</h2>
 * <ul>
 * <li>上游有同名契约 {@code assets/ysm/builtin-index.json}，运行时从模组文件里取：
 * {@code model/catalog/source/ModelCatalogSources.builtinIndex():24-27}
 * （{@code ModList.get().getModFileById(MOD_ID).getFile().findResource("assets", MOD_ID, "builtin-index.json")}）。</li>
 * <li>严格 schema 与"必须声明 default"：{@code model/catalog/builtin/BuiltinModelIndex.java:38-44}
 * （{@code ROOT_FIELDS}/{@code MODEL_FIELDS} 的 {@code requireExactFields}）、{@code :112-115}（formatVersion）、
 * {@code :293-297}（{@code requireDefault} 要求契约里必须有 {@code default}）。</li>
 * <li>双向覆盖：同文件 {@code :283-291} 的 {@code validateCoverage}——{@code missing} = 契约有而实际没有，
 * {@code unexpected} = 实际有而契约没有，两者任一非空即失败。</li>
 * <li>运行时同查：{@code model/catalog/CatalogReconciler.java:101-110} 在每次目录清点时对内置根调用
 * {@code validateCoverage}。</li>
 * </ul>
 *
 * <h2>与上游刻意不同的地方（移植特有）</h2>
 * <ul>
 * <li><b>哈希算法</b>：上游每条是 {@code modelHash}，由 {@code model/storage/ModelHashing.blake3} 算的 blake3，
 * 而且哈希对象是 materialize 之后的载荷（{@code ConvertedObjectStore:97}）。本仓没有那层 protobuf/容器，
 * 所以字段直接叫 {@code sha256}，值是**模型目录整棵文件树的内容摘要**（定义见
 * {@link #sha256OfModelDir(Path)}），用 JDK 的 {@code MessageDigest} 算，不引任何新依赖。</li>
 * <li><b>不算 {@code dedupProfileHash}</b>：上游根字段里有它（{@code :39}），本仓不搬。</li>
 * <li><b>不符时只报，绝不丢根</b>：上游 {@code validateCoverage} 抛 {@code IOException} 后
 * {@code CatalogReconciler:105-109} 直接 {@code continue} —— 整个内置根被跳过，内置模型全部消失。本仓
 * 的内置兜底模型是客户端启动的硬依赖，所以 {@link Report} 只是数据，由调用方
 * （{@code ServerModelManager.verifyBuiltinModelIndex}）打 error/warn，一个模型都不从扫描里摘掉。</li>
 * <li><b>多一个 {@code intentionallyAbsent} 段</b>：记录"上游有、本仓有意不收录"的模型（当前是三个
 * {@code metadata.license = All Rights Reserved} 的 wine_fox 模型）。它们不算 {@code missing}；
 * 反过来，如果它们出现在实际树里，{@link Report#forbiddenPresent()} 会把它报出来——这条同时是许可守卫。</li>
 * </ul>
 *
 * <p>契约不可读/不合法时语义（调用方需要知道）：{@link #declaredModelPaths()} 等一律返回空集合、
 * {@link #fingerprintHex()} 返回 {@code null}、{@link #verify(Set)} 返回 {@code contractAvailable() == false}
 * 的 {@link Report}，**都不抛异常**。调用方据此决定"是否值得报一条"（例如内置树被裁剪掉、连模型都没有时，
 * 缺契约不是问题）。</p>
 */
public final class BuiltinModelIndex {

    /** 契约在 jar 内的位置。上游同名文件叫 {@code assets/ysm/builtin-index.json}。 */
    public static final String RESOURCE_PATH = "/assets/ysmu/builtin-index.json";

    /** 契约格式版本；不匹配即视为不可用（上游 {@code BuiltinModelIndex:36,114-116} 同一形状）。 */
    public static final int FORMAT_VERSION = 1;

    /** 契约必须声明它：内置兜底模型，上游 {@code requireDefault:293-297} 同样强制。 */
    public static final String REQUIRED_MODEL_PATH = "default";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private static final String YSM_JSON = "ysm.json";
    private static final String MAIN_JSON = "main.json";
    private static final String ARM_JSON = "arm.json";
    private static final String PACK_INFO = "ysm-pack.json";

    private static volatile Contract shipped;

    private BuiltinModelIndex() {}

    // ------------------------------------------------------------------ 随 jar 发布的那份契约

    /** 懒加载 jar 内那份契约；读不到时返回 {@link Contract#unavailable(String)}。 */
    public static Contract shipped() {
        Contract local = shipped;
        if (local == null) {
            synchronized (BuiltinModelIndex.class) {
                local = shipped;
                if (local == null) {
                    local = loadShipped();
                    shipped = local;
                }
            }
        }
        return local;
    }

    private static Contract loadShipped() {
        try (InputStream stream = BuiltinModelIndex.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return Contract.unavailable("no such resource: " + RESOURCE_PATH);
            }
            return parse(stream.readAllBytes());
        } catch (Exception | LinkageError e) {
            return Contract.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 静态便利（= shipped() 转发）

    /** 契约声明的模型相对路径（{@code default}、{@code misc/2_steve} …）；契约不可用时为空集。 */
    public static Set<String> declaredModelPaths() {
        return shipped().modelPaths();
    }

    /** 同一集合换算成登记键（{@link ModelIdUtil#getInternalModelId(String)}）；契约不可用时为空集。 */
    public static Set<String> declaredInternalIds() {
        return shipped().internalModelIds();
    }

    /** 路径 → 内容 sha256（小写 hex）；契约不可用时为空表。 */
    public static Map<String, String> declaredModelHashes() {
        return shipped().modelHashes();
    }

    /** 有意不收录的上游模型 → 原因；契约不可用时为空表。 */
    public static Map<String, String> intentionallyAbsent() {
        return shipped().intentionallyAbsent();
    }

    /** 契约文件的原始字节；不可用时为 {@code null}。 */
    public static byte[] rawBytes() {
        return shipped().rawBytes();
    }

    /** 契约文件原始字节的 sha256（小写 hex），可用作"内置集合是否变化"的 stamp；不可用时为 {@code null}。 */
    public static String fingerprintHex() {
        return shipped().fingerprintHex();
    }

    /** 以"实际发现的相对路径集合"为输入做双向覆盖；契约不可用时返回不可用的 {@link Report}。 */
    public static Report verify(Set<String> actualModelPaths) {
        return shipped().verify(actualModelPaths);
    }

    /** 扫 {@code builtRoot} 并做双向覆盖 + 逐路径哈希比对。 */
    public static Report verifyActual(Path builtRoot) throws IOException {
        return shipped().verifyActual(builtRoot);
    }

    // ------------------------------------------------------------------ 解析

    /** 解析一份契约。任何 schema 问题都落成 {@code available() == false} 的 {@link Contract}，不抛。 */
    public static Contract parse(byte[] jsonBytes) {
        if (jsonBytes == null || jsonBytes.length == 0) {
            return Contract.unavailable("empty contract");
        }
        JsonElement document;
        try {
            document = new JsonParser().parse(new String(jsonBytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Contract.unavailable("invalid JSON: " + e.getMessage());
        }
        if (document == null || !document.isJsonObject()) {
            return Contract.unavailable("contract root must be an object");
        }
        JsonObject root = document.getAsJsonObject();

        JsonElement versionElement = root.get("formatVersion");
        if (versionElement == null || !versionElement.isJsonPrimitive() || !versionElement.getAsJsonPrimitive().isNumber()) {
            return Contract.unavailable("formatVersion must be a number");
        }
        if (versionElement.getAsInt() != FORMAT_VERSION) {
            return Contract.unavailable("unsupported formatVersion: " + versionElement.getAsString());
        }

        JsonElement modelsElement = root.get("models");
        if (modelsElement == null || !modelsElement.isJsonArray()) {
            return Contract.unavailable("models must be an array");
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (JsonElement element : modelsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                return Contract.unavailable("each models entry must be an object");
            }
            JsonObject model = element.getAsJsonObject();
            String path = readString(model, "path");
            String hash = readString(model, "sha256");
            if (path == null || path.isEmpty()) {
                return Contract.unavailable("models entry without a path");
            }
            if (hash == null || !SHA256_HEX.matcher(hash).matches()) {
                return Contract.unavailable("models entry " + path + " without a lowercase sha256");
            }
            if (hashes.put(path, hash) != null) {
                return Contract.unavailable("duplicate model path: " + path);
            }
        }
        if (!hashes.containsKey(REQUIRED_MODEL_PATH)) {
            // 上游 requireDefault:293-297 在构建契约时就拒绝没有 default 的索引。
            return Contract.unavailable("contract does not declare the required model: " + REQUIRED_MODEL_PATH);
        }

        Map<String, String> absent = new LinkedHashMap<>();
        JsonElement absentElement = root.get("intentionallyAbsent");
        if (absentElement != null && !absentElement.isJsonNull()) {
            if (!absentElement.isJsonArray()) {
                return Contract.unavailable("intentionallyAbsent must be an array");
            }
            for (JsonElement element : absentElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    return Contract.unavailable("each intentionallyAbsent entry must be an object");
                }
                JsonObject entry = element.getAsJsonObject();
                String path = readString(entry, "path");
                if (path == null || path.isEmpty()) {
                    return Contract.unavailable("intentionallyAbsent entry without a path");
                }
                String reason = readString(entry, "reason");
                absent.put(path, reason == null ? "" : reason);
            }
        }

        List<String> forbiddenDeclared = new ArrayList<>();
        for (String path : absent.keySet()) {
            if (hashes.containsKey(path)) {
                forbiddenDeclared.add(path);
            }
        }
        if (!forbiddenDeclared.isEmpty()) {
            return Contract.unavailable("path declared both as a model and as intentionally absent: " + forbiddenDeclared);
        }

        return Contract.available(jsonBytes, hashes, absent);
    }

    private static String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    // ------------------------------------------------------------------ 内容摘要与扫描

    /**
     * 一个模型目录的**内容摘要**，契约里的 {@code sha256} 就是它。定义（重复实现必须逐字一致，否则校验会假报）：
     *
     * <ol>
     * <li>取该目录下所有常规文件（不递归跟随符号链接，空目录不计）；</li>
     * <li>每个文件算 sha256，得到 {@code "相对路径/NUL/小写hex" }；相对路径用 {@code '/'} 分隔；</li>
     * <li>按相对路径的 Java 自然序升序排序，每行以 {@code '\n'} 结尾拼成一个规范串（UTF-8）；</li>
     * <li>对规范串再取 sha256，小写 hex。</li>
     * </ol>
     *
     * <p>NUL 分隔符让"路径"与"哈希"不可能互相串位。摘要覆盖目录里的**每一个**文件，包括
     * {@code sounds/*.ogg}、{@code functions/*.molang}、{@code lang/*.json} —— 它们都是模型内容
     * （{@code YSMFolderDeserializer.parseGlobalResources:987-1012} 会把它们收进 soundFiles / functionFiles /
     * languageFiles），所以内容变了摘要就该变。</p>
     */
    public static String sha256OfModelDir(Path modelDir) throws IOException {
        List<String> relativePaths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(modelDir)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> relativePaths.add(normalizeRelative(modelDir.relativize(file))));
        }
        Collections.sort(relativePaths);
        StringBuilder canonical = new StringBuilder();
        for (String relative : relativePaths) {
            canonical.append(relative)
                .append('\u0000')
                .append(sha256Hex(Files.readAllBytes(modelDir.resolve(relative))))
                .append('\n');
        }
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 扫出 {@code root} 下所有"模型目录"的相对路径（正斜杠），就地把 {@code root} 自身排除。
     *
     * <p>认领规则与加载链一致，不另立一套：目录有 {@code ysm.json} 即模型（
     * {@code YSMFolderDeserializer.isModelFolder:1201-1209}），否则根下有 {@code main.json}+{@code arm.json}
     * 也算旧扁平模型的判据在 {@code FolderFormat.isModelDirectory:99-117}（那条还要求至少一个 png，
     * 这里不重复它：契约要覆盖的是"加载链会认领的目录"，而 png 缺失的目录由解析层报错，不该从契约里消失）。</p>
     *
     * <p>两处必须复现：包根（含 {@code ysm-pack.json}）自己是容器、要**继续下钻**
     * （{@code OpenYsmFormat:47-50}、{@code FolderFormat:57-62}）；认领一个目录后 {@code SKIP_SUBTREE}
     * （{@code OpenYsmFormat:58}、{@code FolderFormat:81}）——现代包的 {@code models/} 子目录里同样有
     * {@code main.json}+{@code arm.json}，不下钻跳过的话每个模型会被数成两个。</p>
     */
    public static Set<String> discoverModelPaths(Path root) throws IOException {
        Set<String> found = new TreeSet<>();
        if (root == null || !Files.isDirectory(root)) {
            return found;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isRegularFile(dir.resolve(PACK_INFO))) {
                    return FileVisitResult.CONTINUE;
                }
                if (!isModelDirectory(dir)) {
                    return FileVisitResult.CONTINUE;
                }
                found.add(normalizeRelative(root.relativize(dir)));
                return FileVisitResult.SKIP_SUBTREE;
            }
        });
        return found;
    }

    private static boolean isModelDirectory(Path dir) {
        if (Files.isRegularFile(dir.resolve(YSM_JSON))) {
            return true;
        }
        return Files.isRegularFile(dir.resolve(MAIN_JSON)) && Files.isRegularFile(dir.resolve(ARM_JSON));
    }

    private static String normalizeRelative(Path path) {
        return path.toString()
            .replace('\\', '/');
    }

    // ------------------------------------------------------------------ 生成

    /**
     * 由实际树渲染一份契约 JSON（生成器用；运行时只解析与校验，绝不回写）。{@code path} 升序、
     * {@code sha256} 小写 hex，末尾一个换行，走 {@link ysmu#GSON}（与仓库其它 JSON 落盘同一形状）。
     *
     * @param intentionallyAbsent 有意不收录的上游模型 → 原因；本仓的许可结论由生成方给出，
     *                            运行时不内嵌常量，避免"Java 常量与已提交文件两份真相"。
     */
    public static String render(Path builtRoot, Map<String, String> intentionallyAbsent) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty(
            "note",
            "Built-in model contract. Each sha256 is the content digest of that model directory "
                + "(see BuiltinModelIndex.sha256OfModelDir). intentionallyAbsent records upstream models this repository "
                + "does not ship on purpose; they are not coverage failures, and finding them in the tree is an error.");
        JsonArray models = new JsonArray();
        for (String path : discoverModelPaths(builtRoot)) {
            JsonObject model = new JsonObject();
            model.addProperty("path", path);
            model.addProperty("sha256", sha256OfModelDir(builtRoot.resolve(path)));
            models.add(model);
        }
        root.add("models", models);
        JsonArray absent = new JsonArray();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(intentionallyAbsent).entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("path", entry.getKey());
            item.addProperty("reason", entry.getValue());
            absent.add(item);
        }
        root.add("intentionallyAbsent", absent);
        return ysmu.GSON.toJson(root) + "\n";
    }

    // ------------------------------------------------------------------ 哈希工具

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(data);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16))
                    .append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法；真缺了就只能显式失败，而不是悄悄返回 null。
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    // ------------------------------------------------------------------ 契约本体

    /** 一份解析好的契约。不可用时除 {@link #available()} / {@link #failure()} 外一律为空。 */
    public static final class Contract {

        private final boolean available;
        private final String failure;
        private final byte[] raw;
        private final Map<String, String> hashes;
        private final Map<String, String> absent;
        private final String fingerprint;

        private Contract(boolean available, String failure, byte[] raw, Map<String, String> hashes,
            Map<String, String> absent) {
            this.available = available;
            this.failure = failure;
            this.raw = raw;
            this.hashes = hashes;
            this.absent = absent;
            this.fingerprint = raw == null ? null : sha256Hex(raw);
        }

        static Contract available(byte[] raw, Map<String, String> hashes, Map<String, String> absent) {
            return new Contract(
                true,
                null,
                raw,
                Collections.unmodifiableMap(new LinkedHashMap<>(hashes)),
                Collections.unmodifiableMap(new LinkedHashMap<>(absent)));
        }

        static Contract unavailable(String failure) {
            return new Contract(
                false,
                failure,
                null,
                Collections.emptyMap(),
                Collections.emptyMap());
        }

        public boolean available() {
            return available;
        }

        /** 不可用的原因；可用时为 {@code null}。 */
        public String failure() {
            return failure;
        }

        /** 契约声明的模型相对路径，已排序。 */
        public Set<String> modelPaths() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(hashes.keySet()));
        }

        /** 契约声明的模型路径换算成登记键。 */
        public Set<String> internalModelIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (String path : hashes.keySet()) {
                ids.add(ModelIdUtil.getInternalModelId(path));
            }
            return Collections.unmodifiableSet(ids);
        }

        public Map<String, String> modelHashes() {
            return hashes;
        }

        public Map<String, String> intentionallyAbsent() {
            return absent;
        }

        /** 原始字节的副本；不可用时为 {@code null}。 */
        public byte[] rawBytes() {
            return raw == null ? null : raw.clone();
        }

        public String fingerprintHex() {
            return fingerprint;
        }

        /** 双向覆盖（只看路径集合；哈希用 {@link #verifyHashes(Map)} 或 {@link #verifyActual(Path)}）。 */
        public Report verify(Set<String> actualModelPaths) {
            Map<String, String> actual = new LinkedHashMap<>();
            if (actualModelPaths != null) {
                for (String path : actualModelPaths) {
                    actual.put(path, null);
                }
            }
            return report(actual);
        }

        /** 双向覆盖 + 逐路径哈希比对，输入是"路径 → 实际内容 sha256"。 */
        public Report verifyHashes(Map<String, String> actualModelHashes) {
            Map<String, String> actual = actualModelHashes == null ? Collections.emptyMap() : actualModelHashes;
            return report(actual);
        }

        /** 扫 {@code builtRoot}、算每个模型目录的摘要，再做双向覆盖 + 哈希比对。 */
        public Report verifyActual(Path builtRoot) throws IOException {
            Map<String, String> actual = new LinkedHashMap<>();
            for (String path : discoverModelPaths(builtRoot)) {
                actual.put(path, sha256OfModelDir(builtRoot.resolve(path)));
            }
            return report(actual);
        }

        private Report report(Map<String, String> actual) {
            List<String> actualPaths = new ArrayList<>(actual.keySet());
            Collections.sort(actualPaths);
            if (!available) {
                // 契约不可用：不把"实际发现的一切"都报成 unexpected（那会刷屏且毫无信息量），
                // 只把实际集合交出去，让调用方自己决定说什么。
                return new Report(false, failure, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), actualPaths);
            }

            List<String> missing = new ArrayList<>();
            for (String path : hashes.keySet()) {
                if (!actual.containsKey(path) && !absent.containsKey(path)) {
                    missing.add(path);
                }
            }
            List<String> unexpected = new ArrayList<>();
            List<String> hashMismatch = new ArrayList<>();
            List<String> forbiddenPresent = new ArrayList<>();
            for (String path : actualPaths) {
                if (absent.containsKey(path)) {
                    // 契约说这个上游模型"有意不收录"（当前是三个 ARR），树里却有它。
                    forbiddenPresent.add(path);
                    continue;
                }
                String declared = hashes.get(path);
                if (declared == null) {
                    unexpected.add(path);
                    continue;
                }
                String actualHash = actual.get(path);
                if (actualHash != null && !declared.equals(actualHash)) {
                    hashMismatch.add(path);
                }
            }
            Collections.sort(missing);
            Collections.sort(unexpected);
            Collections.sort(hashMismatch);
            Collections.sort(forbiddenPresent);
            return new Report(true, null, missing, unexpected, hashMismatch, forbiddenPresent, actualPaths);
        }
    }

    // ------------------------------------------------------------------ 校验结果

    /** 一次校验的结果。**只是数据**：调用方负责打日志，本类从不据此丢弃任何模型。 */
    public static final class Report {

        private final boolean contractAvailable;
        private final String failure;
        private final List<String> missing;
        private final List<String> unexpected;
        private final List<String> hashMismatch;
        private final List<String> forbiddenPresent;
        private final List<String> actualPaths;

        private Report(boolean contractAvailable, String failure, List<String> missing, List<String> unexpected,
            List<String> hashMismatch, List<String> forbiddenPresent, List<String> actualPaths) {
            this.contractAvailable = contractAvailable;
            this.failure = failure;
            this.missing = Collections.unmodifiableList(missing);
            this.unexpected = Collections.unmodifiableList(unexpected);
            this.hashMismatch = Collections.unmodifiableList(hashMismatch);
            this.forbiddenPresent = Collections.unmodifiableList(forbiddenPresent);
            this.actualPaths = Collections.unmodifiableList(actualPaths);
        }

        public boolean contractAvailable() {
            return contractAvailable;
        }

        /** 契约不可用时的原因；可用时为 {@code null}。 */
        public String failure() {
            return failure;
        }

        /** 契约声明、树里却没有（不含 {@code intentionallyAbsent}）。 */
        public List<String> missing() {
            return missing;
        }

        /** 树里有、契约却没声明。 */
        public List<String> unexpected() {
            return unexpected;
        }

        /** 路径对得上但内容摘要不同。 */
        public List<String> hashMismatch() {
            return hashMismatch;
        }

        /** 契约声明"有意不收录"（例如上游 ARR）却出现在树里——许可守卫。 */
        public List<String> forbiddenPresent() {
            return forbiddenPresent;
        }

        /** 本轮实际发现（已排序），供调用方描述"这轮到底扫到了什么"。 */
        public List<String> actualPaths() {
            return actualPaths;
        }

        /** 契约可用且四项问题清单全空。 */
        public boolean ok() {
            return contractAvailable && missing.isEmpty()
                && unexpected.isEmpty()
                && hashMismatch.isEmpty()
                && forbiddenPresent.isEmpty();
        }
    }
}
