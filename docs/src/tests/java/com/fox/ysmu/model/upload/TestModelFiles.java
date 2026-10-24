package com.fox.ysmu.model.upload;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import com.fox.ysmu.client.GeometryFixtures;
import com.fox.ysmu.util.AESUtil;
import com.fox.ysmu.util.ByteInteger;
import com.fox.ysmu.util.DeflateUtil;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.YesModelUtils;

/**
 * Fixtures for the phase55 upload tests: a real legacy YSGP container and the two OpenYSM container prefixes.
 *
 * <p>The container layout is written out here independently (head/version/md5 + per-entry base64 name, encrypted
 * body lengths, AES key/iv, encrypted deflated body) rather than by calling a production packer: the production
 * writer ({@code YesModelUtils.export}) only writes to {@code config/ysmu/export} and returns nothing, and the
 * reader is what is under test. The format itself is the one {@code YesModelUtils.input} reads, so a fixture that
 * passes it is by construction a file that reader accepts.</p>
 */
final class TestModelFiles {

    static final String VALID_MAIN = GeometryFixtures.INLINE_GEOMETRY;
    static final String VALID_ARM = GeometryFixtures.INLINE_GEOMETRY;

    private static final byte[] PNG = new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0 };
    private static final byte[] OPEN_YSM_PREFIX = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G',
        'P' };

    private TestModelFiles() {}

    /** A valid legacy model: {@code main.json}, {@code arm.json} and one texture. */
    static byte[] validLegacyModel() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("main.json", VALID_MAIN.getBytes(StandardCharsets.UTF_8));
        entries.put("arm.json", VALID_ARM.getBytes(StandardCharsets.UTF_8));
        entries.put("texture.png", PNG);
        return legacyContainer(entries);
    }

    /** The same model with one entry replaced/removed by the caller's map (insertion order preserved). */
    static byte[] legacyContainer(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream filesData = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            filesData.write(newEntry(entry.getKey(), entry.getValue()));
        }
        byte[] body = filesData.toByteArray();
        ByteArrayOutputStream container = new ByteArrayOutputStream();
        container.write(ByteInteger.int2Bytes(YesModelUtils.HEAD));
        container.write(ByteInteger.int2Bytes(YesModelUtils.VERSION_II));
        container.write(Md5Utils.md5(body));
        container.write(body);
        return container.toByteArray();
    }

    /** A file with the OpenYSM binary prefix but nothing that decrypts: used to pin the CORRUPT rejection. */
    static byte[] openYsmPrefixGarbage() {
        byte[] bytes = new byte[256];
        new SecureRandom(new byte[] { 1, 2, 3, 4 }).nextBytes(bytes);
        System.arraycopy(OPEN_YSM_PREFIX, 0, bytes, 0, OPEN_YSM_PREFIX.length);
        return bytes;
    }

    private static byte[] newEntry(String name, byte[] rawData) throws Exception {
        byte[] compressed = DeflateUtil.compressBytes(rawData);
        SecretKey secretKey = AESUtil.generateKey();
        IvParameterSpec iv = AESUtil.generateIv();
        byte[] encrypted = AESUtil.encrypt(secretKey, iv, compressed)
            .toByteArray();
        byte[] cipherSecretKey = AESUtil.encrypt(AESUtil.getKey(keyFromMd5(encrypted)), iv, secretKey.getEncoded())
            .toByteArray();
        byte[] nameBytes = java.util.Base64.getEncoder()
            .encode(name.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        entry.write(ByteInteger.int2Bytes(nameBytes.length));
        entry.write(nameBytes);
        entry.write(ByteInteger.int2Bytes(encrypted.length));
        entry.write(ByteInteger.int2Bytes(cipherSecretKey.length));
        entry.write(cipherSecretKey);
        entry.write(iv.getIV());
        entry.write(encrypted);
        return entry.toByteArray();
    }

    /** Mirrors {@code YesModelUtils.getKeyFromMd5}: md5 as a seed for a fixed pseudo-random AES key. */
    private static byte[] keyFromMd5(byte[] fileData) {
        byte[] md5 = Md5Utils.md5(fileData);
        long value = 0L;
        for (byte b : md5) {
            value = (value << 8) + (b & 255);
        }
        byte[] keys = new byte[16];
        new Random(value).nextBytes(keys);
        return keys;
    }
}
