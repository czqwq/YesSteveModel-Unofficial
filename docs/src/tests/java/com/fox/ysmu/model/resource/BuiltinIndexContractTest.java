package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fox.ysmu.model.format.BuiltinModelIndex;

/**
 * 内置契约 {@code assets/ysmu/builtin-index.json} 的守门测试：**已提交的那份文件必须与
 * {@code assets/ysmu/builtin/**} 的实际内容逐路径、逐哈希一致**。
 *
 * <p>它是 {@link #committedContractIsRegeneratedFromTheBuiltinTree()} 的"防腐层"：改了内置资产却忘了重新生成契约时，
 * 这里会红，而不是等到客户端启动才在日志里出现一条 error。同时它也是唯一的**生成器入口**
 * （见该用例的 {@code YSMU_WRITE_BUILTIN_INDEX} 说明）——生成与校验共用
 * {@link BuiltinModelIndex#sha256OfModelDir(Path)}，不存在第二份哈希实现可以漂移。</p>
 *
 * <p>只用仓库内的 {@code src/main/resources}，不依赖 {@code tmp/} 或 {@code run/}，因此照常入库、CI 上照常运行。</p>
 */
class BuiltinIndexContractTest {

    private static final Path BUILTIN = Paths.get("src", "main", "resources", "assets", "ysmu", "builtin");
    private static final Path CONTRACT = Paths.get("src", "main", "resources", "assets", "ysmu", "builtin-index.json");

    /**
     * 上游有、本仓**有意不收录**的模型。依据：上游 {@code NOTICE.md:3-7}（内置模型资产不属 Apache-2.0，
     * 每个包的 {@code ysm.json metadata.license} 是权威声明）+ {@code docs/decisions/content-rights.md:18,26}
     * （模组的开源许可不自动授予模型再分发权；未显式声明按 {@code All rights reserved} 解释）。
     * 这三个包的 {@code metadata.license.type} 正是 {@code All Rights Reserved}，所以本仓没有随包分发的权利。
     */
    private static final Map<String, String> INTENTIONALLY_ABSENT = intentionallyAbsent();

    private static Map<String, String> intentionallyAbsent() {
        Map<String, String> absent = new LinkedHashMap<>();
        for (String path : List.of("wine_fox/09_hailuo", "wine_fox/10_zhiban", "wine_fox/17_mini")) {
            absent.put(
                path,
                "upstream ysm.json metadata.license.type = \"All Rights Reserved\"; this repository has no "
                    + "redistribution right for it (NOTICE.md)");
        }
        return absent;
    }

