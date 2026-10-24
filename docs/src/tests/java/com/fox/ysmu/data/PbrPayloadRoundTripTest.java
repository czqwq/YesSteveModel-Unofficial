package com.fox.ysmu.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fox.ysmu.model.format.Type;

class PbrPayloadRoundTripTest {
    @Test void v4StillReadsWheelSectionWithEmptyComponents() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();
        ModelData payload = new ModelData("old", Type.FOLDER, Map.of(), Map.of(), Map.of(), Map.of(), WheelDeclarations.empty());
        byte[] current = EncryptTools.assembleEncryptModels(payload);
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(java.util.Arrays.copyOfRange(password, 8, 24), "AES");
        javax.crypto.spec.IvParameterSpec iv = new javax.crypto.spec.IvParameterSpec(java.util.Arrays.copyOfRange(password, 24, 40));
        byte[] clear = com.fox.ysmu.util.DeflateUtil.decompressBytes(com.fox.ysmu.util.AESUtil.decrypt(key, iv,
            java.util.Arrays.copyOfRange(current, 24, current.length)).toByteArray());
        // v4 has none of the trailing sections the current writer emits: the PBR base count (v5, 4 bytes), the links
        // count (v6, 4 bytes) and - since v7 - the preview_animation string (4), its disable_preview_rotation flag (4)
        // and the language-file count (4). Every one of them must be trimmed: leaving any behind leaves bytes the v4
        // reader never consumes, and it rejects trailing bytes. Keep this in sync with EncryptTools.encryptModel when
        // another trailing section is added.
        int trailingSections = 4 + 4 + 12;
        clear = java.util.Arrays.copyOf(clear, clear.length - trailingSections);
        byte[] encrypted = com.fox.ysmu.util.AESUtil.encrypt(key, iv, com.fox.ysmu.util.DeflateUtil.compressBytes(clear)).toByteArray();
        java.io.ByteArrayOutputStream old = new java.io.ByteArrayOutputStream();
        old.write(com.fox.ysmu.util.ByteInteger.int2Bytes(EncryptTools.HEAD));
        old.write(com.fox.ysmu.util.ByteInteger.int2Bytes(4));
        old.write(com.fox.ysmu.util.Md5Utils.md5(encrypted)); old.write(encrypted);
        byte[] uuid = new byte[16];
        ModelData decoded = EncryptTools.decryptModel(uuid, EncryptTools.encryptPassword(uuid, password), old.toByteArray());
        assertNotNull(decoded); assertTrue(decoded.getPbrComponents().isEmpty());
    }

    @Test void v5PreservesExplicitRolesAndWheelDeclarationsWithoutSkinPollution() throws Exception {
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();
        ModelData before = new ModelData("pbr", Type.FOLDER, Map.of("main", new byte[] {1}),
            Map.of("base.png", new byte[] {2}), Map.of(), Map.of(), WheelDeclarations.empty(),
            Map.of("base.png", Map.of(1, new byte[] {3}, 2, new byte[] {4})));
        byte[] uuid = new byte[16];
        ModelData after = EncryptTools.decryptModel(uuid, EncryptTools.encryptPassword(uuid, password),
            EncryptTools.assembleEncryptModels(before));
        assertNotNull(after);
        assertEquals(Map.of("base.png", new byte[] {2}).keySet(), after.getTexture().keySet());
        assertArrayEquals(new byte[] {3}, after.getPbrComponents().get("base.png").get(1));
        assertArrayEquals(new byte[] {4}, after.getPbrComponents().get("base.png").get(2));
        assertTrue(after.getWheelDeclarations().values().animations.isEmpty());
    }
}
