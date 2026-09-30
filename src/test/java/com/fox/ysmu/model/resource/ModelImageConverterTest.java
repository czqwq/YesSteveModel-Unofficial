package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * The image normalisation upstream OpenYSM performs while baking a model: every BMP/JPEG/WebP/AVIF image becomes PNG
 * before it is written into the server cache and sent to a client, so a client never needs a WebP or AVIF reader.
 * <p>
 * The WebP and AVIF cases run against the sample pack in the local runtime directory when it is present (it is not
 * committed), so CI still exercises the plumbing and a developer machine exercises the real decoders.
 */
class ModelImageConverterTest {

    private static final Path PACK_AVATARS = Paths.get("run", "client", "config", "ysmu", "custom", "wine_fox");

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "JPEG", out);
        return out.toByteArray();
    }

    @Test
    void pngIsPassedThroughUntouched() throws Exception {
        byte[] data = png(7, 5);

        ModelImageConverter.Result result = ModelImageConverter.toPng(data, "test.png");

        assertArrayEquals(data, result.data, "a PNG must not be decoded and re-encoded");
        assertEquals(ModelImageConverter.FORMAT_PNG, result.format);
        assertEquals(7, result.width);
        assertEquals(5, result.height);
        assertTrue(result.isPng());
    }

    @Test
    void jpegIsConvertedToPng() throws Exception {
        byte[] data = jpeg(9, 4);

        ModelImageConverter.Result result = ModelImageConverter.toPng(data, "test.jpg");

        assertEquals(ModelImageConverter.FORMAT_PNG, result.format);
        assertEquals(9, result.width);
        assertEquals(4, result.height);
        assertTrue(result.isPng());
        assertEquals(0x89, result.data[0] & 0xFF, "the converted payload must start with the PNG signature");
        assertEquals('P', result.data[1]);
        assertNotEquals(data.length, result.data.length);
        assertEquals(9, ImageIO.read(new ByteArrayInputStream(result.data))
            .getWidth());
    }

    @Test
    void unrecognisedDataIsKeptAsIs() {
        byte[] data = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };

        ModelImageConverter.Result result = ModelImageConverter.toPng(data, "test.bin");

        assertArrayEquals(data, result.data, "an undecodable payload must be shipped unchanged");
        assertEquals(0, result.format);
    }

    @Test
    void emptyDataIsHandled() {
        ModelImageConverter.Result result = ModelImageConverter.toPng(new byte[0], "empty");

        assertEquals(0, result.format);
        assertEquals(0, result.width);
    }

    @Test
    void realPackWebpAndAvifAvatarsBecomePng() throws Exception {
        Path webp = firstWithExtension("sbl.webp");
        Path avif = firstWithExtension("xinghai.avif");
        assumeTrue(webp != null, "sample pack webp avatar not present");
        assumeTrue(avif != null, "sample pack avif avatar not present");

        for (Path source : new Path[] { webp, avif }) {
            byte[] data = Files.readAllBytes(source);
            ModelImageConverter.Result result = ModelImageConverter.toPng(data, source.toString());

            assertEquals(
                ModelImageConverter.FORMAT_PNG,
                result.format,
                source + " must be converted (no client-side reader exists for it)");
            assertTrue(result.width > 0 && result.height > 0, source + " must carry decoded dimensions");
            assertEquals(0x89, result.data[0] & 0xFF);
            assertEquals(
                result.width,
                ImageIO.read(new ByteArrayInputStream(result.data))
                    .getWidth(),
                source + " must decode back to the same width");
        }
    }

    /** Finds one file with the given name anywhere under the sample pack, or {@code null}. */
    private static Path firstWithExtension(String fileName) throws Exception {
        if (!Files.isDirectory(PACK_AVATARS)) {
            return null;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(PACK_AVATARS)) {
            return walk.filter(path -> path.getFileName()
                .toString()
                .equals(fileName))
                .findFirst()
                .orElse(null);
        }
    }
}
