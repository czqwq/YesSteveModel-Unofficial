package com.fox.ysmu.client.texture;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.util.ResourceLocation;

/**
 * The mod's own texture release mechanism, ported from upstream's
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/texture/CustomTextureManager.java}.
 *
 * <p>One paragraph, the same as upstream's: a texture is registered with the <em>platform's</em> texture manager and
 * the mod hands out a holder. When the holder becomes unreachable, or is released explicitly, the registration enters
 * a <b>delayed</b> release - {@link #DEFAULT_REMOVAL_DELAY_TICKS} ticks (upstream {@code :18}) - and only after that
 * does the manager perform the platform's physical release. {@link #tick()} runs on the render thread, polls the
 * cleanup events the cleaner thread appended, and stops after {@link Registry#MAX_MILLI} ms of work (upstream
 * {@code :44}, {@code :99-131}). So the trigger is <b>reachability plus a grace delay</b>, never an idle-time
 * timestamp.
 *
 * <p>The class structure is upstream's, member for member:
 * <table>
 *   <caption>Upstream -&gt; this port</caption>
 *   <tr><td>{@code CustomTextureManager.register/release/tick} ({@code :24-41})</td>
 *       <td>the same three public entry points</td></tr>
 *   <tr><td>{@code Registry} with {@code registrations}/{@code cleanupEvents}/{@code pendingRemovals}/{@code counter}
 *       ({@code :43-146})</td><td>the same, same names</td></tr>
 *   <tr><td>{@code Host} + {@code MinecraftHost} ({@code :148-164})</td>
 *       <td>the same seam; {@link MinecraftHost} substitutes each platform call (see below)</td></tr>
 *   <tr><td>{@code TextureRegistration} holding a weak holder ({@code :166-185})</td>
 *       <td>the same</td></tr>
 *   <tr><td>{@code CleanupEvent}/{@code RemovalEvent} ({@code :187-191})</td><td>the same</td></tr>
 *   <tr><td>{@code TextureHolderImpl} ({@code :193-196})</td><td>the same</td></tr>
 *   <tr><td>{@code CleanerUtil.ref(holder, event, cleanupEvents::add)} ({@code :86})</td>
 *       <td>{@link CleanerUtil#ref} - the same {@code java.lang.ref.Cleaner} API, unchanged (see below)</td></tr>
 *   <tr><td>{@code host.register}/{@code host.release} ({@code :73}, {@code :122})</td>
 *       <td>the same two calls; only <em>what</em> they do differs (substitution, below)</td></tr>
 * </table>
 *
 * <h3>{@code java.lang.ref.Cleaner} is used as upstream uses it</h3>
 * <p>No substitution here. {@link CleanerUtil} is upstream's class on the same API, and it works because the build is
 * on JVM Downgrader ({@code gradle.properties}: {@code enableModernJavaSyntax = jvmDowngrader},
 * {@code downgradeTargetVersion = 8}): the source may use Java 9+ stdlib APIs, the downgrader rewrites them onto its
 * stubs, and the produced jar still carries JVM 8 class files. The mod's normal runtime is a modern JVM where
 * {@code Cleaner} is native. See {@link CleanerUtil} for the runtime-stub caveat.
 *
 * <h3>Substitution - what "physical release" is on 1.7.10</h3>
 * <p>Upstream's {@code MinecraftHost} is two calls into 1.20's {@code TextureManager}:
 * {@code register(id, texture)} and {@code release(id)} (upstream {@code :156-163}). 1.7.10's
 * {@code net.minecraft.client.renderer.texture.TextureManager} has no {@code release}: its nearest call is
 * {@code deleteTexture(ResourceLocation)} ({@code build/rfg/minecraft-src/java/net/minecraft/client/renderer/texture/TextureManager.java:153-161},
 * SRG {@code func_147645_c}), which frees the GL texture through
 * {@code TextureUtil.deleteTexture(glTextureId)} ({@code .../TextureUtil.java:38-41}, i.e. {@code GL11.glDeleteTextures})
 * but <b>leaves the id in {@code mapTextureObjects}</b> and leaves the object's own {@code glTextureId} positive,
 * whose next read regenerates a fresh GL name ({@code .../AbstractTexture.java:12-20}).
 *
 * <p>What this port uses instead is the platform's own per-texture release,
 * {@link AbstractTexture#deleteGlTexture()} ({@code .../AbstractTexture.java:22-29}), which is the same GL call
 * followed by resetting the id to {@code -1} - i.e. the same call {@code OuterFileTexture} already relied on before
 * this change ({@code OuterFileTexture.java:59}) and the exact GL-touching step vanilla's own
 * {@code TextureManager.deleteTexture} performs for its own textures. On top of it, {@link MinecraftHost} removes the
 * id from the manager's map, because that map is the <b>strong reference</b> that would otherwise keep the released
 * {@code OuterFileTexture} - and the image it decodes - alive for the rest of the session, and would re-upload it on
 * the next resource reload ({@code TextureManager.onResourceManagerReload}, {@code :163-175}). See
 * {@link TextureMapAccess} for that one step into the platform's private map, and for the access transformer that
 * replaced the reflection an earlier revision of this port used there.
 *
 * <h3>What this class does <em>not</em> do</h3>
 * <p>No {@code TEXTURE_LAST_USED} table, no {@code unloadIdleTextures}, no idle timestamp, no
 * {@code isUploaded()}/{@code freeGlTexture()}/{@code freeData()} methods on {@code OuterFileTexture}: upstream's one
 * mechanism is used, not the reference branch's second one. Release is driven by the holder becoming unreachable or
 * by an explicit {@link #release}, exactly as upstream drives it.
 *
 * @see ModelTextureRegistry the port's wiring: it owns the holders and drops them on the model-unload path
 */
