package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.data.WheelDeclarations;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.AESUtil;
import com.fox.ysmu.util.ByteInteger;
import com.fox.ysmu.util.DeflateUtil;
import com.fox.ysmu.util.Md5Utils;

/**
 * {@code EncryptTools.PREVIEW_MODEL_VERSION}'s trailing section: the model's GUI preview declaration
 * ({@code preview_animation} / {@code disable_preview_rotation}) and its {@code lang/*.json} tables, carried on the
 * legacy container the way version 6 carries {@code metadata.links}.
 *
 * <p>Until this section existed, a model that reached the client through the legacy channel alone arrived with
 * nothing declared - the selection tile played {@code idle} - and with no language table, because those three fields
 * only ever travelled in the OpenYSM binary payload
 * ({@code YSMBinarySerializer:540,548}, {@code :64}/{@code writeLanguageFiles:437-449}).</p>
 */
class EncryptToolsPreviewAndLanguageSectionTest {

    private static final byte[] UUID = new byte[16];

    /** Writes {@code before} through the current container and reads it back. */
    private static ModelData decode(byte[] passwordFile, byte[] container) throws Exception {
        return EncryptTools.decryptModel(UUID, EncryptTools.encryptPassword(UUID, passwordFile), container);
    }

    private static ModelData payload(String preview, boolean disableRotation,
        Map<String, Map<String, String>> languages) {
        return new ModelData(
            "_test_preview_container",
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            WheelDeclarations.empty(),
            new LinkedHashMap<>(),
            null,
            preview,
            disableRotation,
            languages);
    }

    /** The declaration and the tables survive the container, including a locale's whole key/value table. */
    @Test
    void theLegacyContainerRoundTripsThePreviewDeclarationAndLanguageTables() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();
        Map<String, Map<String, String>> languages = new LinkedHashMap<>();
        languages.put("zh_cn", Map.of("metadata.name", "艾利克斯", "metadata.tips", "原版艾利克斯模型"));
        languages.put("en_us", Map.of("metadata.name", "Alex"));

        ModelData after = decode(password, EncryptTools.assembleEncryptModels(payload("gui", true, languages)));

        assertNotNull(after, "the current container must stay readable by its own reader");
        assertEquals("gui", after.getPreviewAnimation());
        assertTrue(after.isDisablePreviewRotation());
        assertEquals(languages, after.getLanguageFiles());
        assertEquals(
            "艾利克斯",
            after.getLanguageFiles()
                .get("zh_cn")
                .get("metadata.name"));
    }

    /** A model that declares none carries none - the same empty sentinel an older payload decodes to. */
    @Test
    void anEmptyDeclarationRoundTripsAsEmptyRatherThanAsNull() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();

        ModelData after = decode(password, EncryptTools.assembleEncryptModels(payload("", false, Map.of())));

        assertNotNull(after);
        assertEquals("", after.getPreviewAnimation());
        assertFalse(after.isDisablePreviewRotation());
        assertTrue(after.getLanguageFiles()
            .isEmpty());
    }

    /**
     * A version-6 payload - what the previous build wrote, with no preview/language section at all - still decrypts
     * and reports the empty declaration rather than throwing.
     * <p>
     * The fixture writes the v6 layout by hand on purpose: this is the compatibility statement, so it must not be
     * produced by the writer under test. Layout per {@code EncryptTools.decryptModel}: model id, wheel declarations
     * (v4+), the four resource map headers, the four (here empty) resource blobs, the PBR section (v5+), the links
     * section (v6+), and then - for v6 - nothing.
     */
    @Test
    void aVersionSixPayloadStillDecryptsWithNoPreviewAndNoLanguageTables() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();

        ModelData after = decode(password, legacyVersionSixContainer(password));

        assertNotNull(after, "a v6 payload must stay readable");
        assertEquals("_test_preview_v6", after.getModelId());
        assertEquals("", after.getPreviewAnimation(), "an older payload declares no preview clip");
        assertFalse(after.isDisablePreviewRotation(), "an older payload declares no framing flag");
        assertTrue(
            after.getLanguageFiles()
                .isEmpty(),
            "an older payload declares no language tables; that is not an error");
    }

    /** The writer stamps the version the reader gates the new section on. */
    @Test
    void theWriterStampsTheCurrentContainerVersion() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] container = EncryptTools.assembleEncryptModels(payload("gui", false, Map.of()));

        assertEquals(EncryptTools.PREVIEW_MODEL_VERSION, ByteInteger.bytes2Int(container, 4));
        assertEquals(EncryptTools.HEAD, ByteInteger.bytes2Int(container, 0));
    }

    /**
     * The language tables really are inside the encrypted payload: a change to a table alone must change the
     * container's identity, because the legacy cache file is named after the complete container's MD5
     * ({@code ModelCacheWriter.write:81-86}) - if it did not, a client would keep being served the old tables.
     */
    @Test
    void changingOnlyTheLanguageTablesChangesTheContainerIdentity() throws Exception {
        EncryptTools.createRandomPassword();
        ModelData before = payload("gui", false, Map.of("zh_cn", Map.of("metadata.name", "艾利克斯")));
        ModelData renamed = payload("gui", false, Map.of("zh_cn", Map.of("metadata.name", "亚历山大")));

        String first = Md5Utils.md5Hex(EncryptTools.assembleEncryptModels(before));
        String second = Md5Utils.md5Hex(EncryptTools.assembleEncryptModels(renamed));

        assertFalse(first.equals(second), "a language-table change must change the cache identity");
    }

    /** Writes the exact v6 plaintext, encrypts it with the current password and wraps it in the file header. */
    private static byte[] legacyVersionSixContainer(byte[] passwordFile) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        writeString(plain, "_test_preview_v6");
        writeString(plain, WheelDeclarations.empty().encode());
        for (int map = 0; map < 4; map++) {
            // Four resource map headers (model, texture, animation, GUI images), each with zero entries.
            plain.write(ByteInteger.int2Bytes(0));
        }
        // v5's PBR section: zero bases.
        plain.write(ByteInteger.int2Bytes(0));
        // v6's links section: an empty string map is a zero count.
        plain.write(ByteInteger.int2Bytes(0));

        byte[] key = Arrays.copyOfRange(passwordFile, 8, 24);
        byte[] iv = Arrays.copyOfRange(passwordFile, 24, 40);
        byte[] encrypted = AESUtil
            .encrypt(
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv),
                DeflateUtil.compressBytes(plain.toByteArray()))
            .toByteArray();

        ByteArrayOutputStream container = new ByteArrayOutputStream();
        container.write(ByteInteger.int2Bytes(EncryptTools.HEAD));
        // The version is what the reader gates the preview/language section on: 6 is EncryptTools.LINKS_MODEL_VERSION,
        // the build before PREVIEW_MODEL_VERSION (7).
        container.write(ByteInteger.int2Bytes(EncryptTools.LINKS_MODEL_VERSION));
        container.write(Md5Utils.md5(encrypted));
        container.write(encrypted);
        return container.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream stream, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] length = ByteInteger.int2Bytes(bytes.length);
        stream.write(length, 0, length.length);
        stream.write(bytes, 0, bytes.length);
    }
}
