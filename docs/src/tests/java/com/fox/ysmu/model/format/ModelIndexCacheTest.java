package com.fox.ysmu.model.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.Md5Utils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import rip.ysm.security.YsmCrypt;

/**
 * The server-side model index sidecar. The contract these cases pin is the whole point of the class:
 * a hit only happens when the source bytes are unchanged <b>and</b> the on-disk cache files are the ones the full
 * path would reuse; everything else (missing / empty / malformed / foreign-version index, missing / tampered cache
 * file, changed source, changed bake-affecting constant) is a miss that rebuilds - never an exception and never a
 * different registration.
 */
class ModelIndexCacheTest {

    private static final String MOD_VERSION = "test-build-1";
    private static final String SERVER_KEY_FP = "server-key-fingerprint";
    private static final String PASSWORD_FP = "password-fingerprint";
    /** 模型目录名同时也是三个扫描器算出来的 modelId（{@code custom/example} → {@code example}）。 */
    private static final String MODEL_ID = "example";

    // ---------------------------------------------------------------- 夹具

    /** 一个临时"安装"：扫描根 {@code custom}、一个模型目录、一个缓存目录。 */
    private static final class World {

        final Path root;
        final Path model;
        final Path cacheDir;
        final Path index;
        final byte[] serverKey = new byte[56];

        World(Path temp) throws IOException {
            root = Files.createDirectories(temp.resolve("custom"));
            model = Files.createDirectories(root.resolve("example"));
            cacheDir = Files.createDirectories(temp.resolve("cache").resolve("server"));
            index = cacheDir.resolve(ModelIndexCache.INDEX_FILE_NAME);
            for (int i = 0; i < serverKey.length; i++) {
                serverKey[i] = (byte) (i * 31 + 7);
            }
            write(model.resolve("main.json"), "{\"main\":true}");
            write(model.resolve("skin.png"), "PNG-A");
        }

        /** 生产入口用的 Source（扫描器 + 扫描根 + 相对路径），这里直接用 OPEN_YSM。 */
        ModelIndexCache.Source source() {
            return source(ModelIndexCache.Scanner.OPEN_YSM);
        }

        ModelIndexCache.Source source(ModelIndexCache.Scanner scanner) {
            return ModelIndexCache.Source.of(scanner, root, model);
        }

        ModelIndexCache newCache() {
            return new ModelIndexCache(cacheDir, MOD_VERSION, SERVER_KEY_FP, PASSWORD_FP);
        }

        /** 模拟"完整路径烘完并登记"这一轮：记下条目并落盘。 */
        void bake(ModelIndexCache.Source source, ServerModelInfo legacy, OpenYsmSyncInfo sync) {
            ModelIndexCache cache = newCache();
            cache.load();
            cache.record(source, MODEL_ID, legacy, sync);
            assertTrue(cache.save(), "落盘必须成功");
        }

        /** 模拟下一次启动：新建实例、读盘、查命中。 */
        Optional<ModelIndexCache.Entry> lookup(ModelIndexCache.Source source) {
            ModelIndexCache cache = newCache();
            cache.load();
            return cache.hit(source, MODEL_ID);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成一个"完整路径烘出来的"传统缓存：文件名就是内容 MD5，于是通过了 {@code matchesCacheContent}。 */
    private static String writeLegacyCache(Path cacheDir, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String md5 = Md5Utils.md5Hex(bytes);
        Files.write(cacheDir.resolve(md5), bytes);
        return md5;
    }

    /** 生成一个通过了 {@code YsmCrypt.verifyServerCache} 的 OpenYSM 缓存文件。 */
    private static OpenYsmSyncInfo writeOpenYsmCache(Path cacheDir, byte[] serverKey, long hash1, long hash2)
        throws Exception {
        String name = "openysm-" + Long.toHexString(hash1) + "-" + Long.toHexString(hash2);
        Files.write(cacheDir.resolve(name),
            YsmCrypt.encryptServerCache("openysm-payload".getBytes(StandardCharsets.UTF_8), serverKey, hash1, hash2));
        return new OpenYsmSyncInfo(MODEL_ID, name, hash1, hash2, 32, false);
    }

    private static ServerModelInfo legacyInfo(String md5, String... textures) {
        ServerModelInfo info = new ServerModelInfo(new LinkedHashSet<>(Arrays.asList(textures)), Type.FOLDER);
        info.setMd5(md5);
        return info;
    }

    private static List<String> tempFiles(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(
                path -> path.getFileName()
                    .toString())
                .filter(name -> name.endsWith(".tmp"))
                .collect(Collectors.toList());
        }
    }