public final class CustomTextureManager {

    /** Upstream {@code CustomTextureManager.java:18}: 10 s at 20 ticks, the re-registration grace. */
    private static final int DEFAULT_REMOVAL_DELAY_TICKS = 10 * 20;
    private static final Registry REGISTRY = new Registry(new MinecraftHost());

    private CustomTextureManager() {}

    /** Registers a texture with the default delay, returning its holder; see {@link Registry#register}. */
    public static TextureHolder register(AbstractTexture texture) {
        return register(texture, DEFAULT_REMOVAL_DELAY_TICKS);
    }

    /** Registers a texture with an explicit delay, returning its holder; see {@link Registry#register}. */
    public static TextureHolder register(AbstractTexture texture, int removingDelayTicks) {
        return REGISTRY.register(texture, removingDelayTicks);
    }

    /**
     * Releases a texture's registration now, starting its delayed physical release; see {@link Registry#release}.
     * <p>
     * This is upstream's second trigger and the one the port uses: upstream's reachability trigger cannot fire while
     * the platform's texture map holds the texture alive (see {@link ModelTextureRegistry}).
     */
    public static void release(AbstractTexture texture) {
        REGISTRY.release(texture);
    }

    /** Drains cleanup events and performs due releases; see {@link Registry#tick}. */
    public static void tick() {
        REGISTRY.tick();
    }

    /**
     * Upstream's {@code Registry} ({@code CustomTextureManager.java:43-146}): the per-texture registrations, the
     * cleanup-event queue filled by the reachability trigger, and the removal queue the 20 ms budget is spent on.
     */
    static final class Registry {

        /** Upstream {@code :44}: the per-tick time budget of the physical-release loop. */
        static final int MAX_MILLI = 20;

        private final Host host;
        /**
         * Identity, like upstream ({@code :47-48}): two distinct texture objects that happen to answer
         * {@code equals} are two registrations.
         */
        private final IdentityHashMap<AbstractTexture, TextureRegistration> registrations = new IdentityHashMap<>();
        private final Queue<CleanupEvent> cleanupEvents = new ConcurrentLinkedQueue<>();
        private final Queue<RemovalEvent> pendingRemovals = new ArrayDeque<>();
        private long counter;

        Registry(Host host) {
            this.host = host;
        }