    /**
     * 已提交的契约 == 由实际内置树重新算出来的契约。失效即"契约过期"。
     *
     * <p>生成方式：{@code YSMU_WRITE_BUILTIN_INDEX=1 .\gradlew.bat test --tests
     * "com.fox.ysmu.model.resource.BuiltinIndexContractTest"}（Windows PowerShell 用
     * {@code $env:YSMU_WRITE_BUILTIN_INDEX=1}），跑完请清掉该环境变量。选这个方式而不是 Gradle 任务的理由：
     * 写文件用的 {@link BuiltinModelIndex#render(Path, Map)} 与运行时校验用的
     * {@link BuiltinModelIndex#sha256OfModelDir(Path)} 是**同一份** Java 实现，Kotlin DSL 里再写一遍哈希
     * 会引入两份可能漂移的实现；而且本仓 build.gradle.kts 只有 5 行、完全交给 GTNH 约定插件，
     * 加一个"构建期改写 src/"的任务会把源码树变成构建输出。</p>
     */
    @Test
    void committedContractIsRegeneratedFromTheBuiltinTree() throws Exception {
        if (System.getenv("YSMU_WRITE_BUILTIN_INDEX") != null) {
            Files.write(
                CONTRACT,
                BuiltinModelIndex.render(BUILTIN, INTENTIONALLY_ABSENT)
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }

        BuiltinModelIndex.Contract contract = BuiltinModelIndex
            .parse(Files.readAllBytes(CONTRACT));
        assertTrue(
            contract.available(),
            "已提交的契约不可用（" + contract.failure() + "）：" + CONTRACT.toAbsolutePath());

        Set<String> declared = contract.modelPaths();
        Set<String> actual = BuiltinModelIndex.discoverModelPaths(BUILTIN);
        assertFalse(actual.isEmpty(), "扫描 " + BUILTIN.toAbsolutePath() + " 没找到任何内置模型");

        // 双向覆盖分开断言：失败信息要直接告诉人是"契约多了"还是"树里多了"。
        Set<String> declaredNotInTree = new LinkedHashSet<>(declared);
        declaredNotInTree.removeAll(actual);
        Set<String> inTreeNotDeclared = new LinkedHashSet<>(actual);
        inTreeNotDeclared.removeAll(declared);
        assertEquals(Set.of(), declaredNotInTree, "契约声明了树里没有的模型——契约过期，需重新生成");
        assertEquals(Set.of(), inTreeNotDeclared, "树里有契约没声明的模型——契约过期，需重新生成");

        // 逐路径哈希：契约里的 sha256 必须等于按 sha256OfModelDir 重算的值。
        Map<String, String> recomputed = new LinkedHashMap<>();
        for (String path : actual) {
            recomputed.put(path, BuiltinModelIndex.sha256OfModelDir(BUILTIN.resolve(path)));
        }
        Map<String, String> mismatch = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : recomputed.entrySet()) {
            String declaredHash = contract.modelHashes()
                .get(entry.getKey());
            if (!entry.getValue()
                .equals(declaredHash)) {
                mismatch.put(entry.getKey(), "declared=" + declaredHash + " actual=" + entry.getValue());
            }
        }
        assertEquals(Map.of(), mismatch, "契约哈希与内置树不一致——契约过期，需重新生成");

        // 契约里的指纹 = 契约原始字节的 sha256（registry-rules 用它当"内置内容是否变化"的 stamp）。
        assertEquals(BuiltinModelIndex.sha256Hex(contract.rawBytes()), contract.fingerprintHex());
        assertEquals(64, contract.fingerprintHex().length());

        // 运行时真正走的那条路（扫树 + 逐模型算摘要 + 双向覆盖 + 许可守卫）在这里整条跑一遍：
        // ServerModelManager.verifyBuiltinModelIndex 只是它的日志外壳。
        BuiltinModelIndex.Report runtimeReport = contract.verifyActual(BUILTIN);
        assertTrue(
            runtimeReport.ok(),
            () -> "verifyActual(" + BUILTIN + ") 不通过：failure=" + runtimeReport.failure()
                + " missing=" + runtimeReport.missing()
                + " unexpected=" + runtimeReport.unexpected()
                + " hashMismatch=" + runtimeReport.hashMismatch()
                + " forbiddenPresent=" + runtimeReport.forbiddenPresent());
        assertEquals(actual.size(), runtimeReport.actualPaths()
            .size());
    }

    /**
     * 契约必须真的随 jar 发布：{@link BuiltinModelIndex#shipped()} 从 classpath 读的就是这份文件
     * （{@code processResources} 拷到 build 输出后进入测试与运行时类路径）。读不到时运行时会安静地降级成
     * "契约不可用"，所以打包漏了它必须在这里就红。
     */
    @Test
    void theContractShipsOnTheRuntimeClasspath() throws Exception {
        BuiltinModelIndex.Contract shipped = BuiltinModelIndex.shipped();
        assertTrue(shipped.available(), "classpath 上没有可用的内置契约：" + shipped.failure());

        BuiltinModelIndex.Contract onDisk = BuiltinModelIndex.parse(Files.readAllBytes(CONTRACT));
        assertEquals(onDisk.modelPaths(), shipped.modelPaths(), "随包的契约与工作区里的契约不是同一份");
        assertEquals(onDisk.fingerprintHex(), shipped.fingerprintHex(), "随包的契约指纹与工作区不一致");
    }

