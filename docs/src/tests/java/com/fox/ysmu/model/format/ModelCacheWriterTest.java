package com.fox.ysmu.model.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import rip.ysm.security.YsmCrypt;

/**
 * The sync cache file name comes from the model's own sha256, so it cannot notice that the *payload* changed. These
 * cases pin the salt that makes a baker change invalidate the cache - the reason ten models could keep rendering
 * nothing across reloads and restarts after the baking bug itself was fixed.
 */
class ModelCacheWriterTest {

    @Test
    void hashSourceCarriesTheBakeVersionAndTheContent() {
        String source = ModelCacheWriter.hashSourceFor(null, "wine_fox/some_model");

        assertTrue(
            source.startsWith(ModelCacheWriter.OPEN_YSM_BAKE_VERSION + "|"),
            "the bake version must be part of the hashed content: " + source);
        assertTrue(source.endsWith("wine_fox/some_model"), source);
        assertFalse(source.equals("wine_fox/some_model"), "an unsalted source would keep an older payload valid");
    }

    @Test
    void saltChangesTheHashesOfTheSameModel() {
        byte[] key = new byte[56];
        Arrays.fill(key, (byte) 7);
        String salted = ModelCacheWriter.hashSourceFor(null, "wine_fox/some_model");

        long[] withoutSalt = YsmCrypt.calculateModelHashes("wine_fox/some_model", key);
        long[] withSalt = YsmCrypt.calculateModelHashes(salted, key);

        assertNotEquals(withoutSalt[0], withSalt[0], "the salted hash must differ, or old caches stay valid");
        assertEquals(2, withSalt.length);
    }
}