        /**
         * Ensures {@code texture} is registered with the platform and returns its holder.
         *
         * <p>Re-registering <b>while the previous holder is still reachable</b> returns that same holder and performs
         * no second platform registration (upstream {@code :57-68}); once the holder is unreachable (or was released
         * explicitly) a fresh activation is handed out and the platform registration is re-issued.
         */
        TextureHolder register(AbstractTexture texture, int removingDelayTicks) {
            TextureRegistration registration = registrations.get(texture);
            if (registration == null) {
                registration = new TextureRegistration(new TextureRegistrationState<>(nextId()));
                registrations.put(texture, registration);
            } else {
                TextureHolderImpl current = registration.holder();
                if (current != null && registration.state.isActive(current.nonce())) {
                    return current;
                }
            }

            // Raw outer type on purpose: `Activation` is a static nested record, and Java refuses to select a static
            // member through a parameterized type (`TextureRegistrationState<ResourceLocation>.Activation` does not
            // compile). Upstream writes `var`; this tree's `com/fox/ysmu` code does not use `var`, and its own
            // `CleanupEvent`/`RemovalEvent`/`TextureHolderImpl` records name `TextureRegistrationState.Token` the same
            // raw way.
            TextureRegistrationState.Activation activation = registration.state.activate(removingDelayTicks);
            if (!activation.registered()) {
                try {
                    host.register(registration.state.id(), texture);
                } catch (RuntimeException | Error failure) {
                    // Upstream :71-78: a failed platform registration must not leave the slot active, and a slot
                    // nothing was registered for is discarded rather than waiting out a delay.
                    discardIfUnregistered(
                        texture,
                        registration,
                        registration.state.release(activation.token()));
                    throw failure;
                }
                if (!registration.state.markRegistered(activation.token())) {
                    throw new IllegalStateException("Standalone texture registration lost ownership");
                }
            }

            TextureHolderImpl holder = new TextureHolderImpl(registration.state.id(), activation.token());
            registration.holder(holder);
            // The reachability trigger: upstream's own call at :86. The event carries the nonce, so a stale event
            // cannot release a later activation of the same texture. Upstream discards the returned Cleanable; it is
            // kept here only so the registered action can be driven synchronously by a test
            // (see Registry#runCleanerCallback). Keeping it does not make the holder reachable: the Cleaner holds a
            // phantom reference to the referent, and the action it carries captures only the event queue and the
            // CleanupEvent, never the holder.
            registration.cleanerRegistration = CleanerUtil.ref(
                holder,
                new CleanupEvent(texture, activation.token()),
                cleanupEvents::add);
            return holder;
        }

        /**
         * Releases {@code texture} explicitly (upstream {@code :90-97}): the holder is dropped, so the reachability
         * trigger cannot fire a second time, and the current activation is released - which either discards a slot
         * the platform never registered or starts the delayed release.
         * <p>
         * A texture this registry does not know, or one whose slot is already released and pending, is ignored.
         */
        void release(AbstractTexture texture) {
            TextureRegistration registration = registrations.get(texture);
            if (registration == null) {
                return;
            }
            registration.clearHolder();
            discardIfUnregistered(texture, registration, registration.state.releaseCurrent());
        }

