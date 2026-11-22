package parity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the Rust library for the harness and reports which mechanism worked.
 *
 * <p>
 * Two paths are tried, in this order:
 * <ol>
 *   <li><b>NativeLoader</b> — the shipping path. {@code com.fox.ysmu.nativebridge.NativeLoader}
 *       extracts {@code assets/ysmu/native/<platform>/<lib>} from the classpath into
 *       {@code config/ysmu/native/} and {@code System.load}s it. Running the harness from the repository
 *       root makes that relative directory resolve exactly as it does in game. This path is only used
 *       when the artifact packaged into {@code build/resources/main} is at least as new as any other
 *       candidate library file; otherwise the harness would silently prove an older DLL.</li>
 *   <li><b>direct load</b> — {@code System.load} the newest library file found under
 *       {@code build/resources/main}, {@code build/generated/rust-resources}, {@code rust/target} or
 *       {@code build/rust-target}, then flip {@code NativeLoader}'s private {@code attempted}/
 *       {@code available} fields so the public facades consider the layer usable. This is the
 *       development path: a {@code cargo build} alone produces a newer DLL than the one Gradle copied
 *       into the resources directory.</li>
 * </ol>
 *
 * <p>
 * The fallback contract is verified separately by the modules: setting {@code Config.ENABLE_RUST_NATIVE}
 * to {@code false} must make every facade decline, and restoring it must make them serve the native
 * implementation again.
 */
public final class NativeBootstrap {

    private static final String NOT_INITIALISED = "not initialised";

    private static String mode = "unavailable";
    private static boolean available;
    private static String status = NOT_INITIALISED;
    private static String loadedFrom = "";
    private static Path packagedLibrary;
    private static Path freshestLibrary;
    private static boolean packagedStale;

    private NativeBootstrap() {}

    public static synchronized void initialize(Path repo, boolean forceDirect) {
        if (!NOT_INITIALISED.equals(status)) {
            return;
        }
        String platform = platformTag();
        String libraryName = libraryFileName();
        if (platform == null) {
            status = "unsupported platform " + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
            return;
        }

        List<Path> candidates = new ArrayList<>();
        packagedLibrary = repo.resolve("build/resources/main/assets/ysmu/native/" + platform + "/" + libraryName);
        candidates.add(packagedLibrary);
        candidates.add(repo.resolve("build/generated/rust-resources/assets/ysmu/native/" + platform + "/" + libraryName));
        candidates.add(repo.resolve("rust/target/release/" + libraryName));
        candidates.add(repo.resolve("build/rust-target/release/" + libraryName));

        freshestLibrary = null;
        long freshestTime = Long.MIN_VALUE;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            long modified = lastModified(candidate);
            if (modified > freshestTime) {
                freshestTime = modified;
                freshestLibrary = candidate;
            }
        }
        if (freshestLibrary == null) {
            status = "no native library found (looked for " + libraryName + " under build/ and rust/target/)";
            return;
        }
        // "Stale" must be about content, not timestamps: Gradle's copy task treats an identical file as
        // up-to-date, so build/resources/main can keep an older mtime while holding exactly the same bytes
        // as build/generated/rust-resources. Comparing the content lets the packaged-artifact path
        // (NativeLoader) be used for the final report instead of falling back to a direct load.
        packagedStale = !freshestLibrary.equals(packagedLibrary) && !sameContent(packagedLibrary, freshestLibrary);

        if (!forceDirect && !packagedStale) {
            if (tryNativeLoader()) {
                return;
            }
        }

