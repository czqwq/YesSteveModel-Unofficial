package com.fox.ysmu.nativebridge;

/**
 * Rust zstd codec (module {@code codec}).
 *
 * <p>
 * Replaces the two hot calls of the pure-Java zstd port ({@code rip/ysm/zstd/ZstdUtil}, used by
 * {@code rip/ysm/algorithms/YsmZstd}) with the zstd crate's C implementation. Only <b>standard zstd
 * frames</b> are handled here: the YSM protocol's own frame transformation
 * ({@code YsmZstd.wash}/{@code obfuscate}) stays in Java, because that encoding is what other YSM
 * implementations speak and it is already the tested path. The reference is upstream's
 * {@code natives/Zstd.java} (a JNI wrapper around {@code modules/codec/src/codec/zstd.cc}).
 *
 * <p>
 * Every method returns {@code null} when the native implementation is unavailable <b>or</b> declines the
 * input (malformed frame, output above the caller's bound). Callers must then use the Java path. This
 * "decline" style is deliberate: bad network data must not disable the module, unlike a native failure,
 * which {@link NativeLoader#onModuleFailure} handles by turning this module off for the session.
 */
public final class NativeCodecs {

    private static final String MODULE = "codec";

    private NativeCodecs() {}

    /**
     * Decompresses one standard zstd frame.
     *
     * @param source         the frame bytes
     * @param maxOutputBytes hard upper bound on the decompressed size; a frame that would exceed it is
     *                       declined instead of allocated
     * @return the decompressed bytes, or {@code null} when the native module cannot handle this input
     */
    public static byte[] zstdDecompress(byte[] source, int maxOutputBytes) {
        if (source == null || source.length == 0 || maxOutputBytes <= 0 || !NativeLoader.moduleUsable(MODULE)) {
            return null;
        }
        try {
            return zstdDecompressNative(source, maxOutputBytes);
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return null;
        }
    }

    /**
     * Compresses into one standard zstd frame.
     *
     * @param level zstd level; clamped to 1..=19 by the native side (the Java callers pass 3)
     * @return the frame, or {@code null} when the native module cannot handle this input
     */
    public static byte[] zstdCompress(byte[] source, int level) {
        if (source == null || source.length == 0 || !NativeLoader.moduleUsable(MODULE)) {
            return null;
        }
        try {
            return zstdCompressNative(source, level);
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return null;
        }
    }

    private static native byte[] zstdDecompressNative(byte[] source, int maxOutputBytes);

    private static native byte[] zstdCompressNative(byte[] source, int level);
}
