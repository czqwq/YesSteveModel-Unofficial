package com.fox.ysmu.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.catalog.CatalogAccess;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.OpenYsmFormat;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.ServerPackData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.format.YsmFormat;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * 第 6 项：同名模型/同名包根的裁决点。上游是
 * {@code model/catalog/ReloadableModelCatalog.selectRecords:602-642}（显式优先级 {@code BUILTIN 0 / AUTH 1 /
 * CUSTOM 2}、冲突记 {@code "Catalog source conflicts with a selected model"} 并丢弃低优先级那份），本仓把它收敛
 * 到 {@link ServerModelManager#registerModel} 这一个入口。
 *
 * <p>这里钉住四件事：内置赢且与扫描顺序无关、同优先级按上游的相对路径排序键取先者、冲突有计数可观测、用户手放进
 * 内置目录的目录不算内置。</p>
 *
 * <p>日志文本本身没有在这里断言：单元 JVM 里 {@code ysmu.LOG} 背后的 appender 由 log4j 实现决定，仓库没有测试
 * 用的 log4j-core 依赖；冲突日志的两个来源路径与两个优先级在 {@code ServerModelManager.claim} 里，用例改成断言
 * "哪个来源赢了"（赢家写进 {@code CACHE_NAME_INFO} 的 md5）——那才是日志背后的同一份事实。</p>
 */
class ServerModelRegistrationTest {

    /** {@code default} 是契约保证声明的内置模型（{@code BuiltinModelIndex.requireDefault} 的上游同款约束）。 */
    private static final String BUILTIN_MODEL = "default";

    @AfterEach
    void resetRegistry() {
        // 三张登记表与来源表都是静态的，用例之间必须复位到 reload 起点。
        ServerModelManager.clearModelCaches();
    }

    // -----------------------------------------------------------------------------------------------------------
    // 内置赢：与扫描顺序无关
    // -----------------------------------------------------------------------------------------------------------

    @Test
    void theBuiltinCopyWinsAConflictWhicheverOrderTheScannersRunIn() {
        registerFromCustom(BUILTIN_MODEL, "custom-md5");
        registerFromBuiltin(BUILTIN_MODEL, "builtin-md5");
        assertEquals("builtin-md5", winnerMd5(BUILTIN_MODEL), "内置排在后面时内置赢（今天的既有结果）");
        assertEquals(1, ServerModelManager.modelConflictCount());

        ServerModelManager.clearModelCaches();

        registerFromBuiltin(BUILTIN_MODEL, "builtin-md5");
        registerFromCustom(BUILTIN_MODEL, "custom-md5");
        assertEquals("builtin-md5", winnerMd5(BUILTIN_MODEL), "内置排在前面时同样赢：胜负不再由扫描顺序决定");
        assertEquals(1, ServerModelManager.modelConflictCount());
        assertTrue(ServerModelManager.isBuiltinModel(new ResourceLocation("ysmu", BUILTIN_MODEL)));
    }

    @Test
    void anEncodedBuiltinIdIsRecognizedByItsInternalIdNotByItsNakedName() {
        // misc/2_steve 这种带斜杠的路径登记键是 _name_<utf8 hex>；这正是 AGENTS.md 记录过的那次"按 id 末段比
        // steve/alex 恒不命中"的同一个坑，只是方向相反——判定必须用登记键本身。
        String internalId = ModelIdUtil.getInternalModelId("misc/2_steve");
        assertNotEquals("misc/2_steve", internalId, "这条用例的前提是该 id 需要编码");

        registerFromBuiltin(internalId, "builtin-md5");

        assertTrue(ServerModelManager.isBuiltinModel(new ResourceLocation("ysmu", internalId)));
        assertFalse(
            ServerModelManager.isBuiltinModel(new ResourceLocation("ysmu", "2_steve")),
            "裸目录名不是登记键，不能凭它放行");
    }

    // -----------------------------------------------------------------------------------------------------------
    // 受权限保护的第 3 个根：auth（优先级 BUILTIN 0 / AUTH 1 / CUSTOM 2）
    // -----------------------------------------------------------------------------------------------------------

    @Test
    void theAuthRootBeatsCustomAtTheSameRelativePathButStillLosesToBuiltin() {
        // 上游 ReloadableModelCatalog.rootPriority:636-642 的 AUTH 档，以及
        // docs/architecture/model-management/catalog-and-sources.md:25 的"同一路径的 auth 候选优先于 custom"。
        registerFromCustom(BUILTIN_MODEL, "custom-md5");
        registerFromAuth(BUILTIN_MODEL, "auth-md5");
        assertEquals("auth-md5", winnerMd5(BUILTIN_MODEL), "auth 的优先级 1 胜过 custom 的 2");
        assertEquals(1, ServerModelManager.modelConflictCount());

        ServerModelManager.clearModelCaches();

        registerFromAuth(BUILTIN_MODEL, "auth-md5");
        registerFromCustom(BUILTIN_MODEL, "custom-md5");
        assertEquals("auth-md5", winnerMd5(BUILTIN_MODEL), "胜负与登记顺序无关");
        assertEquals(1, ServerModelManager.modelConflictCount());

        ServerModelManager.clearModelCaches();

        registerFromAuth(BUILTIN_MODEL, "auth-md5");
        registerFromBuiltin(BUILTIN_MODEL, "builtin-md5");
        assertEquals("builtin-md5", winnerMd5(BUILTIN_MODEL), "内置的优先级 0 仍然压过 auth 的 1");
        assertEquals(1, ServerModelManager.modelConflictCount());
    }

    @Test
    void aModelIsRestrictedExactlyWhenItsEffectiveSourceIsTheAuthRoot() {
        // 上游 CatalogRecord.localEntry:32-33：access = rootKind == AUTH ? AUTHORIZED : PUBLIC。
        ResourceLocation id = new ResourceLocation("ysmu", "guarded");

        assertFalse(ServerModelManager.isAuthModel(id), "还没登记：未知 id 一律按 PUBLIC（不能凭未知就拦人）");
        assertEquals(CatalogAccess.PUBLIC, ServerModelManager.accessOf(null));

        registerFromAuth("guarded", "auth-md5");
        assertTrue(ServerModelManager.isAuthModel(id));
        assertEquals(Collections.singleton("guarded"), ServerModelManager.authModelIds());

        // 生效来源换人（内置压过 auth），访问级别跟着换：同一个 id 回到 PUBLIC。用契约声明的内置 id，因为内置根里
        // 未被声明的目录按 CUSTOM 优先级参与裁决（第 10 项），那种情况下 auth 的 1 档仍然赢，测不到本命题。
        registerFromAuth("default", "auth-md5");
        assertTrue(ServerModelManager.isAuthModel(new ResourceLocation("ysmu", "default")));

        registerFromBuiltin("default", "builtin-md5");
        assertEquals("builtin-md5", winnerMd5("default"), "内置的 0 档压过 auth 的 1 档");
        assertFalse(
            ServerModelManager.isAuthModel(new ResourceLocation("ysmu", "default")),
            "生效来源换人，权限判定跟着换");
        assertEquals(Collections.singleton("guarded"), ServerModelManager.authModelIds(), "guarded 仍然来自 auth 根");
    }

    @Test
    void theAuthRootIsRecognizedWithTheSameNormalizationAsTheBuiltinRoot() {
        assertTrue(ServerModelManager.isAuthRoot(ServerModelManager.AUTH));
        assertFalse(ServerModelManager.isAuthRoot(ServerModelManager.CUSTOM));
        assertFalse(ServerModelManager.isAuthRoot(ServerModelManager.BUILT));
        assertFalse(ServerModelManager.isAuthRoot(null));
        // 权限判定与 isBuiltinModel 共用一套拼写规范化：裸磁盘名、ysmu: 全名、_name_<hex> 内部 id。
        String internalId = ModelIdUtil.getInternalModelId("misc/2_steve");
        registerFromAuth(internalId, "auth-md5");
        assertTrue(ServerModelManager.isAuthModel(new ResourceLocation("ysmu", internalId)));
    }

    @Test
    void samePriorityFollowsTheUpstreamRelativePathOrder() {
        // 上游排序键是 (rootKind 优先级, 相对路径) 升序、先者胜（ReloadableModelCatalog:602-642）：
        // "example" 排在 "example.ysm" 之前，所以目录赢——与登记顺序无关。
        Path ysmFile = ServerModelManager.CUSTOM.resolve("example.ysm");
        Path folder = ServerModelManager.CUSTOM.resolve("example");

        ServerModelManager.registerModel("example", info("ysm-file"), null, ysmFile, ServerModelManager.CUSTOM);
        ServerModelManager.registerModel("example", info("folder"), null, folder, ServerModelManager.CUSTOM);
        assertEquals("folder", winnerMd5("example"), "目录的相对路径更小，接替先登记的 .ysm");
        assertEquals(1, ServerModelManager.modelConflictCount());

        ServerModelManager.clearModelCaches();

        ServerModelManager.registerModel("example", info("folder"), null, folder, ServerModelManager.CUSTOM);
        ServerModelManager.registerModel("example", info("ysm-file"), null, ysmFile, ServerModelManager.CUSTOM);
        assertEquals("folder", winnerMd5("example"), "胜负由相对路径决定，不随登记顺序翻转");
        assertEquals(1, ServerModelManager.modelConflictCount());
    }

    @Test
    void equalRankKeepsTheFirstRegistration() {
        // 排序键完全相等只在本仓出现（PRIORITY_CUSTOM 同时覆盖 custom 与内置根里未被契约声明的目录）：
        // 按上游"先者占位、后者丢弃"处理。
        registerFromCustom("user_dropped", "custom-md5");
        ServerModelManager.registerModel("user_dropped", info("stray-md5"), null,
            ServerModelManager.BUILT.resolve("user_dropped"), ServerModelManager.BUILT);

        assertEquals("custom-md5", winnerMd5("user_dropped"), "同优先级同相对路径时先登记者保留");
        assertEquals(1, ServerModelManager.modelConflictCount());
    }

    @Test
    void aSecondRegistrationOfTheSameSourceIsATopUpNotAConflict() {
        // OpenYsmFormat 的真实顺序：先登记 legacy 半边，拿到 17 载荷后同一来源再登记一次。
        Path source = ServerModelManager.CUSTOM.resolve("example");
        OpenYsmSyncInfo sync = new OpenYsmSyncInfo("example", "cache-name", 1L, 2L, 32, false);

        ServerModelManager.registerModel("example", info("md5"), null, source, ServerModelManager.CUSTOM);
        ServerModelManager.registerModel("example", info("md5"), sync, source, ServerModelManager.CUSTOM);

        assertEquals(0, ServerModelManager.modelConflictCount(), "同一磁盘来源的补齐不是同名冲突");
        assertSame(sync, ServerModelManager.OPEN_YSM_SYNC_INFO.get("example"));
    }

    @Test
    void theWinnerWritesBothRegistries() {
        // 先登记的 custom 带 17 载荷，内置那份赢了却没有载荷：旧载荷必须清掉，否则 CACHE_NAME_INFO 与
        // OPEN_YSM_SYNC_INFO 会指向两个不同的模型（AGENTS.md 记录过这个隐患）。
        OpenYsmSyncInfo stale = new OpenYsmSyncInfo(BUILTIN_MODEL, "stale-cache", 1L, 2L, 32, false);
        ServerModelManager.registerModel(BUILTIN_MODEL, info("custom-md5"), stale,
            ServerModelManager.CUSTOM.resolve(BUILTIN_MODEL), ServerModelManager.CUSTOM);

        registerFromBuiltin(BUILTIN_MODEL, "builtin-md5");

        assertEquals("builtin-md5", winnerMd5(BUILTIN_MODEL));
        assertNull(ServerModelManager.OPEN_YSM_SYNC_INFO.get(BUILTIN_MODEL), "赢家没有 17 载荷时要清掉输家那份");
    }

    // -----------------------------------------------------------------------------------------------------------
    // 内置身份 = 契约声明的路径 + 内置根
    // -----------------------------------------------------------------------------------------------------------

    @Test
    void aStrayDirectoryInTheBuiltinRootIsNotABuiltinModel() {
        // 第 10 项的要求：用户手放在 config/ysmu/builtin 的多余文件既不算内置、也不被清掉。
        assertTrue(ServerModelManager.isBuiltinOrigin(ServerModelManager.BUILT, BUILTIN_MODEL));
        assertFalse(ServerModelManager.isBuiltinOrigin(ServerModelManager.BUILT, "user_dropped"));
        assertFalse(ServerModelManager.isBuiltinOrigin(ServerModelManager.CUSTOM, BUILTIN_MODEL));

        registerFromCustom("user_dropped", "custom-md5");
        ServerModelManager.registerModel("user_dropped", info("stray-md5"), null,
            ServerModelManager.BUILT.resolve("user_dropped"), ServerModelManager.BUILT);

        assertEquals("custom-md5", winnerMd5("user_dropped"), "它不是内置，排序键与 custom 同档，先登记者保留");
        assertEquals(1, ServerModelManager.modelConflictCount());
        assertFalse(ServerModelManager.isBuiltinModel(new ResourceLocation("ysmu", "user_dropped")));
    }

    @Test
    void packManifestsFollowTheSamePriorityRule() {
        ServerPackData custom = pack("wine_fox/", "custom cover");
        ServerPackData builtin = pack("wine_fox/", "builtin cover");

        ServerModelManager.registerPack("wine_fox/", custom, ServerModelManager.CUSTOM.resolve("wine_fox"),
            ServerModelManager.CUSTOM);
        ServerModelManager.registerPack("wine_fox/", builtin, ServerModelManager.BUILT.resolve("wine_fox"),
            ServerModelManager.BUILT);

        assertSame(builtin, ServerModelManager.PACKS.get("wine_fox/"), "同名包根同样是内置赢");
        assertEquals(1, ServerModelManager.packConflictCount());
    }

    // -----------------------------------------------------------------------------------------------------------
    // 唯一入口：扫描器不再直接写登记表
    // -----------------------------------------------------------------------------------------------------------

    @Test
    void theScannersRegisterThroughTheSingleEntryPoint() throws Exception {
        // 行为断言证明不了"没有第二个写入口"，而重新长出裸 put 正是本项要防的回归：编译产物里三个扫描器都必须
        // 引用 registerModel（与 ModelAuthorizationTest 读常量池的同一手法）。
        // 查法要点：FolderFormat/YsmFormat 的登记点在 walkFileTree 的**匿名** visitor 里（FolderFormat$1 /
        // YsmFormat$1），而匿名类不是 Class.getDeclaredClasses() 意义上的"成员"（其 InnerClasses 条目的
        // outer_class_info_index 为 0），所以按"同一个编译单元"读该目录下所有 <SimpleName>*.class：
        // 只查外层类会漏，只查 getDeclaredClasses() 一个都看不到。
        for (Class<?> scanner : new Class<?>[] { FolderFormat.class, YsmFormat.class, OpenYsmFormat.class }) {
            List<Path> units = compiledUnitsOf(scanner);
            assertFalse(units.isEmpty(), "找不到 " + classLocation(scanner) + " 所在目录里的编译产物");
            boolean referencesEntryPoint = false;
            for (Path unit : units) {
                if (new String(Files.readAllBytes(unit), StandardCharsets.ISO_8859_1).contains("registerModel")) {
                    referencesEntryPoint = true;
                }
            }
            assertTrue(
                referencesEntryPoint,
                scanner.getSimpleName() + " 必须经 ServerModelManager.registerModel 登记，而不是裸 put（读了 " + units + "）");
        }
    }

    /** 这条用例实际读到的 class 位置，失败信息里带上它，免得下次再怀疑"读到的是不是另一份同名类"。 */
    private static String classLocation(Class<?> type) {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        java.net.URL url = type.getClassLoader()
            .getResource(resource);
        return url == null ? resource + " (unresolved)" : url.toString();
    }

    /**
     * 与 {@code type} 同属一个编译单元的 class 文件：外层类加具名/匿名嵌套类。按目录前缀取，所以匿名类也包含
     * 在内；classpath 是 jar 时退回只读外层类那一份。
     */
    private static List<Path> compiledUnitsOf(Class<?> type) throws Exception {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        java.net.URL url = type.getClassLoader()
            .getResource(resource);
        assertNotNull(url, resource + " 必须能从 classpath 读到");
        Path classFile = Paths.get(url.toURI());
        if (!"file".equals(url.getProtocol()) || classFile.getParent() == null) {
            return Collections.singletonList(classFile);
        }
        String prefix = type.getSimpleName();
        try (Stream<Path> stream = Files.list(classFile.getParent())) {
            return stream.filter(path -> {
                String name = path.getFileName()
                    .toString();
                return name.startsWith(prefix) && name.endsWith(".class");
            })
                .sorted()
                .collect(Collectors.toList());
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // 夹具
    // -----------------------------------------------------------------------------------------------------------

    private static void registerFromCustom(String modelId, String md5) {
        ServerModelManager.registerModel(modelId, info(md5), null,
            ServerModelManager.CUSTOM.resolve(modelId), ServerModelManager.CUSTOM);
    }

    private static void registerFromBuiltin(String modelId, String md5) {
        ServerModelManager.registerModel(modelId, info(md5), null,
            ServerModelManager.BUILT.resolve(modelId), ServerModelManager.BUILT);
    }

    private static void registerFromAuth(String modelId, String md5) {
        ServerModelManager.registerModel(modelId, info(md5), null,
            ServerModelManager.AUTH.resolve(modelId), ServerModelManager.AUTH);
    }

    private static String winnerMd5(String modelId) {
        ServerModelInfo registered = ServerModelManager.CACHE_NAME_INFO.get(modelId);
        assertNotNull(registered, modelId + " 应该已登记");
        return registered.getMd5();
    }

    private static ServerModelInfo info(String md5) {
        Set<String> textures = new LinkedHashSet<>();
        textures.add("skin.png");
        ServerModelInfo info = new ServerModelInfo(textures, Type.FOLDER);
        info.setMd5(md5);
        return info;
    }

    private static ServerPackData pack(String hierarchy, String name) {
        return new ServerPackData(hierarchy, name, "", Collections.emptyMap(), null, 0, 0, 2);
    }
}
