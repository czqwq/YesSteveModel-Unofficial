package com.fox.ysmu.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fox.ysmu.model.format.BuiltinModelIndex;

/**
 * 第 ⑩ 项：内置运行时副本的**按需重建**（stamp）与 notice 的"内容变化才写"。
 *
 * <p>三件事被钉住：契约指纹与落点一致时跳过清空+整树解压（所以运行时副本里用户的改动/多余文件保留，这是本项有意的
 * 语义变化）；契约路径缺失时同一个 stamp 也重写（自愈，走的就是原来那条 clean + 整树解压）；stamp 不一致或契约不可读
 * 时不跳过。判定本身（{@link ServerModelManager#builtinTreeMatches}）另用"只造目录、不解压"的廉价用例覆盖，
 * 所以真正解压整棵树（约 57 MB / 585 个文件）的用例只有两个、共三次。</p>
 *
 * <p>stamp 用测试自造的字符串，不读 {@link BuiltinModelIndex#fingerprintHex()}：这两个用例测的是"指纹怎么用"，
 * 不是"指纹是什么"（后者由 {@code BuiltinIndexContractTest} 守着）。</p>
 */
class BuiltinExtractionTest {

    private static final String STAMP_A = "contract-a\n1.0\n";
    private static final String STAMP_B = "contract-b\n1.0\n";

    @Test
    void anUnchangedContractSkipsExtractionAndAMissingModelPathRewritesIt(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("builtin");
        ServerModelManager.extractBuiltinModels(target, STAMP_A);
        assertTrue(Files.isDirectory(target.resolve("default")), "第一次必须真的解压");
        assertEquals(STAMP_A, stampOf(target), "解压完要留下 stamp");

        // 两处"用户动过"的痕迹：改一个契约声明的文件、加一个契约外的文件。
        Path shippedFile = target.resolve("default")
            .resolve("ysm.json");
        byte[] userEdit = "// user edit\n".getBytes(StandardCharsets.UTF_8);
        Files.write(shippedFile, userEdit);
        Path userFile = target.resolve("user_extra.txt");
        Files.write(userFile, "mine".getBytes(StandardCharsets.UTF_8));

        ServerModelManager.extractBuiltinModels(target, STAMP_A);

        assertArrayEquals(userEdit, Files.readAllBytes(shippedFile), "stamp 一致 ⇒ 跳过整树解压，用户改过的文件不被还原");
        assertTrue(Files.exists(userFile), "契约外的文件保留（它也不算内置模型，见 ServerModelRegistrationTest）");

        // 同一个 stamp，但契约路径缺了一个 ⇒ 不跳过，走原来的"清空 + 整树解压"，顺带把缺件修好。
        Path declared = target.resolve("default");
        FileUtils.deleteDirectory(declared.toFile());
        assertFalse(Files.exists(declared), "这条用例的前提是先把一个契约声明的模型目录删掉");

        ServerModelManager.extractBuiltinModels(target, STAMP_A);

        assertTrue(Files.isDirectory(declared), "缺件时同一 stamp 也要重写，顺带修好它（自愈）");
        assertFalse(Files.exists(userFile), "这一步真的走了清空 + 整树解压：契约外的文件被清掉");
        assertEquals(STAMP_A, stampOf(target), "stamp 内容没变，重写后仍然是它");
    }

    @Test
    void theTreeCheckRequiresEveryDeclaredPathAndTheSameStamp(@TempDir Path temp) throws IOException {
        // 廉价版本：只按契约建空目录 + stamp，不复制那 57 MB。
        Path target = Files.createDirectories(temp.resolve("builtin"));
        for (String modelPath : BuiltinModelIndex.declaredModelPaths()) {
            Files.createDirectories(target.resolve(modelPath));
        }
        assertTrue(
            BuiltinModelIndex.declaredModelPaths()
                .contains("default"),
            "契约必须声明 default（上游 BuiltinModelIndex.requireDefault 的同款约束），缺了本用例就没有前提");
        Path stampFile = target.resolve(ServerModelManager.BUILTIN_STAMP_FILE_NAME);
        Files.write(stampFile, STAMP_A.getBytes(StandardCharsets.UTF_8));

        assertTrue(ServerModelManager.builtinTreeMatches(target, STAMP_A), "路径齐全 + stamp 一致 ⇒ 跳过");
        assertFalse(ServerModelManager.builtinTreeMatches(target, STAMP_B), "stamp 不一致（换版本/换契约）⇒ 不跳过");
        assertFalse(ServerModelManager.builtinTreeMatches(target, null), "契约不可读 ⇒ 不跳过");

        FileUtils.deleteDirectory(target.resolve("default")
            .toFile());
        assertFalse(ServerModelManager.builtinTreeMatches(target, STAMP_A), "少一个契约路径 ⇒ 同一 stamp 也不跳过");
    }

    @Test
    void theNoticeIsWrittenOnlyWhenItsContentChanges(@TempDir Path temp) throws IOException {
        Path built = Files.createDirectories(temp.resolve("builtin"));

        ServerModelManager.initBuiltNoticeFile(built);
        Path notice = built.resolve("notice.txt");
        assertTrue(Files.isRegularFile(notice), "第一次必须写");
        String shipped = new String(Files.readAllBytes(notice), StandardCharsets.UTF_8);

        FileTime old = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(notice, old);
        ServerModelManager.initBuiltNoticeFile(built);
        assertEquals(old, Files.getLastModifiedTime(notice), "内容没变 ⇒ 不写盘（mtime 原封不动）");

        Files.write(notice, "user text\n".getBytes(StandardCharsets.UTF_8));
        ServerModelManager.initBuiltNoticeFile(built);
        assertEquals(shipped, new String(Files.readAllBytes(notice), StandardCharsets.UTF_8), "内容变了才重写");
    }

    private static String stampOf(Path target) throws IOException {
        return new String(Files.readAllBytes(target.resolve(ServerModelManager.BUILTIN_STAMP_FILE_NAME)),
            StandardCharsets.UTF_8);
    }
}
