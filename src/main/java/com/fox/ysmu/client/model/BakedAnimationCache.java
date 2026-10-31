package com.fox.ysmu.client.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.file.AnimationFile;

/**
 * A content-addressed disk cache for parsed animation files.
 *
 * <p>
 * The mirror image of {@link BakedGeometryCache}, for the other half of the client's load path: a model's animation set
 * is the most expensive thing left to build (one {@code Animation} per clip, with a Molang expression per keyframe
 * component), and the engine's animation graph is serialisable, so it can be written to disk and read back.
 *
 * <p>
 * <b>Measured, and therefore off by default.</b> Once the OpenYSM channel stopped re-emitting animations as JSON
 * ({@code RawAnimationBuilder} builds them from structures), building a whole model's animation set costs 6-57 ms
 * while reading the same set back costs 30-79 ms: Java serialisation of the object graph (one expression object per
 * keyframe component) is slower than parsing the expressions again. The same measurement made the geometry cache
 * default-off ({@link com.fox.ysmu.Config#ENABLE_BAKED_GEOMETRY_CACHE}). The switch exists so the mechanism is
 * complete, tested and available if a future bake becomes expensive.
 *
 * <p>
 * Key: ({@code "a" + BAKE_FORMAT + "-" + Tags.VERSION} directory, payload identity, animation key, input digest). The
 * digest is the JSON bytes' MD5 for the folder/legacy channels and {@code RawAnimationBuilder.structureDigest} for the
 * binary channel, so an identity that a pack declares wrongly can only misplace an entry, never serve another model's
 * animations for it.
 */
public final class BakedAnimationCache {

    private static final String MAGIC = "YSMANIM1";
    /** Bump when a cached animation's meaning changes without the mod version changing (engine-side edits). */
    public static final int BAKE_FORMAT = 1;
    private static final String EXTENSION = ".anim";
    private static final String BAKED_DIR = "baked_animations";
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;
    private static final long RECENT_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_KEY_NAME = 40;

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final AtomicBoolean PREPARE_QUEUED = new AtomicBoolean();

    private BakedAnimationCache() {}

    /** Whether the cache participates at all; see the class comment for why it is off. */
    public static boolean isEnabled() {
        return com.fox.ysmu.Config.ENABLE_BAKED_ANIMATION_CACHE;
    }

    /** The ABI tag naming this build's cache directory. */
    public static String abiTag() {
        return "a" + BAKE_FORMAT + "-" + com.fox.ysmu.Tags.VERSION;
    }

    /** {@code config/ysmu/cache/client/baked_animations/<ABI>} for this build. */
    public static Path root() {
        return ServerModelManager.CACHE_CLIENT.resolve(BAKED_DIR)
            .resolve(abiTag());
    }

    /** Schedules the once-per-session cleanup on the background pool; see {@link BakedGeometryCache#prepareAsync()}. */
    public static void prepareAsync() {
        if (!isEnabled() || !PREPARE_QUEUED.compareAndSet(false, true)) {
            return;
        }
        ThreadTools.THREAD_POOL.submit(BakedAnimationCache::prepare);
    }

    /** Reads one parsed animation file, or {@code null} when there is no usable entry. */
    @Nullable
    public static AnimationFile load(String identity, String animationKey, String inputDigest) {
        if (!isEnabled() || identity == null || identity.isEmpty() || animationKey == null || inputDigest == null) {
            return null;
        }
        prepareAsync();
        Path file = fileFor(identity, animationKey, inputDigest);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        LOCK.lock();
        try {
            return read(file, identity, animationKey, inputDigest);
        } finally {
            LOCK.unlock();
        }
    }

