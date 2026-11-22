package parity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fox.ysmu.nativebridge.NativeCodecs;

import rip.ysm.algorithms.YsmZstd;
import rip.ysm.zstd.ZstdUtil;

/**
 * M1 — zstd parity and benchmark.
 *
 * <p>
 * The proof the ExecPlan asks for is wire compatibility, so the checks are both directions:
 * <ul>
 *   <li>a frame produced by the Rust codec must be decoded by the vendored pure-Java zstd
 *       ({@code rip/ysm/zstd/ZstdUtil}) back to the original bytes;</li>
 *   <li>a frame produced by the vendored Java zstd must be decoded by the Rust codec back to the
 *       original bytes;</li>
 *   <li>the same two directions through {@code rip/ysm/algorithms/YsmZstd}, i.e. after the YSM frame
 *       obfuscation/washing step that defines the on-wire format. Because {@link YsmZstd#compress}
 *       picks the native codec when it is available and the Java one otherwise, the harness flips
 *       {@code Config.ENABLE_RUST_NATIVE} to force each side and proves the two wire outputs are
 *       interchangeable.</li>
 * </ul>
 * The benchmark compares {@code ZstdUtil.compress}/{@code decompress} with
 * {@code NativeCodecs.zstdCompress}/{@code zstdDecompress} on the same payloads.
 */
public final class ParityZstd {

    private static final int LEVEL = 3;
    /** Cross-codec corpus cap: enough real payloads to catch block/format bugs without a slow run. */
    private static final long CROSS_CORPUS_LIMIT = 24L * 1024 * 1024;

    private ParityZstd() {}

    public static void run(Path repo, Report report) {
        if (!NativeBootstrap.available()) {
            report.pending("zstd", "cross-codec parity", "native layer unavailable: " + NativeBootstrap.status());
            return;
        }

        List<byte[]> corpus;
        try {
            corpus = Corpus.payloads(repo, CROSS_CORPUS_LIMIT);
        } catch (Exception failure) {
            report.fail("zstd", "corpus", "", "", "", "could not read the payload corpus: " + failure);
            return;
        }
        if (corpus.isEmpty()) {
            report.fail("zstd", "corpus", "", "", "", "payload corpus is empty");
            return;
        }

        // ── 1. Sanity + cross-codec, both directions, byte-exact ────────────────────────────────────
        //
        // Direction 1 is straightforward: the Rust codec emits standard zstd frames and the vendored
        // Java decompressor reads standard frames.
        //
        // Direction 2 has one protocol bit to normalise, and that is not a test workaround: the vendored
        // Java compressor sets the frame-content-checksum flag in the frame header but never appends the
        // four checksum bytes (`writeChecksum` is commented out in `ZstdUtil.compress`), so its raw output
        // is intentionally not a valid standard zstd frame. `YsmZstd.wash` clears exactly that bit
        // (`fhd & 0xFB`) before handing the bytes to a standard decoder. The harness repeats that one bit
        // of the transformation (see {@link #clearYsmChecksumFlag}) and then proves the native decoder
        // recovers the original bytes; the full wire path (obfuscate + wash through `YsmZstd`) is asserted
        // separately below.
        int checked = 0;
        long corpusBytes = 0L;
        String failure = null;
        for (byte[] payload : corpus) {
            byte[] rustFrame = NativeCodecs.zstdCompress(payload, LEVEL);
            if (rustFrame == null) {
                failure = "NativeCodecs.zstdCompress returned null for payload #" + checked + " ("
                    + Corpus.humanBytes(payload.length) + ")";
                break;
            }
            try {
                byte[] javaDecoded = ZstdUtil.decompress(rustFrame);
                if (!Arrays.equals(javaDecoded, payload)) {
                    failure = "Java ZstdUtil decoded the native standard frame to different bytes (payload #" + checked
                        + ", " + Corpus.humanBytes(payload.length) + ", first difference at "
                        + firstDifference(javaDecoded, payload).describe() + ")";
                    break;
                }
            } catch (Throwable decodeFailure) {
                failure = "Java ZstdUtil could not decode the native standard frame (payload #" + checked + "): "
                    + decodeFailure;
                break;
            }

            byte[] javaFrame = ZstdUtil.compress(payload, LEVEL);
            byte[] normalizedFrame = clearYsmChecksumFlag(javaFrame);
            byte[] rustDecoded = NativeCodecs.zstdDecompress(normalizedFrame, payload.length);
            if (rustDecoded == null) {
                failure = "NativeCodecs.zstdDecompress declined the vendored Java frame after clearing the declared"
                    + " (but absent) checksum flag - payload #" + checked + ", " + Corpus.humanBytes(payload.length);
                break;
            }
            if (!Arrays.equals(rustDecoded, payload)) {
                failure = "native zstdDecompress decoded the vendored Java frame to different bytes (payload #" + checked
                    + ", " + Corpus.humanBytes(payload.length) + ", first difference at "
                    + firstDifference(rustDecoded, payload).describe() + ")";
                break;
            }
            checked++;
            corpusBytes += payload.length;
        }
        if (failure == null) {
            report.pass(
                "zstd",
                "cross-codec (after protocol normalisation)",
                checked + " payloads",
                Corpus.humanBytes(corpusBytes),
                "",
                "native standard frame -> Java ZstdUtil.decompress == original; java ZstdUtil frame + wash's"
                    + " fhd&0xFB bit-clear -> native decode == original; the raw Java frame is not a conforming"
                    + " standard frame and is never fed to the native decoder in production because YsmZstd always"
                    + " washes first");
        } else {
            report.fail("zstd", "cross-codec (after protocol normalisation)", "", "", "", failure);
        }

        // ── 2. Bounded-output contract: a frame larger than the caller's bound must be declined ─────
        try {
            byte[] sample = corpus.get(0);
            byte[] frame = NativeCodecs.zstdCompress(sample, LEVEL);
            byte[] declined = NativeCodecs.zstdDecompress(frame, sample.length - 1);
            if (declined == null) {
                report.pass(
                    "zstd",
                    "bounded output declines oversized frame",
                    "limit " + (sample.length - 1),
                    "null",
                    "",
                    "");
            } else {
                report.fail(
                    "zstd",
                    "bounded output declines oversized frame",
                    "limit " + (sample.length - 1),
                    declined.length + " bytes",
                    "",
                    "zstdDecompress returned output larger than the caller's bound");
            }
        } catch (Throwable thrown) {
            report.fail("zstd", "bounded output declines oversized frame", "", "", "", String.valueOf(thrown));
        }

        // ── 3. YsmZstd protocol wire compatibility, both directions ────────────────────────────────
        protocolRoundTrip(repo, report);

        // ── 4. Fallback: with the config flag off every facade must decline ────────────────────────
        fallback(report, corpus.get(0));

        // ── 5. Benchmarks ──────────────────────────────────────────────────────────────────────────
        benchmark(repo, report);
    }

