package com.fox.ysmu.model.resource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import com.fox.ysmu.ysmu;

import rip.ysm.imagestream.avif.AvifDecoder;
import rip.ysm.imagestream.jpeg.JpegDecoder;
import rip.ysm.imagestream.webp.WebpDecoder;

/**
 * Normalizes a model's images to PNG while the model is parsed - the 1.7.10 port of what upstream OpenYSM does in
 * {@code YSMClientMapper}: it detects the image format, decodes BMP/JPEG/WebP/AVIF with the matching decoder from
 * the {@code com.github.OpenYSM:ImageStream} library ({@code case 3 -> new JpegDecoder().read(data)} and friends)
 * and writes the result back out as PNG. The client therefore only ever receives PNG and never needs a WebP or AVIF
 * reader - which is why upstream never meets {@code no image reader} for an avatar while this port did.
 * <p>
 * PNG is passed through untouched (no decode, no re-encode), and anything that cannot be decoded is returned
 * unchanged with a warning once per format, so a broken or unsupported image degrades exactly as it did before this
 * class existed instead of failing the whole model.
 * <p>
 * The decoder classes are referenced directly (not through {@code ImageIO}'s SPI registry) so that the conversion
 * works even where the service files are missing, and every call is wrapped so a missing library degrades to "keep
 * the original bytes" rather than a class-loading failure.
 */
public final class ModelImageConverter {

    /** Format id used by the model formats: 1=BMP, 2=PNG, 3=JPEG, 4=WEBP, 5=AVIF. */
    public static final int FORMAT_PNG = 2;

    private static final Set<Integer> WARNED_FORMATS = Collections
        .newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    private ModelImageConverter() {}

    /** The image bytes to ship plus the size and format that go with them. */
    public static final class Result {

        /** PNG bytes, or the untouched input when it could not be converted. */
        public final byte[] data;
        /** {@link #FORMAT_PNG} after a conversion; the detected format otherwise. */
        public final int format;
        public final int width;
        public final int height;

        private Result(byte[] data, int format, int width, int height) {
            this.data = data;
            this.format = format;
            this.width = width;
            this.height = height;
        }

        public boolean isPng() {
            return this.format == FORMAT_PNG;
        }
    }

    /**
     * Decodes {@code data} when it is not PNG and re-encodes it as PNG; returns it unchanged when it already is PNG
     * or cannot be decoded.
     *
     * @param sourceName resource name, for the "cannot be decoded" warning only
     */
    public static Result toPng(byte[] data, String sourceName) {
        if (data == null || data.length == 0) {
            return new Result(data, 0, 0, 0);
        }
        int format = YSMFolderDeserializer.detectFormat(data);
        if (format == FORMAT_PNG) {
            int[] size = pngSize(data);
            return new Result(data, FORMAT_PNG, size[0], size[1]);
        }

        BufferedImage image = decode(data, format);
        if (image == null) {
            return new Result(data, format, 0, 0);
        }
        byte[] png = encodePng(image);
        if (png == null) {
            return new Result(data, format, image.getWidth(), image.getHeight());
        }
        return new Result(png, FORMAT_PNG, image.getWidth(), image.getHeight());
    }

    private static BufferedImage decode(byte[] data, int format) {
        try {
            switch (format) {
                case 1:
                    return ImageIO.read(new ByteArrayInputStream(data)); // BMP
                case 3:
                    return new JpegDecoder().read(data); // JPEG
                case 4:
                    return new WebpDecoder().read(data); // WEBP
                case 5:
                    return new AvifDecoder().read(data); // AVIF
                default:
                    warnOnce(format, "the format is not recognised");
                    return null;
            }
        } catch (Exception | LinkageError e) {
            // A missing ImageStream (or an image its decoders reject) must not fail the model: keep the original
            // bytes, exactly like before this class existed, and say so once per format.
            warnOnce(format, e.getClass()
                .getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return null;
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            warnOnce(FORMAT_PNG, "PNG re-encoding failed: " + e);
            return null;
        }
    }

    /** PNG dimensions straight from the IHDR chunk, without decoding the pixels. */
    private static int[] pngSize(byte[] data) {
        if (data.length < 24) {
            return new int[] { 0, 0 };
        }
        int width = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8)
            | (data[19] & 0xFF);
        int height = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8)
            | (data[23] & 0xFF);
        return new int[] { width, height };
    }

    private static void warnOnce(int format, String reason) {
        if (WARNED_FORMATS.add(format)) {
            ysmu.LOG.warn(
                "YSM image normalisation skipped a {} image ({}); it is shipped in its original format and the"
                    + " client will show the missing texture if it cannot read it. Further occurrences of this"
                    + " format are not logged.",
                formatName(format),
                reason);
        }
    }

    private static String formatName(int format) {
        switch (format) {
            case 1:
                return "BMP";
            case 2:
                return "PNG";
            case 3:
                return "JPEG";
            case 4:
                return "WebP";
            case 5:
                return "AVIF/HEIF";
            default:
                return "unrecognised";
        }
    }
}
