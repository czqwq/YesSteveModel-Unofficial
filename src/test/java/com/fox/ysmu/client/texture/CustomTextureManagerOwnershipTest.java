package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * {@link CustomTextureManager} driven against a fake platform, ported from upstream's
 * {@code tmp/YesSteveModel-dev-1.20/src/test/java/com/elfmcys/ysm/client/texture/CustomTextureManagerOwnershipTest.java}
 * (its four cases) plus the port-specific half: the cleanup action the reachability trigger registers is the one
 * {@link CustomTextureManager.Registry#tick()} drains into exactly one host release. The GC-timing half - the cleaner
 * thread actually firing that action once the holder becomes unreachable - is not unit-testable; see
 * {@link #theCleanerCallbackDrivesExactlyOneDelayedHostRelease()} and
 * {@code .agent/phase37-unit-jvm-test-repair.md}.
 *
 * <p>The fake {@code Host} plays the platform: it uploads on registration (upstream's {@code TracingHost} calls
 * {@code texture.load(null)} at {@code :98}; 1.7.10's equivalent is
 * {@code AbstractTexture.loadTexture(IResourceManager)}, hence the null resource manager), it counts registrations and
 * releases, and it releases the texture the way 1.7.10's {@code MinecraftHost.release} does, through
 * {@code AbstractTexture.deleteGlTexture()}.
 *
 * <p>Nothing here needs a client: {@link CustomTextureManager.Registry} holds no Minecraft reference - only the
 * production {@code MinecraftHost} does - and {@link TextureHolder} is a plain interface.
 */
class CustomTextureManagerOwnershipTest {

    @Test
    void everyTextureGetsItsOwnRegistrationAndId() {
        TracingHost host = new TracingHost();
        CustomTextureManager.Registry registry = new CustomTextureManager.Registry(host);
        TracingTexture preview = new TracingTexture();
        TracingTexture avatar = new TracingTexture();
        TracingTexture packIcon = new TracingTexture();

        TextureHolder previewHolder = registry.register(preview, 0);
        TextureHolder avatarHolder = registry.register(avatar, 0);
        TextureHolder packHolder = registry.register(packIcon, 0);

        assertEquals(1, preview.loads.get(), "registration publishes the texture immediately");
        assertEquals(1, avatar.loads.get());
        assertEquals(1, packIcon.loads.get());
        assertNotEquals(previewHolder.id(), avatarHolder.id());
        assertNotEquals(avatarHolder.id(), packHolder.id());
        assertEquals(3, registry.size());
    }

    @Test
    void reuseWhileTheHolderIsAliveKeepsOneHolderAndOneHostRegistration() {
        TracingHost host = new TracingHost();
        CustomTextureManager.Registry registry = new CustomTextureManager.Registry(host);
        TracingTexture texture = new TracingTexture();

        TextureHolder first = registry.register(texture, 0);
        TextureHolder reused = registry.register(texture, 0);

        assertSame(first, reused, "a live registration is handed back, not replaced");
        assertEquals(1, host.registrations.get());
        assertEquals(1, texture.loads.get());
    }

    @Test
    void replacementBeforeDelayedReleaseKeepsHostOwnershipAndIdentity() {
        TracingHost host = new TracingHost();
        CustomTextureManager.Registry registry = new CustomTextureManager.Registry(host);
        TracingTexture texture = new TracingTexture();
        TextureHolder old = registry.register(texture, 0);

        registry.release(texture);
        TextureHolder replacement = registry.register(texture, 0);
        registry.tick();

        assertNotSame(old, replacement);
        assertEquals(old.id(), replacement.id());
        assertEquals(1, host.registrations.get());
        assertEquals(0, host.releases.get(), "the re-registration cancelled the pending removal");

        registry.release(texture);
        registry.tick();
        assertEquals(1, host.releases.get());
        assertEquals(1, texture.closes.get());
    }

    @Test
    void configuredDelayDefersPhysicalHostRelease() {
        TracingHost host = new TracingHost();
        CustomTextureManager.Registry registry = new CustomTextureManager.Registry(host);
        TracingTexture texture = new TracingTexture();
        registry.register(texture, 1);

        registry.release(texture);
        registry.tick();
        assertEquals(0, host.releases.get(), "one tick is not enough for a 1-tick delay");

        registry.tick();
        assertEquals(1, host.releases.get());
        assertEquals(1, texture.closes.get());
        assertEquals(0, registry.size(), "a fully released registration is forgotten");
    }

    /** A texture the registry never saw cannot be released, and releasing twice releases once. */
    @Test
    void releaseIsIgnoredForAnUnknownTextureAndIdempotentOtherwise() {
        TracingHost host = new TracingHost();
        CustomTextureManager.Registry registry = new CustomTextureManager.Registry(host);
        TracingTexture texture = new TracingTexture();

        registry.release(texture);
        assertEquals(0, host.releases.get(), "an unknown texture has no registration to release");

        registry.register(texture, 0);
        registry.release(texture);
        registry.release(texture);
        registry.tick();
        registry.tick();

        assertEquals(1, host.releases.get(), "the second explicit release must not queue a second removal");
        assertEquals(1, texture.closes.get());
    }

    /**
     * The cleaner callback's half of the mechanism, pinned deterministically.
     *
     * <p>Upstream registers a {@code java.lang.ref.Cleaner} action that appends a cleanup event to the registry's
     * concurrent queue ({@code Registry.register} via {@code CleanerUtil.ref}), and
     * {@link CustomTextureManager.Registry#tick()} drains that queue. <b>Scheduling</b> that action is the cleaner
     * thread's job and is not unit-testable here: {@code System.gc()} clears the <em>weak</em> holder reference a
     * test's probe can watch, but nothing guarantees a further collection in which the cleaner's <em>phantom</em>
     * reference is enqueued and its action run - the previous version of this test polled {@code tick()} 200 times
     * with a 5 ms sleep and still saw no release. {@link java.lang.ref.Cleaner.Cleanable#clean()} runs exactly the
     * registered action synchronously, so the chain "callback -&gt; cleanup event -&gt; delayed removal -&gt; one host
     * release" is pinned without GC.
     *
     * <p><b>Not covered</b> (recorded, not approximated): that the cleaner thread really fires the action once the
     * holder becomes unreachable, and that the registry holds the holder only weakly - which is what lets it become
     * unreachable at all. Those are the GC-timing half; see {@code .agent/phase37-unit-jvm-test-repair.md}.
     */
    @Test
    void theCleanerCallbackDrivesExactlyOneDelayedHostRelease() {
        TracingHost host = new TracingHost();
        CustomTextureManager.Registry registry = new CustomTextureManager.Registry(host);
        TracingTexture texture = new TracingTexture();

        registry.register(texture, 1);
        assertEquals(0, host.releases.get(), "registering with the platform releases nothing by itself");

        // Run the action the Cleaner registered for this texture, instead of waiting for the cleaner thread.
        registry.runCleanerCallback(texture);

        registry.tick();
        assertEquals(0, host.releases.get(), "the callback queues the removal; the 1-tick delay still applies");
        registry.tick();
        assertEquals(1, host.releases.get(), "the drained cleanup event becomes exactly one host release");
        assertEquals(1, texture.closes.get());
        assertEquals(0, registry.size(), "a fully released registration is forgotten");
    }

    /** The fake platform: publishes, counts, and physically releases by closing the texture. */
    private static final class TracingHost implements CustomTextureManager.Host {

        private final Map<ResourceLocation, AbstractTexture> textures = new HashMap<>();
        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public void register(ResourceLocation id, AbstractTexture texture) {
            registrations.incrementAndGet();
            try {
                texture.loadTexture(null);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            textures.put(id, texture);
        }

        @Override
        public void release(ResourceLocation id) {
            releases.incrementAndGet();
            AbstractTexture texture = textures.remove(id);
            if (texture != null) {
                // The port's platform half: AbstractTexture.deleteGlTexture(), i.e. the texture frees its own GL name.
                texture.deleteGlTexture();
            }
        }
    }

    /** A texture that records how often the platform published it and how often it was released. */
    private static final class TracingTexture extends AbstractTexture {

        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void loadTexture(IResourceManager resourceManager) throws IOException {
            loads.incrementAndGet();
        }

        /**
         * No GL in a unit test: the real implementation's first read would call {@code GL11.glGenTextures()} through
         * {@code TextureUtil}, which a headless JVM cannot do, so a published texture reports a fake positive name
         * exactly as {@code MinecraftHost} requires before it will release one.
         */
        @Override
        public int getGlTextureId() {
            return loads.get() > 0 ? 1 : -1;
        }

        @Override
        public void deleteGlTexture() {
            if (getGlTextureId() != -1) {
                closes.incrementAndGet();
            }
        }
    }
}
