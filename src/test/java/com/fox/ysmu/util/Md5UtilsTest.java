package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * The hex form {@link Md5Utils#toHexString(byte[])} produces is not cosmetic: it is the disk name of every cache
 * file the sync channel writes ({@code network/message/SendModelFile}, {@code model/format/ModelCacheWriter}) and
 * the comparison side of the cache integrity checks ({@code data/EncryptTools}, {@code ServerModelInfo}). The method
 * now delegates to {@code HexFormat.of().formatHex(bytes)} - upstream's mechanism
 * ({@code model/domain/Hash256.java:10,99}) - whose default is lower case with exactly two digits per byte. These
 * cases pin that contract so a future change to the formatter cannot silently rename every cached model.
 */
class Md5UtilsTest {

    @Test
    void hexFormIsLowerCaseWithTwoDigitsPerByte() {
        byte[] bytes = { 0x00, 0x0f, 0x10, (byte) 0xff, (byte) 0x80, 0x7f, 0x01, (byte) 0xa5 };

        String hex = Md5Utils.toHexString(bytes);

        assertEquals("000f10ff807f01a5", hex);
        assertEquals(hex.toLowerCase(Locale.ROOT), hex, "the cache file name must stay lower case");
        assertEquals(bytes.length * 2, hex.length(), "every byte must occupy exactly two characters: " + hex);
    }

    @Test
    void singleDigitBytesAreZeroPadded() {
        // 0x0b is the case the hand-rolled loop had to special-case; a formatter that dropped the pad would turn a
        // 16 byte digest into a 31 character name and break every cache name whose length is checked
        // (ServerModelInfo.CACHE_FILE_NAME_LENGTH = 32).
        assertEquals("0b", Md5Utils.toHexString(new byte[] { 0x0b }));
        assertEquals("00", Md5Utils.toHexString(new byte[] { 0 }));
        assertEquals("", Md5Utils.toHexString(new byte[0]));
    }

    @Test
    void digestHexMatchesTheKnownMd5OfTheSameBytes() {
        byte[] digest = Md5Utils.md5("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8));

        String hex = Md5Utils.toHexString(digest);

        assertEquals("9e107d9d372bb6826bd81d3542a419d6", hex);
        assertEquals(hex, Md5Utils.md5Hex("The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5Utils.md5Hex(new byte[0]));
    }

    /**
     * The chunked-transfer receiver compares the digest it computes against the name the server sent, with
     * {@code equalsIgnoreCase} ({@code network/message/SendModelFileChunk#contentMatchesFileName} - a private handler
     * method that needs live network state and therefore cannot be called from here). Both sides of that comparison
     * are pinned instead: {@link Md5Utils#md5Hex} produces the lower-case form (asserted above), while the name it is
     * compared with is the <b>upper-case</b> one the server writes - {@code model/format/ModelCacheWriter.java:60}
     * {@code md5Hex(...).toUpperCase(Locale.US)} into {@code ServerModelInfo.md5}, sent by
     * {@code network/message/SyncModelFiles.java:196}. This case asserts the two forms meet under
     * {@code equalsIgnoreCase}; it deliberately does not assert case equality, which would contradict the wire format.
     */
    @Test
    void theChunkedReceiversCaseInsensitiveComparisonStillMatchesTheUpperCaseCacheName() {
        byte[] payload = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        String upperCaseName = "9E107D9D372BB6826BD81D3542A419D6";

        String computed = Md5Utils.md5Hex(payload);

        assertEquals(computed.toLowerCase(Locale.ROOT), computed, "the computed digest is the lower-case form");
        assertTrue(computed.equalsIgnoreCase(upperCaseName), "equalsIgnoreCase must still accept the wire name");
        assertFalse(computed.equals(upperCaseName), "the two forms differ in case, so a case-sensitive check would fail");
    }
}