    private static void protocolRoundTrip(Path repo, Report report) {
        List<byte[]> payloads;
        try {
            payloads = Corpus.protocolPayloads(repo);
        } catch (Exception failure) {
            report.fail("zstd", "YsmZstd protocol wire-compat", "", "", "", "corpus: " + failure);
            return;
        }
        int cases = 0;
        long bytes = 0L;
        String failure = null;
        boolean wasEnabled = NativeBootstrap.setRustEnabled(true);
        try {
            for (byte[] payload : payloads) {
                // native compress -> Java decompress
                NativeBootstrap.setRustEnabled(true);
                byte[] nativeWire = YsmZstd.compress(payload);
                NativeBootstrap.setRustEnabled(false);
                byte[] javaDecoded = YsmZstd.decompress(nativeWire);
                if (!Arrays.equals(javaDecoded, payload)) {
                    failure = "Java YsmZstd decoded the native wire frame to different bytes ("
                        + Corpus.humanBytes(payload.length) + ")";
                    break;
                }

                // Java compress -> native decompress
                byte[] javaWire = YsmZstd.compress(payload);
                NativeBootstrap.setRustEnabled(true);
                byte[] nativeDecoded = YsmZstd.decompress(javaWire);
                if (!Arrays.equals(nativeDecoded, payload)) {
                    failure = "native YsmZstd decoded the Java wire frame to different bytes ("
                        + Corpus.humanBytes(payload.length) + ")";
                    break;
                }
                cases++;
                bytes += payload.length;
            }
        } catch (Throwable thrown) {
            failure = "YsmZstd round-trip threw: " + thrown;
        } finally {
            NativeBootstrap.setRustEnabled(wasEnabled);
        }
        if (failure == null) {
            report.pass(
                "zstd",
                "YsmZstd protocol wire-compat",
                cases + " payloads",
                Corpus.humanBytes(bytes),
                "",
                "native wire frames decode on the Java path and Java wire frames decode on the native path"
                    + " (both implementations forced; production always goes through wash first)");
        } else {
            report.fail("zstd", "YsmZstd protocol wire-compat", "", "", "", failure);
        }
    }

