package com.fox.ysmu.model.roaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.Md5Utils;

/**
 * The roaming key decides which settings namespace a model uses (see
 * {@code .agent/phase15-roaming-variables.md}). It has to be a pure function of values that exist on the client and
 * on the server, because a disagreement would silently show one player's settings on another model.
 */
class ModelRoamingKeyTest {

    @Test
    void usesTheFirstFourBytesOfTheHashInBigEndianOrder() {
        // Upstream's Hash256.roamingHash() takes the first four bytes of the model hash in big-endian order.
        assertEquals(0x01234567, ModelRoamingKey.of("0123456789abcdef", "ignored"));
        assertEquals(0xFFFFFFFF, ModelRoamingKey.of("ffffffff", "ignored"));
        assertEquals(0x00000000, ModelRoamingKey.of("00000000", "ignored"));
        // A 64 character sha256 works the same way; only the leading four bytes matter.
        StringBuilder sha256 = new StringBuilder("01234567");
        for (int i = 0; i < 28; i++) {
            sha256.append("89");
        }
        assertEquals(ModelRoamingKey.of("01234567", "a"), ModelRoamingKey.of(sha256.toString(), "b"));
    }

    @Test
    void fallsBackToTheModelIdWhenTheHashIsUnusable() {
        String modelId = "ysmu:_name_e889bee88eb2c2b7e4b994312e342e30";
        int expected = firstFourBytesOfMd5(modelId);

        assertEquals(expected, ModelRoamingKey.of(null, modelId));
        assertEquals(expected, ModelRoamingKey.of("", modelId));
        // Too short, odd length, and non-hexadecimal are all treated as unusable rather than decoded partially.
        assertEquals(expected, ModelRoamingKey.of("012345", modelId));
        assertEquals(expected, ModelRoamingKey.of("0123456", modelId));
        assertEquals(expected, ModelRoamingKey.of("zzzzzzzz", modelId));
        assertEquals(expected, ModelRoamingKey.of(" 01234567", modelId));

        // A null model id is the empty string, not an error.
        assertEquals(firstFourBytesOfMd5(""), ModelRoamingKey.of(null, null));
    }

    @Test
    void differentModelsGetDifferentKeys() {
        assertNotEquals(
            ModelRoamingKey.of(null, "ysmu:alex"),
            ModelRoamingKey.of(null, "ysmu:steve"));
        assertNotEquals(
            ModelRoamingKey.of("0123456789abcdef", null),
            ModelRoamingKey.of("fedcba9876543210", null));
    }

    @Test
    void hexFormRoundTripsThroughUnsignedParsing() {
        for (int key : new int[] { 0, 1, 0x01234567, -1, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            String hex = ModelRoamingKey.toHex(key);
            assertEquals(8, hex.length(), "every key must occupy the same number of characters: " + hex);
            assertEquals(hex, hex.toLowerCase(), "the save-data key must be lower case: " + hex);
            assertEquals(key, Integer.parseUnsignedInt(hex, 16));
        }
        assertEquals("01234567", ModelRoamingKey.toHex(0x01234567));
        assertEquals("ffffffff", ModelRoamingKey.toHex(-1));
    }

    private static int firstFourBytesOfMd5(String value) {
        byte[] digest = Md5Utils.md5(value.getBytes(StandardCharsets.UTF_8));
        return ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16) | ((digest[2] & 0xFF) << 8)
            | (digest[3] & 0xFF);
    }
}
