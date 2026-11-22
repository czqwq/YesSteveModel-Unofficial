package com.fox.ysmu.nativebridge;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * Rust geometry bake (module {@code geometry}).
 *
 * <p>
 * Replaces, when available, the Java half of {@code ClientModelManager.buildGeo(ResourceLocation, byte[])}:
 * {@code Converter.fromJsonString} (Jackson) plus {@code RawGeometryTree.parseHierarchy} plus
 * {@code GeoBuilder.constructGeoModel}. The native bake parses the geometry JSON and produces a compact
 * little-endian blob that {@link NativeGeometryReader} materialises into the engine's existing
 * {@code GeoModel}/{@code GeoBone}/{@code GeoCube}/{@code GeoQuad}/{@code GeoVertex} objects, so every
 * consumer of the object graph (animation processor, renderer, locators) is unchanged.
 *
 * <p>
 * The reference is upstream's {@code natives/render/NativeBakedModel.java}: a bake returns an immutable
 * native model, can serialize it into a content-addressed cache, and keeps the geometry in native memory
 * for the per-frame renderer. Here the same handle serves {@link NativeDrawGroups}.
 *
 * <p>
 * Handles are owned explicitly: the client geometry publish path closes the wrapper when the geometry is
 * released ({@code CustomPlayerModel.onGeoModelReleased}), and {@link #releaseAll()} is the connection
 * clear / shutdown sweep. A handle is a plain {@code long} into the Rust registry; {@code 0} means
 * "no native geometry", which every caller treats as a request to run the Java path.
 */
public final class NativeGeometry {

    private static final String MODULE = "geometry";
    private static final Set<Long> LIVE_HANDLES = ConcurrentHashMap.newKeySet();
    private static final int[] EMPTY_INFO = new int[0];
    private static final Set<Long> EMPTY_KEEP = Collections.emptySet();

    private NativeGeometry() {}

    /** Whether native baking can be attempted at all. */
    public static boolean isUsable() {
        return NativeLoader.moduleUsable(MODULE);
    }

    /**
     * Bakes one geometry file.
     *
     * @param geometryJson  the raw model JSON (the same bytes
     *                      {@code Converter.fromJsonString} receives)
     * @param formatVersion {@code 0} when the caller has not parsed the document: the native bake then
     *                      reads and validates the declared {@code format_version} itself, because the
     *                      Java loader only has raw bytes and parsing JSON to find the version would
     *                      defeat the point of baking natively. A non-zero value is the already-validated
     *                      version as {@code FormatVersion.ordinal() + 1} and skips that gate; no current
     *                      caller uses it.
     * @return a native handle, or {@code 0} when the native module declines or is unavailable; the
     *         caller must then use the Java parse+bake path
     */
    public static long bake(byte[] geometryJson, int formatVersion) {
        if (geometryJson == null || geometryJson.length == 0 || !NativeLoader.moduleUsable(MODULE)) {
            return 0L;
        }
        try {
            long handle = bakeNative(geometryJson, formatVersion);
            if (handle != 0L) {
                LIVE_HANDLES.add(handle);
            }
            return handle;
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return 0L;
        }
    }

    /**
     * Reads the materialisation blob of a native geometry handle.
     *
     * <p>
     * The blob is the compact little-endian encoding of the engine object graph the bake produced; its
     * layout is defined by the Rust writer ({@code rust/ysmu_native/src/geometry/blob.rs}) and read by
     * {@link NativeGeometryReader}, which is the only Java place that knows it. The Java caller cancels the
     * handle after materialising, because the handle owns the render-ready copy of the same geometry and
     * nothing else in the Java path needs it before the renderer adopts the model.
     *
     * @return the blob bytes, or {@code null} when the handle is invalid, the native module is unavailable,
     *         or the native side returned an inconsistent result (the caller then uses the Java bake)
     */
    public static byte[] blob(long handle) {
        if (handle == 0L || !NativeLoader.moduleUsable(MODULE)) {
            return null;
        }
        try {
            int size = blobSizeNative(handle);
            if (size <= 0) {
                return null;
            }
            byte[] destination = new byte[size];
            int written = readBlobNative(handle, destination);
            if (written == size) {
                return destination;
            }
            if (written > 0) {
                // blobSize and readBlob disagree: a contract violation, not an invalid handle, so the
                // module is turned off for the session instead of producing a corrupt model next time.
                NativeLoader.onModuleFailure(
                    MODULE,
                    new IllegalStateException("native geometry blob size changed between calls: " + size + " vs " + written));
            }
            return null;
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return null;
        }
    }

    /**
     * Counts and flags of a native geometry handle, for the caller's consistency check against the
     * materialised model: {@code [boneCount, cubeCount, quadCount, flags]}.
     *
     * @return the four values, or an empty array when the handle is invalid or the module is unavailable
     */
    public static int[] info(long handle) {
        if (handle == 0L || !NativeLoader.moduleUsable(MODULE)) {
            return EMPTY_INFO;
        }
        try {
            int[] info = infoNative(handle);
            return info == null ? EMPTY_INFO : info;
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
            return EMPTY_INFO;
        }
    }

    /**
     * The native handle a baked model carries, or {@code 0} when it was built by the Java path.
     *
     * <p>
     * This is the renderer's gate for the native fast path ({@code IGeoRenderer}): only a model from
     * {@link #bake} and materialised by {@link NativeGeometryReader} has a live handle, while a model read
     * back from the baked-geometry cache has {@code 0} because the field is transient.
     */
    public static long handleOf(GeoModel model) {
        return model == null ? 0L : model.nativeHandle;
    }

    /** Releases one native geometry handle; safe to call twice or with {@code 0}. */
    public static void release(long handle) {
        if (handle == 0L || !LIVE_HANDLES.remove(handle)) {
            return;
        }
        try {
            freeNative(handle);
        } catch (Throwable failure) {
            NativeLoader.onModuleFailure(MODULE, failure);
        }
    }

    /** Releases every handle this session still owns; called on connection clear and shutdown. */
    public static void releaseAll() {
        releaseAllExcept(EMPTY_KEEP);
    }

    /**
     * Releases every live handle except the ones in {@code keepHandles}.
     *
     * <p>
     * This is the orphan sweep: the geometry release path frees the handles of the models it drops, but a
     * build that was completed and then discarded (stale connection generation, replaced payload, failed
     * publish) never reaches the engine's geometry cache, so nothing else can see its handle. Called on the
     * client thread once per catalogue clear, with the handles of the geometry still installed in the
     * engine cache as the keep set - notably the pinned built-in default, whose geometry outlives the
     * connection.
     *
     * <p>
     * Idempotent by construction: {@link #release(long)} removes the handle from the live set before the
     * native free, so a second sweep (or a model's own release racing it) is a no-op and a handle can never
     * be freed twice. A native free that throws has already disabled the module for the session; that one
     * registry entry then lives until the process exits rather than being retried.
     *
     * @param keepHandles handles that are still owned by a live model; must not be {@code null}
     */
    public static void releaseAllExcept(Set<Long> keepHandles) {
        Objects.requireNonNull(keepHandles, "keepHandles");
        for (long handle : LIVE_HANDLES.toArray(new Long[0])) {
            if (!keepHandles.contains(handle)) {
                release(handle);
            }
        }
    }

    /**
     * Implemented by the M3 milestone of {@code .agent/phase78-rust-native-modules.md}; {@code 0} means
     * the input was declined (unsupported layout, malformed JSON) and the caller must use Java.
     */
    private static native long bakeNative(byte[] geometryJson, int formatVersion);

    /** Implemented by the M3 milestone; {@code 0} for an invalid handle. */
    private static native int blobSizeNative(long handle);

    /** Implemented by the M3 milestone; returns bytes written, {@code -1} when {@code dst} is too small. */
    private static native int readBlobNative(long handle, byte[] destination);

    /** Implemented by the M3 milestone; {@code [bones, cubes, quads, flags]} or {@code null}. */
    private static native int[] infoNative(long handle);

    /** Implemented by the M3 milestone; only called for handles {@link #bakeNative} returned. */
    private static native void freeNative(long handle);
}
