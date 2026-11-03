package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.CACHE_SERVER;
import static com.fox.ysmu.model.ServerModelManager.registerModel;
import static com.fox.ysmu.model.ServerModelManager.removeExtension;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.model.upload.ModelFileValidator;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

/**
 * ZIP model packages under {@code config/ysmu/{custom,auth}} (and any other scan root): {@code <name>.zip} is one
 * raw model, the same thing a raw model directory is.
 *
 * <h2>Upstream anchor</h2>
 * <p>Upstream discovers {@code .zip} as a model source with exactly this meaning. Its extension set is
 * {@code {"\.mxc", "\.ysm", "\.zip", "\.7z"}} ({@code model/catalog/source/ModelSourceDiscovery.java:26}); every regular
 * file with one of those names becomes a <b>model</b> candidate ({@code :128-136}, suffix match
 * {@code isArchive:158-161}), and a {@code .zip} (like {@code .7z}) is classified
 * {@code CURRENT_RAW_ARCHIVE} ({@code :163-186}; the {@code .zip} case is the final
 * {@code return CURRENT_RAW_ARCHIVE} at {@code :185}). The archive is then parsed by the <em>same</em> raw model
 * parser as a raw directory: {@code RawModelImporter.capture} hands a directory to {@code vfs.Directory} and a file to
 * {@code natives.NativeArchive}, both to {@code ModelParser.capture}
 * ({@code model/catalog/RawModelImporter.java:21-30}); the native reader opens {@code .zip} with the miniz zip codec
 * ({@code tmp/YesSteveModel-Native-dev/modules/lib-jni/src/archive/archive.cc:113-137},
 * {@code modules/codec/src/codec/zip.cc:89-119}).
 *
 * <h2>What may be inside the archive</h2>
 * <p>The archive root <em>is</em> the model root. The raw parser requires either the OpenYSM manifest {@code ysm.json}
 * ({@code format/parser/RawModelAssembler.java:49,118-129}) or the legacy flat layout that
 * {@code LegacyManifestBuilder} reconstructs from {@code main.json} + {@code arm.json} (required, {@code :27-36}), the
 * root-level {@code *.png} textures ({@code :49-66}), the root-level {@code <set>.animation.json} files
 * ({@code :42-47}) and the optional {@code info.json} metadata ({@code :72-73}). The archive is a snapshot of one raw
 * model directory: the fixture in {@code ModelAudioContentPathTest.java:40-54} zips a raw model directory by writing
 * each file with a path relative to the model root ({@code :160-172}), and the identity standard states that a folder,
 * zip or 7z of the same content must produce the same record set
 * ({@code docs/standards/model-schema/manifest-and-identity.md:92}).
 *
 * <p>Two details of the native archive reader are part of the rule and are reproduced here:</p>
 * <ul>
 * <li><b>A single enclosing top-level directory is transparently stripped.</b> The archive tree is re-rooted while the
 * current root has no files and exactly one child directory ({@code codec/include/codec/archive.h:73-84}, loop
 * {@code :79-83}), so zipping the model folder itself (operating systems add that folder) works like zipping its
 * contents.</li>
 * <li><b>A {@code ysm-pack.json} is not a pack here.</b> Upstream recognizes pack roots only by walking
 * <em>directories</em> ({@code ModelSourceDiscovery.java:68-83}; {@code ModelPackScanner.java:27}); an archive file is
 * only ever a model candidate, so an archive whose root is a pack manifest fails raw-model parsing. A pack's models can
 * be shipped as separate archives, not as one pack archive.</li>
 * </ul>
 *
 * <h2>What is intentionally not ported</h2>
 * <ul>
 * <li>{@code .7z}: upstream reads it through its bundled native 7z codec
 * ({@code archive.cc:124-125} -> {@code codec::SevenZip}). The port has no 7z decoder and adding one is a new
 * dependency/format engine, not a zip-parser variant; recorded as a divergence for the owner.</li>
 * <li>{@code .mxc}: upstream's current Asset Container ("direct container",
 * {@code ModelSourceDiscovery.java:168-170}); this port has no Asset Container format at all (its container is the
 * encrypted cache in {@code config/ysmu/cache/server}), so there is nothing to open.</li>
 * </ul>
 *
 * <h2>Port-specific shape (not invented semantics)</h2>
 * <p>Upstream parses the archive in memory through its native VFS. This port's folder parser is a {@link Path}-based
 * reader ({@link YSMFolderDeserializer}, {@link FolderFormat}), so the archive is materialized into a private staging
 * directory and that directory is then handed to <em>the same parsers a raw directory goes through</em> - modern
 * ({@code ysm.json}) to {@link OpenYsmFormat}, legacy flat to {@link FolderFormat}. Materializing into a staging
 * directory and deleting it in {@code finally} is upstream's own conversion-staging shape
 * ({@code model/catalog/source/ModelSourceResolver.java:78-93} creates a temp directory under
 * {@code ConvertedObjectStore.temporaryRoot()} and calls {@code deleteTree} in {@code finally}; the staging directory
 * name is {@code .tmp} under the converted cache root, {@code model/storage/ConvertedObjectStore.java:26-43}). It is
 * only a staging area, never a cache: the persistent result is the normal encrypted server cache that
 * {@link ModelCacheWriter} writes for every model source, and {@link ModelIndexCache} keeps reloads from re-doing the
 * work when the archive bytes are unchanged.
 *
 * <h2>Untrusted archive safety</h2>
 * <p>A {@code .zip} is attacker-controlled content, both from disk and over {@code /ysm upload}. The reader enforces,
 * with the single policy owner the upload path already established (no second set of limits):</p>
 * <ul>
 * <li>entry names are relative, {@code /}-separated, and every segment is rejected if it is empty, {@code .},
 * {@code ..}, absolute, contains {@code \\} or a control/Windows-illegal character, ends with a space/dot, or is a
 * Windows reserved device name - so nothing can be written outside the staging directory and no name can alias another
 * on Windows. A leading {@code "./"} chain ({@code zip -r archive .}) is stripped first because upstream's archive tree
 * re-roots through it (see {@link #stripLeadingCurrentDirectory});</li>
 * <li>duplicate entry names (case-insensitively) are rejected, because extraction order would otherwise decide the
 * content;</li>
 * <li>the entry count is capped by {@link ModelFileValidator#MAX_ENTRIES}, the total declared uncompressed size and the
 * actually decompressed bytes by {@link ModelFileValidator#maxDecompressedBytes()} - checked before allocation and
 * enforced again while streaming, so a lying central directory cannot get past it; the first violation aborts the whole
 * archive instead of skipping one entry (the CPU-amplification reason documented on
 * {@code YesModelUtils.inputStrict});</li>
 * <li>content is streamed straight to the staging directory through a fixed buffer, so no entry is ever held in memory
 * as a whole;</li>
 * <li>the staging directory is a fresh directory whose parent is created by this class, every write target is
 * normalized and re-checked to stay under it, and the whole directory is deleted in {@code finally}.</li>
 * </ul>
 */