        /**
         * One render-thread step (upstream {@code :99-131}): take the cleanup events the reachability trigger produced,
         * ask every registration whether its delay has run out, and then perform the due physical releases within
         * {@link #MAX_MILLI} ms.
         * <p>
         * Upstream's {@code Cleaner} runs its own callback on its own cleaner thread and appends to the
         * concurrent {@code cleanupEvents} queue; this method only {@code poll}s it, exactly like upstream
         * ({@code :101}). No drain step is needed, so the queue is safe against a cleanup firing mid-tick.
         */
        void tick() {
            CleanupEvent cleanup;
            while ((cleanup = cleanupEvents.poll()) != null) {
                TextureRegistration registration = registrations.get(cleanup.texture());
                if (registration != null) {
                    discardIfUnregistered(
                        cleanup.texture(),
                        registration,
                        registration.state.release(cleanup.token()));
                }
            }

            // 1. Count every inactive registration's delay down by one tick and queue the ones that reached zero.
            //    Iterated over a copy because step 2 removes from the map.
            List<AbstractTexture> known = new ArrayList<>(registrations.keySet());
            for (AbstractTexture texture : known) {
                TextureRegistration registration = registrations.get(texture);
                if (registration == null) {
                    continue;
                }
                Optional<TextureRegistrationState.Token> due = registration.state.tickRemoval();
                if (due.isPresent()) {
                    pendingRemovals.add(new RemovalEvent(texture, due.get()));
                }
            }

            // 2. The physical releases, bounded by the same 20 ms budget upstream uses so one tick cannot stall.
            long startedAt = System.nanoTime();
            while (true) {
                RemovalEvent removal = pendingRemovals.poll();
                if (removal == null) {
                    return;
                }
                TextureRegistration registration = registrations.get(removal.texture());
                if (registration != null && registration.state.shouldRelease(removal.token())) {
                    host.release(registration.state.id());
                    // markReleased is idempotent, so a removal queued twice releases once (upstream :123-126).
                    if (registration.state.markReleased(removal.token())) {
                        registrations.remove(removal.texture());
                    }
                }
                if ((System.nanoTime() - startedAt) / 1_000_000L >= MAX_MILLI) {
                    return;
                }
            }
        }

        /** Current registration count; diagnostics and tests only. */
        int size() {
            return registrations.size();
        }

        /**
         * Runs, synchronously, the cleanup action the Cleaner registered for {@code texture}'s current activation.
         *
         * <p><b>Test seam.</b> Upstream discards the {@link java.lang.ref.Cleaner.Cleanable} that
         * {@link CleanerUtil#ref} returns and lets the cleaner thread fire the action once the holder becomes
         * unreachable - which a unit JVM cannot schedule reliably. {@code System.gc()} clears the <em>weak</em>
         * holder reference a test can watch, but nothing guarantees a further collection in which the cleaner's
         * <em>phantom</em> reference is enqueued and its action run; the original version of this test polled
         * {@link #tick()} 200 times with a 5 ms sleep and still saw no release (see
         * {@code .agent/phase37-unit-jvm-test-repair.md}). {@code Cleanable.clean()} executes exactly the registered
         * action - the one that appends the {@link CleanupEvent} {@link #tick()} drains - so the chain
         * "callback -&gt; cleanup event -&gt; delayed removal -&gt; one host release" is pinned without GC. Production
         * never calls this.
         */
        void runCleanerCallback(AbstractTexture texture) {
            TextureRegistration registration = registrations.get(texture);
            if (registration == null || registration.cleanerRegistration == null) {
                throw new IllegalStateException("no cleaner registration for " + texture);
            }
            registration.cleanerRegistration.clean();
        }

        private void discardIfUnregistered(AbstractTexture texture, TextureRegistration registration,
            TextureRegistrationState.ReleaseResult result) {
            if (result == TextureRegistrationState.ReleaseResult.DISCARD
                && registrations.get(texture) == registration) {
                registrations.remove(texture);
            }
        }

        /**
         * The slot's identity, allocated once per registration entry (upstream {@code :142-145}).
         * <p>
         * Upstream returns {@code new ResourceLocation(MOD_ID, "textures/" + ++counter)} and registers the texture
         * with <em>that</em> id, because in 1.20 an {@code AbstractTexture} is addressed by whatever id the manager
         * was given. This port keeps the model's own id instead - the id under which
         * {@code ClientModelManager.registerTexture} already registers it and the id every render site already binds -
         * so this counter only names the registration entry and is never handed to the platform. Keeping the real id
         * is also what lets the port's release step find the manager's map entry for the texture it releases; with a
         * synthetic id it would have to remember the mapping for no benefit. See
         * {@code .agent/phase34-texture-release.md}.
         */
        private ResourceLocation nextId() {
            return new ResourceLocation("ysmu", "textures/" + ++counter);
        }
    }

    /**
     * The platform seam (upstream {@code :148-152}): everything 1.7.10-specific is behind these two methods, so the
     * registry itself is testable against a fake platform.
     */
    interface Host {

        /** Publishes {@code texture} under {@code id}, as upstream's {@code TextureManager.register} does. */
        void register(ResourceLocation id, AbstractTexture texture);