    /** Writes one parsed animation file; a failed write only means the next session parses again. */
    public static void store(String identity, String animationKey, String inputDigest, AnimationFile file) {
        if (!isEnabled() || identity == null || identity.isEmpty() || animationKey == null || inputDigest == null
            || file == null || file.animations.isEmpty()) {
            return;
        }
        prepareAsync();
        Path target = fileFor(identity, animationKey, inputDigest);
        if (Files.isRegularFile(target)) {
            return;
        }
        byte[] payload = serialize(file);
        if (payload == null) {
            return;
        }
        LOCK.lock();
        try {
            Path directory = target.getParent();
            Files.createDirectories(directory);
            Path temporary = directory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                writeFile(temporary, identity, animationKey, inputDigest, payload);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Leftover temp files are pruned by the next prepare().
                }
            }
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not write the animation cache file {}", target, e);
        } finally {
            LOCK.unlock();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // File format
    // ---------------------------------------------------------------------------------------------------------

    private static Path fileFor(String identity, String animationKey, String inputDigest) {
        String directory = Md5Utils.md5Hex(identity.getBytes(StandardCharsets.UTF_8));
        return root().resolve(directory)
            .resolve(keyFileName(animationKey) + "-" + inputDigest + EXTENSION);
    }

    private static String keyFileName(String animationKey) {
        StringBuilder name = new StringBuilder(Math.min(animationKey.length(), MAX_KEY_NAME));
        for (int index = 0; index < animationKey.length() && name.length() < MAX_KEY_NAME; index++) {
            char character = Character.toLowerCase(animationKey.charAt(index));
            name.append((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-' ? character : '_');
        }
        String sanitized = name.toString();
        if (sanitized.equals(animationKey)) {
            return sanitized;
        }
        return sanitized + "-" + Md5Utils.md5Hex(animationKey.getBytes(StandardCharsets.UTF_8))
            .substring(0, 8);
    }

    private static byte[] serialize(AnimationFile file) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(file);
        } catch (IOException e) {
            // An animation this build cannot serialise (an expression wrapper holding live parser state) simply is
            // not cached; the build result is already in memory.
            ysmu.LOG.warn("YSM client could not serialise an animation set for the cache: {}", e.toString());
            return null;
        }
        return buffer.toByteArray();
    }

    private static void writeFile(Path target, String identity, String animationKey, String inputDigest,
        byte[] payload) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
            Files.newOutputStream(target))) {
            out.writeUTF(MAGIC);
            out.writeInt(BAKE_FORMAT);
            out.writeUTF(abiTag());
            out.writeUTF(identity);
            out.writeUTF(animationKey);
            out.writeUTF(inputDigest);
            out.writeInt(payload.length);
            out.write(payload);
            out.write(Md5Utils.md5(payload));
            out.flush();
        }
    }

    @Nullable
    private static AnimationFile read(Path file, String identity, String animationKey, String inputDigest) {
        try (DataInputStream in = new DataInputStream(
            Files.newInputStream(file))) {
            if (!MAGIC.equals(in.readUTF())) {
                return discard(file, "bad magic");
            }
            if (in.readInt() != BAKE_FORMAT) {
                return discard(file, "stale format");
            }
            if (!abiTag().equals(in.readUTF())) {
                return discard(file, "stale ABI");
            }
            if (!identity.equals(in.readUTF())) {
                return discard(file, "identity mismatch");
            }
            if (!animationKey.equals(in.readUTF())) {
                return discard(file, "animation key mismatch");
            }
            if (!inputDigest.equals(in.readUTF())) {
                return discard(file, "input mismatch");
            }
            int length = in.readInt();
            if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
                return discard(file, "implausible payload length " + length);
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            byte[] expected = new byte[16];
            in.readFully(expected);
            if (!java.security.MessageDigest.isEqual(expected, Md5Utils.md5(payload))) {
                return discard(file, "payload checksum mismatch");
            }
            Object value = readPayload(payload);
            if (!(value instanceof AnimationFile)) {
                return discard(file, "payload is not an AnimationFile");
            }
            return (AnimationFile) value;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return discard(file, e.getClass()
                .getSimpleName());
        }
    }

    @Nullable
    private static AnimationFile discard(Path file, String reason) {
        ysmu.LOG.warn("YSM client discarding animation cache entry {}: {}", file, reason);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not delete the unreadable animation cache file {}", file, e);
        }
        return null;
    }

    private static Object readPayload(byte[] payload) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new CacheObjectInputStream(new ByteArrayInputStream(payload))) {
            return in.readObject();
        }
    }

    /** The object stream a parsed animation may reference, and nothing else; see BakedGeometryCache's twin. */
    private static final class CacheObjectInputStream extends ObjectInputStream {

        private CacheObjectInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
            String name = descriptor.getName();
            if (isAllowed(name)) {
                return super.resolveClass(descriptor);
            }
            throw new InvalidClassException(name, "is not allowed in an animation cache");
        }

        private static boolean isAllowed(String name) {
            int index = 0;
            while (index < name.length() && name.charAt(index) == '[') {
                index++;
            }
            String className = name.substring(index);
            if (className.startsWith("L") && className.endsWith(";")) {
                className = className.substring(1, className.length() - 1);
            }
            if (className.length() == 1) {
                return true;
            }
            return className.startsWith("software.bernie.geckolib3.")
                || className.startsWith("com.eliotlash.mclib.")
                || className.startsWith("it.unimi.dsi.fastutil.")
                || className.startsWith("java.lang.")
                || className.startsWith("java.util.");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------------------------------------------

    private static void prepare() {
        Path parent = ServerModelManager.CACHE_CLIENT.resolve(BAKED_DIR);
        if (!Files.isDirectory(parent)) {
            return;
        }
        LOCK.lock();
        try {
            List<Path> stale = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child) && !abiTag().equals(
                        child.getFileName()
                            .toString())) {
                        stale.add(child);
                    }
                }
            } catch (IOException e) {
                ysmu.LOG.warn("YSM client could not list the animation cache directories", e);
            }
            for (Path directory : stale) {
                deleteRecursively(directory);
            }
            enforceBudget();
        } finally {
            LOCK.unlock();
        }
    }

    private static void enforceBudget() {
        Path current = root();
        if (!Files.isDirectory(current)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        long total = 0L;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(current)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                    for (Path entry : entries) {
                        if (!Files.isRegularFile(entry)) {
                            continue;
                        }
                        total += Files.size(entry);
                        files.add(entry);
                    }
                }
            }
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not measure the animation cache", e);
            return;
        }
        if (total <= DEFAULT_MAX_BYTES) {
            return;
        }
        files.sort(Comparator.comparingLong(BakedAnimationCache::lastModified));
        long now = System.currentTimeMillis();
        for (Path file : files) {
            if (total <= DEFAULT_MAX_BYTES) {
                break;
            }
            if (now - lastModified(file) < RECENT_MILLIS) {
                continue;
            }
            try {
                long size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
            } catch (IOException e) {
                ysmu.LOG.warn("YSM client could not evict the animation cache file {}", file, e);
            }
        }
        ysmu.LOG.info("YSM client trimmed the animation cache to {} bytes (budget {})", total, DEFAULT_MAX_BYTES);
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file)
                .toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteRecursively(Path directory) {
        try {
            if (!Files.isDirectory(directory)) {
                Files.deleteIfExists(directory);
                return;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    deleteRecursively(entry);
                }
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not delete the stale animation cache directory {}", directory, e);
        }
    }
}
