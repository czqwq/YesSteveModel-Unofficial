package parity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Locates and loads the payload corpora the modules compare against.
 *
 * <p>
 * The built-in model set under {@code src/main/resources/assets/ysmu/builtin} is the same data the
 * running client ships (17 packs, 330 JSON + 154 PNG), which makes it the honest corpus for every
 * module: geometry JSON for the bake, PNG for the image codec, both for the zstd cross-codec check.
 * The corpus is read straight from the resource tree rather than from the built jar so a model edit is
 * visible to the next harness run without a build.
 *
 * <p>
 * Synthetic payloads cover the cases the shipped corpus does not: a very compressible JSON-shaped blob
 * and a pseudo-random (incompressible) blob, so the codec numbers are not only "this particular PNG".
 */
public final class Corpus {

    /** Source tree of the built-in packs. */
    public static final Path BUILTIN = Path.of("src/main/resources/assets/ysmu/builtin");

    private Corpus() {}

    /** All files under the built-in resource tree, sorted by path for reproducible runs. */
    public static List<Path> allBuiltinFiles(Path repo) throws IOException {
        Path root = repo.resolve(BUILTIN);
        if (!Files.isDirectory(root)) {
            throw new IOException("built-in model tree not found: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::toString))
                .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * Every built-in JSON that is a geometry document (contains a {@code "minecraft:geometry"} key).
     *
     * <p>
     * A path-based filter on {@code models/} would miss geometry stored elsewhere; the M3 owner's Rust
     * side scans by content and reports 86 built-in geometry documents, so the harness uses the same
     * rule and both sides compare the same corpus.
     */
    public static List<Path> geometryFiles(Path repo) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path file : allBuiltinFiles(repo)) {
            if (!file.getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT)
                .endsWith(".json")) {
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (containsAscii(bytes, "\"minecraft:geometry\"")) {
                result.add(file);
            }
        }
        return result;
    }

    /** Cheap byte-level substring search (avoids decoding every animation JSON into a String). */
    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int start = 0; start <= haystack.length - pattern.length; start++) {
            for (int offset = 0; offset < pattern.length; offset++) {
                if (haystack[start + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Every {@code *.png} of the built-in packs. */
    public static List<Path> pngFiles(Path repo) throws IOException {
        List<Path> result = new ArrayList<>();
        for (Path file : allBuiltinFiles(repo)) {
            if (file.getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT)
                .endsWith(".png")) {
                result.add(file);
            }
        }
        return result;
    }

    /** The largest file matching a suffix (and optional path fragment) under the built-in tree. */
    public static Path largest(Path repo, String suffix, String pathFragment) throws IOException {
        Path best = null;
        long bestSize = -1L;
        for (Path file : allBuiltinFiles(repo)) {
            String name = file.getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT);
            if (!name.endsWith(suffix)) {
                continue;
            }
            String full = file.toString()
                .replace('\\', '/');
            if (pathFragment != null && !full.contains(pathFragment)) {
                continue;
            }
            long size = Files.size(file);
            if (size > bestSize) {
                bestSize = size;
                best = file;
            }
        }
        return best;
    }

    /** Reads a list of files, skipping anything that would exceed {@code maxTotalBytes}. */
    public static List<byte[]> readCapped(List<Path> files, long maxTotalBytes) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        long total = 0L;
        for (Path file : files) {
            long size = Files.size(file);
            if (size > 0L && total + size > maxTotalBytes) {
                continue;
            }
            payloads.add(Files.readAllBytes(file));
            total += size;
        }
        return payloads;
    }

    /**
     * Corpus for the codec cross-check and benchmark, in priority order: geometry JSON first (real,
     * compressible), then PNG (real, mostly incompressible), then the remaining built-in JSON, with two
     * synthetic payloads appended so the set is never corpus-dependent only.
     */
    public static List<byte[]> payloads(Path repo, long maxTotalBytes) throws IOException {
        List<Path> files = new ArrayList<>();
        files.addAll(geometryFiles(repo));
        files.addAll(pngFiles(repo));
        for (Path file : allBuiltinFiles(repo)) {
            if (files.contains(file)) {
                continue;
            }
            String name = file.getFileName()
                .toString()
                .toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".json") || name.endsWith(".png")) {
                files.add(file);
            }
        }
        List<byte[]> payloads = readCapped(files, maxTotalBytes - jsonLike(256 * 1024).length - noise(64 * 1024).length);
        payloads.add(jsonLike(256 * 1024));
        payloads.add(noise(64 * 1024));
        return payloads;
    }

    /**
     * Small, representative payload set for the slower {@code YsmZstd} protocol round-trip (each case
     * pays a Java zstd compress because the wire-compat check forces the Java path too).
     */
    public static List<byte[]> protocolPayloads(Path repo) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        addIfPresent(payloads, largest(repo, ".json", "/models/"), 0L);
        addIfPresent(payloads, largest(repo, ".json", "/animations/"), 0L);
        addIfPresent(payloads, largest(repo, ".png", null), 0L);
        payloads.add(jsonLike(512 * 1024));
        payloads.add(noise(64 * 1024));
        // A tiny payload as well: the wire transformation walks the block headers, which is most
        // fragile for one-block frames.
        payloads.add("{\"format_version\":\"1.12.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return payloads;
    }

    private static void addIfPresent(List<byte[]> payloads, Path file, long ignored) throws IOException {
        if (file != null) {
            payloads.add(Files.readAllBytes(file));
        }
    }

    /** Deterministic JSON-shaped payload: very compressible, like a model or animation file. */
    public static byte[] jsonLike(int targetBytes) {
        StringBuilder builder = new StringBuilder(targetBytes + 1024);
        builder.append("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"bones\":[");
        int index = 0;
        while (builder.length() < targetBytes) {
            builder.append("{\"name\":\"bone_")
                .append(index)
                .append("\",\"parent\":\"bone_")
                .append(index == 0 ? "root" : index - 1)
                .append("\",\"pivot\":[")
                .append(index % 16)
                .append(".5,0.0,1.0],\"rotation\":[0.0,1.0,2.0],\"cubes\":[{\"origin\":[-1.0,0.0,-1.0],")
                .append("\"size\":[2.0,12.0,2.0],\"uv\":[0,0]}]},");
            index++;
            if (index > 4096) {
                break;
            }
        }
        builder.append("]}]}");
        byte[] bytes = builder.toString()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] trimmed = new byte[targetBytes];
        System.arraycopy(bytes, 0, trimmed, 0, Math.min(bytes.length, targetBytes));
        return trimmed;
    }

    /** Deterministic pseudo-random payload: incompressible, worst case for the codec. */
    public static byte[] noise(int size) {
        byte[] bytes = new byte[size];
        new Random(0x594D554C).nextBytes(bytes);
        return bytes;
    }

    public static String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    /** Short display name for a corpus file: {@code pack/relative/path}. */
    public static String name(Path repo, Path file) {
        Path root = repo.resolve(BUILTIN);
        try {
            return root.relativize(file)
                .toString()
                .replace('\\', '/');
        } catch (Exception ignored) {
            return file.getFileName()
                .toString();
        }
    }
}
