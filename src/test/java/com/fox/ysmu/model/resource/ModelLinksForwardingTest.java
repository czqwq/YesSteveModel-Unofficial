package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.AESUtil;
import com.fox.ysmu.util.ByteInteger;
import com.fox.ysmu.util.DeflateUtil;
import com.fox.ysmu.util.Md5Utils;

/**
 * {@code metadata.links} on the data plane: the bridge that forwards a parsed model's links into a client payload, the
 * legacy container's version-6 section that carries them on the legacy channel, and the compatibility both versions
 * need.
 *
 * <p>Before this field had a consumer, {@code YSMFolderDeserializer:163} parsed it and
 * {@code YSMBinarySerializer:486-490} / {@code YSMBinaryDeserializer:605-608} moved it across the OpenYSM wire, but
 * {@code RawYsmModelAdapter.toLegacyModelData} dropped it and the legacy container had no section for it at all.</p>
 *
 * <p>The OpenYSM baked payload itself is unchanged by this: it already carried the links, so
 * {@code ModelCacheWriter.OPEN_YSM_BAKE_VERSION} is deliberately not bumped (the constant's own rule is "bump when the
 * baked payload changes meaning"). Only the legacy container gained a field, which is what the version-6 constant and
 * the v5 fixture below are about.</p>
 */
class ModelLinksForwardingTest {

    private static final int PNG_FORMAT = 2;
    private static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    /** A raw model the bridge accepts: main + arm geometry and one texture, which is all {@code isBridgeable} asks. */
    private static RawYsmModel bridgeableModel() {
        RawYsmModel raw = new RawYsmModel();
        raw.modelId = "_test_links_bridge";
        raw.mainEntity.mainModel = geometry();
        raw.mainEntity.armModel = geometry();
        raw.mainEntity.textures.put("texture.png", pngTexture("texture.png"));
        return raw;
    }

    private static RawYsmModel.RawGeometry geometry() {
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();
        geo.identifier = "geometry.test";
        geo.sourceJson = GEO.getBytes(StandardCharsets.UTF_8);
        return geo;
    }

    private static RawYsmModel.RawTexture pngTexture(String name) {
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = name;
        texture.sourceFileName = name;
        texture.imageFormat = PNG_FORMAT;
        texture.data = new byte[] { (byte) 0x89, 'P', 'N', 'G' };
        return texture;
    }

    private static Map<String, String> links(String... typeToUrl) {
        Map<String, String> links = new LinkedHashMap<>();
        for (int i = 0; i < typeToUrl.length; i += 2) {
            links.put(typeToUrl[i], typeToUrl[i + 1]);
        }
        return links;
    }

    /** The bridge keeps the links and their declaration order; "the first two" depends on that order. */
    @Test
    void theBridgeForwardsLinksInDeclarationOrder() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.metadata.links.putAll(links("home", "https://example.invalid/home", "donate", "https://example.invalid/pay"));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);

        assertEquals(
            Arrays.asList("home", "donate"),
            new ArrayList<>(data.getLinks().keySet()),
            "declaration order must survive the bridge");
        assertEquals("https://example.invalid/pay", data.getLinks().get("donate"));
    }

    /** A model that declares none carries none, and the accessor is stable rather than null. */
    @Test
    void aModelWithoutLinksBridgesToAnEmptyMap() throws Exception {
        ModelData data = RawYsmModelAdapter.toLegacyModelData(bridgeableModel(), "_test_links_bridge");

        assertTrue(data.getLinks().isEmpty());
    }

    /** The legacy container round-trips the links and their order. */
    @Test
    void theLegacyContainerRoundTripsLinks() throws Exception {
        ModelData before = payload(links("home", "u1", "donate", "u2", "wiki", "u3"));
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();
        byte[] uuid = new byte[16];

        byte[] container = EncryptTools.assembleEncryptModels(before);
        ModelData after = EncryptTools.decryptModel(uuid, EncryptTools.encryptPassword(uuid, password), container);

        assertNotNull(after);
        assertEquals(
            Arrays.asList("home", "donate", "wiki"),
            new ArrayList<>(after.getLinks().keySet()),
            "order and content must survive the container");
        assertEquals("u2", after.getLinks().get("donate"));
    }

    /**
     * A version-5 payload - what the previous build wrote, with no links section at all - still decrypts, and reports
     * no links rather than throwing.
     * <p>
     * The fixture writes the v5 layout by hand on purpose: this is the compatibility statement, so it must not be
     * produced by the writer under test. Layout per {@code EncryptTools.decryptModel}: model id, wheel declarations
     * (v4+), the four resource map headers, the four (here empty) resource blobs, the PBR section (v5+), and then -
     * for v5 - nothing.
     */
    @Test
    void aVersionFivePayloadStillDecryptsWithNoLinks() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();
        byte[] uuid = new byte[16];
        byte[] container = legacyVersionFiveContainer(password);

        ModelData after = EncryptTools.decryptModel(uuid, EncryptTools.encryptPassword(uuid, password), container);

        assertNotNull(after, "a v5 payload must stay readable");
        assertEquals("_test_links_v5", after.getModelId());
        assertTrue(after.getLinks().isEmpty(), "an older payload declares no links; that is not an error");
    }

    private static ModelData payload(Map<String, String> links) {
        return new ModelData(
            "_test_links_container",
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            WheelDeclarations.empty(),
            new LinkedHashMap<>(),
            links);
    }

    /** Writes the exact v5 plaintext, encrypts it with the current password and wraps it in the file header. */
    private static byte[] legacyVersionFiveContainer(byte[] passwordFile) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        writeString(plain, "_test_links_v5");
        writeString(plain, WheelDeclarations.empty().encode());
        for (int map = 0; map < 4; map++) {
            // Four resource map headers (model, texture, animation, GUI images), each with zero entries.
            plain.write(ByteInteger.int2Bytes(0));
        }
        // v5's PBR section: zero bases.
        plain.write(ByteInteger.int2Bytes(0));

        byte[] key = Arrays.copyOfRange(passwordFile, 8, 24);
        byte[] iv = Arrays.copyOfRange(passwordFile, 24, 40);
        byte[] encrypted = AESUtil
            .encrypt(new SecretKeySpec(key, "AES"), new IvParameterSpec(iv), DeflateUtil.compressBytes(plain.toByteArray()))
            .toByteArray();

        ByteArrayOutputStream container = new ByteArrayOutputStream();
        container.write(ByteInteger.int2Bytes(EncryptTools.HEAD));
        // The version is what the reader gates the links section on: 5 is EncryptTools.MODEL_VERSION, the build before
        // LINKS_MODEL_VERSION (6).
        container.write(ByteInteger.int2Bytes(EncryptTools.MODEL_VERSION));
        container.write(Md5Utils.md5(encrypted));
        container.write(encrypted);
        return container.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream stream, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        // The one-argument OutputStream.write(byte[]) declares IOException; ByteArrayOutputStream's offset overload
        // does not, and this fixture's plaintext writer has no checked exceptions of its own.
        byte[] length = ByteInteger.int2Bytes(bytes.length);
        stream.write(length, 0, length.length);
        stream.write(bytes, 0, bytes.length);
    }
}