    /**
     * 三个 ARR 模型必须被记成"有意缺失"，且**不能**出现在契约的模型集合里；同时它们在树里也必须真的不存在。
     * 这三条一起把"上游 22 → 本仓 19"这个许可结论钉住。
     */
    @Test
    void allRightsReservedUpstreamModelsAreRecordedAsIntentionallyAbsent() throws Exception {
        BuiltinModelIndex.Contract contract = BuiltinModelIndex.parse(Files.readAllBytes(CONTRACT));
        assertTrue(contract.available(), "契约不可用：" + contract.failure());

        assertEquals(
            INTENTIONALLY_ABSENT.keySet(),
            contract.intentionallyAbsent()
                .keySet(),
            "有意缺失清单不是预期的三个 ARR 模型");
        for (Map.Entry<String, String> entry : contract.intentionallyAbsent()
            .entrySet()) {
            assertTrue(
                entry.getValue()
                    .contains("All Rights Reserved"),
                entry.getKey() + " 的原因里没有写清 All Rights Reserved: " + entry.getValue());
            assertFalse(
                contract.modelPaths()
                    .contains(entry.getKey()),
                entry.getKey() + " 同时出现在模型集合里——契约自相矛盾");
            assertFalse(
                Files.exists(BUILTIN.resolve(entry.getKey())),
                entry.getKey() + " 被声明为有意缺失，却真的在树里");
        }
    }

    /** 双向覆盖的三种问题与"ARR 却出现"的许可守卫，都各有各的清单，不能混成一条。 */
    @Test
    void verifyReportsMissingUnexpectedHashMismatchAndForbiddenModels() {
        BuiltinModelIndex.Contract contract = BuiltinModelIndex.parse(
            ("{\"formatVersion\":1,\"models\":["
                + "{\"path\":\"default\",\"sha256\":\"" + "a".repeat(64) + "\"},"
                + "{\"path\":\"misc/2_steve\",\"sha256\":\"" + "b".repeat(64) + "\"}],"
                + "\"intentionallyAbsent\":[{\"path\":\"wine_fox/09_hailuo\",\"reason\":\"All Rights Reserved\"}]}")
                    .getBytes(StandardCharsets.UTF_8));
        assertTrue(contract.available(), "测试契约本身不可用：" + contract.failure());

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("default", "a".repeat(64));
        actual.put("misc/extra", "c".repeat(64));
        actual.put("wine_fox/09_hailuo", "d".repeat(64));
        BuiltinModelIndex.Report report = contract.verifyHashes(actual);

        assertEquals(List.of("misc/2_steve"), report.missing());
        assertEquals(List.of("misc/extra"), report.unexpected());
        assertEquals(List.of(), report.hashMismatch(), "只有 default 的哈希是对的，misc/2_steve 根本没在树里");
        assertEquals(List.of("wine_fox/09_hailuo"), report.forbiddenPresent());
        assertEquals(List.of("default", "misc/extra", "wine_fox/09_hailuo"), report.actualPaths());
        assertFalse(report.ok());

        // 路径集合版只看覆盖：没有哈希可比，所以 hashMismatch 必须为空，其余语义一致。
        BuiltinModelIndex.Report coverageOnly = contract.verify(actual.keySet());
        assertEquals(List.of("misc/2_steve"), coverageOnly.missing());
        assertEquals(List.of("misc/extra"), coverageOnly.unexpected());
        assertEquals(List.of(), coverageOnly.hashMismatch());
        assertEquals(List.of("wine_fox/09_hailuo"), coverageOnly.forbiddenPresent());

        // 哈希对不上时只报 hashMismatch，不牵连 missing/unexpected。
        BuiltinModelIndex.Report drifted = contract.verifyHashes(
            new LinkedHashMap<>(Map.of("default", "a".repeat(64), "misc/2_steve", "f".repeat(64))));
        assertEquals(List.of("misc/2_steve"), drifted.hashMismatch());
        assertEquals(List.of(), drifted.missing());
        assertEquals(List.of(), drifted.unexpected());
        assertFalse(drifted.ok());
    }

    /**
     * 契约不可用时的语义（调用方要能分辨"契约坏了"和"契约说你有问题"）：返回"不可用"的 Report，
     * 集合一律为空、不抛异常、**不把实际发现的一切都报成 unexpected**，但仍然交出 actualPaths。
     */
    @Test
    void anUnavailableContractDegradesQuietlyInsteadOfFloodingFalseFindings() {
        BuiltinModelIndex.Contract broken = BuiltinModelIndex.parse(new byte[0]);
        assertFalse(broken.available());
        assertNotNull(broken.failure());
        assertEquals(Set.of(), broken.modelPaths());
        assertEquals(Map.of(), broken.intentionallyAbsent());
        assertNull(broken.rawBytes());
        assertNull(broken.fingerprintHex());

        BuiltinModelIndex.Report report = broken.verify(Set.of("default", "misc/2_steve"));
        assertFalse(report.contractAvailable());
        assertFalse(report.ok());
        assertEquals(List.of(), report.missing());
        assertEquals(List.of(), report.unexpected());
        assertEquals(List.of(), report.hashMismatch());
        assertEquals(List.of("default", "misc/2_steve"), report.actualPaths());
    }