public final class ZipModelFormat {

    /**
     * The archive suffix. Upstream's extension list carries it ({@code ModelSourceDiscovery.java:26}) and its model
     * name derivation strips it ({@code util/ModelIdUtil.java:12-18,34-48}: a known model extension is removed from the
     * last path component; the port's equivalent is {@link ServerModelManager#removeExtension(String)} on the
     * path-derived name).
     */
    public static final String SUFFIX = ".zip";

    /** Staging directory under the server cache root; see the class javadoc for the upstream staging shape. */
    private static final String STAGING_DIRECTORY_NAME = ".zip-tmp";

    /** {@code ysm.json} is the raw model manifest; its presence selects the modern OpenYSM folder layout. */
    private static final String MANIFEST_FILE_NAME = "ysm.json";

    /** Streaming copy buffer for one archive entry; the total is bounded by the decompression budget. */
    private static final int COPY_BUFFER_SIZE = 8192;

    /** The ZIP end-of-central-directory record is at most 22 + 65535 comment bytes; read that tail to find it. */
    private static final int MAX_EOCD_SCAN_LENGTH = 22 + 65535;

    /** The end-of-central-directory signature ({@code PK\005\006}), read little-endian from bytes. */
    private static final int EOCD_SIGNATURE = 0x06054b50;