        // Direct load of the newest library file. Used when the packaged artifact is stale or the
        // NativeLoader path failed, so the harness always exercises the code that was just built.
        //
        // The DLL is loaded from a private copy under out/native/, never from rust/target/release: on
        // Windows a loaded DLL is locked against writing, and the Rust owners rebuild that canonical
        // artifact while the harness runs. Copying first lets `cargo build --release` swap the canonical
        // file at any time without LNK1104.
        try {
            Path scratchDirectory = repo.resolve("rust/parity/out/native");
            Files.createDirectories(scratchDirectory);
            Path scratchLibrary = scratchDirectory.resolve(libraryName);
            Files.copy(freshestLibrary, scratchLibrary, StandardCopyOption.REPLACE_EXISTING);
            System.load(scratchLibrary.toAbsolutePath().toString());
            int libraryAbi = callNativeVersion();
            int expectedAbi = expectedAbiVersion();
            if (libraryAbi != expectedAbi) {
                status = "abi mismatch after direct load: library " + libraryAbi + ", jar " + expectedAbi;
                return;
            }
            markLoaderAvailable("direct load " + scratchLibrary);
            available = true;
            mode = packagedStale ? "direct-load (packaged artifact stale)" : "direct-load";
            loadedFrom = scratchLibrary.toAbsolutePath() + " (copy of " + freshestLibrary + ")";
            status = "direct System.load of a copy of " + freshestLibrary.getFileName() + " (abi " + libraryAbi + ")";
        } catch (Throwable failure) {
            status = "direct load failed: " + failure;
            available = false;
        }
    }

    private static boolean tryNativeLoader() {
        try {
            Class<?> loader = Class.forName("com.fox.ysmu.nativebridge.NativeLoader");
            loader.getMethod("ensureLoaded")
                .invoke(null);
            boolean loaded = (Boolean) loader.getMethod("isAvailable")
                .invoke(null);
            String loaderStatus = String.valueOf(
                loader.getMethod("status")
                    .invoke(null));
            if (!loaded) {
                status = "NativeLoader declined: " + loaderStatus;
                return false;
            }
            available = true;
            mode = "NativeLoader";
            loadedFrom = packagedLibrary.toAbsolutePath().toString();
            status = loaderStatus;
            return true;
        } catch (Throwable failure) {
            status = "NativeLoader path threw: " + rootCause(failure);
            return false;
        }
    }

    /** Reads the ABI version out of the library through {@code NativeLoader.nVersion()}. */
    private static int callNativeVersion() throws Exception {
        Class<?> loader = Class.forName("com.fox.ysmu.nativebridge.NativeLoader");
        Method method = loader.getDeclaredMethod("nVersion");
        method.setAccessible(true);
        Object value = method.invoke(null);
        return ((Number) value).intValue();
    }

    private static int expectedAbiVersion() throws Exception {
        Class<?> loader = Class.forName("com.fox.ysmu.nativebridge.NativeLoader");
        Field field = loader.getField("ABI_VERSION");
        return field.getInt(null);
    }

    /**
     * Makes the facades consider the layer available after a direct {@code System.load}: sets the
     * loader's private {@code attempted}/{@code available} and descriptive {@code status}.
     */
    private static void markLoaderAvailable(String description) throws Exception {
        Class<?> loader = Class.forName("com.fox.ysmu.nativebridge.NativeLoader");
        setPrivate(loader, "attempted", true);
        setPrivate(loader, "available", true);
        setPrivate(loader, "status", description);
    }

    private static void setPrivate(Class<?> type, String name, Object value) throws Exception {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (NoSuchFieldException ignored) {
            // The field disappeared (loader refactor); the facade tests below will show it.
        }
    }

    /**
     * Flips {@code Config.ENABLE_RUST_NATIVE}, which every facade checks through
     * {@code NativeLoader.moduleUsable}.
     *
     * @return the value the flag had before the call, so callers can restore it in a {@code finally}
     *         block; {@code true} (the shipped default) when the field cannot be reached at all
     */
    public static boolean setRustEnabled(boolean enabled) {
        try {
            Class<?> config = Class.forName("com.fox.ysmu.Config");
            Field field = config.getField("ENABLE_RUST_NATIVE");
            boolean previous = field.getBoolean(null);
            field.setBoolean(null, enabled);
            return previous;
        } catch (Throwable failure) {
            return true;
        }
    }

    public static boolean available() {
        return available;
    }

    /**
     * Whether {@code NativeLoader} has disabled one module after a native call threw (missing symbol,
     * panic guard). Used to tell "M4 is not in this artifact yet" apart from a real emitter bug.
     */
    public static boolean moduleDisabled(String module) {
        try {
            Class<?> loader = Class.forName("com.fox.ysmu.nativebridge.NativeLoader");
            Field field = loader.getDeclaredField("DISABLED_MODULES");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof java.util.Set && ((java.util.Set<?>) value).contains(module);
        } catch (Throwable failure) {
            return false;
        }
    }

    public static String mode() {
        return mode;
    }

    public static String status() {
        return status;
    }

    public static String loadedFrom() {
        return loadedFrom;
    }

    public static Path packagedLibrary() {
        return packagedLibrary;
    }

    public static Path freshestLibrary() {
        return freshestLibrary;
    }

    public static boolean packagedStale() {
        return packagedStale;
    }

    public static String platformTag() {
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

    public static String libraryFileName() {
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

    private static long lastModified(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** Byte-for-byte comparison; false when either file is missing. */
    private static boolean sameContent(Path first, Path second) {
        try {
            if (!Files.isRegularFile(first) || !Files.isRegularFile(second)) {
                return false;
            }
            if (Files.size(first) != Files.size(second)) {
                return false;
            }
            return Files.mismatch(first, second) == -1L;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.toString();
    }
}
