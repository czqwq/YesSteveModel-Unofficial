package com.fox.ysmu.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fox.ysmu.Config;
import com.fox.ysmu.Tags;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.ysmu;

/**
 * Loads the Rust acceleration library that ships inside this mod jar, or explains why it cannot.
 *
 * <p>
 * <b>Why a native library in a 1.7.10 mod.</b> Upstream dev-1.20 implements its geometry bake, its
 * per-frame vertex generation and its codecs in native code
 * ({@code tmp/YesSteveModel-Native-dev/modules/**}); this port reimplemented every one of them in Java
 * because it must ship as a single jar. The Rust crate under {@code rust/} is the same set of modules
 * rewritten for this port, reached through JNI. The reference for the split is upstream's native
 * renderer: native code produces CPU vertex data, the host keeps every OpenGL decision.
 *
 * <p>
 * <b>Always optional.</b> Nothing in the mod requires the library. A client or server on a platform the
 * mod was not built for, a read-only game directory, a security manager or a stale extracted copy all
 * end in the same place: {@link #isAvailable()} stays false, one log line says why, and every facade in
 * this package returns "not handled" so the caller runs the Java implementation it had before. That is
 * why the layer is loaded defensively and never with a hard failure.
 *
 * <p>
 * <b>Where it is unpacked.</b> The jar carries {@code assets/ysmu/native/<platform>/<library>} (for
 * example {@code windows-x86_64/ysmu_native.dll}). It is copied once per ABI version into
 * {@code config/ysmu/native/<platform>-abi<N>-<modversion>/} and loaded from that absolute path, so no
 * {@code java.library.path} or {@code LD_LIBRARY_PATH} setup is needed and an upgrade can never load a
 * library extracted by an older jar.
 */
public final class NativeLoader {

    /**
     * Native ABI version. Bump this whenever a JNI signature or a layout shared with Rust changes; the
     * Rust side declares the same number in {@code rust/ysmu_native/src/lib.rs} and the loader refuses
     * a library that disagrees, which turns a stale file into a clean fallback.
     *
     * <p>
     * History: 1 = zstd + PNG; 2 = geometry bake/blob/info entries, the render-ready geometry layout and
     * the render draw-group emitter.
     */
    public static final int ABI_VERSION = 2;

    private static final String RESOURCE_ROOT = "/assets/ysmu/native/";
    private static final String NATIVE_DIRECTORY = "native";

    private static final Object LOCK = new Object();
    private static final Set<String> DISABLED_MODULES = ConcurrentHashMap.newKeySet();

    private static boolean attempted;
    private static boolean available;
    private static String status = "not initialised";

    private NativeLoader() {}

    /**
     * Loads the library once. Safe to call from any thread and more than once; later calls only read the
     * result. Called from {@link com.fox.ysmu.CommonProxy#preInit} after the config is read.
     */
    public static void ensureLoaded() {
        synchronized (LOCK) {
            if (attempted) {
                return;
            }
            attempted = true;
            try {
                load();
            } catch (Throwable failure) {
                available = false;
                status = "unexpected failure: " + failure;
                ysmu.LOG.warn("[YSMU-NATIVE] {} - continuing with the Java implementations", status);
            }
        }
    }

    /**
     * Whether the native layer loaded and a given module may be called.
     *
     * <p>
     * A module is disabled for the rest of the session after a native call failed (a missing symbol, a
     * panic turned into an exception, a contract violation). Other modules keep working: one broken
     * bake must not cost the zstd or texture speedups.
     *
     * @param module stable module id used in logs, for example {@code "codec"}, {@code "geometry"}
     */
    public static boolean moduleUsable(String module) {
        return Config.ENABLE_RUST_NATIVE && isAvailable() && !DISABLED_MODULES.contains(module);
    }

    /** Whether the library is loaded and its ABI matches this jar. */
    public static boolean isAvailable() {
        if (!attempted) {
            ensureLoaded();
        }
        return available;
    }