    /** 上游 {@code requireDefault:293-297} 的形状：没有 {@code default} 的契约在解析层就不可用。 */
    @Test
    void aContractWithoutTheDefaultModelIsRejected() {
        BuiltinModelIndex.Contract contract = BuiltinModelIndex.parse(
            ("{\"formatVersion\":1,\"models\":[{\"path\":\"misc/1_alex\",\"sha256\":\"" + "a".repeat(64) + "\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        assertFalse(contract.available());
        assertTrue(contract.failure().contains("default"), "失败原因要指名 default: " + contract.failure());
    }

    /** 版本不匹配即不可用：旧构建写下的契约不能被当成同一份来校验。 */
    @Test
    void aContractWithAnotherFormatVersionIsRejected() {
        BuiltinModelIndex.Contract contract = BuiltinModelIndex.parse(
            ("{\"formatVersion\":2,\"models\":[{\"path\":\"default\",\"sha256\":\"" + "a".repeat(64) + "\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        assertFalse(contract.available());
        assertTrue(contract.failure().contains("formatVersion"), contract.failure());
    }

    /** 内容摘要的定义要真的覆盖整棵目录树：改内容、加文件、改名都必须改变摘要。 */
    @Test
    void theModelDigestCoversEveryFileInTheDirectory(@TempDir Path dir) throws Exception {
        Path model = Files.createDirectory(dir.resolve("model"));
        Files.write(model.resolve("ysm.json"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(model.resolve("textures"));
        Files.write(model.resolve("textures/skin.png"), new byte[] { 1, 2, 3 });
        String baseline = BuiltinModelIndex.sha256OfModelDir(model);

        // 同一份内容反复算必须一样（契约的可重复性前提）。
        assertEquals(baseline, BuiltinModelIndex.sha256OfModelDir(model));

        Files.write(model.resolve("textures/skin.png"), new byte[] { 1, 2, 4 });
        String changedContent = BuiltinModelIndex.sha256OfModelDir(model);
        assertNotEquals(baseline, changedContent, "改了文件内容摘要必须变");

        Files.write(model.resolve("sounds.ogg"), new byte[] { 9 });
        String addedFile = BuiltinModelIndex.sha256OfModelDir(model);
        assertNotEquals(changedContent, addedFile, "新增文件（例如 sounds/*.ogg）摘要必须变");

        Files.move(model.resolve("sounds.ogg"), model.resolve("renamed.ogg"));
        assertNotEquals(addedFile, BuiltinModelIndex.sha256OfModelDir(model), "改文件名摘要必须变");
    }

    /**
     * 守门必须**真的会咬**：上面那些断言全绿，也可能是因为校验恒返回 ok。这里在内存里把契约改成三种坏法，
     * 再对**真实的内置树**跑 {@link BuiltinModelIndex.Contract#verifyActual(Path)}，三种问题必须各自被点名。
     * 不落盘、不改工作区。
     */
    @Test
    void theRealTreeGuardBitesOnHashDriftAndOnBothCoverageDirections() throws Exception {
        BuiltinModelIndex.Contract good = BuiltinModelIndex.parse(Files.readAllBytes(CONTRACT));
        assertTrue(good.available(), "契约不可用：" + good.failure());
        Map<String, String> hashes = good.modelHashes();
        Map<String, String> absent = good.intentionallyAbsent();
        String defaultHash = hashes.get("default");
        assertNotNull(defaultHash);

        // (1) 摘要漂移：default 的 sha256 被写成另一串合法 hex。
        Map<String, String> drifted = new LinkedHashMap<>(hashes);
        drifted.put("default", defaultHash.equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64));
        BuiltinModelIndex.Report driftReport = BuiltinModelIndex.parse(contractJson(drifted, absent))
            .verifyActual(BUILTIN);
        assertFalse(driftReport.ok(), "摘要被改过却仍然 ok");
        assertEquals(List.of("default"), driftReport.hashMismatch());
        assertEquals(List.of(), driftReport.missing());
        assertEquals(List.of(), driftReport.unexpected());

        // (2) 契约少了"树里明明有"的那个模型 → unexpected。
        Map<String, String> dropped = new LinkedHashMap<>(hashes);
        dropped.remove("misc/6_wine_fox");
        BuiltinModelIndex.Report dropReport = BuiltinModelIndex.parse(contractJson(dropped, absent))
            .verifyActual(BUILTIN);
        assertFalse(dropReport.ok(), "契约删了一个模型却仍然 ok");
        assertEquals(List.of("misc/6_wine_fox"), dropReport.unexpected());
        assertEquals(List.of(), dropReport.missing());

        // (3) 契约多了一个树里没有的模型 → missing。
        Map<String, String> added = new LinkedHashMap<>(hashes);
        added.put("wine_fox/99_not_shipped", "0".repeat(64));
        BuiltinModelIndex.Report addReport = BuiltinModelIndex.parse(contractJson(added, absent))
            .verifyActual(BUILTIN);
        assertFalse(addReport.ok(), "契约多了一个模型却仍然 ok");
        assertEquals(List.of("wine_fox/99_not_shipped"), addReport.missing());
        assertEquals(List.of(), addReport.unexpected());

        // (4) 许可守卫：把树里**确实存在**的模型标成"有意缺失"（模拟将来有人把某个 ARR 资产拷进来后被禁止分发），
        //     它必须被点名 forbiddenPresent，而不是被算成 unexpected。注意不能同时留在模型集合里——那会自相矛盾。
        Map<String, String> forbiddenModels = new LinkedHashMap<>(hashes);
        Map<String, String> forbiddenAbsent = new LinkedHashMap<>(absent);
        forbiddenModels.remove("misc/6_wine_fox");
        forbiddenAbsent.put("misc/6_wine_fox", "hypothetically forbidden by its license");
        BuiltinModelIndex.Contract forbiddenContract = BuiltinModelIndex
            .parse(contractJson(forbiddenModels, forbiddenAbsent));
        assertTrue(forbiddenContract.available(), forbiddenContract.failure());
        BuiltinModelIndex.Report forbiddenReport = forbiddenContract.verifyActual(BUILTIN);
        assertFalse(forbiddenReport.ok(), "被标为禁发的模型出现在树里却仍然 ok");
        assertEquals(List.of("misc/6_wine_fox"), forbiddenReport.forbiddenPresent());
        assertEquals(List.of(), forbiddenReport.unexpected());
    }

    /** 测试用的最小契约渲染器：字段与提交的文件一致，格式无关，避免依赖 Gson 的排版。 */
    private static byte[] contractJson(Map<String, String> models, Map<String, String> absent) {
        StringBuilder json = new StringBuilder("{\"formatVersion\":1,\"models\":[");
        boolean first = true;
        for (Map.Entry<String, String> entry : models.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"path\":\"")
                .append(entry.getKey())
                .append("\",\"sha256\":\"")
                .append(entry.getValue())
                .append("\"}");
        }
        json.append("],\"intentionallyAbsent\":[");
        first = true;
        for (Map.Entry<String, String> entry : absent.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"path\":\"")
                .append(entry.getKey())
                .append("\",\"reason\":\"")
                .append(entry.getValue()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\""))
                .append("\"}");
        }
        json.append("]}");
        return json.toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 扫描规则的三个要点：包根不被当模型（是容器，要继续下钻）、认领后跳过子树（现代包的
     * {@code models/} 里有 {@code main.json}+{@code arm.json}）、旧扁平布局也算模型。
     */
    @Test
    void theScanSkipsPackRootsAndDoesNotDescendIntoAModel(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("misc/1_alex/models"));
        Files.write(root.resolve("misc/ysm-pack.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("misc/1_alex/ysm.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("misc/1_alex/models/main.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("misc/1_alex/models/arm.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("misc/5_qingluka"));
        Files.write(root.resolve("misc/5_qingluka/main.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("misc/5_qingluka/arm.json"), "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(Set.of("misc/1_alex", "misc/5_qingluka"), BuiltinModelIndex.discoverModelPaths(root));
    }
}
