package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * The texture decode, which now happens on the loader thread instead of inside the client tick.
 * <p>
 * Upstream splits these two: a worker prepares the image and only the GPU upload is asserted onto the render thread.
 * Moving the decode here means the limits that protect the upload - the payload size, the per-axis pixel count, and
 * whether any reader accepts the bytes at all - now run off the client thread, so they are worth pinning directly.
 * This is pure {@code ImageIO} work and needs no game, which is why the test is not gated on anything.
 */
class OuterFileTextureDecodeTest {

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aRealPngDecodesToAnImageOfTheSameSize() throws Exception {
        BufferedImage decoded = OuterFileTexture.decode(png(64, 32));
        assertNotNull(decoded, "a PNG the game would accept must decode");
        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
    }

    @Test
    void anEmptyOrMissingPayloadIsRejected() {
        assertThrows(IOException.class, () -> OuterFileTexture.decode(null));
        assertThrows(IOException.class, () -> OuterFileTexture.decode(new byte[0]));
    }

    @Test
    void bytesNoReaderAcceptsAreRejected() {
        assertThrows(IOException.class, () -> OuterFileTexture.decode(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
    }

    @Test
    void aPayloadOverTheSizeLimitIsRejectedBeforeItIsDecoded() {
        // The check has to come first: decoding a decompression bomb is the thing it exists to prevent. The bytes are
        // deliberately not an image, so a test that passed by failing to decode would not prove anything.
        IOException failure = assertThrows(IOException.class, () -> OuterFileTexture.decode(new byte[9 * 1024 * 1024]));
        assertTrue(
            failure.getMessage()
                .contains("too large"),
            "the size limit must be what rejected it, not the decoder: " + failure.getMessage());
    }

    @Test
    void anImageWiderThanThePerAxisLimitIsRejected() throws Exception {
        // 1.7.10's TextureUtil allocates w*h*4 bytes of GL memory with no check of its own, so this limit is the only
        // thing between a hostile pack and the driver.
        IOException failure = assertThrows(IOException.class, () -> OuterFileTexture.decode(png(4097, 1)));
        assertTrue(
            failure.getMessage()
                .contains("too large"),
            "the pixel limit must be what rejected it: " + failure.getMessage());
    }
}