    /** Human readable load result, logged once and shown by debug commands. */
    public static String status() {
        if (!attempted) {
            ensureLoaded();
        }
        return status;
    }

    /**
     * Records that one module failed and turns that module off for this session.
     *
     * <p>
     * Called by the facades when a native call throws. The throwable is usually an
     * {@link UnsatisfiedLinkError} (the Rust side did not export the expected symbol, i.e. the host and
     * the library disagree) or an {@link IllegalStateException} thrown by the Rust panic guard; in both
     * cases falling back to Java is correct and the log line is the only user-visible effect.
     */
    public static void onModuleFailure(String module, Throwable failure) {
        if (!DISABLED_MODULES.add(module)) {
            return;
        }
        ysmu.LOG.warn(
            "[YSMU-NATIVE] module '{}' failed and is disabled for this session; the Java implementation takes over: {}",
            module,
            failure.toString());
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.warn("[YSMU-NATIVE] module failure stack trace", failure);
        }
    }

    private static void load() {
        if (!Config.ENABLE_RUST_NATIVE) {
            status = "disabled by Config.EnableRustNative";
            ysmu.LOG.info("[YSMU-NATIVE] {} - using the Java implementations", status);
            return;
        }
        String platform = platformTag();
        String libraryName = libraryFileName();
        if (platform == null) {
            status = "unsupported platform " + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
            ysmu.LOG.info("[YSMU-NATIVE] {} - using the Java implementations", status);
            return;
        }
        String resource = RESOURCE_ROOT + platform + "/" + libraryName;
        try (InputStream artifact = NativeLoader.class.getResourceAsStream(resource)) {
            if (artifact == null) {
                status = "this build carries no native artifact for " + platform;
                ysmu.LOG.info("[YSMU-NATIVE] {} - using the Java implementations", status);
                return;
            }
            Path directory = ServerModelManager.FOLDER
                .resolve(NATIVE_DIRECTORY)
                .resolve(platform + "-abi" + ABI_VERSION + "-" + sanitize(Tags.VERSION));
            Files.createDirectories(directory);
            Path target = directory.resolve(libraryName);
            if (!Files.isRegularFile(target)) {
                extract(artifact, target);
            }
            System.load(target.toAbsolutePath()
                .toString());
            int version = nVersion();
            if (version != ABI_VERSION) {
                status = "abi mismatch: library " + version + ", jar " + ABI_VERSION;
                ysmu.LOG.warn("[YSMU-NATIVE] {} - using the Java implementations", status);
                return;
            }
            available = true;
            status = "loaded " + platform + " " + libraryName + " (abi " + ABI_VERSION + ")";
            ysmu.LOG.info("[YSMU-NATIVE] {}", status);
        } catch (Throwable failure) {
            status = "cannot load for " + platform + ": " + failure;
            ysmu.LOG.warn("[YSMU-NATIVE] {} - using the Java implementations", status);
        }
    }

    /**
     * Copies the packaged library next to its final name and moves it into place, so a process that is
     * killed mid-write never leaves a half-written library behind.
     */
    private static void extract(InputStream artifact, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.copy(artifact, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String platformTag() {
        String os = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT);
        String arch = architectureTag();
        if (arch == null) {
            return null;
        }
        if (os.contains("win")) {
            return "windows-" + arch;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos-" + arch;
        }
        if (os.contains("linux") || os.contains("freebsd") || os.contains("unix")) {
            return "linux-" + arch;
        }
        return null;
    }

    private static String architectureTag() {
        String arch = System.getProperty("os.arch", "")
            .toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x86-64") || arch.equals("x64")) {
            return "x86_64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        return null;
    }

    private static String libraryFileName() {
        String os = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "ysmu_native.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "libysmu_native.dylib";
        }
        return "libysmu_native.so";
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean safe = character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '.'
                || character == '_'
                || character == '-';
            builder.append(safe ? character : '_');
        }
        return builder.toString();
    }

    /** Declared by the Rust crate; returns its ABI version. */
    private static native int nVersion();
}
