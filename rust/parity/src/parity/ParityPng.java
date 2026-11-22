package parity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import com.fox.ysmu.nativebridge.NativeImages;

/**
 * M2 — PNG parity and benchmark.
 *
 * <p>
 * Decode parity: for every PNG in the built-in packs, {@code NativeImages.decodePng} must return the
 * exact same {@code 0xAARRGGBB} pixels as {@code ImageIO.read(...).getRGB(...)}. That covers palette,
 * grayscale, alpha, and interlaced PNGs, because the built-in set contains all of those. A failure
 * prints the PNG name and the first differing pixel with both values.
 *
 * <p>
 * Encode parity: a native PNG encode of those same pixels must decode, through {@code ImageIO}, back
 * to the identical pixel array (the encoder is not required to produce the same bytes as ImageIO, only
 * a lossless PNG of the same image).
 */
public final class ParityPng {

    private ParityPng() {}

    public static void run(Path repo, Report report) {
        List<Path> pngs;
        List<byte[]> payloads = new ArrayList<>();
        try {
            pngs = Corpus.pngFiles(repo);
            for (Path png : pngs) {
                payloads.add(Files.readAllBytes(png));
            }
        } catch (IOException failure) {
            report.fail("png", "corpus", "", "", "", "could not read the built-in PNG corpus: " + failure);
            return;
        }
        if (payloads.isEmpty()) {
            report.fail("png", "corpus", "", "", "", "no PNG found under " + Corpus.BUILTIN);
            return;
        }
        if (!NativeBootstrap.available()) {
            report.pending("png", "decode parity", "native layer unavailable: " + NativeBootstrap.status());
            return;
        }
        if (NativeImages.decodePng(payloads.get(0)) == null) {
            report.pending(
                "png",
                "decode parity",
                "NativeImages.decodePng returned null for a valid built-in PNG - the M2 native image module is not"
                    + " implemented in the loaded artifact yet (or the module is disabled). Rerun after M2 lands.");
            return;
        }

        // ── Decode parity ──────────────────────────────────────────────────────────────────────────
        int compared = 0;
        long totalBytes = 0L;
        long totalPixels = 0L;
        String firstFailure = null;
        for (int index = 0; index < payloads.size(); index++) {
            byte[] bytes = payloads.get(index);
            String name = Corpus.name(repo, pngs.get(index));
            BufferedImage image;
            try {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                firstFailure = name + ": ImageIO could not read the PNG: " + failure;
                break;
            }
            if (image == null) {
                firstFailure = name + ": ImageIO returned null for a .png file";
                break;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] javaPixels = image.getRGB(0, 0, width, height, null, 0, width);
            int[] nativePixels = NativeImages.decodePng(bytes);
            if (nativePixels == null) {
                firstFailure = name + ": native decodePng returned null";
                break;
            }
            if (nativePixels.length != width * height + 2) {
                firstFailure = name + ": native decode returned " + nativePixels.length + " ints, expected "
                    + (width * height + 2) + " ([width, height, argb...])";
                break;
            }
            if (nativePixels[0] != width || nativePixels[1] != height) {
                firstFailure = name + ": native size " + nativePixels[0] + "x" + nativePixels[1] + ", ImageIO says "
                    + width + "x" + height;
                break;
            }
            int mismatch = -1;
            for (int pixel = 0; pixel < javaPixels.length; pixel++) {
                if (nativePixels[pixel + 2] != javaPixels[pixel]) {
                    mismatch = pixel;
                    break;
                }
            }
            if (mismatch >= 0) {
                int x = mismatch % width;
                int y = mismatch / width;
                firstFailure = name + ": first differing pixel (" + x + "," + y + "): Java "
                    + hex(javaPixels[mismatch]) + ", Rust " + hex(nativePixels[mismatch + 2]);
                break;
            }
            compared++;
            totalBytes += bytes.length;
            totalPixels += (long) width * height;
        }
        if (firstFailure == null) {
            report.pass(
                "png",
                "decode ARGB parity",
                compared + " PNGs (" + Corpus.humanBytes(totalBytes) + ")",
                totalPixels + " pixels",
                "",
                "every built-in PNG decodes to the exact ImageIO ARGB pixels");
        } else {
            report.fail(
                "png",
                "decode ARGB parity",
                compared + "/" + payloads.size() + " compared",
                "",
                "",
                firstFailure);
        }

        // ── Encode round-trip parity ───────────────────────────────────────────────────────────────
        int encoded = 0;
        String firstEncodeFailure = null;
        for (int index = 0; index < payloads.size(); index++) {
            byte[] bytes = payloads.get(index);
            String name = Corpus.name(repo, pngs.get(index));
            BufferedImage image;
            try {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                firstEncodeFailure = name + ": ImageIO could not read the PNG: " + failure;
                break;
            }
            if (image == null) {
                firstEncodeFailure = name + ": ImageIO returned null";
                break;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] javaPixels = image.getRGB(0, 0, width, height, null, 0, width);
            byte[] encodedBytes = NativeImages.encodePng(width, height, javaPixels, 6);
            if (encodedBytes == null) {
                firstEncodeFailure = name + ": native encodePng returned null";
                break;
            }
            BufferedImage roundTripped;
            try {
                roundTripped = ImageIO.read(new ByteArrayInputStream(encodedBytes));
            } catch (IOException failure) {
                firstEncodeFailure = name + ": ImageIO could not read the native PNG: " + failure;
                break;
            }
            if (roundTripped == null) {
                firstEncodeFailure = name + ": ImageIO returned null for the native PNG bytes";
                break;
            }
            if (roundTripped.getWidth() != width || roundTripped.getHeight() != height) {
                firstEncodeFailure = name + ": native PNG is " + roundTripped.getWidth() + "x"
                    + roundTripped.getHeight() + ", expected " + width + "x" + height;
                break;
            }
            int[] roundTrippedPixels = roundTripped.getRGB(0, 0, width, height, null, 0, width);
            int mismatch = -1;
            for (int pixel = 0; pixel < javaPixels.length; pixel++) {
                if (roundTrippedPixels[pixel] != javaPixels[pixel]) {
                    mismatch = pixel;
                    break;
                }
            }
            if (mismatch >= 0) {
                int x = mismatch % width;
                int y = mismatch / width;
                firstEncodeFailure = name + ": round-trip pixel (" + x + "," + y + "): original "
                    + hex(javaPixels[mismatch]) + ", decoded native PNG " + hex(roundTrippedPixels[mismatch]);
                break;
            }
            encoded++;
        }
        if (firstEncodeFailure == null) {
            report.pass(
                "png",
                "encode round-trip parity",
                encoded + " PNGs",
                "",
                "",
                "native encodePng output decodes through ImageIO to the identical pixels");
        } else {
            report.fail(
                "png",
                "encode round-trip parity",
                encoded + "/" + payloads.size() + " compared",
                "",
                "",
                firstEncodeFailure);
        }

        // ── Benchmarks ─────────────────────────────────────────────────────────────────────────────
        Bench.Result javaDecode = Bench.time(totalBytes, () -> {
            try {
                for (byte[] bytes : payloads) {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image != null) {
                        image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        Bench.Result rustDecode = Bench.time(totalBytes, () -> {
            for (byte[] bytes : payloads) {
                NativeImages.decodePng(bytes);
            }
        });
        report.pass(
            "png",
            "decode speed (" + payloads.size() + " PNGs)",
            Bench.mb(javaDecode),
            Bench.mb(rustDecode),
            Report.speedup(javaDecode.mbPerSecond(), rustDecode.mbPerSecond()),
            "ImageIO.read+getRGB vs NativeImages.decodePng");

        BufferedImage benchImage = null;
        String benchImageName = "";
        for (int index = 0; index < payloads.size(); index++) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(payloads.get(index)));
                if (image != null
                    && (benchImage == null || (long) image.getWidth() * image.getHeight()
                        > (long) benchImage.getWidth() * benchImage.getHeight())) {
                    benchImage = image;
                    benchImageName = Corpus.name(repo, pngs.get(index));
                }
            } catch (IOException ignored) {
                // Already reported above; keep looking for a bench image.
            }
        }
        if (benchImage != null) {
            final BufferedImage encodeImage = benchImage;
            int width = benchImage.getWidth();
            int height = benchImage.getHeight();
            int[] argb = benchImage.getRGB(0, 0, width, height, null, 0, width);
            long inputBytes = (long) width * height * 4L;
            Bench.Result javaEncode = Bench.time(inputBytes, () -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(encodeImage, "png", out);
                    out.toByteArray();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            Bench.Result rustEncode = Bench.time(
                inputBytes,
                () -> NativeImages.encodePng(width, height, argb, 6));
            report.pass(
                "png",
                "encode speed (" + benchImageName + ", " + width + "x" + height + ")",
                Bench.mb(javaEncode),
                Bench.mb(rustEncode),
                Report.speedup(javaEncode.mbPerSecond(), rustEncode.mbPerSecond()),
                "ImageIO PNG writer vs NativeImages.encodePng, ARGB input bytes/s");
        }
    }

    private static String hex(int value) {
        return String.format(Locale.ROOT, "0x%08x", value);
    }
}
