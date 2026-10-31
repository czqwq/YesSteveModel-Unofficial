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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.raw.pojo.Bone;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.MinecraftGeometry;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;
import software.bernie.geckolib3.geo.raw.pojo.PolyMesh;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * A content-addressed disk cache for baked geometry.
 *
 * <p>
 * Building one sub-model means parsing its geometry and running the engine's {@code GeoBuilder} over it, which is the
 * expensive half of the client's model pipeline for large packs. The result is a plain object graph
 * ({@code GeoModel} -> bones -> cubes -> quads -> vertices, plus the model's {@code ModelProperties}), and the engine's
 * classes are {@link java.io.Serializable}, so it can be written to disk and read back instead of rebuilt.
 *
 * <p>
 * <b>Key.</b> An entry is addressed by four things: the cache ABI tag (directory), the payload identity, the sub-model
 * key, and a digest of the exact geometry input the bake consumed. The ABI tag is
 * {@code "v" + BAKE_FORMAT + "-" + Tags.VERSION}, so any mod update - and any manual bump of {@link #BAKE_FORMAT} for
 * an engine-side change - invalidates every entry without inspecting a single file. The Java serialisation stream
 * cannot do that job: every engine class pins {@code serialVersionUID = 42L}, so an added or reordered field would be
 * read back silently instead of rejected.
 *
 * <p>
 * The payload identity alone is not enough to identify the bake input: on the OpenYSM channel it is derived from the
 * hash the pack declares about itself, which a pack can get wrong (or lie about). The per-sub-model input digest is
 * computed from the exact bytes (folder/legacy channel) or from the exact POJO (binary channel) that
 * {@code GeoBuilder} is about to consume, so a wrong identity can at worst put an entry in the right directory under
 * a different name - never serve one model's geometry for another.
 *
 * <p>
 * <b>Layout.</b> {@code config/ysmu/cache/client/baked/<ABI>/<md5(identity)>/<subModelKey>-<inputDigest>.geo}. Under
 * the client cache root so it is deleted together with the rest of the client cache, and named so it cannot be
 * mistaken for an encrypted payload: {@link ServerModelManager} walks that root recursively and treats 32-hex file
 * names as payload MD5s.
 *
 * <p>
 * <b>Lifecycle.</b> Entries are written by the model loader thread right after a successful build and read before the
 * build. Nothing here is cleared by a disconnect, a server switch or {@code /ysm reload}: those drop in-memory state,
 * and the disk entries are keyed by content, so the next server's copy of the same model is exactly what should hit.
 * {@link #prepareAsync()} runs once per session on a background thread and deletes every other ABI directory, then
 * trims the current one to a size budget by modification time.
 *
 * <p>
 * This is a port-specific mechanism: the reference bakes into a native payload
 * ({@code BakedModelCache} / {@code AtomicSharedCache} in {@code tmp/YesSteveModel-dev-1.20}), which 1.7.10 cannot
 * load. Its shape is nevertheless the same - ABI-tagged directory, content-addressed file, self-describing header,
 * atomic write - see {@code .agent/plan-bake-cache-and-structured-bridge.md}.
 */
public final class BakedGeometryCache {

    /** File magic; also the first thing a read validates. */
    private static final String MAGIC = "YSMBAKE1";
    /** Bump when the meaning of a cached entry changes without the mod version changing (engine-side edits). */
    public static final int BAKE_FORMAT = 1;
    private static final String EXTENSION = ".geo";
    private static final String BAKED_DIR = "baked";
    /** Refuse to allocate a payload larger than this when reading a header; a build that big is not a model. */
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    /** Budget for one ABI directory; the reference has none, this port bounds the disk it may use. */
    private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;
    /** Files written this recently are never evicted, so a session cannot fight its own writes. */
    private static final long RECENT_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_SUB_MODEL_NAME = 40;

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final AtomicBoolean PREPARE_QUEUED = new AtomicBoolean();

    private BakedGeometryCache() {}

    /**
     * Whether the cache participates at all.
     * <p>
     * Off by default: measured, reading a baked geometry back through Java serialisation is slower than letting
     * {@code GeoBuilder} run again (see {@link com.fox.ysmu.Config#ENABLE_BAKED_GEOMETRY_CACHE}). Everything below is
     * a no-op while it is off, so no file is ever written or looked up.
     */
    public static boolean isEnabled() {
        return com.fox.ysmu.Config.ENABLE_BAKED_GEOMETRY_CACHE;
    }

    /** The ABI tag naming this build's cache directory. */
    public static String abiTag() {
        return "v" + BAKE_FORMAT + "-" + com.fox.ysmu.Tags.VERSION;
    }

    /** {@code config/ysmu/cache/client/baked/<ABI>} for this build. */
    public static Path root() {
        return ServerModelManager.CACHE_CLIENT.resolve(BAKED_DIR)
            .resolve(abiTag());
    }

    /**
     * Schedules the once-per-session cleanup (other ABI directories, then the size budget) on the background pool.
     * <p>
     * Deliberately not on the client thread: it walks the cache directory and may delete a few hundred files.
     */
    public static void prepareAsync() {
        if (!isEnabled() || !PREPARE_QUEUED.compareAndSet(false, true)) {
            return;
        }
        ThreadTools.THREAD_POOL.submit(BakedGeometryCache::prepare);
    }

    /**
     * Reads one baked geometry, or {@code null} when there is no usable entry.
     * <p>
     * Every check that fails deletes the file and reports once: a corrupt, truncated or stale entry is worthless and
     * would otherwise be re-read on every session.
     */
    @Nullable
    public static GeoModel load(String identity, String subModelKey, String inputDigest) {
        if (!isEnabled() || identity == null || identity.isEmpty() || subModelKey == null || inputDigest == null) {
            return null;
        }
        prepareAsync();
        Path file = fileFor(identity, subModelKey, inputDigest);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        LOCK.lock();
        try {
            return read(file, identity, subModelKey, inputDigest);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Writes one baked geometry. Failures are logged and swallowed: the geometry in memory is already correct, and a
     * cache that cannot be written is only a slower next session.
     */
    public static void store(String identity, String subModelKey, String inputDigest, GeoModel model) {
        if (!isEnabled() || identity == null || identity.isEmpty() || subModelKey == null || inputDigest == null
            || model == null) {
            return;
        }
        prepareAsync();
        Path target = fileFor(identity, subModelKey, inputDigest);
        if (Files.isRegularFile(target)) {
            return;
        }
        byte[] payload = serialize(model);
        if (payload == null) {
            return;
        }
        LOCK.lock();
        try {
            Path directory = target.getParent();
            Files.createDirectories(directory);
            Path temporary = directory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                writeFile(temporary, identity, subModelKey, inputDigest, payload);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover temp file is pruned by the next prepare().
                }
            }
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not write the baked geometry cache file {}", target, e);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * A digest of the exact geometry input a bake consumed, so that two different geometries can never share an entry
     * even when the payload identity says they are the same model.
     * <p>
     * The binary channel hands the builder POJOs, not bytes, so this walks the fields the builder reads, in the order
     * the builder reads them. Only the fields {@code GeoBuilder} consumes are part of it: those are the only ones a
     * {@code GeoModel} is made of, and including parse-only trivia would invalidate entries for no visible change.
     */
    public static String geometryDigest(RawGeoModel model) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            MinecraftGeometry[] geometries = model.getMinecraftGeometry();
            updateInt(digest, model.getFormatVersion() == null ? -1 : model.getFormatVersion()
                .ordinal());
            updateInt(digest, geometries == null ? -1 : geometries.length);
            if (geometries != null && geometries.length > 0 && geometries[0] != null) {
                updateProperties(digest, geometries[0].getProperties());
                Bone[] bones = geometries[0].getBones();
                updateInt(digest, bones == null ? -1 : bones.length);
                if (bones != null) {
                    for (Bone bone : bones) {
                        updateBone(digest, bone);
                    }
                }
            }
            return Md5Utils.toHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // MD5 is required by every JRE; treat as "no digest" rather than failing a model load.
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    /** A digest of the exact geometry bytes a bake consumed (folder and legacy channels). */
    public static String bytesDigest(byte[] geometryBytes) {
        return Md5Utils.md5Hex(geometryBytes);
    }

    private static void updateProperties(MessageDigest digest, @Nullable ModelProperties properties) {
        updateInt(digest, properties == null ? 0 : 1);
        if (properties == null) {
            return;
        }
        updateString(digest, properties.getIdentifier());
        updateDouble(digest, properties.getTextureWidth());
        updateDouble(digest, properties.getTextureHeight());
        updateDouble(digest, properties.getVisibleBoundsWidth());
        updateDouble(digest, properties.getVisibleBoundsHeight());
        updateDoubleArray(digest, properties.getVisibleBoundsOffset());
        updateDouble(digest, properties.getHeightScale());
        updateDouble(digest, properties.getWidthScale());
        updateBoolean(digest, properties.getRenderLayersFirst());
        updateString(digest, properties.getDefaultTexture());
        updateBoolean(digest, properties.getForceCulling());
        ExtraInfo extraInfo = properties.getExtraInfo();
        updateInt(digest, extraInfo == null ? 0 : 1);
        if (extraInfo != null) {
            updateString(digest, extraInfo.getName());
            updateString(digest, extraInfo.getTips());
            updateString(digest, extraInfo.getLicense());
            updateStringArray(digest, extraInfo.getExtraAnimationNames());
            updateStringArray(digest, extraInfo.getAuthors());
        }
    }

    private static void updateBone(MessageDigest digest, @Nullable Bone bone) {
        updateInt(digest, bone == null ? 0 : 1);
        if (bone == null) {
            return;
        }
        updateString(digest, bone.getName());
        updateString(digest, bone.getParent());
        updateDoubleArray(digest, bone.getPivot());
        updateDoubleArray(digest, bone.getRotation());
        updateBoolean(digest, bone.getMirror());
        updateBoolean(digest, bone.getNeverRender());
        updateBoolean(digest, bone.getReset());
        updateDouble(digest, bone.getInflate());
        PolyMesh mesh = bone.getPolyMesh();
        updateInt(digest, mesh == null ? 0 : 1);
        if (mesh == null) {
            return;
        }
        updateBoolean(digest, mesh.getNormalizedUvs());
        updateString(digest, mesh.getPolys() == null || mesh.getPolys().enumValue == null ? null
            : mesh.getPolys().enumValue.name());
        updateDoubleArray(digest, mesh.getPositions());
        updateDoubleArray(digest, mesh.getNormals());
        updateDoubleArray(digest, mesh.getUvs());
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        updateInt(digest, (int) (value >>> 32));
        updateInt(digest, (int) value);
    }

    private static void updateDouble(MessageDigest digest, @Nullable Double value) {
        updateInt(digest, value == null ? 0 : 1);
        if (value != null) {
            updateLong(digest, Double.doubleToRawLongBits(value));
        }
    }

    private static void updateBoolean(MessageDigest digest, @Nullable Boolean value) {
        updateInt(digest, value == null ? -1 : (value ? 1 : 0));
    }

    private static void updateString(MessageDigest digest, @Nullable String value) {
        if (value == null) {
            updateInt(digest, -1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateStringArray(MessageDigest digest, @Nullable String[] values) {
        updateInt(digest, values == null ? -1 : values.length);
        if (values != null) {
            for (String value : values) {
                updateString(digest, value);
            }
        }
    }

    private static void updateDoubleArray(MessageDigest digest, @Nullable double[] values) {
        updateInt(digest, values == null ? -1 : values.length);
        if (values != null) {
            for (double value : values) {
                updateLong(digest, Double.doubleToRawLongBits(value));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // File format
    // ---------------------------------------------------------------------------------------------------------

    private static Path fileFor(String identity, String subModelKey, String inputDigest) {
        String directory = Md5Utils.md5Hex(identity.getBytes(StandardCharsets.UTF_8));
        return root().resolve(directory)
            .resolve(subModelFileName(subModelKey) + "-" + inputDigest + EXTENSION);
    }

    /**
     * A filesystem-safe rendering of a sub-model key.
     * <p>
     * Sub-model keys are payload keys, not ids: {@code projectile_#minecraft:arrow} and
     * {@code vehicle_#minecraft:boat} carry characters Windows rejects. Everything outside {@code [a-z0-9._-]} becomes
     * {@code _}, and the MD5 prefix keeps two keys that sanitise to the same name apart.
     */
    private static String subModelFileName(String subModelKey) {
        StringBuilder name = new StringBuilder(Math.min(subModelKey.length(), MAX_SUB_MODEL_NAME));
        for (int index = 0; index < subModelKey.length() && name.length() < MAX_SUB_MODEL_NAME; index++) {
            char character = Character.toLowerCase(subModelKey.charAt(index));
            name.append((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-' ? character : '_');
        }
        String sanitized = name.toString();
        if (sanitized.equals(subModelKey)) {
            return sanitized;
        }
        return sanitized + "-" + Md5Utils.md5Hex(subModelKey.getBytes(StandardCharsets.UTF_8))
            .substring(0, 8);
    }

    private static byte[] serialize(GeoModel model) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(model);
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not serialise a baked geometry for the cache", e);
            return null;
        }
        return buffer.toByteArray();
    }

    private static void writeFile(Path target, String identity, String subModelKey, String inputDigest,
        byte[] payload) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
            Files.newOutputStream(target))) {
            out.writeUTF(MAGIC);
            out.writeInt(BAKE_FORMAT);
            out.writeUTF(abiTag());
            out.writeUTF(identity);
            out.writeUTF(subModelKey);
            out.writeUTF(inputDigest);
            out.writeInt(payload.length);
            out.write(payload);
            out.write(Md5Utils.md5(payload));
            out.flush();
        }
    }

    @Nullable
    private static GeoModel read(Path file, String identity, String subModelKey, String inputDigest) {
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
            if (!subModelKey.equals(in.readUTF())) {
                return discard(file, "sub-model mismatch");
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
            if (!MessageDigest.isEqual(expected, Md5Utils.md5(payload))) {
                return discard(file, "payload checksum mismatch");
            }
            Object value = readPayload(payload);
            if (!(value instanceof GeoModel)) {
                return discard(file, "payload is not a GeoModel");
            }
            GeoModel model = (GeoModel) value;
            if (model.properties == null) {
                // buildGeo's output always carries properties; a hit without them would NPE in BuiltGeo.
                return discard(file, "payload has no model properties");
            }
            return model;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return discard(file, e.getClass()
                .getSimpleName());
        }
    }

    @Nullable
    private static GeoModel discard(Path file, String reason) {
        ysmu.LOG.warn("YSM client discarding baked geometry cache entry {}: {}", file, reason);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            ysmu.LOG.warn("YSM client could not delete the unreadable baked geometry cache file {}", file, e);
        }
        return null;
    }

    private static Object readPayload(byte[] payload) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new CacheObjectInputStream(new ByteArrayInputStream(payload))) {
            return in.readObject();
        }
    }

    /**
     * The object stream a baked geometry may reference, and nothing else.
     * <p>
     * The file was written by this process, so this is defence in depth rather than a trust boundary: if the cache
     * file were ever replaced, deserialising it would otherwise be able to construct arbitrary classes. Every class
     * in a built geometry lives in the engine tree, fastutil, vecmath or the JDK's collections.
     */
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
            throw new InvalidClassException(name, "is not allowed in a baked geometry cache");
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
                // A primitive array's element type ("[D", "[[F", ...).
                return true;
            }
            return className.startsWith("software.bernie.geckolib3.")
                || className.startsWith("net.geckominecraft.")
                || className.startsWith("javax.vecmath.")
                || className.startsWith("it.unimi.dsi.fastutil.")
                || className.startsWith("java.lang.")
                || className.startsWith("java.util.");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Deletes every other ABI directory, then trims this build's directory to its size budget.
     * <p>
     * Other builds' entries can never be read by this build (their engine classes and bake rules differ), so keeping
     * them only costs disk. Within this build, the oldest files go first; the newest ten minutes are kept so a session
     * cannot evict entries it is about to hit.
     */
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
                ysmu.LOG.warn("YSM client could not list the baked geometry cache directories", e);
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
            ysmu.LOG.warn("YSM client could not measure the baked geometry cache", e);
            return;
        }
        if (total <= DEFAULT_MAX_BYTES) {
            return;
        }
        files.sort(Comparator.comparingLong(BakedGeometryCache::lastModified));
        long now = System.currentTimeMillis();
        for (Path file : files) {
            if (total <= DEFAULT_MAX_BYTES) {
                break;
            }
            long modified = lastModified(file);
            if (now - modified < RECENT_MILLIS) {
                continue;
            }
            long size;
            try {
                size = Files.size(file);
                Files.deleteIfExists(file);
                total -= size;
            } catch (IOException e) {
                ysmu.LOG.warn("YSM client could not evict the baked geometry cache file {}", file, e);
            }
        }
        ysmu.LOG.info("YSM client trimmed the baked geometry cache to {} bytes (budget {})", total, DEFAULT_MAX_BYTES);
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
            ysmu.LOG.warn("YSM client could not delete the stale baked geometry cache directory {}", directory, e);
        }
    }

    /** Diagnostics: the number of cache files currently on disk for this build. */
    public static int entryCount() {
        Path current = root();
        if (!Files.isDirectory(current)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(current)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                    for (Path entry : entries) {
                        if (Files.isRegularFile(entry) && entry.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(EXTENSION)) {
                            count++;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            return count;
        }
        return count;
    }
}