    // ---------------------------------------------------------------- 命中 → 跳过

    @Test
    void hitRestoresBothRegistrationsFromDisk(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        String md5 = writeLegacyCache(world.cacheDir, "legacy-cache-bytes");
        OpenYsmSyncInfo sync = writeOpenYsmCache(world.cacheDir, world.serverKey, 0x1122334455667788L,
            -0x0FEDCBA987654321L);
        world.bake(world.source(), legacyInfo(md5, "skin.png", "arm.png"), sync);

        Optional<ModelIndexCache.Entry> hit = world.lookup(world.source());

        assertTrue(hit.isPresent(), "同一份源 + 可用缓存文件必须命中");
        ServerModelInfo restored = hit.get()
            .toServerModelInfo();
        assertEquals(md5, restored.getMd5());
        assertEquals(Type.FOLDER, restored.getType());
        assertEquals(Arrays.asList("skin.png", "arm.png"), new ArrayList<>(restored.getTextures()),
            "贴图集合的顺序就是原样（ServerModelInfo.getTexture() 取第一个）");
        OpenYsmSyncInfo restoredSync = hit.get()
            .toOpenYsmSyncInfo();
        assertEquals(sync.getModelId(), restoredSync.getModelId());
        assertEquals(sync.getCacheFileName(), restoredSync.getCacheFileName());
        assertEquals(sync.getHash1(), restoredSync.getHash1());
        assertEquals(sync.getHash2(), restoredSync.getHash2());
        assertEquals(sync.getFormat(), restoredSync.getFormat());
        assertEquals(sync.isCustomSkinModel(), restoredSync.isCustomSkinModel());
    }

    @Test
    void aModelWithoutOpenYsmRegistrationRestoresWithoutOne(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        String md5 = writeLegacyCache(world.cacheDir, "legacy-only");
        world.bake(world.source(), legacyInfo(md5, "skin.png"), null);

        ModelIndexCache cache = world.newCache();
        cache.load();
        Optional<ModelIndexCache.Entry> hit = cache.hit(world.source(), MODEL_ID);

        assertTrue(hit.isPresent());
        assertNull(
            hit.get()
                .toOpenYsmSyncInfo(),
            "当初没登记 OpenYSM 同步缓存，命中时也不能凭空造一个");
        assertEquals(1, cache.restoredCount());
        cache.hit(world.source(ModelIndexCache.Scanner.LEGACY_YSM), MODEL_ID);
        assertEquals(1, cache.restoredCount(), "只有命中才计数，未命中不算");
    }

    @Test
    void anEntryBelongsToTheScannerThatRecordedIt(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        world.bake(world.source(ModelIndexCache.Scanner.OPEN_YSM),
            legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        assertEquals("openysm/custom/example", world.source()
            .key());
        assertTrue(world.lookup(world.source(ModelIndexCache.Scanner.OPEN_YSM))
            .isPresent());
        assertFalse(
            world.lookup(world.source(ModelIndexCache.Scanner.LEGACY_YSM))
                .isPresent(),
            "三个扫描器各有自己的键：命中只允许重放同一个扫描器当初记下的东西");
    }

    @Test
    void sourceKeyUsesForwardSlashesForNestedModels(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("builtin"));
        Path nested = Files.createDirectories(root.resolve("group").resolve("model"));

        assertEquals("folder/builtin/group/model",
            ModelIndexCache.Source.of(ModelIndexCache.Scanner.LEGACY_FOLDER, root, nested)
                .key());
        assertEquals("builtin", ModelIndexCache.Source.namespaceOf(root));
    }

    // ---------------------------------------------------------------- 指纹失效

