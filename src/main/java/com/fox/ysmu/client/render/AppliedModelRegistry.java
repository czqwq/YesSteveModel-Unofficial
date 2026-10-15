package com.fox.ysmu.client.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

/**
 * The model each rendered entity is currently <b>shown with</b>: the one whose geometry and texture are on screen
 * right now, plus the texture that was bound with it.
 *
 * <h3>Why this exists (and what upstream's equivalent is)</h3>
 * <p>Upstream keeps this per entity in the entity's own resource binding: {@code EntityModelBinding#resourceHolder}
 * is the installed ("applied") render target, and a new request replaces it <b>only once it is loaded</b>
 * ({@code EntityModelBinding#synchronize:145-167} - {@code installReady} on {@code AcquireResult.Ready}, and the
 * fallback installed only {@code if (resourceHolder == null)}), while {@code CustomEntity#applyBoundResource:133-145}
 * swaps {@code currentModelRenderTarget} under the same {@code isLoaded()} gate. Every render query then reads that
 * applied target - geometry, texture, animations, scale - so while a newly selected model is still
 * <i>Pending</i> or has <i>Failed</i>, the entity keeps drawing the model it already had. Its own test states the
 * contract: {@code EntityModelBindingTest#pendingModelReplacementKeepsRequestedTextureWhileOldVariantRemainsVisible}.
 * Upstream's product contract words it as "a consumer may keep the old ready lease while the new authoritative target
 * is Pending or Failed; the humanoid uses the intrinsic default only when it has <b>no</b> old lease"
 * ({@code docs/architecture/model-management/failure-and-recovery.md:36}).
 *
 * <p>This port has no per-entity binding: one {@code CustomPlayerEntity} animatable and one renderer are shared by
 * every entity in the frame, so "what this entity is currently shown with" has nowhere to live on the render side and
 * the animatable is deliberately re-seeded from the entity on every draw (otherwise one entity would be drawn with
 * another's model - the regression {@code CustomPlayerRenderer#applyEntityModel} exists to prevent). This registry is
 * the missing per-entity record: the renderer writes it after a model is really drawn, and reads it at the start of
 * the next draw of the same entity so a Pending or Failed request can keep the applied model instead of dropping to
 * the built-in default.
 *
 * <p>Keyed by the tracked entity id, like {@link com.fox.ysmu.data.NPCData} and for the same 1.7.10 reason: ids are
 * synced with the spawn packet, never reused, and identical on both sides, whereas a non-player entity's UUID is
 * generated per side and never synced. The backing map is concurrent because a packet thread may write the overrides
 * the render thread reads, exactly as that registry's map is.
 *
 * <p>Lifetime: per connection. {@code ClientModelManager#clearRuntimeModelCaches} drops it with the model list it
 * describes (that is also upstream's rule - a catalog that no longer has the applied model's content releases it,
 * {@code EntityModelBinding:297-302} - and the port's clear is the whole catalog), and the disconnect cleanup drops
 * it with the entities themselves.
 */
public final class AppliedModelRegistry {

    /** One entity's applied model: the geometry being drawn and the texture bound with it. */
    public record Applied(ResourceLocation mainModelId, ResourceLocation textureId) {}

    /** Applied records by tracked entity id, plus the timestamp of the last write (see {@link #retainAll}). */
    private static final Map<Integer, Applied> APPLIED = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> TOUCHED = new ConcurrentHashMap<>();

    private AppliedModelRegistry() {}

    /**
     * The rule the renderer applies each frame, as a pure function so it can be pinned without a client (a unit JVM
     * cannot link Minecraft, so the renderer itself cannot be constructed there):
     *
     * <ul>
     *   <li>the requested model is published (Ready): no - that is the frame the swap happens on;</li>
     *   <li>the requested model is <i>Pending</i> (this connection has its payload, the geometry is being built) or
     *       <i>Failed</i> (refused), and something is applied and still published: yes - upstream keeps the old
     *       lease for both ({@code EntityModelBinding#synchronize:145-167}, {@code failure-and-recovery.md:36});</li>
     *   <li>anything else - nothing applied yet, or an id this connection has no content for at all
     *       ({@code EntityModelBinding:106-112}): no, the built-in default stands in.</li>
     * </ul>
     *
     * @param requestedPublished whether {@code ClientModelManager.isModelPublished(requestedMainId)}
     * @param requestedHasContent whether {@code ClientModelManager.hasModelContent(requestedMainId)}
     * @param appliedPublished whether an applied record exists and its model is still published
     */
    public static boolean keepsAppliedModel(boolean requestedPublished, boolean requestedHasContent,
        boolean appliedPublished) {
        return !requestedPublished && requestedHasContent && appliedPublished;
    }

    /**
     * Records that {@code entityId} is now drawn with {@code mainModelId} and {@code textureId}.
     * <p>
     * Called by the renderer once the geometry for that model was actually resolved, which is the port's equivalent of
     * upstream installing a loaded target ({@code installReady}). Both arguments are required: a record names a model
     * that can really be drawn and the texture that belongs with it, and either half being missing would only invite a
     * reader to bind something the client may not have.
     */
    public static void install(int entityId, @Nullable ResourceLocation mainModelId,
        @Nullable ResourceLocation textureId) {
        if (mainModelId == null || textureId == null) {
            return;
        }
        APPLIED.put(entityId, new Applied(mainModelId, textureId));
        TOUCHED.put(entityId, System.currentTimeMillis());
    }

    /** {@link #install(int, ResourceLocation, ResourceLocation)} for an entity instance; a null entity is ignored. */
    public static void install(@Nullable EntityLivingBase entity, @Nullable ResourceLocation mainModelId,
        @Nullable ResourceLocation textureId) {
        if (entity != null) {
            install(entity.getEntityId(), mainModelId, textureId);
        }
    }

    /** The model {@code entityId} is currently shown with, or {@code null} when nothing has been applied yet. */
    @Nullable
    public static Applied applied(int entityId) {
        return APPLIED.get(entityId);
    }

    @Nullable
    public static Applied applied(@Nullable EntityLivingBase entity) {
        return entity == null ? null : applied(entity.getEntityId());
    }

    /** Forget every record, because the model list they describe is being dropped. */
    public static void clear() {
        APPLIED.clear();
        TOUCHED.clear();
    }

    /** Forgets one entity's record; for tests and for a caller that knows the entity is gone. */
    public static void forget(int entityId) {
        APPLIED.remove(entityId);
        TOUCHED.remove(entityId);
    }

    /** Live records; diagnostics and tests only. */
    public static int size() {
        return APPLIED.size();
    }

    /**
     * Drops records whose entity no longer exists in the client's world.
     * <p>
     * Same shape and same reason as {@link com.fox.ysmu.data.NPCData#retainAll(long, IntPredicate, long)}: entity ids
     * are never reused, so a leaked record is only wasted memory, and entries younger than {@code graceMillis} are
     * kept so a temporarily unloaded entity does not lose the model it was already showing.
     *
     * @param now         current time in milliseconds
     * @param alive       tests whether an entity id still exists in the loaded client world
     * @param graceMillis how long an untracked record is protected from pruning
     * @return how many records were dropped
     */
    public static int retainAll(long now, IntPredicate alive, long graceMillis) {
        int dropped = 0;
        for (Integer entityId : APPLIED.keySet()) {
            Long touched = TOUCHED.get(entityId);
            if (touched != null && now - touched < graceMillis) {
                continue;
            }
            if (!alive.test(entityId)) {
                APPLIED.remove(entityId);
                TOUCHED.remove(entityId);
                dropped++;
            }
        }
        return dropped;
    }
}
