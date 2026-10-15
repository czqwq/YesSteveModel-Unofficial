package com.fox.ysmu.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.AESUtil;
import com.fox.ysmu.util.ByteInteger;
import com.fox.ysmu.util.DeflateUtil;
import com.fox.ysmu.util.Md5Utils;

/** Fixtures write the old three-map layout independently, not by rewriting a new container's header. */
class EncryptToolsGuiImagesTest {

    private static final byte[] UUID = new byte[16];

    private static Map<String, byte[]> map(String key, byte... value) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private static ModelData data(Map<String, byte[]> images) {
        return new ModelData("gui_probe", Type.FOLDER, map("main", (byte) 1),
            map("skin.png", (byte) 2), map("extra", (byte) 3), images);
    }

    private static byte[] password() throws Exception {
        EncryptTools.createRandomPassword();
        return EncryptTools.writePassword();
    }

    private static ModelData decode(byte[] password, byte[] container) throws Exception {
        return EncryptTools.decryptModel(UUID, EncryptTools.encryptPassword(UUID, password), container);
    }

    @Test
    void roundTripPreservesDedicatedImagesWithoutAddingSkinsOrGeometry() throws Exception {
        byte[] password = password();
        Map<String, byte[]> images = map("gui_background", (byte) 9, (byte) 8);
        images.put("gui_foreground", new byte[] { 7, 6 });
        ModelData before = data(images);
        byte[] container = EncryptTools.assembleEncryptModels(before);
        ModelData after = decode(password, container);

        assertNotNull(after);
        // The writer stamps the *current* container version; that is PREVIEW_MODEL_VERSION since v7 appended the GUI
        // preview declaration and the language tables (EncryptTools.java:63-85, written at :158). An older version is
        // accepted on read instead (see :120).
        assertEquals(EncryptTools.PREVIEW_MODEL_VERSION, ByteInteger.bytes2Int(container, 4));
        assertEquals(before.getModel().keySet(), after.getModel().keySet());
        assertEquals(before.getTexture().keySet(), after.getTexture().keySet());
        assertEquals(Collections.singleton("skin.png"), after.getInfo().getTextures());
        assertArrayEquals(images.get("gui_background"), after.getGuiImages().get("gui_background"));
        assertArrayEquals(images.get("gui_foreground"), after.getGuiImages().get("gui_foreground"));
        images.put("gui_foreground", new byte[] { 5 });
        assertNotEquals(Md5Utils.md5Hex(container),
            Md5Utils.md5Hex(EncryptTools.assembleEncryptModels(data(images))),
            "changing only a GUI image must invalidate legacy cache identity");
    }

    @Test
    void currentPayloadWithoutImagesStillRoundTrips() throws Exception {
        byte[] password = password();
        ModelData after = decode(password, EncryptTools.assembleEncryptModels(data(Collections.emptyMap())));
        assertNotNull(after);
        assertTrue(after.getGuiImages().isEmpty());
    }

    @Test
    void oldThreeMapPayloadRemainsReadableAndPasswordVersionDoesNotRotate() throws Exception {
        byte[] password = password();
        byte[] old = oldContainer(data(Collections.emptyMap()), password);
        ModelData after = decode(password, old);
        assertNotNull(after);
        assertTrue(after.getGuiImages().isEmpty());
        assertArrayEquals(new byte[] { 1 }, after.getModel().get("main"));
        assertEquals(2, ByteInteger.bytes2Int(password, 4));
        assertTrue(EncryptTools.readPassword(password));
        assertArrayEquals(password, EncryptTools.writePassword());
    }

    @Test
    void rebakeChangesLegacyCacheNameEvenWithoutImagesAndPruneIdentityDropsOldFile() throws Exception {
        byte[] password = password();
        ModelData before = data(Collections.emptyMap());
        byte[] old = oldContainer(before, password);
        byte[] current = EncryptTools.assembleEncryptModels(before);
        String oldName = Md5Utils.md5Hex(old);
        String currentName = Md5Utils.md5Hex(current);
        assertNotEquals(oldName, currentName);
        before.setMd5(currentName);
        Map<String, com.fox.ysmu.model.format.ServerModelInfo> registered =
            Collections.singletonMap(before.getModelId(), before.getInfo());
        assertFalse(com.fox.ysmu.model.format.ModelCacheWriter.isRegisteredCacheFile(
            oldName, registered, Collections.emptyMap()));
        assertTrue(com.fox.ysmu.model.format.ModelCacheWriter.isRegisteredCacheFile(
            currentName, registered, Collections.emptyMap()));
        assertArrayEquals(current, EncryptTools.assembleEncryptModels(before), "same password gives stable cache identity");
    }

    @Test
    void unknownVersionAndTruncatedImagePayloadAreRejected() throws Exception {
        byte[] password = password();
        byte[] current = EncryptTools.assembleEncryptModels(data(map("gui_background", (byte) 4)));
        byte[] future = current.clone();
        System.arraycopy(ByteInteger.int2Bytes(99), 0, future, 4, 4);
        assertNull(decode(password, future));
        assertNull(decode(password, Arrays.copyOf(current, current.length - 1)));
        byte[] old = oldContainer(data(Collections.emptyMap()), password);
        // A v3 header over a v2 body is malformed: it lacks the mandatory fourth map directory.
        System.arraycopy(ByteInteger.int2Bytes(EncryptTools.MODEL_VERSION), 0, old, 4, 4);
        assertNull(decode(password, old));
    }

    @Test
    void versionThreeImagePayloadStillReadsWithoutDeclarations() throws Exception {
        byte[] password = password();
        ModelData before = data(map("gui_background", (byte) 7));
        ModelData after = decode(password, historicalContainer(before, password, 3));
        assertNotNull(after);
        assertArrayEquals(new byte[] { 7 }, after.getGuiImages().get("gui_background"));
        assertTrue(after.getWheelDeclarations().values().animations.isEmpty());
    }

    private static byte[] oldContainer(ModelData data, byte[] password) throws Exception {
        return historicalContainer(data, password, 2);
    }

    private static byte[] historicalContainer(ModelData data, byte[] password, int version) throws Exception {
        ByteArrayOutputStream clear = new ByteArrayOutputStream();
        string(clear, data.getModelId());
        info(clear, data.getModel());
        info(clear, data.getTexture());
        info(clear, data.getAnimation());
        if (version == 3) info(clear, data.getGuiImages());
        for (Map<String, byte[]> resources : Arrays.asList(data.getModel(), data.getTexture(), data.getAnimation())) {
            for (byte[] bytes : resources.values()) clear.write(bytes);
        }
        if (version == 3) {
            for (byte[] bytes : data.getGuiImages().values()) clear.write(bytes);
        }
        byte[] encrypted = AESUtil.encrypt(
            new SecretKeySpec(Arrays.copyOfRange(password, 8, 24), "AES"),
            new IvParameterSpec(Arrays.copyOfRange(password, 24, 40)),
            DeflateUtil.compressBytes(clear.toByteArray())).toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteInteger.int2Bytes(EncryptTools.HEAD));
        out.write(ByteInteger.int2Bytes(version));
        out.write(Md5Utils.md5(encrypted));
        out.write(encrypted);
        return out.toByteArray();
    }

    private static void info(ByteArrayOutputStream out, Map<String, byte[]> resources) throws Exception {
        out.write(ByteInteger.int2Bytes(resources.size()));
        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
            string(out, entry.getKey());
            out.write(ByteInteger.int2Bytes(entry.getValue().length));
        }
    }

    private static void string(ByteArrayOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(ByteInteger.int2Bytes(bytes.length));
        out.write(bytes);
    }
}