    @Test
    void changedSourceContentIsAMiss(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);
        assertTrue(world.lookup(world.source())
            .isPresent());

        write(world.model.resolve("skin.png"), "PNG-B");

        assertFalse(
            world.lookup(world.source())
                .isPresent(),
            "源的字节变了就不是命中，必须重建");
    }

    @Test
    void aNewFileInsideTheModelFolderIsAMiss(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        write(world.model.resolve("extra.png"), "PNG-C");

        assertFalse(
            world.lookup(world.source())
                .isPresent(),
            "模型目录整棵树都是载荷的输入：多一个贴图就不是命中");
    }

    @Test
    void fingerprintFollowsContentNotTimestamps(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        Path skin = world.model.resolve("skin.png");
        Files.setLastModifiedTime(skin, FileTime.fromMillis(1_000_000L));
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);
        assertTrue(world.lookup(world.source())
            .isPresent());

        // 等长的另一份内容 + 与上一版完全相同的 mtime：只看 mtime/大小的实现会在这里错误命中。
        write(skin, "PNG-B");
        Files.setLastModifiedTime(skin, FileTime.fromMillis(1_000_000L));
        assertEquals(5L, Files.size(skin), "这份用例必须是等长替换，否则它证明不了什么");

        assertFalse(
            world.lookup(world.source())
                .isPresent(),
            "只用 mtime/大小判定会在等长改写 + 保留时间戳时漏掉载荷变化");
    }

    @Test
    void sameContentWrittenAgainIsStillAHit(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        Path skin = world.model.resolve("skin.png");
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        // 内容不变、mtime 变新（解压/回写/复制都会这样）：仍然命中，指纹不参与时间。
        write(skin, "PNG-A");
        Files.setLastModifiedTime(skin, FileTime.fromMillis(2_000_000L));

        assertTrue(world.lookup(world.source())
            .isPresent());
    }

    @Test
    void commitPrunesOnlyTheScansThatFinished(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        Path builtin = Files.createDirectories(temp.resolve("builtin"));
        Path builtinModel = Files.createDirectories(builtin.resolve("example"));
        write(builtinModel.resolve("main.json"), "{}");
        ModelIndexCache.Source custom = world.source();
        ModelIndexCache.Source other = ModelIndexCache.Source.of(ModelIndexCache.Scanner.OPEN_YSM, builtin,
            builtinModel);

        ModelIndexCache first = world.newCache();
        first.load();
        first.record(custom, MODEL_ID, legacyInfo(writeLegacyCache(world.cacheDir, "custom-bytes"), "skin.png"), null);
        first.record(other, MODEL_ID, legacyInfo(writeLegacyCache(world.cacheDir, "builtin-bytes"), "skin.png"), null);
        first.complete(ModelIndexCache.Scanner.OPEN_YSM, "custom");
        first.complete(ModelIndexCache.Scanner.OPEN_YSM, "builtin");
        assertTrue(first.save());
        assertEquals(2, first.entryCount());

        // builtin 这一轮没跑完（例如扫描抛了）：custom 的旧条目可以清掉，builtin 的必须留着。
        ModelIndexCache second = world.newCache();
        second.load();
        assertEquals(2, second.entryCount());
        second.record(custom, MODEL_ID, legacyInfo(writeLegacyCache(world.cacheDir, "custom-bytes"), "skin.png"), null);
        second.complete(ModelIndexCache.Scanner.OPEN_YSM, "custom");
        assertTrue(second.save());
        assertEquals(2, second.entryCount(), "没跑完的命名空间的条目不能被这次 commit 丢掉");

        // custom 这一轮跑完了但什么都没记（模型目录消失了）：它的旧条目才该被清掉。
        ModelIndexCache third = world.newCache();
        third.load();
        third.complete(ModelIndexCache.Scanner.OPEN_YSM, "custom");
        assertTrue(third.save());
        assertEquals(1, third.entryCount());
    }

    // ---------------------------------------------------------------- 版本 / 密钥围栏

    @Test
    void changedModVersionInvalidatesEveryEntry(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        ModelIndexCache otherBuild = new ModelIndexCache(world.cacheDir, "test-build-2", SERVER_KEY_FP, PASSWORD_FP);
        otherBuild.load();

        assertEquals(0, otherBuild.entryCount(), "换了一个模组版本（= 烘法可能变了）就没有可用条目");
        assertFalse(
            otherBuild.hit(world.source(), MODEL_ID)
                .isPresent());
        assertTrue(Files.exists(world.index), "版本不符不是损坏：文件留着，下一次 commit 覆盖它");
        assertNotEquals(world.newCache()
            .fingerprint(world.source(), MODEL_ID),
            otherBuild.fingerprint(world.source(), MODEL_ID),
            "模组版本同时进指纹，不只是文件头围栏");
    }

    @Test
    void changedServerKeyOrPasswordInvalidatesEveryEntry(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        ModelIndexCache rotatedKey = new ModelIndexCache(world.cacheDir, MOD_VERSION, "other-server-key", PASSWORD_FP);
        rotatedKey.load();
        assertEquals(0, rotatedKey.entryCount(),
            "OpenYSM 缓存文件名由 server key 派生：key 换了，条目里的名字就作废");

        ModelIndexCache rotatedPassword = new ModelIndexCache(world.cacheDir, MOD_VERSION, SERVER_KEY_FP,
            "other-password");
        rotatedPassword.load();
        assertEquals(0, rotatedPassword.entryCount(),
            "传统缓存的 MD5 由 PASSWORD 那把 AES 钥匙决定：钥匙换了就必须重烘");
    }

    // ---------------------------------------------------------------- 索引文件损坏 / 缺失 / 空

    @Test
    void missingIndexFileIsAnEmptyMiss(@TempDir Path temp) throws Exception {
        World world = new World(temp);

        ModelIndexCache cache = world.newCache();
        cache.load();

        assertEquals(0, cache.entryCount());
        assertFalse(
            cache.hit(world.source(), MODEL_ID)
                .isPresent());
        assertFalse(Files.exists(world.index), "读一个不存在的索引不能顺手创建一个");
    }

    @Test
    void emptyIndexFileIsAnEmptyMiss(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        Files.write(world.index, new byte[0]);

        ModelIndexCache cache = world.newCache();
        cache.load();

        assertEquals(0, cache.entryCount());
        assertFalse(
            cache.hit(world.source(), MODEL_ID)
                .isPresent());
    }

    @Test
    void malformedIndexIsDiscardedAndRebuiltWithoutThrowing(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        writeLegacyCache(world.cacheDir, "bytes");

        for (String broken : Arrays.asList("{ not json", "{\"schema\":\"ysmu-model-index\",\"version\":1,",
            "{\"schema\":\"ysmu-model-index\",\"version\":1,\"modVersion\":\"" + MOD_VERSION + "\",\"serverKeyFp\":\""
                + SERVER_KEY_FP + "\",\"passwordFp\":\"" + PASSWORD_FP
                + "\",\"entries\":[{\"scanner\":\"openysm\"}]}")) {
            write(world.index, broken);
            ModelIndexCache cache = world.newCache();

            cache.load();

            assertEquals(0, cache.entryCount(), "坏索引一律当没有：" + broken);
            assertFalse(
                cache.hit(world.source(), MODEL_ID)
                    .isPresent(),
                broken);
            assertFalse(Files.exists(world.index), "坏索引被丢掉，下一次 commit 会写一份新的：" + broken);
        }
    }

    @Test
    void anIndexFromAnotherSchemaIsFencedOutButKept(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        write(world.index, "{\"schema\":\"some-other-tool\",\"version\":1,\"entries\":[]}");

        ModelIndexCache cache = world.newCache();
        cache.load();

        assertEquals(0, cache.entryCount());
        assertTrue(Files.exists(world.index), "不是我们的索引 = 不适用，不是损坏，不删别人的文件");
    }

    @Test
    void theWrittenFilePinsTheSchema(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        String md5 = writeLegacyCache(world.cacheDir, "bytes");
        OpenYsmSyncInfo sync = writeOpenYsmCache(world.cacheDir, world.serverKey, 7L, 9L);
        world.bake(world.source(), legacyInfo(md5, "skin.png", "arm.png"), sync);

        JsonObject root = new JsonParser().parse(
            new String(Files.readAllBytes(world.index), StandardCharsets.UTF_8))
            .getAsJsonObject();

        assertEquals("ysmu-model-index", root.get("schema")
            .getAsString());
        assertEquals(1, root.get("version")
            .getAsInt());
        assertEquals(MOD_VERSION, root.get("modVersion")
            .getAsString());
        assertEquals(SERVER_KEY_FP, root.get("serverKeyFp")
            .getAsString());
        assertEquals(PASSWORD_FP, root.get("passwordFp")
            .getAsString());
        JsonObject entry = root.getAsJsonArray("entries")
            .get(0)
            .getAsJsonObject();
        assertEquals("openysm", entry.get("scanner")
            .getAsString());
        assertEquals("custom", entry.get("namespace")
            .getAsString());
        assertEquals("example", entry.get("source")
            .getAsString());
        assertEquals(MODEL_ID, entry.get("modelId")
            .getAsString());
        assertEquals("FOLDER", entry.get("type")
            .getAsString());
        assertEquals(md5, entry.get("md5")
            .getAsString());
        assertEquals("skin.png",
            entry.getAsJsonArray("textures")
                .get(0)
                .getAsString());
        assertEquals(sync.getCacheFileName(),
            entry.getAsJsonObject("openYsm")
                .get("cacheFileName")
                .getAsString());
    }

    // ---------------------------------------------------------------- 缓存文件不在 / 被改

    @Test
    void missingOrTamperedCacheFileIsAMiss(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        String md5 = writeLegacyCache(world.cacheDir, "legacy-bytes");
        OpenYsmSyncInfo sync = writeOpenYsmCache(world.cacheDir, world.serverKey, 42L, 43L);
        world.bake(world.source(), legacyInfo(md5, "skin.png"), sync);
        assertTrue(world.lookup(world.source())
            .isPresent());

        Files.delete(world.cacheDir.resolve(md5));
        assertFalse(
            world.lookup(world.source())
                .isPresent(),
            "传统缓存文件被 prune 掉了就不是命中：否则会登记一个发不出去的 md5");

        Files.write(world.cacheDir.resolve(md5), "legacy-bytes-tampered".getBytes(StandardCharsets.UTF_8));
        assertFalse(
            world.lookup(world.source())
                .isPresent(),
            "文件内容与名字（MD5）不符时完整路径会重写，索引也必须退回完整路径");

        // 传统缓存修好，改坏 OpenYSM 那一份：签名对不上同样是未命中。
        Files.write(world.cacheDir.resolve(md5), "legacy-bytes".getBytes(StandardCharsets.UTF_8));
        assertTrue(world.lookup(world.source())
            .isPresent());
        byte[] openYsm = Files.readAllBytes(world.cacheDir.resolve(sync.getCacheFileName()));
        openYsm[openYsm.length - 1] ^= 0x01;
        Files.write(world.cacheDir.resolve(sync.getCacheFileName()), openYsm);
        assertFalse(
            world.lookup(world.source())
                .isPresent(),
            "OpenYSM 缓存签名校验失败时必须重烘");
    }

    // ---------------------------------------------------------------- 落盘安全

    @Test
    void saveWritesAtomicallyAndLeavesNoTemporaryFiles(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        world.bake(world.source(), legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        assertTrue(Files.isRegularFile(world.index));
        assertTrue(tempFiles(world.cacheDir).isEmpty(), "临时文件必须被清掉，不能留在缓存目录里");
    }

    @Test
    void aFailedSaveKeepsTheDiskAsItWasAndDoesNotThrow(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        // A real write failure, forced portably: the existing index is read-only, so the atomic replace cannot
        // land. Pointing a *directory* at the index path is NOT this fixture - on Windows the atomic move happily
        // replaces a directory with the temporary file, so the save legitimately succeeds there.
        String original = "{\"entries\":[]}";
        Files.write(world.index, original.getBytes(StandardCharsets.UTF_8));
        assertTrue(world.index.toFile()
            .setReadOnly(), "the fixture needs a read-only index to force the failure");

        ModelIndexCache cache = world.newCache();
        cache.load();
        cache.record(world.source(), MODEL_ID, legacyInfo(writeLegacyCache(world.cacheDir, "bytes"), "skin.png"), null);

        assertFalse(cache.save(), "写不进去时只返回 false，绝不抛给 reload");
        assertEquals(original, new String(Files.readAllBytes(world.index), StandardCharsets.UTF_8),
            "a failed replace must leave the original index exactly as it was");
        assertTrue(tempFiles(world.cacheDir).isEmpty(), "a failed replace must not leave its temporary file behind");
    }

    // ---------------------------------------------------------------- 失败/空结果不入索引

    @Test
    void aModelThatRegisteredNothingIsNeverIndexed(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        ModelIndexCache cache = world.newCache();
        cache.load();

        cache.record(world.source(), MODEL_ID, null, null);
        cache.complete(ModelIndexCache.Scanner.OPEN_YSM, "custom");

        assertTrue(cache.save());
        assertEquals(0, cache.entryCount(), "完整路径什么都没登记时索引也不该记：下一次照旧重试");
        assertFalse(
            world.lookup(world.source())
                .isPresent());
    }

    @Test
    void recordAndHitSurviveNullArguments(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        ModelIndexCache cache = world.newCache();
        cache.load();

        cache.record(null, MODEL_ID, legacyInfo("0123456789abcdef0123456789abcdef", "skin.png"), null);
        cache.record(world.source(), null, legacyInfo("0123456789abcdef0123456789abcdef", "skin.png"), null);
        cache.complete(null, null);

        assertEquals(0, cache.entryCount());
        assertFalse(
            cache.hit(null, MODEL_ID)
                .isPresent());
        assertFalse(
            cache.hit(world.source(), null)
                .isPresent());
    }

    // ---------------------------------------------------------------- 生产入口：命中即跳过

    /**
     * {@code restoreIfHit} 是三个扫描器真正调用的那个入口：命中时必须把两份登记重新放进
     * {@code CACHE_NAME_INFO}/{@code OPEN_YSM_SYNC_INFO} 并回答 true（调用方据此跳过整条链）。
     * 未命中/没装索引时回答 false，调用方的完整路径一行都不会少走。
     */
    @Test
    void restoreIfHitFillsBothRegistriesAndReportsTheSkip(@TempDir Path temp) throws Exception {
        World world = new World(temp);
        String modelId = MODEL_ID;
        String md5 = writeLegacyCache(world.cacheDir, "legacy-bytes");
        OpenYsmSyncInfo sync = writeOpenYsmCache(world.cacheDir, world.serverKey, 5L, 6L);
        world.bake(world.source(), legacyInfo(md5, "skin.png"), sync);
        ModelIndexCache cache = world.newCache();
        cache.load();

        try {
            assertFalse(ModelIndexCache.restoreIfHit(world.source(), modelId), "没装索引时必须是未命中");

            ModelIndexCache.install(cache);
            assertTrue(ModelIndexCache.restoreIfHit(world.source(), modelId), "命中 → 调用方跳过解密/解析/烘焙/加密");
            ServerModelInfo restored = ServerModelManager.CACHE_NAME_INFO.get(modelId);
            assertEquals(md5, restored.getMd5());
            assertEquals(sync.getCacheFileName(),
                ServerModelManager.OPEN_YSM_SYNC_INFO.get(modelId)
                    .getCacheFileName());

            ModelIndexCache.uninstall();
            ServerModelManager.CACHE_NAME_INFO.remove(modelId);
            ServerModelManager.OPEN_YSM_SYNC_INFO.remove(modelId);
            assertFalse(ModelIndexCache.restoreIfHit(world.source(), modelId), "摘掉索引后又回到完整路径");
        } finally {
            ModelIndexCache.uninstall();
            ServerModelManager.CACHE_NAME_INFO.remove(modelId);
            ServerModelManager.OPEN_YSM_SYNC_INFO.remove(modelId);
        }
    }
}
