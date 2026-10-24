package com.fox.ysmu.model.manage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.network.message.RequestServerModelInfo;

/**
 * {@link ServerModelManage}: the model-manage GUI's server half (listing plus delete/move/rename).
 *
 * <p>All paths are temporary directories: production roots are the fixed {@code config/ysmu/{custom,auth}} and a
 * unit test must never write into the repository tree. The security-critical half is here: every name arrives from
 * the network, so the tests cover path traversal, symlink rejection, destination-exists refusal and the extension
 * rule for renames.</p>
 */
class ServerModelManageTest {

    private static final String YSM = ".ysm";

    @Test
    void theListingShowsOnlyTopLevelFoldersAndYsmFiles(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("folderModel"));
        Files.write(root.resolve("fileModel.ysm"), new byte[] { 1, 2, 3 });
        Files.write(root.resolve("archive.zip"), new byte[] { 1 });
        Files.write(root.resolve("notes.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("nested"));
        Files.write(root.resolve("nested/deep.ysm"), new byte[] { 1 });
        Files.write(root.resolve("folderModel/main.json"), new byte[] { 1 });

        List<RequestServerModelInfo.Info> entries = ServerModelManage.listEntries(root);

        assertEquals(
            3,
            entries.size(),
            "only the top-level folders and the .ysm file are managed entries (zip/txt are ignored, the nested "
                + ".ysm is not a top-level entry): " + entries);
        assertEquals("fileModel.ysm", entries.get(0)
            .getFileName(), "the listing is sorted by name");
        assertEquals(Type.YSM, entries.get(0)
            .getType());
        assertEquals(3L, entries.get(0)
            .getSize());
        assertEquals("folderModel", entries.get(1)
            .getFileName());
        assertEquals(Type.FOLDER, entries.get(1)
            .getType());
        assertTrue(entries.get(1)
            .getSize() > 0L, "a folder size is the recursive byte count");
        assertEquals("nested", entries.get(2)
            .getFileName(), "a top-level directory is a managed entry even if it is just a pack-shaped folder");
    }

    @Test
    void listingAMissingRootIsEmptyInsteadOfFailing(@TempDir Path root) {
        assertTrue(ServerModelManage.listEntries(root.resolve("does-not-exist"))
            .isEmpty());
        assertTrue(ServerModelManage.listEntries(null)
            .isEmpty());
    }

    @Test
    void everyExistingEntryCanBeDeleted(@TempDir Path root) throws Exception {
        Files.write(root.resolve("model.ysm"), new byte[] { 1 });
        Files.createDirectories(root.resolve("modelFolder"));
        Files.write(root.resolve("modelFolder/main.json"), new byte[] { 1 });

        assertEquals(ServerModelManage.Outcome.OK, ServerModelManage.delete(root, "model.ysm"));
        assertFalse(Files.exists(root.resolve("model.ysm")));
        assertEquals(ServerModelManage.Outcome.OK, ServerModelManage.delete(root, "modelFolder"));
        assertFalse(Files.exists(root.resolve("modelFolder")));
    }

    @Test
    void deletingAMissingOrUnmanagedEntryIsRefused(@TempDir Path root) throws Exception {
        Files.write(root.resolve("notes.txt"), new byte[] { 1 });

        assertEquals(ServerModelManage.Outcome.INVALID_NAME, ServerModelManage.delete(root, "missing.ysm"));
        assertEquals(
            ServerModelManage.Outcome.INVALID_NAME,
            ServerModelManage.delete(root, "notes.txt"),
            "only folders and .ysm files are managed");
        assertTrue(Files.exists(root.resolve("notes.txt")), "a refused delete must not touch the file");
    }

    @Test
    void aPathTraversalNameCanNeitherResolveNorDeleteOutsideTheRoot(@TempDir Path root) throws Exception {
        Path outside = root.getParent()
            .resolve("outside.ysm");
        Files.write(outside, new byte[] { 1 });
        try {
            for (String name : new String[] { "../outside.ysm", "..\\outside.ysm", "/etc/passwd", ".." }) {
                assertEquals(
                    ServerModelManage.Outcome.INVALID_NAME,
                    ServerModelManage.delete(root, name),
                    name + " must be refused");
            }
            assertTrue(Files.exists(outside), "nothing outside the root may be deleted");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void movingMovesBetweenTheTwoRoots(@TempDir Path directory) throws Exception {
        Path custom = directory.resolve("custom");
        Path auth = directory.resolve("auth");
        Files.createDirectories(custom);
        Files.write(custom.resolve("model.ysm"), new byte[] { 1 });

        assertEquals(ServerModelManage.Outcome.OK, ServerModelManage.move(custom, auth, "model.ysm"));
        assertFalse(Files.exists(custom.resolve("model.ysm")));
        assertTrue(Files.isRegularFile(auth.resolve("model.ysm")));
    }

    @Test
    void movingRefusesAnExistingDestination(@TempDir Path directory) throws Exception {
        Path custom = directory.resolve("custom");
        Path auth = directory.resolve("auth");
        Files.createDirectories(custom);
        Files.createDirectories(auth);
        Files.write(custom.resolve("model.ysm"), new byte[] { 1 });
        Files.write(auth.resolve("model.ysm"), new byte[] { 2 });

        assertEquals(ServerModelManage.Outcome.DEST_EXISTS, ServerModelManage.move(custom, auth, "model.ysm"));
        assertEquals(1, Files.readAllBytes(custom.resolve("model.ysm")).length, "the source must stay untouched");
    }

    @Test
    void renamingAYsmFileKeepsTheExtension(@TempDir Path root) throws Exception {
        Files.write(root.resolve("model.ysm"), new byte[] { 1 });

        assertEquals(ServerModelManage.Outcome.OK, ServerModelManage.rename(root, "model.ysm", "renamed"));
        assertTrue(Files.isRegularFile(root.resolve("renamed.ysm")));
        assertFalse(Files.exists(root.resolve("model.ysm")));
    }

    @Test
    void renamingAFolderDoesNotForceAnExtension(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("modelFolder"));

        assertEquals(ServerModelManage.Outcome.OK, ServerModelManage.rename(root, "modelFolder", "renamedFolder"));
        assertTrue(Files.isDirectory(root.resolve("renamedFolder")));
    }

    @Test
    void aRenameToTheSameNameIsANoOp(@TempDir Path root) throws Exception {
        Files.write(root.resolve("model.ysm"), new byte[] { 1 });

        assertEquals(ServerModelManage.Outcome.OK, ServerModelManage.rename(root, "model.ysm", "model"));
        assertTrue(Files.isRegularFile(root.resolve("model.ysm")));
    }

    @Test
    void renamingRefusesAnExistingDestinationAndInvalidNames(@TempDir Path root) throws Exception {
        Files.write(root.resolve("a.ysm"), new byte[] { 1 });
        Files.write(root.resolve("b.ysm"), new byte[] { 2 });

        assertEquals(ServerModelManage.Outcome.DEST_EXISTS, ServerModelManage.rename(root, "a.ysm", "b"));
        for (String bad : new String[] { "../a.ysm", "a/b", "CON", "", " ", ".hidden", "na\u0000me" }) {
            assertEquals(
                ServerModelManage.Outcome.INVALID_NAME,
                ServerModelManage.rename(root, "a.ysm", bad),
                "rename target '" + bad + "' must be refused");
        }
    }

    @Test
    void theEntryNameSanitizerAcceptsFlatNamesAndRejectsEverythingElse() {
        assertEquals("model.ysm", ServerModelManage.sanitizeEntryName("model.ysm"));
        assertEquals("my model", ServerModelManage.sanitizeEntryName(" my model "));
        assertNull(ServerModelManage.sanitizeEntryName(null));
        assertNull(ServerModelManage.sanitizeEntryName(""));
        assertNull(ServerModelManage.sanitizeEntryName("a/b"));
        assertNull(ServerModelManage.sanitizeEntryName("a\\b"));
        assertNull(ServerModelManage.sanitizeEntryName(".."));
        assertNull(ServerModelManage.sanitizeEntryName("CON"));
        assertNull(ServerModelManage.sanitizeEntryName("trailing."));
        assertNull(ServerModelManage.sanitizeEntryName("nul"));
    }

    @Test
    void aSymbolicLinkIsNeitherListedNorManaged(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("target.ysm");
        Files.write(target, new byte[] { 1 });
        Path root = directory.resolve("root");
        Files.createDirectories(root);
        Path link = root.resolve("link.ysm");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // 该平台/环境不支持符号链接：跳过这一项（不是被测代码的问题）。
        }

        assertTrue(
            ServerModelManage.listEntries(root)
                .isEmpty(),
            "a symlink must not be listed: deleting the entry would act on whatever it points at");
        assertEquals(ServerModelManage.Outcome.INVALID_NAME, ServerModelManage.delete(root, "link.ysm"));
        assertTrue(Files.exists(target), "the symlink target must survive");
    }
}
