package com.fox.ysmu.model.upload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ModelUploadInstaller}: the destination-name policy and the write itself.
 *
 * <p>Installs go through the package-private {@code installInto(Path, ...)} seam rather than
 * {@link ModelUploadInstaller#install(ModelUploadTarget, String, byte[])}: the production targets are the fixed
 * {@code config/ysmu/...} directories, and a unit test must never write into the repository's own config tree. The
 * public {@code install} is a one-line delegation to that seam (`target.root()` + `target.commandWord()`), so the
 * production name policy and the tested one are the same code.</p>
 */
class ModelUploadInstallerTest {

    @Test
    void aPlainNameKeepsItsSuffix() {
        assertEquals("model.ysm", ModelUploadInstaller.sanitizeFileName("model.ysm"));
        assertEquals("Model.YSM", ModelUploadInstaller.sanitizeFileName("Model.YSM"));
    }

    @Test
    void directoryComponentsAreDropped() {
        assertEquals("evil.ysm", ModelUploadInstaller.sanitizeFileName("../../evil.ysm"));
        assertEquals("evil.ysm", ModelUploadInstaller.sanitizeFileName("C:\\models\\evil.ysm"));
        assertEquals("evil.ysm", ModelUploadInstaller.sanitizeFileName("/home/user/evil.ysm"));
        assertEquals("model.ysm", ModelUploadInstaller.sanitizeFileName("..\\..\\"));
    }

    @Test
    void unsafeCharactersAreReplacedAndTheSuffixIsAdded() {
        assertEquals("na_me__.ysm", ModelUploadInstaller.sanitizeFileName("na<me>?.ysm"));
        assertEquals("a_b.ysm", ModelUploadInstaller.sanitizeFileName("a\u0001b"));
        assertEquals("model.zsm.ysm", ModelUploadInstaller.sanitizeFileName("model.zsm"));
        assertEquals("model.ysm", ModelUploadInstaller.sanitizeFileName("   "));
        assertEquals("model.ysm", ModelUploadInstaller.sanitizeFileName(null));
    }

    @Test
    void windowsReservedNamesArePrefixed() {
        assertEquals("_CON.ysm", ModelUploadInstaller.sanitizeFileName("CON.ysm"));
        assertEquals("_nul.ysm", ModelUploadInstaller.sanitizeFileName("nul.ysm"));
    }

    @Test
    void aNameThatIsTooLongIsTruncatedButKeepsTheSuffix() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            name.append('a');
        }

        String sanitized = ModelUploadInstaller.sanitizeFileName(name.toString());

        assertTrue(sanitized.length() <= 100, "the stored name must stay below the file-name cap: " + sanitized);
        assertTrue(sanitized.endsWith(".ysm"));
        assertEquals(100, sanitized.length());
    }

    @Test
    void aFreeNameIsUsedAsIs(@TempDir Path directory) {
        assertEquals("model.ysm", ModelUploadInstaller.uniqueFileName(directory, "model.ysm"));
    }

    @Test
    void aTakenNameGetsASequenceSuffix(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve("model.ysm"), new byte[] { 1 });
        assertEquals("model_1.ysm", ModelUploadInstaller.uniqueFileName(directory, "model.ysm"));

        Files.write(directory.resolve("model_1.ysm"), new byte[] { 1 });
        assertEquals("model_2.ysm", ModelUploadInstaller.uniqueFileName(directory, "model.ysm"));
    }

    @Test
    void aValidModelIsWrittenUnderTheTargetDirectory(@TempDir Path directory) throws Exception {
        byte[] model = TestModelFiles.validLegacyModel();
        Path target = directory.resolve("custom");

        ModelUploadInstaller.InstallResult result = ModelUploadInstaller.installInto(target, "custom", "MyModel.ysm", model);

        assertEquals(ModelUploadInstaller.InstallResult.Outcome.OK, result.outcome(), result.detail());
        Path installed = target.resolve("MyModel.ysm");
        assertEquals(installed, result.installedPath());
        assertEquals("custom/MyModel.ysm", result.relativePath());
        assertArrayEquals(model, Files.readAllBytes(installed), "the stored file must be byte-identical to the upload");
        assertEquals(1, listFiles(target).size(), "no temporary file may be left behind: " + listFiles(target));
    }

    @Test
    void aSecondUploadWithTheSameNameIsRenamedInsteadOfOverwriting(@TempDir Path directory) throws Exception {
        byte[] model = TestModelFiles.validLegacyModel();
        Path target = directory.resolve("auth");

        ModelUploadInstaller.InstallResult first = ModelUploadInstaller.installInto(target, "auth", "model.ysm", model);
        ModelUploadInstaller.InstallResult second = ModelUploadInstaller.installInto(target, "auth", "model.ysm", model);
        ModelUploadInstaller.InstallResult third = ModelUploadInstaller.installInto(target, "auth", "model.ysm", model);

        assertEquals("auth/model.ysm", first.relativePath());
        assertEquals("auth/model_1.ysm", second.relativePath());
        assertEquals("auth/model_2.ysm", third.relativePath());
        assertEquals(3, listFiles(target).size());
    }

    @Test
    void aPathTraversalNameStillLandsInsideTheTargetDirectory(@TempDir Path directory) throws Exception {
        byte[] model = TestModelFiles.validLegacyModel();
        Path target = directory.resolve("custom");

        ModelUploadInstaller.InstallResult result = ModelUploadInstaller
            .installInto(target, "custom", "../../../outside.ysm", model);

        assertEquals(ModelUploadInstaller.InstallResult.Outcome.OK, result.outcome(), result.detail());
        assertEquals(target.resolve("outside.ysm"), result.installedPath());
        assertFalse(Files.exists(directory.resolve("outside.ysm")), "nothing may be written outside the target");
    }

    @Test
    void invalidContentIsRejectedWithoutTouchingTheDisk(@TempDir Path directory) {
        Path target = directory.resolve("custom");

        ModelUploadInstaller.InstallResult result = ModelUploadInstaller
            .installInto(target, "custom", "model.ysm", new byte[] { 1, 2, 3 });

        assertEquals(ModelUploadInstaller.InstallResult.Outcome.INVALID, result.outcome());
        assertNotNull(result.validation());
        assertFalse(Files.exists(target), "a rejected upload must not even create the target directory");
    }

    private static List<Path> listFiles(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.collect(Collectors.toList());
        }
    }
}
