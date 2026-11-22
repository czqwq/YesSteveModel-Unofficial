package com.fox.ysmu.nativebridge;

/**
 * Rust PNG codec (module {@code image}).
 *
 * <p>
 * Replaces the {@code javax.imageio.ImageIO} PNG decode that every model texture upload pays
 * ({@code client/texture/OuterFileTexture}, {@code ClientModelManager} on the loader thread) and the PNG
 * re-encode of {@code model/resource/ModelImageConverter}. The reference is upstream's
 * {@code natives/image/Image.java} plus {@code modules/codec/src/codec/image/png.cc}.
 *
 * <p>
 * The decoder does the whole normalisation the Java call sites used to get from {@code ImageIO.read} plus their
 * {@code TYPE_INT_ARGB} conversion: every colour type and bit depth ImageIO reads is expanded to unmultiplied
 * 8-bit ARGB, including palettes, grayscale with and without alpha, colour-key ({@code tRNS}) transparency, 16-bit
 * samples and Adam7 interlacing. PNG {@code gAMA}/{@code iCCP} chunks are ignored exactly as they were before.
 * The pixels were verified equal to the Java path on every built-in texture and on synthetic images of every
 * colour type/bit depth (see {@code rust/ysmu_native/src/image/mod.rs}).
 *
 * <p>
 * Only PNG is ported in this milestone. BMP/JPEG/WebP/AVIF keep the vendored Java decoders
 * ({@code rip/ysm/imagestream/**} and {@code ImageIO}): they are rare in shipped packs and the vendored
 * decoders are the upstream-shaped answer for those formats.
 */
public final class NativeImages {

    private static final String MODULE = "image";

    private NativeImages() {}

    /**
     * Decodes a PNG into {@code [width, height, argb...]}, row-major {@code 0xAARRGGBB}.
     *
     * @return the decoded image, or {@code null} when the native module cannot handle this input (the
     *         caller then keeps its ImageIO path, which reports malformed textures itself)
     */
    public static int[] decodePng(byte[] source) {
        if (source == null || source.length == 0 || !NativeLoader.moduleUsable(MODULE)) {
            return null;
        }
        try {
            return decodePngNative(source);
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return null;
        }
    }

    /**
     * Encodes ARGB pixels as an RGBA8 PNG.
     *
     * @param level deflate effort, 0..=9. The {@code png} crate exposes three compressor configurations rather than
     *              numeric levels, so the native side buckets it (0..=2 fastest, 3..=7 default, 8..=9 best); every
     *              bucket is lossless. The pixels also survive a decode exactly, alpha unmultiplied.
     * @return the PNG bytes, or {@code null} when the native module cannot handle this input
     */
    public static byte[] encodePng(int width, int height, int[] argb, int level) {
        if (width <= 0 || height <= 0 || argb == null || argb.length < width * height
            || !NativeLoader.moduleUsable(MODULE)) {
            return null;
        }
        try {
            return encodePngNative(width, height, argb, Math.max(0, Math.min(9, level)));
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return null;
        }
    }

    /** Native PNG decoder: {@code [width, height, argb...]} for a decodable PNG, {@code null} otherwise. */
    private static native int[] decodePngNative(byte[] source);

    /** Native PNG encoder: RGBA8 PNG bytes for {@code width*height} ARGB pixels, {@code null} on failure. */
    private static native byte[] encodePngNative(int width, int height, int[] argb, int level);
}