        /** Physically releases the texture published under {@code id}, as upstream's {@code TextureManager.release} does. */
        void release(ResourceLocation id);
    }

    /**
     * 1.7.10's platform calls (upstream {@code :154-164}).
     *
     * <p>{@code register} is {@code TextureManager.loadTexture}, which uploads immediately and returns {@code false}
     * when it substituted the game's shared missing texture. The return value is tracked as
     * {@code ownsGlTexture}, because that is exactly the "is this id ours to delete" question the old
     * {@code ClientModelManager.UPLOADED_TEXTURES} set answered: deleting the shared missing texture's GL name would
     * {@code blank every missing texture in the game} - the regression the old set's comment warned about, kept here
     * by construction. This mirrors upstream's {@code MinecraftHost} delegating to a platform whose {@code release}
     * only ever sees textures it registered.
     *
     * <p>{@code release} is {@code AbstractTexture.deleteGlTexture} plus the map-entry removal described in the class
     * javadoc. It is idempotent: a second call sees {@code glTextureId == -1} and only retries the map removal.
     */
    static final class MinecraftHost implements Host {

        /** Whether the id currently in the platform map holds a GL texture this mod uploaded. */
        private final java.util.Set<ResourceLocation> ownsGlTexture = new java.util.HashSet<>();

        @Override
        public void register(ResourceLocation id, AbstractTexture texture) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            if (minecraft == null) {
                return;
            }
            net.minecraft.client.renderer.texture.TextureManager manager = minecraft.getTextureManager();
            if (ownsGlTexture.contains(id)) {
                // Re-registering the same id replaces the map entry, so the previous GL texture would leak; upstream
                // never re-registers the same id because its ids are fresh per registration.
                manager.deleteTexture(id);
            }
            if (manager.loadTexture(id, texture)) {
                ownsGlTexture.add(id);
            } else {
                ownsGlTexture.remove(id);
            }
        }

        @Override
        public void release(ResourceLocation id) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            if (minecraft == null) {
                return;
            }
            net.minecraft.client.renderer.texture.TextureManager manager = minecraft.getTextureManager();
            net.minecraft.client.renderer.texture.ITextureObject current = manager.getTexture(id);
            if (current instanceof AbstractTexture && ownsGlTexture.remove(id)) {
                // The platform's own per-texture release: GL11.glDeleteTextures + glTextureId = -1.
                ((AbstractTexture) current).deleteGlTexture();
            }
            TextureMapAccess.remove(manager, id);
        }
    }

    /**
     * One texture's registration (upstream {@code :166-185}): the state machine plus a <b>weak</b> view of the holder.
     * <p>
     * The weakness is the point of the design - the registry must not be a strong reference to the holder, or the
     * reachability trigger could never fire. {@link #holder()} therefore answers {@code null} once the caller that
     * received the holder has dropped it.
     */
    private static final class TextureRegistration {

        private final TextureRegistrationState<ResourceLocation> state;
        /** The Cleaner registration for the current activation; see {@link Registry#runCleanerCallback}. */
        private Cleaner.Cleanable cleanerRegistration;
        private WeakReference<TextureHolderImpl> holder;

        private TextureRegistration(TextureRegistrationState<ResourceLocation> state) {
            this.state = state;
        }

        private TextureHolderImpl holder() {
            return holder == null ? null : holder.get();
        }

        private void holder(TextureHolderImpl holder) {
            this.holder = new WeakReference<>(holder);
        }

        private void clearHolder() {
            holder = null;
        }
    }

    /** A holder became unreachable; run its release at the next tick (upstream {@code :187-188}). */
    private record CleanupEvent(AbstractTexture texture, TextureRegistrationState.Token token) {}

    /** A registration's delay ran out; perform its physical release (upstream {@code :190-191}). */
    private record RemovalEvent(AbstractTexture texture, TextureRegistrationState.Token token) {}

    /** The holder handed to the owner; it is what the owner keeps and drops (upstream {@code :193-196}). */
    private record TextureHolderImpl(ResourceLocation id, TextureRegistrationState.Token nonce)
        implements TextureHolder {}
}