    private static void fallback(Report report, byte[] payload) {
        byte[] javaFrame = ZstdUtil.compress(payload, LEVEL);
        boolean wasEnabled = NativeBootstrap.setRustEnabled(false);
        try {
            boolean compressDeclined = NativeCodecs.zstdCompress(payload, LEVEL) == null;
            boolean decompressDeclined = NativeCodecs.zstdDecompress(javaFrame, payload.length) == null;
            if (compressDeclined && decompressDeclined) {
                report.pass(
                    "zstd",
                    "falls back when EnableRustNative=false",
                    "null",
                    "null",
                    "",
                    "every facade declines and YsmZstd takes the vendored Java implementation");
            } else {
                report.fail(
                    "zstd",
                    "falls back when EnableRustNative=false",
                    String.valueOf(compressDeclined),
                    String.valueOf(decompressDeclined),
                    "",
                    "a facade served the native codec while Config.ENABLE_RUST_NATIVE was false");
            }
        } finally {
            NativeBootstrap.setRustEnabled(wasEnabled);
        }
    }

    private static void benchmark(Path repo, Report report) {
        List<NamedPayload> payloads = new ArrayList<>();
        try {
            add(repo, payloads, "largest geometry JSON", Corpus.largest(repo, ".json", "/models/"));
            add(repo, payloads, "largest animation JSON", Corpus.largest(repo, ".json", "/animations/"));
            add(repo, payloads, "largest PNG", Corpus.largest(repo, ".png", null));
        } catch (Exception failure) {
            report.fail("zstd", "benchmark corpus", "", "", "", String.valueOf(failure));
            return;
        }
        payloads.add(new NamedPayload("synthetic JSON-like 2 MiB", Corpus.jsonLike(2 * 1024 * 1024)));
        payloads.add(new NamedPayload("synthetic noise 256 KiB", Corpus.noise(256 * 1024)));

        long javaCompressNanos = 0L;
        long rustCompressNanos = 0L;
        long javaCompressBytes = 0L;
        long rustCompressBytes = 0L;
        long javaDecompressNanos = 0L;
        long rustDecompressNanos = 0L;
        long javaDecompressBytes = 0L;
        long rustDecompressBytes = 0L;
        long totalBytes = 0L;
        int measured = 0;

        for (NamedPayload named : payloads) {
            byte[] payload = named.bytes;
            byte[] javaFrame = clearYsmChecksumFlag(ZstdUtil.compress(payload, LEVEL));
            byte[] rustFrame = NativeCodecs.zstdCompress(payload, LEVEL);
            if (rustFrame == null) {
                report.fail("zstd", "benchmark " + named.name, "", "", "", "zstdCompress returned null");
                return;
            }
            // Correctness before timing: the Java decoder must accept the Rust frame and the native
            // decoder must accept the washed Java wire frame.
            try {
                if (!Arrays.equals(ZstdUtil.decompress(rustFrame), payload)
                    || !Arrays.equals(NativeCodecs.zstdDecompress(javaFrame, payload.length), payload)) {
                    report.fail("zstd", "benchmark " + named.name, "", "", "", "cross-decode mismatch before timing");
                    return;
                }
            } catch (Throwable thrown) {
                report.fail("zstd", "benchmark " + named.name, "", "", "", "cross-decode threw: " + thrown);
                return;
            }

            Bench.Result javaCompress = Bench.time(
                payload.length,
                () -> ZstdUtil.compress(payload, LEVEL));
            Bench.Result rustCompress = Bench.time(
                payload.length,
                () -> NativeCodecs.zstdCompress(payload, LEVEL));
            Bench.Result javaDecompress = Bench.time(
                payload.length,
                () -> ZstdUtil.decompress(rustFrame));
            Bench.Result rustDecompress = Bench.time(
                payload.length,
                () -> NativeCodecs.zstdDecompress(javaFrame, payload.length));

            report.pass(
                "zstd",
                "compress " + named.name,
                Bench.mb(javaCompress),
                Bench.mb(rustCompress),
                Report.speedup(javaCompress.mbPerSecond(), rustCompress.mbPerSecond()),
                Corpus.humanBytes(payload.length) + " payload");
            report.pass(
                "zstd",
                "decompress " + named.name,
                Bench.mb(javaDecompress),
                Bench.mb(rustDecompress),
                Report.speedup(javaDecompress.mbPerSecond(), rustDecompress.mbPerSecond()),
                Corpus.humanBytes(payload.length) + " payload");

            javaCompressNanos += javaCompress.nanos;
            rustCompressNanos += rustCompress.nanos;
            javaCompressBytes += javaCompress.totalBytes();
            rustCompressBytes += rustCompress.totalBytes();
            javaDecompressNanos += javaDecompress.nanos;
            rustDecompressNanos += rustDecompress.nanos;
            javaDecompressBytes += javaDecompress.totalBytes();
            rustDecompressBytes += rustDecompress.totalBytes();
            totalBytes += payload.length;
            measured++;
        }

        if (measured > 0) {
            double javaCompressMBps = throughput(javaCompressBytes, javaCompressNanos);
            double rustCompressMBps = throughput(rustCompressBytes, rustCompressNanos);
            double javaDecompressMBps = throughput(javaDecompressBytes, javaDecompressNanos);
            double rustDecompressMBps = throughput(rustDecompressBytes, rustDecompressNanos);
            report.pass(
                "zstd",
                "compress aggregate",
                String.format(java.util.Locale.ROOT, "%.1f MiB/s", javaCompressMBps),
                String.format(java.util.Locale.ROOT, "%.1f MiB/s", rustCompressMBps),
                Report.speedup(javaCompressMBps, rustCompressMBps),
                measured + " payloads, " + Corpus.humanBytes(totalBytes));
            report.pass(
                "zstd",
                "decompress aggregate",
                String.format(java.util.Locale.ROOT, "%.1f MiB/s", javaDecompressMBps),
                String.format(java.util.Locale.ROOT, "%.1f MiB/s", rustDecompressMBps),
                Report.speedup(javaDecompressMBps, rustDecompressMBps),
                measured + " payloads, " + Corpus.humanBytes(totalBytes));
        }
    }

