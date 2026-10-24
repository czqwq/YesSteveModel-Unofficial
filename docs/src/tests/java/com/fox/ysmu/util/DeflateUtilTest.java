package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;

import org.junit.jupiter.api.Test;

/**
 * phase55: the bounded overload of {@link DeflateUtil} that the upload validator uses.
 *
 * <p>The legacy YSGP container stores each entry as a deflate stream; deflate's compression ratio has no upper
 * bound, so a small uploaded container could otherwise expand to gigabytes on the server. The old one-argument
 * method has to keep decompressing without a limit (the disk scanner uses it and its behaviour must not change);
 * only the new overload refuses oversized output.</p>
 */
class DeflateUtilTest {

    @Test
    void theUnboundedDecodeStillRoundTrips() throws Exception {
        byte[] raw = "the quick brown fox ".repeat(4096)
            .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeflateUtil.compressBytes(raw);

        assertArrayEquals(raw, DeflateUtil.decompressBytes(compressed));
    }

    @Test
    void theBoundedDecodeRefusesAnOutputAboveItsLimit() {
        byte[] raw = "bomb ".repeat(8192)
            .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeflateUtil.compressBytes(raw);

        assertThrows(DataFormatException.class, () -> DeflateUtil.decompressBytes(compressed, 1024));
        assertThrows(DataFormatException.class, () -> DeflateUtil.decompressBytes(compressed, raw.length - 1L));
    }

    @Test
    void theBoundedDecodeAcceptsAnOutputAtItsLimit() throws Exception {
        byte[] raw = "bomb ".repeat(16)
            .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeflateUtil.compressBytes(raw);

        assertArrayEquals(raw, DeflateUtil.decompressBytes(compressed, raw.length));
    }
}
