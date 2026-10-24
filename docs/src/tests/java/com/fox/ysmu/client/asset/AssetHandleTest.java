package com.fox.ysmu.client.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The {@link AssetHandle} facade and the release-mode contract of {@link AssetCache}, on a synthetic clock
 * (no Minecraft, no GL). {@link AssetHandle} is what business code holds, so its three delegations -
 * {@code get} / {@code touch} / {@code isPending} - and the mode the cache hands to
 * {@link AssetProvider#release} are the parts a caller can observe without a client.
 */
class AssetHandleTest {

    private static final class RecordingProvider implements AssetProvider<String, String> {

        final List<String> released = new ArrayList<>();
        /** {@code release()} 转发给 provider 的 mode，按调用顺序。 */
        final List<ReleaseMode> releaseModes = new ArrayList<>();
        ReleaseMode defaultMode = ReleaseMode.GPU_ONLY;

        @Override
        public String load(String key) {
            return key;
        }

        @Override
        public void apply(String key, String value) {
            // no-op
        }

        @Override
        public void release(String key, String value, ReleaseMode mode) {
            released.add(key);
            releaseModes.add(mode);
        }

        @Override
        public ReleaseMode defaultReleaseMode() {
            return defaultMode;
        }

        @Override
        public long weight(String key, String value) {
            return 0L;
        }
    }

    @Test
    void handleExposesItsKeyAndReportsAnUnregisteredKeyAsPending() {
        AssetCache<String, String> cache = new AssetCache<>(new RecordingProvider(), 0L);
        AssetHandle<String, String> handle = new AssetHandle<>(cache, "ysmu:a/main");

        assertEquals("ysmu:a/main", handle.key());
        assertTrue(handle.isPending(), "a key that was never registered is pending, not 'known missing'");
    }

    /** {@code get()} on a READY entry returns the value and must not start a background load. */
    @Test
    void getReturnsTheRegisteredValueAndClearsPending() {
        AssetCache<String, String> cache = new AssetCache<>(new RecordingProvider(), 0L);
        cache.register("ysmu:a/main", "geo");
        AssetHandle<String, String> handle = new AssetHandle<>(cache, "ysmu:a/main");

        assertEquals("geo", handle.get());
        assertFalse(handle.isPending());
    }

    /**
     * {@code touch()} is the handle's only way to keep its own entry resident. Both entries start with the same
     * synthetic age (10 s) and the window is 5 s, so the touched entry (idle ~0) survives and the untouched one
     * (idle ~10 s) is released - with several seconds of slack against a stalled test JVM.
     */
    @Test
    void touchKeepsItsOwnEntryOutOfIdleEviction() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        long now = System.currentTimeMillis();
        cache.register("ysmu:a/main", "geo", now - 10_000L);
        cache.register("ysmu:b/main", "geo", now - 10_000L);

        new AssetHandle<>(cache, "ysmu:a/main").touch();
        cache.evict(System.currentTimeMillis() + 100L, 5_000L, 0L);

        assertEquals(Collections.singletonList("ysmu:b/main"), provider.released);
        assertEquals(1, cache.size(), "the touched handle's entry stays resident");
    }

    /**
     * {@code release()} forwards the provider's own {@link AssetProvider#defaultReleaseMode() default mode},
     * while {@code clear()} always drops the heap - the two are not interchangeable.
     */
    @Test
    void releaseUsesTheProviderDefaultModeAndClearAlwaysDropsHeap() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:a/main", "geo");
        cache.register("ysmu:b/main", "geo");

        cache.release("ysmu:a/main");
        assertEquals(Collections.singletonList("ysmu:a/main"), provider.released,
            "release() must hand the released key to the provider");
        assertEquals(Collections.singletonList(ReleaseMode.GPU_ONLY), provider.releaseModes,
            "release() must not hardcode a mode");

        cache.clear();
        assertEquals(Arrays.asList("ysmu:a/main", "ysmu:b/main"), provider.released);
        assertEquals(2, provider.releaseModes.size());
        assertEquals(ReleaseMode.DROP_HEAP, provider.releaseModes.get(1),
            "clear() releases the heap regardless of the provider's default");
    }
}