    private static double throughput(long bytes, long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        return seconds <= 0.0 ? 0.0 : bytes / seconds / (1024.0 * 1024.0);
    }

    /**
     * Clears the content-checksum flag in the frame header of a vendored-Java-compressed frame.
     *
     * <p>
     * {@code ZstdFrameCompressor.writeFrameHeader} always sets the checksum flag while
     * {@code ZstdUtil.compress} never appends the checksum bytes (the call is commented out), so the raw
     * Java frame claims a checksum it does not carry and a conforming zstd decoder is right to reject it.
     * {@code YsmZstd.wash} clears exactly this bit ({@code fhd & 0xFB}) before decoding. The harness does
     * the same single-bit normalisation here so the raw-codec cross-check is about codecs and not about
     * the protocol's frame shape; the complete obfuscate/wash round-trip is asserted separately.
     */
    private static byte[] clearYsmChecksumFlag(byte[] frame) {
        if (frame == null || frame.length < 5) {
            return frame;
        }
        byte[] normalised = frame.clone();
        normalised[4] = (byte) (normalised[4] & 0xFB);
        return normalised;
    }

    private static void add(Path repo, List<NamedPayload> payloads, String label, Path file) throws Exception {
        if (file != null) {
            payloads.add(new NamedPayload(label + " (" + Corpus.name(repo, file) + ")", java.nio.file.Files.readAllBytes(file)));
        }
    }

    private static Difference firstDifference(byte[] actual, byte[] expected) {
        int limit = Math.min(actual.length, expected.length);
        for (int i = 0; i < limit; i++) {
            if (actual[i] != expected[i]) {
                return new Difference(i, actual[i], expected[i]);
            }
        }
        return new Difference(limit, (byte) 0, (byte) 0);
    }

    private static final class Difference {

        final int index;
        final byte actual;
        final byte expected;

        Difference(int index, byte actual, byte expected) {
            this.index = index;
            this.actual = actual;
            this.expected = expected;
        }

        String describe() {
            return "byte " + index + ": 0x" + String.format("%02x", actual) + " vs 0x" + String.format("%02x", expected);
        }
    }

    private static final class NamedPayload {

        final String name;
        final byte[] bytes;

        NamedPayload(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }
}