    /**
     * Windows reserved device names. They are rejected as path segments for the same reason
     * {@code ModelUploadInstaller}/{@code ServerModelManage} reject them for file names: writing {@code CON} on Windows
     * opens a device, not a file.
     */
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "COM1",
        "COM2",
        "COM3",
        "COM4",
        "COM5",
        "COM6",
        "COM7",
        "COM8",
        "COM9",
        "LPT1",
        "LPT2",
        "LPT3",
        "LPT4",
        "LPT5",
        "LPT6",
        "LPT7",
        "LPT8",
        "LPT9");

    private ZipModelFormat() {}

    /** Which raw model layout the archive materialized into. */
    public enum Layout {
        /** The root carries {@code ysm.json}: same layout a raw directory with a manifest has. */
        MODERN,
        /** The root carries the legacy flat layout: {@code main.json} + {@code arm.json} + a root-level PNG. */
        LEGACY_FLAT,
        /** Neither layout: this archive is not a supported model package. */
        UNSUPPORTED
    }

    /** Why an archive was rejected; the scanner logs it and the upload validator maps it to its own reason. */
    public enum RejectKind {
        NOT_A_ZIP,
        UNSAFE_ENTRY,
        TOO_MANY_ENTRIES,
        TOO_LARGE,
        NO_MODEL,
        INVALID_MODEL,
        IO_FAILURE
    }

    /** A rejected archive. {@link #detail()} is the one-line reason used by both presentations. */
    public static final class RejectedException extends IOException {

        private static final long serialVersionUID = 1L;

        private final RejectKind kind;
        private final String detail;

        RejectedException(RejectKind kind, String detail) {
            super(detail);
            this.kind = kind;
            this.detail = detail;
        }

        RejectedException(RejectKind kind, String detail, Throwable cause) {
            super(detail, cause);
            this.kind = kind;
            this.detail = detail;
        }

        /** Why the archive was rejected; {@code ModelFileValidator} maps it onto its own upload-result reason. */
        public RejectKind kind() {
            return this.kind;
        }

        /** The reason, without the archive path; callers prefix the source they were reading. */
        public String detail() {
            return this.detail;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // production scan
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Production entry point, run once per scan root by {@link ServerModelManager#reloadPacks} in the ZIP cache step.
     * Every top-level and nested {@code .zip} under {@code rootPath} is registered like a {@code .ysm} file: its model
     * id is the archive path under the root minus the known extension (nested archives therefore register as
     * {@code group/model}, the same naming rule the other scanners use), and the registration goes through
     * {@link ServerModelManager#registerModel} so the source priorities ({@code BUILTIN 0 / AUTH 1 / CUSTOM 2}) and
     * conflict logging are exactly the ones a directory model gets.
     */
    public static void cacheAllModels(Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return;
        }
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attributes) {
                    // Upstream discovery does not descend into a raw model directory: a directory carrying
                    // ysm.json or main.json is a model and its subtree is skipped
                    // (model/catalog/source/ModelSourceDiscovery.java:119-122, predicate :153-156). So an archive
                    // that happens to live inside a model directory is not a second model there; pack roots are not
                    // raw model directories, so archives inside a pack are still discovered.
                    if (!dir.equals(rootPath) && isRawModelDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && isZipFile(file)) {
                        cacheArchive(rootPath, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to scan ZIP model packages under {}", rootPath, e);
        }
    }

    /**
     * Upstream's raw-directory predicate: a directory that carries {@code ysm.json} or {@code main.json} is a model
     * source and the source walk stops there ({@code ModelSourceDiscovery.java:153-156}).
     */
    private static boolean isRawModelDirectory(Path dir) {
        return Files.isRegularFile(dir.resolve(MANIFEST_FILE_NAME))
            || Files.isRegularFile(dir.resolve(ServerModelManager.MAIN_MODEL_FILE_NAME));
    }

    /** Whether {@code file} has the archive suffix; upstream matches archive extensions case-insensitively. */
    public static boolean isZipFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        return file.getFileName()
            .toString()
            .toLowerCase(Locale.ROOT)
            .endsWith(SUFFIX);
    }

    /** Whether the bytes start with a ZIP signature ({@code PK\003\004} or the empty-archive {@code PK\005\006}). */
    public static boolean isZipArchive(byte[] data) {
        if (data == null || data.length < 4 || data[0] != 'P' || data[1] != 'K') {
            return false;
        }
        return (data[2] == 3 && data[3] == 4) || (data[2] == 5 && data[3] == 6);
    }

    /** Registers one archive; failures are logged and never interrupt the rest of the scan. */
    private static void cacheArchive(Path rootPath, Path archive) {
        // Same naming rule as every other file source: relative path under the scan root, extension removed
        // (OpenYsmFormat.toModelName + ModelIdUtil.getInternalModelId; upstream's util/ModelIdUtil.java:12-18,34-48
        // strips the same known extensions before deriving the display name).
        String modelId = ModelIdUtil.getInternalModelId(
            removeExtension(OpenYsmFormat.toModelName(rootPath, archive)));
        ModelIndexCache.Source source = ModelIndexCache.Source.of(ModelIndexCache.Scanner.ZIP, rootPath, archive);
        // Index hit = this exact archive content was parsed before and its cache files still verify. Skip the staging
        // extraction and the parse entirely; the registration is replayed by ModelIndexCache.
        if (ModelIndexCache.restoreIfHit(source, modelId)) {
            return;
        }
        try (Archive materialized = materialize(archive)) {
            switch (materialized.layout()) {
                case MODERN:
                    OpenYsmFormat.cacheExtractedFolderModel(rootPath, materialized.root(), modelId, source, archive);
                    break;
                case LEGACY_FLAT: {
                    ServerModelInfo info = FolderFormat.cacheExtractedFolderModel(materialized.root(), modelId, archive);
                    if (info != null) {
                        registerModel(modelId, info, null, archive, rootPath);
                        ModelIndexCache.remember(source, modelId, info, null);
                    }
                    break;
                }
                case UNSUPPORTED:
                default:
                    ysmu.LOG.warn(
                        "Rejected ZIP model package {}: it contains no supported model ({})",
                        archive,
                        describeUnsupported(materialized.root()));
                    break;
            }
        } catch (RejectedException e) {
            ysmu.LOG.warn("Rejected ZIP model package {}: {}", archive, e.detail());
        } catch (Exception | LinkageError e) {
            ysmu.LOG.warn("Failed to load ZIP model package {}", archive, e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // materialization (scanner and upload validator share it)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Materializes a {@code .zip} on disk into a fresh staging directory. The caller closes the result, which deletes
     * the staging directory.
     */
    public static Archive materialize(Path archive) throws RejectedException {
        return materialize(archive, false);
    }

    /**
     * The {@code /ysm upload} path: the bytes are already in memory and bounded by the upload size limit, so they are
     * staged to a temporary archive file first and then read through the exact same bounded reader.
     */
    public static Archive materialize(byte[] data) throws RejectedException {
        if (data == null || data.length == 0) {
            throw new RejectedException(RejectKind.NOT_A_ZIP, "the file is empty");
        }
        if (data.length > ModelFileValidator.maxUploadBytes()) {
            throw new RejectedException(
                RejectKind.TOO_LARGE,
                "the archive is " + data.length + " bytes, above the "
                    + ModelFileValidator.maxUploadBytes() + " byte upload limit");
        }
        Path temporaryArchive = null;
        try {
            temporaryArchive = Files.createTempFile(stagingRoot(), "upload-", SUFFIX);
            Files.write(temporaryArchive, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return materialize(temporaryArchive, true);
        } catch (RejectedException e) {
            deleteQuietly(temporaryArchive);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temporaryArchive);
            throw new RejectedException(RejectKind.IO_FAILURE, "cannot stage the uploaded archive: " + e, e);
        }
    }

    /**
     * The one extraction implementation. {@code deleteArchiveOnClose} is true only for the upload path, whose temporary
     * archive file belongs to this class; a {@code .zip} under a scan root is the user's file and is never deleted.
     */
    private static Archive materialize(Path archive, boolean deleteArchiveOnClose) throws RejectedException {
        if (archive == null) {
            throw new RejectedException(RejectKind.IO_FAILURE, "the archive path is null");
        }
        Path stagingRoot = stagingRoot();
        final Path destination;
        try {
            destination = Files.createTempDirectory(stagingRoot, "model-");
        } catch (IOException e) {
            throw new RejectedException(
                RejectKind.IO_FAILURE,
                "cannot create the ZIP staging directory under " + stagingRoot + ": " + e,
                e);
        }
        try {
            extract(archive, destination);
        } catch (RejectedException e) {
            deleteTree(destination);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteTree(destination);
            throw new RejectedException(RejectKind.IO_FAILURE, "cannot read the archive: " + e, e);
        }
        return new Archive(archive, destination, deleteArchiveOnClose);
    }

    /**
     * A materialized archive. {@link #close()} deletes the staging directory (and the temporary archive file when the
     * source was an upload's bytes); both are best-effort and only logged, because a failed cleanup must not mask the
     * parse result ({@code ModelSourceResolver.java:272-279} has exactly this rule for its staging cleanup).
     */
    public static final class Archive implements AutoCloseable {

        private final Path archive;
        private final Path root;
        private final boolean deleteArchiveOnClose;
        private Layout layout;

        private Archive(Path archive, Path root, boolean deleteArchiveOnClose) {
            this.archive = archive;
            this.root = root;
            this.deleteArchiveOnClose = deleteArchiveOnClose;
        }

        /** The staging directory that is the model root (single-top-level-directory already stripped). */
        public Path root() {
            return this.root;
        }

        /** The layout detected at the materialized root. */
        public Layout layout() {
            if (this.layout == null) {
                this.layout = layoutOf(this.root);
            }
            return this.layout;
        }

        /**
         * Read-only validation for the upload path: parses the materialized model with the same parsers the scanners
         * use and performs no cache write. The upload contract requires "the content must pass the complete model
         * parse" ({@code model/upload/ModelFileValidator} javadoc), and this is that parse for an archive.
         */
        public void validate() throws RejectedException {
            switch (layout()) {
                case MODERN:
                    validateModern(this.root);
                    break;
                case LEGACY_FLAT:
                    validateLegacyFlat(this.root);
                    break;
                case UNSUPPORTED:
                default:
                    throw new RejectedException(
                        RejectKind.NO_MODEL,
                        "it contains no supported model (" + describeUnsupported(this.root) + ")");
            }
        }

        @Override
        public void close() {
            deleteTree(this.root);
            if (this.deleteArchiveOnClose) {
                deleteQuietly(this.archive);
            }
        }
    }

    /** The staging root: {@code cache/server/.zip-tmp}, the port counterpart of upstream's converted-cache staging. */
    private static Path stagingRoot() {
        Path staging = CACHE_SERVER.resolve(STAGING_DIRECTORY_NAME);
        try {
            Files.createDirectories(staging);
        } catch (IOException e) {
            // The extraction will fail with its own informative rejection when it cannot create a directory.
            ysmu.LOG.warn("Cannot create the ZIP staging directory {}", staging, e);
        }
        return staging;
    }

    /**
     * Bounded extraction of {@code archive} into the fresh, empty {@code destination}.
     *
     * <p>Checks run in this order so a hostile archive is rejected before doing expensive work: whole-archive entry
     * count, then every entry name (path traversal / Windows aliasing / duplication), then the declared uncompressed
     * total against the budget, and only then decompression, which re-enforces the budget on the actual bytes.</p>
     */
    private static void extract(Path archive, Path destination) throws IOException {
        int maxEntries = ModelFileValidator.MAX_ENTRIES;
        long budget = ModelFileValidator.maxDecompressedBytes();
        // The entry-count cap has to be checked from the end-of-central-directory record *before* ZipFile builds its
        // entry table, otherwise a small archive that declares a million entries would make that allocation happen
        // first and only be rejected afterwards (no unbounded allocation on untrusted input).
        rejectIfCentralDirectoryIsTooLarge(archive, maxEntries);
        try (ZipFile zip = openZip(archive)) {
            if (zip.size() > maxEntries) {
                throw new RejectedException(
                    RejectKind.TOO_MANY_ENTRIES,
                    "it declares " + zip.size() + " entries, above the " + maxEntries + " entry limit");
            }
            List<ZipEntry> entries = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            long declaredTotal = 0L;
            int count = 0;
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++count > maxEntries) {
                    throw new RejectedException(
                        RejectKind.TOO_MANY_ENTRIES,
                        "it declares more than the " + maxEntries + " entry limit");
                }
                String name = stripLeadingCurrentDirectory(entry.getName());
                if (entry.isDirectory()) {
                    // Directory entries are only markers; the directories a file needs are created from the file path.
                    // Their names are validated all the same, because a hostile name is a hostile name. "./" alone is
                    // the directory marker a "zip -r archive ." archive carries for the root itself.
                    if (!name.isEmpty()) {
                        String problem = entryNameProblem(name);
                        if (problem != null) {
                            throw new RejectedException(RejectKind.UNSAFE_ENTRY, "'" + name + "' " + problem);
                        }
                    }
                    continue;
                }
                String problem = entryNameProblem(name);
                if (problem != null) {
                    throw new RejectedException(RejectKind.UNSAFE_ENTRY, "'" + name + "' " + problem);
                }
                String portableName = name.toLowerCase(Locale.ROOT);
                if (!seenNames.add(portableName)) {
                    throw new RejectedException(
                        RejectKind.UNSAFE_ENTRY,
                        "'" + name + "' appears more than once (case-insensitively); extraction order would decide"
                            + " its content");
                }
                long declaredSize = entry.getSize();
                if (declaredSize > 0) {
                    declaredTotal += declaredSize;
                    if (declaredTotal > budget) {
                        throw new RejectedException(
                            RejectKind.TOO_LARGE,
                            "it declares more than the " + budget + " byte decompressed archive budget");
                    }
                }
                entries.add(entry);
                names.add(name);
            }
            if (entries.isEmpty()) {
                throw new RejectedException(RejectKind.NO_MODEL, "it contains no files");
            }
            String rootPrefix = singleTopLevelDirectoryPrefix(names);
            long remaining = budget;
            for (int i = 0; i < entries.size(); i++) {
                String relative = names.get(i)
                    .substring(rootPrefix.length());
                Path target = destination.resolve(relative)
                    .normalize();
                if (!target.startsWith(destination) || target.getParent() == null) {
                    // Defense in depth only: entryNameProblem already rejects traversal, so this must not trigger.
                    throw new RejectedException(RejectKind.UNSAFE_ENTRY, "'" + names.get(i) + "' escapes the staging root");
                }
                try {
                    Files.createDirectories(target.getParent());
                    remaining -= copyBounded(zip.getInputStream(entries.get(i)), target, remaining, names.get(i));
                } catch (RejectedException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    throw new RejectedException(
                        RejectKind.IO_FAILURE,
                        "it could not be decompressed ('" + names.get(i) + "': " + e + ")",
                        e);
                }
            }
        } catch (RejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new RejectedException(RejectKind.NOT_A_ZIP, "it is not a readable ZIP archive: " + e, e);
        }
    }

    private static ZipFile openZip(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new RejectedException(RejectKind.IO_FAILURE, "the archive file is missing: " + archive);
        }
        return new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * Reads the total-entry field from the end-of-central-directory record (signature {@code PK\005\006}, structure per
     * the ZIP specification: signature, disk numbers, entry counts at offsets 8 and 10, central-directory size/offset,
     * comment length) and rejects before {@link ZipFile} is constructed.
     *
     * <p>If no EOCD record is found the file is not a well-formed ZIP; that is left to {@link ZipFile} to reject with its
     * own error, so this check stays a single-purpose bound. The {@code 0xFFFF} value means the real count is in a
     * ZIP64 record; such an archive is already far above {@link ModelFileValidator#MAX_ENTRIES} and is rejected.</p>
     */
    private static void rejectIfCentralDirectoryIsTooLarge(Path archive, int maxEntries) throws IOException {
        final long length;
        try {
            length = Files.size(archive);
        } catch (IOException e) {
            return; // openZip reports the unreadable archive; no count to bound.
        }
        int tailLength = (int) Math.min(length, MAX_EOCD_SCAN_LENGTH);
        if (tailLength < 22) {
            return; // Too small to hold an EOCD record; ZipFile rejects it as not-a-zip.
        }
        byte[] tail = new byte[tailLength];
        try (RandomAccessFile file = new RandomAccessFile(archive.toFile(), "r")) {
            file.seek(length - tailLength);
            file.readFully(tail);
        }
        for (int offset = tail.length - 22; offset >= 0; offset--) {
            int signature = (tail[offset] & 0xFF) | ((tail[offset + 1] & 0xFF) << 8)
                | ((tail[offset + 2] & 0xFF) << 16) | ((tail[offset + 3] & 0xFF) << 24);
            if (signature != EOCD_SIGNATURE) {
                continue;
            }
            int entries = (tail[offset + 10] & 0xFF) | ((tail[offset + 11] & 0xFF) << 8);
            if (entries == 0xFFFF) {
                throw new RejectedException(
                    RejectKind.TOO_MANY_ENTRIES,
                    "it declares a ZIP64 entry count, which is far above the " + maxEntries + " entry limit");
            }
            if (entries > maxEntries) {
                throw new RejectedException(
                    RejectKind.TOO_MANY_ENTRIES,
                    "it declares " + entries + " entries, above the " + maxEntries + " entry limit");
            }
            return;
        }
    }

    /**
     * Copies one entry to {@code target}, enforcing the remaining decompression budget on the bytes actually written.
     * The central directory can lie about the size, so this counter - not {@code entry.getSize()} - is authoritative.
     *
     * @return the number of bytes written
     */
    private static long copyBounded(InputStream input, Path target, long remainingBudget, String name)
        throws IOException {
        long written = 0L;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream in = input;
            OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > remainingBudget) {
                    throw new RejectedException(
                        RejectKind.TOO_LARGE,
                        "it expands past the " + ModelFileValidator.maxDecompressedBytes()
                            + " byte decompressed archive budget ('" + name + "')");
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // rules: entry names, single-top-level directory, layout
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Strips a leading {@code "./"} chain from an entry name, which is how {@code zip -r archive .} stores a tree.
     * Upstream's archive tree splits names on {@code /} without special-casing {@code "."}
     * ({@code modules/core/src/hierarchy/path_walker.h:17-25} only trims leading/trailing slashes,
     * {@code tree.h:49-73} inserts every segment as a child) and then re-roots through single-child directories
     * ({@code codec/include/codec/archive.h:79-83}), so a {@code "./"} prefix is equivalent to no prefix for every
     * layout this reader accepts. {@code "./"} directory markers become the empty name and are skipped by the caller.
     */
    private static String stripLeadingCurrentDirectory(String name) {
        String normalized = name;
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /**
     * The reason {@code name} is not a safe archive path, or {@code null} when it is. The archive root is a model
     * directory, so entries are relative {@code /}-separated paths; anything that could escape the staging directory,
     * alias another entry on Windows, or name a device is rejected instead of normalized (this is a strict reader:
     * the first bad entry rejects the archive, like {@code YesModelUtils.inputStrict}).
     */
    private static String entryNameProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "has an empty entry name";
        }
        if (name.length() > ModelFileValidator.MAX_ENTRY_NAME_LENGTH) {
            return "is longer than the " + ModelFileValidator.MAX_ENTRY_NAME_LENGTH + " character entry-name limit";
        }
        if (name.charAt(0) == '/') {
            return "is an absolute path";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\') {
                return "contains a backslash (archive paths must use '/')";
            }
            if (c < 0x20 || c == 0x7F) {
                return "contains a control character";
            }
            if (c == ':' || c == '"' || c == '<' || c == '>' || c == '|' || c == '?' || c == '*') {
                return "contains the character '" + c + "'";
            }
        }
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (trimmed.isEmpty()) {
            return "has an empty entry name";
        }
        for (String segment : trimmed.split("/", -1)) {
            if (segment.isEmpty()) {
                return "contains an empty path segment";
            }
            if (".".equals(segment) || "..".equals(segment)) {
                return "contains the path segment '" + segment + "'";
            }
            char last = segment.charAt(segment.length() - 1);
            if (last == '.' || last == ' ') {
                // Windows strips these, so 'x.' and 'x' would alias the same file.
                return "has a path segment ending with a dot or a space";
            }
            int dot = segment.lastIndexOf('.');
            String stem = dot > 0 ? segment.substring(0, dot) : segment;
            if (WINDOWS_RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
                return "names the Windows reserved device '" + stem + "'";
            }
        }
        return null;
    }

    /**
     * The prefix to strip so that the staging directory is the model root. Upstream's archive tree keeps descending
     * while the current root has no files and exactly one child directory
     * ({@code tmp/YesSteveModel-Native-dev/modules/codec/include/codec/archive.h:73-84}, loop {@code :79-83}), so a
     * folder zipped "as a folder" parses exactly like the folder does. Any root-level file, or more than one top-level
     * directory, stops the strip - the same rule as upstream, and the reason sibling junk (for example the
     * {@code __MACOSX/} directory some tools add) makes the archive fail layout detection instead of being ignored.
     *
     * @return {@code ""} or a prefix ending with {@code "/"}
     */
    private static String singleTopLevelDirectoryPrefix(List<String> names) {
        StringBuilder prefix = new StringBuilder();
        while (true) {
            String child = null;
            for (String name : names) {
                String rest = name.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash < 0) {
                    // A file at the current root level: the root is already the model root.
                    return prefix.toString();
                }
                String candidate = rest.substring(0, slash);
                if (child == null) {
                    child = candidate;
                } else if (!child.equals(candidate)) {
                    return prefix.toString();
                }
            }
            if (child == null) {
                return prefix.toString();
            }
            prefix.append(child)
                .append('/');
        }
    }

    /** Which raw model layout the materialized root has; same rules the directory scanners use. */
    static Layout layoutOf(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Layout.UNSUPPORTED;
        }
        if (Files.isRegularFile(root.resolve(MANIFEST_FILE_NAME))) {
            return Layout.MODERN;
        }
        if (FolderFormat.isModelDirectory(root)) {
            return Layout.LEGACY_FLAT;
        }
        return Layout.UNSUPPORTED;
    }

    /** The layout hint shared by the scanner's log line and the upload validator's rejection detail. */
    private static String describeUnsupported(Path root) {
        StringBuilder description = new StringBuilder(
            "expected ysm.json at the archive root, or the legacy flat layout main.json + arm.json + a root-level .png");
        if (root != null && Files.isRegularFile(root.resolve(ServerModelManager.PACK_INFO_FILE_NAME))) {
            description.append(
                "; a ysm-pack.json root is a model pack directory and packs are not read from archives");
        }
        return description.toString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // validation halves (read-only, reuse the scanners' parsers)
    // ---------------------------------------------------------------------------------------------------------------

    private static void validateModern(Path root) throws RejectedException {
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(root)) {
            RawYsmModel raw = deserializer.deserialize();
            raw.modelId = "<upload>";
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                throw new RejectedException(
                    RejectKind.INVALID_MODEL,
                    "the OpenYSM folder model parsed but cannot be bridged to the port's legacy ModelData");
            }
        } catch (RejectedException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            throw new RejectedException(
                RejectKind.INVALID_MODEL,
                "the OpenYSM folder model could not be parsed: " + e,
                e);
        }
    }

    private static void validateLegacyFlat(Path root) throws RejectedException {
        try {
            FolderFormat.validateModelData(root, "<upload>");
        } catch (Exception | LinkageError e) {
            throw new RejectedException(
                RejectKind.INVALID_MODEL,
                "the legacy flat model could not be parsed: " + e,
                e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // cleanup
    // ---------------------------------------------------------------------------------------------------------------

    /** Deletes a staged directory; the shared shape of upstream's {@code ModelSourceResolver.deleteTree:180-202}. */
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes)
                    throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(@NotNull Path directory, IOException failure)
                    throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            // Best effort: leftovers are bounded staging artifacts, never a cache, and a cleanup failure must not
            // replace the parse result (same rule as ModelSourceResolver.java:272-279).
            ysmu.LOG.warn("Failed to delete the ZIP staging directory {}", root, e);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            ysmu.LOG.warn("Failed to delete the temporary ZIP archive {}", file, e);
        }
    }
}
