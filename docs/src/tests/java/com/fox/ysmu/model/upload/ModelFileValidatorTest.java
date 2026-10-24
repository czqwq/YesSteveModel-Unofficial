package com.fox.ysmu.model.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;

/**
 * {@link ModelFileValidator}: the gate between arbitrary upload bytes and the server's model directory.
 *
 * <p>Every case here is a class of file the validator must answer for: the two real containers (legacy YSGP,
 * OpenYSM binary) and the ways a hostile or broken file can differ from them (wrong magic, corrupted checksum,
 * missing model files, non-flat entry names, unparsable geometry, oversized file, decompression bomb). The one
 * positive OpenYSM binary case needs a genuinely serialized model payload, which no test fixture in this repository
 * can build (the serializer writes this port's baked sync payload layout, not a real {@code .ysm}), so the binary
 * branch is pinned on its rejection paths and the legacy branch carries the positive case - recorded here rather
 * than silently skipped.</p>
 */
class ModelFileValidatorTest {

    @Test
    void aValidLegacyContainerIsAccepted() throws Exception {
        ModelFileValidator.Result result = ModelFileValidator.validate(TestModelFiles.validLegacyModel());

        assertTrue(result.isValid(), "a file with main.json + arm.json + a texture must be accepted: " + result);
        assertEquals(ModelFileValidator.Result.Reason.OK, result.reason());
    }

    @Test
    void anEmptyOrTooShortFileIsRejected() {
        assertEquals(ModelFileValidator.Result.Reason.EMPTY, ModelFileValidator.validate(new byte[0]).reason());
        assertEquals(ModelFileValidator.Result.Reason.EMPTY, ModelFileValidator.validate(null).reason());

        assertFalse(ModelFileValidator.validate(new byte[] { 0x59, 0x53, 0x47 }).isValid());
    }

    @Test
    void aFileWithAnUnknownHeaderIsNotAYsmContainer() {
        byte[] random = new byte[128];
        for (int i = 0; i < random.length; i++) {
            random[i] = (byte) (i * 31 + 7);
        }

        ModelFileValidator.Result result = ModelFileValidator.validate(random);

        assertEquals(ModelFileValidator.Result.Reason.NOT_YSM, result.reason());
    }

    @Test
    void aLegacyContainerWithACorruptedChecksumIsRejected() throws Exception {
        byte[] model = TestModelFiles.validLegacyModel();
        // Header layout: head(4) + version(4) + md5(16). Flip one md5 byte so the container checksum no longer matches.
        model[10] = (byte) (model[10] ^ 0xFF);

        ModelFileValidator.Result result = ModelFileValidator.validate(model);

        assertEquals(ModelFileValidator.Result.Reason.CORRUPT, result.reason(), result.detail());
    }

    @Test
    void aLegacyContainerWithoutATextureIsRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("main.json", TestModelFiles.VALID_MAIN.getBytes(StandardCharsets.UTF_8));
        entries.put("arm.json", TestModelFiles.VALID_ARM.getBytes(StandardCharsets.UTF_8));

        ModelFileValidator.Result result = ModelFileValidator.validate(TestModelFiles.legacyContainer(entries));

        assertEquals(ModelFileValidator.Result.Reason.MISSING_MODEL_FILES, result.reason(), result.detail());
    }

    @Test
    void aLegacyContainerWithoutTheArmModelIsRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("main.json", TestModelFiles.VALID_MAIN.getBytes(StandardCharsets.UTF_8));
        entries.put("texture.png", new byte[] { 1, 2, 3 });

        ModelFileValidator.Result result = ModelFileValidator.validate(TestModelFiles.legacyContainer(entries));

        assertEquals(ModelFileValidator.Result.Reason.MISSING_MODEL_FILES, result.reason(), result.detail());
    }

    @Test
    void aLegacyContainerWhoseEntryNameIsNotFlatIsRejected() throws Exception {
        // The scanner uses entry names only as in-memory map keys today; the validator still refuses path separators so
        // that no future "write by entry name" path can turn an upload into a path traversal.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("main.json", TestModelFiles.VALID_MAIN.getBytes(StandardCharsets.UTF_8));
        entries.put("arm.json", TestModelFiles.VALID_ARM.getBytes(StandardCharsets.UTF_8));
        entries.put("../evil.png", new byte[] { 1, 2, 3 });

        ModelFileValidator.Result result = ModelFileValidator.validate(TestModelFiles.legacyContainer(entries));

        assertEquals(ModelFileValidator.Result.Reason.INVALID_ENTRY_NAME, result.reason(), result.detail());
    }

    @Test
    void aLegacyContainerWithUnparsableGeometryIsRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("main.json", "this is not geometry json".getBytes(StandardCharsets.UTF_8));
        entries.put("arm.json", TestModelFiles.VALID_ARM.getBytes(StandardCharsets.UTF_8));
        entries.put("texture.png", new byte[] { 1, 2, 3 });

        ModelFileValidator.Result result = ModelFileValidator.validate(TestModelFiles.legacyContainer(entries));

        assertEquals(ModelFileValidator.Result.Reason.INVALID_GEOMETRY, result.reason(), result.detail());
    }

    @Test
    void anOpenYsmBinaryFileThatDoesNotDecryptIsRejected() {
        ModelFileValidator.Result result = ModelFileValidator.validate(TestModelFiles.openYsmPrefixGarbage());

        assertEquals(ModelFileValidator.Result.Reason.CORRUPT, result.reason(), result.detail());
    }

    @Test
    void aFileAboveTheUploadLimitIsRejectedBeforeAnyParsing() throws Exception {
        int previousLimit = Config.MAX_MODEL_UPLOAD_MB;
        Config.MAX_MODEL_UPLOAD_MB = 1;
        try {
            // 2 MiB of zeros: above the 1 MiB limit, and deliberately not a container - the size check must win
            // before the header dispatch so a huge upload never reaches the parser at all.
            byte[] oversized = new byte[2 * 1024 * 1024];
            ModelFileValidator.Result result = ModelFileValidator.validate(oversized);

            assertEquals(ModelFileValidator.Result.Reason.TOO_LARGE, result.reason(), result.detail());
            assertEquals(1, ModelFileValidator.maxUploadMb());
            assertEquals(1024L * 1024L, ModelFileValidator.maxUploadBytes());
        } finally {
            Config.MAX_MODEL_UPLOAD_MB = previousLimit;
        }
    }

    @Test
    void theDecompressionBudgetIsAtLeastTheMinimumAndAboveTheUploadLimit() {
        long uploadBytes = ModelFileValidator.maxUploadBytes();
        long budget = ModelFileValidator.maxDecompressedBytes();

        assertTrue(budget >= 64L * 1024L * 1024L, "small models must pass under any configuration: " + budget);
        assertTrue(budget > uploadBytes, "a compressed model may legitimately expand past its wire size: " + budget);
        assertTrue(budget <= Integer.MAX_VALUE, "the budget must stay within one byte array: " + budget);
    }
}
