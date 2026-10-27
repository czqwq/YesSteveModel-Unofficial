package com.fox.ysmu.client.renderer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.render.AppliedModelRegistry;
import com.fox.ysmu.client.render.ModelPoseSnapshot;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.renderer.layer.CustomPlayerHeadLayer;
import com.fox.ysmu.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.fox.ysmu.client.renderer.layer.EtFuturumElytraLayer;
import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.RenderContext;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

public class CustomPlayerRenderer extends GeoReplacedEntityRenderer<CustomPlayerEntity> {

    private GeoModel geoModel;
    private final Set<ResourceLocation> warnedMissingModels = ConcurrentHashMap.newKeySet();

    /**
     * The texture the current render must use instead of the model's own, set from the render event; see
     * {@code SpecialPlayerRenderEvent#getTextureLocationOverride}.
     * <p>
     * Mirrors upstream's {@code CustomPlayerRenderer#textureOverride}: it is the one place a texture outside the
     * model's own list is allowed to reach the engine, and it exists because the built-in {@code steve}/{@code alex}
     * models draw the player's actual Minecraft skin. Reset on every render, so a listener that sets it for one frame
     * cannot leak it into the next.
     */
    @Nullable
    private ResourceLocation textureOverride;

    /**
     * Non-zero while a preview render (model GUI, model information screen or the HUD overlay) is in progress.
     * <p>
     * A preview plays an animation of its own and writes it into the shared {@code GeoModel}, so the world would
     * inherit that pose afterwards; the preview paths therefore bracket themselves with
     * {@link #beginPreviewRender(RenderContext)}/{@link #endPreviewRender()} and this renderer restores the pose in
     * between.
     */
    private static int previewRenderDepth;

    /**
     * The render pass of each open preview bracket, innermost first; see {@link #beginPreviewRender(RenderContext)}.
     * <p>
     * The depth alone answers "is a preview being drawn"; the pass answers "which one", which is what the pack-facing
     * {@code ysm.rendering_in_inventory} / {@code ysm.rendering_in_paperdoll} queries and the frame's immutability bit
     * are read from ({@link software.bernie.geckolib3.geo.RenderContext}).
     */
    private static final Deque<RenderContext> previewContexts = new ArrayDeque<>();

    @SuppressWarnings("all")
    public CustomPlayerRenderer() {
        super(new CustomPlayerModel(), new CustomPlayerEntity());
        addLayer(new CustomPlayerItemInHandLayer<>(this));
        // Upstream registers the head layer alongside the in-hand one
        // (client/renderer/CustomPlayerRenderer.java:38-41); without it the head slot is invisible, because cancelling
        // RenderPlayerEvent.Pre also skips the vanilla pass that drew it.
        addLayer(new CustomPlayerHeadLayer<>(this));
        // Same reasoning for the elytra: upstream's CustomPlayerElytraLayer hangs the wings off the model, and the
        // vanilla pass that would have drawn 1.7.10's elytra (Et Futurum Requiem's SetArmorModel hook) is skipped
        // along with the rest of RenderPlayer. Handles itself when that mod is absent.
        addLayer(new EtFuturumElytraLayer<>(this));
    }

    @Override
    public void doRender(EntityLivingBase entityObj, double x, double y, double z, float entityYaw,
        float partialTicks) {
        // Replacement-renderer path (players, and the RenderLivingEvent bridge): a selected model this
        // client does not have still falls back to the default, so the entity is never hidden. The verdict
        // is intentionally ignored here.
        doRenderModel(entityObj, x, y, z, entityYaw, partialTicks, true);
    }

    /**
     * Renders the entity through YSM and reports whether this renderer produced a frame.
     * <p>
     * A caller that owns its own fallback renderer (for example a GeckoLib companion renderer) uses the
     * return value to decide whether to draw instead: {@code false} means the model the entity actually
     * asked for is not loaded on this client, so falling through keeps the entity visible with its own
     * renderer instead of drawing YSM's generic default model over it.
     * <p>
     * This is why the default substitution is not applied here: if a missing model resolved to
     * {@code ysmu:default/main} (which is loaded on every client), the model lookup below could never be
     * null and this method could never report {@code false} for the case it exists to report. An entity that
     * already has an applied model ({@link AppliedModelRegistry}) is still drawn - upstream keeps the old lease
     * for a pending or failed request instead of dropping the entity to its own renderer.
     *
     * @return {@code true} when a model was drawn or another listener claimed the frame; {@code false} when
     *         the requested model is unavailable to this client.
     */
    public boolean doRenderModel(EntityLivingBase entityObj, double x, double y, double z, float entityYaw,
        float partialTicks) {
        return doRenderModel(entityObj, x, y, z, entityYaw, partialTicks, false);
    }

    /**
     * What a frame draws for an entity - this port's form of upstream's <b>applied render target</b> rule, as one pure
     * decision both consumers share: the draw path turns it into animatable state
     * ({@link #resolveDrawnModel}) and the entry point that decides whether to cancel the vanilla renderer asks
     * whether the chosen model is really installed ({@link #canDrawFor}).
     * <p>
     * Upstream installs a request's target into the entity's binding only once it is loaded
     * ({@code EntityModelBinding#synchronize:145-167}, {@code installReady} on {@code AcquireResult.Ready}) and swaps
     * {@code currentModelRenderTarget} under the same gate ({@code CustomEntity#applyBoundResource:133-145}), so while
     * a newly selected model is still <i>Pending</i> or has <i>Failed</i> the entity keeps drawing the model it
     * already had; its product contract says exactly that - "a consumer may keep the old ready lease while the new
     * authoritative target is Pending or Failed; the humanoid uses the intrinsic default only when it has <b>no</b>
     * old lease" ({@code docs/architecture/model-management/failure-and-recovery.md:36}). The built-in default is
     * installed as the fallback only {@code if (resourceHolder == null)} ({@code EntityModelBinding:165-167}), or for
     * a model id this connection has no content for at all ({@code :106-112}).
     */
    private enum Drawn {
        /** The requested model itself, once it is published - the frame the swap happens on. */
        REQUESTED,
        /** The model the entity is already shown with, kept while the request is Pending or refused. */
        APPLIED,
        /** The built-in default: no override at all, an unknown id, or nothing applied yet. */
        FALLBACK,
        /** Nothing this renderer may draw; the caller keeps the entity on its own renderer. */
        NONE
    }

    /**
     * The pure form of {@link Drawn}; both consumers go through it, so the draw path and the "may I cancel the vanilla
     * renderer?" query can never disagree about which model a frame would use.
     *
     * @param requested         the entity's requested main model id, or {@code null} when it has no usable override
     * @param applied           what this entity is currently shown with; see {@link AppliedModelRegistry}
     * @param fallBackToDefault whether the built-in default may stand in - false for a caller that owns its own
     *                          fallback renderer and would rather draw that than YSM's generic model
     */
    private static Drawn selectDrawn(@Nullable ResourceLocation requested,
        @Nullable AppliedModelRegistry.Applied applied, boolean fallBackToDefault) {
        if (requested != null && ClientModelManager.isModelPublished(requested)) {
            return Drawn.REQUESTED;
        }
        if (requested != null && AppliedModelRegistry.keepsAppliedModel(
            false,
            ClientModelManager.hasModelContent(requested),
            applied != null && ClientModelManager.isModelPublished(applied.mainModelId()))) {
            return Drawn.APPLIED;
        }
        return fallBackToDefault ? Drawn.FALLBACK : Drawn.NONE;
    }

    /**
     * Puts the model the frame draws on the shared animatable and answers with its location; see {@link Drawn}.
     *
     * <p>{@link AppliedModelRegistry} is where "what this entity is already shown with" lives: upstream keeps it in
     * the entity's own binding, which this port does not have (one shared animatable for every entity). Keeping that
     * model means restoring its texture too, so geometry, texture, animations, scale and layer order all come from one
     * model - upstream never shows an applied target's geometry with another target's resources.
     *
     * @return the main model id to draw, or {@code null} when this renderer must not draw the entity at all
     */
    @Nullable
    private ResourceLocation resolveDrawnModel(EntityLivingBase entityObj, @Nullable ResourceLocation requested,
        boolean fallBackToDefault) {
        if (requested == null) {
            if (!fallBackToDefault) {
                // No override at all, so there is no YSM model for an external renderer to claim.
                return null;
            }
            return this.modelProvider.getModelLocation(this.animatable);
        }
        // Not published yet: keep asking for it, because this is the call that submits the build - the acquire that
        // upstream drives on every checkModelUpdate - and it has to happen while an applied model is being kept, or
        // the switch would never finish.
        ClientModelManager.ensureGeometry(requested);
        AppliedModelRegistry.Applied applied = AppliedModelRegistry.applied(entityObj);
        switch (selectDrawn(requested, applied, fallBackToDefault)) {
            case REQUESTED:
                return requested;
            case APPLIED:
                this.animatable.setMainModel(applied.mainModelId());
                this.animatable.setTexture(applied.textureId());
                return applied.mainModelId();
            case FALLBACK:
                // The built-in default's own pair: applyEntityModel left the animatable on the requested model, whose
                // geometry is not there yet, and leaving it there would draw the default's geometry with that model's
                // animation and scale. This is the substitution upstream makes by installing its default target into
                // the binding when the requested one is not ready.
                this.animatable.setMainModel(CustomPlayerModel.DEFAULT_MAIN_MODEL);
                this.animatable.setTexture(CustomPlayerModel.DEFAULT_TEXTURE);
                return CustomPlayerModel.DEFAULT_MAIN_MODEL;
            default:
                warnMissingModel(requested, entityObj);
                return null;
        }
    }

    private boolean doRenderModel(EntityLivingBase entityObj, double x, double y, double z, float entityYaw,
        float partialTicks, boolean fallBackToDefault) {
        ResourceLocation requested = applyEntityModel(entityObj);
        ResourceLocation location = resolveDrawnModel(entityObj, requested, fallBackToDefault);
        if (location == null) {
            return false;
        }
        SpecialPlayerRenderEvent renderEvent = new SpecialPlayerRenderEvent(
            entityObj,
            this.animatable,
            ModelIdUtil.getModelIdFromMainId(this.animatable.getMainModel()));
        if (MinecraftForge.EVENT_BUS.post(renderEvent)) {
            // The render event was cancelled, so another listener owns this entity's frame.
            return true;
        }
        // Read after the event has been posted, so the listeners have had their say: upstream reads it before, which
        // is why its renderer field never sees what its own vanilla-skin listener sets.
        this.textureOverride = renderEvent.getTextureLocationOverride();
        GeoModel geoModel = drawableGeometry(location);
        if (geoModel == null) {
            // Nothing is drawable for this model on this connection: report it and leave the frame to whoever called
            // us (a preview tile keeps its last image, an external renderer draws the entity itself). The built-in
            // default is never silently substituted here any more - resolveDrawnModel already decided what belongs on
            // screen, and drawing a different model's geometry under this model's texture is the mismatch upstream
            // avoids by never mixing an applied target with another target's resources.
            warnMissingModel(location, entityObj);
            return false;
        }
        this.geoModel = geoModel;
        // Upstream records the target when it is installed ({@code EntityModelBinding#installReady}), and that record
        // is what the next frame keeps drawing while a newer selection is still Pending or has Failed. Recording here
        // - the geometry resolved and the texture that goes with it known - is the same moment.
        AppliedModelRegistry.install(entityObj, location, this.animatable.resolvedTexture());
        // A preview may pose the model however its own animation wants; the world must not inherit that pose.
        ModelPoseSnapshot pose = previewRenderDepth > 0 ? ModelPoseSnapshot.capture(geoModel) : null;
        // Bone-transform tracking for the draw (reference branch CustomPlayerRenderer.java:170/192):
        // MatrixStack.transformBone records each bone's accumulated matrix so ysm.bone_pivot_abs can read this
        // frame's pose. The scales are the ones renderEarly applies (width, height, width) and the model id is the
        // main id being drawn, which is what keeps two models' bones apart. The matching disable call sits in the
        // finally below, so a failed render cannot leave tracking switched on.
        MolangPhysicsRuntime.setBoneTracking(true,
            getWidthScale(this.animatable),
            getHeightScale(this.animatable),
            getWidthScale(this.animatable),
            this.animatable.getMainModel());
        try {
            super.doRender(entityObj, x, y, z, entityYaw, partialTicks);
            // 渲染 AdventureBackpack2 背部可穿戴物品（直升机背包等）
            // (reference branch CustomPlayerRenderer.java:183-188)。YSMU 取消了 RenderPlayerEvent.Pre，
            // AdventureBackpack2 在 RenderPlayerEvent.Specials.Pre 注册的背包渲染随之不会触发，
            // 所以在这里手工补一次；Config.RENDER_WEARABLE 是它的开关。
            if (entityObj instanceof EntityPlayer player && com.fox.ysmu.Config.RENDER_WEARABLE) {
                com.fox.ysmu.compat.AdventureBackpackCompat.renderWearable(player, x, y, z, partialTicks);
            }
            // Restore the step that cancelling RenderPlayerEvent.Pre takes away with the rest of the draw.
            // RendererLivingEntity#doRender calls passSpecialRender as its own step once its matrix is popped
            // (build/rfg/minecraft-src .../RendererLivingEntity.java:295-296), and that is where a name tag is drawn
            // and where RenderLivingEvent.Specials.Pre/Post fire. This renderer implements the whole draw itself
            // instead of calling super, so both were simply missing: a YSM-drawn player had no name tag at all, and
            // CustomPlayerRenderer#func_96449_a - which the port wrote to add the scoreboard line above the name -
            // was waiting for a call that never came. The coordinates are the ones vanilla passes: passSpecialRender
            // translates by them itself, so this runs with the matrix back at the camera-relative origin.
            // A preview needs no equivalent of upstream's `isFakePlayer` gate: vanilla's own func_110813_b already
            // refuses the camera's own player (the local player) and passengers, which is every entity this port
            // previews.
            passSpecialRender(entityObj, x, y, z);
        } finally {
            MolangPhysicsRuntime.setBoneTracking(false, 1.0F, 1.0F, 1.0F, null);
            if (pose != null) {
                pose.restore();
            }
        }
        return true;
    }

    /**
     * Marks the start of a preview render (model GUI, information screen, HUD overlay) drawn in {@code context}.
     * <p>
     * Upstream carries the same fact as ambient flags - {@code RenderUtil.setRenderingInInventory(true)} around its
     * texture screen ({@code util/RenderUtil.java:108}), {@code setRenderingInPaperDoll(true)} around its HUD doll
     * ({@code :445}) - and reads them back at event-creation time ({@code extractRenderContext}, {@code :57-67}). This
     * port keeps that shape on the bracket it already had, so the pass is available to the event the engine builds
     * for the same frame.
     */
    public static void beginPreviewRender(RenderContext context) {
        previewRenderDepth++;
        previewContexts.push(context == null ? RenderContext.NONE : context);
    }

    /** Marks the end of a preview render; safe with nesting and in a {@code finally}. */
    public static void endPreviewRender() {
        if (previewRenderDepth > 0) {
            previewRenderDepth--;
        }
        if (!previewContexts.isEmpty()) {
            previewContexts.pop();
        }
    }

    /** Whether a preview render is currently in progress. */
    public static boolean isPreviewRendering() {
        return previewRenderDepth > 0;
    }

    /**
     * The pass of the innermost open preview bracket, or {@code null} when no bracket is open - i.e. the world pass,
     * which upstream also classifies as neither GUI flag.
     */
    @Nullable
    public static RenderContext currentPreviewContext() {
        return previewContexts.peek();
    }

    /**
     * Attaches this frame's pass to every event this renderer builds.
     * <p>
     * The host builds its own event for the GUI tiles and the settings panel's form preview
     * ({@code RenderUtil#renderModel}), but the world pass, the HUD doll and the first-person arm all take the event
     * the engine builds in {@code GeoReplacedEntityRenderer#buildAnimationEvent}
     * ({@code GeoReplacedEntityRenderer#doRender:88} and {@code FirstPersonHandRenderer#poseFirstPersonArm:290}). This
     * override is the host's only reach into those, and without it the HUD doll's frame would answer "neither pass"
     * where {@code FrameRenderQueries} used to infer the paper doll.
     */
    @Override
    public AnimationEvent<CustomPlayerEntity> buildAnimationEvent(EntityLivingBase entity, float partialTicks,
        CustomPlayerEntity eventAnimatable) {
        AnimationEvent<CustomPlayerEntity> event = super.buildAnimationEvent(entity, partialTicks, eventAnimatable);
        event.setRenderContext(renderContextFor(eventAnimatable));
        return event;
    }

    /**
     * The pass an event built by this renderer belongs to.
     * <p>
     * The arm answers first because it is its own animatable <em>and</em> its own pass, and its event is built outside
     * every preview bracket ({@code FirstPersonHandRenderer} draws the held item, not a preview). Upstream answers
     * {@code firstPersonMod} from {@code FirstPersonCompat.isRenderingPlayer()}; in this port the first-person pass
     * <em>is</em> that arm's frame, which is also how {@code FirstPersonModHideVariable} identifies it.
     */
    private static RenderContext renderContextFor(CustomPlayerEntity eventAnimatable) {
        if (eventAnimatable instanceof CustomFirstPersonArmEntity) {
            return RenderContext.firstPersonPass();
        }
        RenderContext preview = currentPreviewContext();
        return preview == null ? RenderContext.NONE : preview;
    }

    /**
     * Whether this client currently has a drawable YSM model for the entity's override.
     * <p>
     * This asks about the model the entity actually requested, not the default model that
     * {@link CustomPlayerEntity#getMainModel()} substitutes for a missing one. Asking about the substituted
     * location would always answer {@code true} on a client that has the default model and so would never
     * reveal that the entity's own model is absent.
     * <p>
     * A parked model counts as available here on purpose: this is the gate that keeps the entity on the YSM renderer,
     * and the path behind it is also what asks for the build. Answering false would hand the entity to its vanilla
     * renderer and, because nothing else asks for that build, it would stay parked for the session.
     * {@link ClientModelManager#isModelPublished} is the narrower question, for callers that want to know whether
     * the real model can be drawn right now.
     */
    public boolean hasModelFor(EntityLivingBase entityObj) {
        // Pure query: must not touch the shared animatable, or asking whether an entity can be drawn would
        // itself change what the next entity renders with.
        return isModelAvailable(requestedModel(entityObj));
    }

    /**
     * Whether this renderer can produce a frame for the entity right now, so a caller that has to decide between
     * cancelling the vanilla renderer and leaving it alone can ask first. Pure query: it never touches the shared
     * animatable and never submits a build (see {@link #hasModelFor}).
     * <p>
     * It asks the very same decision the draw path does ({@link #selectDrawn}), then whether the model that decision
     * picked is really installed: cancelling the vanilla renderer and then finding no geometry to draw would hide the
     * entity, which is the one outcome no branch of this must produce. Two states make that possible and both are
     * answered here - the built-in fallback being unusable ({@code ClientModelManager#isFallbackAvailable}: its own
     * install failed or was refused, so it can no longer stand in for anything), and a model whose geometry is not in
     * the engine cache although it is selected.
     * <p>
     * Upstream has no equivalent branch: its default initialization is a startup requirement and a failure there is
     * the documented exception ("humanoid 回退 default 失败时不要求继续恢复宿主",
     * {@code docs/product-decisions/decisions/model-fallback.md:49-53}). A port that cancels the vanilla player
     * renderer does have a host renderer to hand the entity back to, so it uses it - the same degradation this port
     * uses for {@code DISABLE_SELF_MODEL} and for a non-player entity with no model
     * ({@code ClientEventHandler#onRenderLiving}).
     */
    public boolean canDrawFor(EntityLivingBase entityObj) {
        ResourceLocation requested = requestedModel(entityObj);
        AppliedModelRegistry.Applied applied = AppliedModelRegistry.applied(entityObj);
        switch (selectDrawn(requested, applied, true)) {
            case REQUESTED:
                return hasGeometry(requested);
            case APPLIED:
                return hasGeometry(applied.mainModelId());
            case FALLBACK:
                // The built-in default is the substitute this whole renderer is built around, and it is the one model
                // that outlives a connection's model-list reset (#isFallbackAvailable), so only its own availability
                // and its geometry being there decide.
                return ClientModelManager.isFallbackAvailable()
                    && hasGeometry(CustomPlayerModel.DEFAULT_MAIN_MODEL);
            default:
                return false;
        }
    }

    /** Whether this model's geometry is really in the engine cache (a pure lookup: no build request, no marker). */
    private static boolean hasGeometry(@Nullable ResourceLocation mainModelId) {
        return mainModelId != null && GeckoLibCache.getInstance()
            .getGeoModels()
            .get(mainModelId) != null;
    }

    /**
     * The geometry to draw for {@code location}, asking for a build first and then taking whatever the engine has.
     * <p>
     * The request goes out on every frame ({@code ensureGeometry} is also the port's acquire), but its <em>answer</em>
     * is not the drawability check: a payload that is parked for a rebuild - which happens to the built-in default on
     * every sync, because the server ships it too - makes that call answer "wait" while the geometry this client
     * already installed is still in the engine cache and still the right thing to draw. Reading the cache directly is
     * what the frame before this port's on-demand loading did, and it keeps such a window from hiding every player.
     *
     * @return the geometry for {@code location}, or {@code null} when this connection has none at all
     */
    @Nullable
    private static GeoModel drawableGeometry(ResourceLocation location) {
        ClientModelManager.ensureGeometry(location);
        return GeckoLibCache.getInstance()
            .getGeoModels()
            .get(location);
    }

    /**
     * The main model id the entity asks for, before any default substitution.
     * <p>
     * Pure query: it must not touch the shared animatable (see {@link #hasModelFor}), and it must not
     * allocate - it reads the stored override or the EEP fields directly (N-7).
     *
     * @return the requested main model id, or {@code null} when the entity has no usable override.
     */
    @Nullable
    private static ResourceLocation requestedModel(EntityLivingBase entityObj) {
        EntityModelData npcOverride = NPCData.getData(entityObj);
        if (isComplete(npcOverride)) {
            return ModelIdUtil.getMainId(npcOverride.getModelId());
        }
        ExtendedModelInfo eep = playerModelInfo(entityObj);
        if (isComplete(eep)) {
            return ModelIdUtil.getMainId(eep.getModelId());
        }
        return null;
    }

    /**
     * Applies the entity's model/texture to the shared animatable; render path only. Returns the main model
     * id the entity actually requested, before any default substitution.
     * <p>
     * R-05: when the entity has no usable override the shared animatable is reset to the built-in default
     * instead of keeping what the previously rendered entity left there, so one entity can never be drawn
     * with another entity's model or skin.
     * <p>
     * N-1: a half-filled override (a model without a texture) is treated as "no override" as well - the
     * {@code null} texture would otherwise reach {@code TextureManager.bindTexture(null)} through
     * {@link CustomPlayerModel#getTextureLocation(Object)} and crash the client. The reset below also
     * re-establishes "texture is never null" before anything else runs.
     *
     * @return the requested main model id, or {@code null} when the entity has no usable override and the
     *         default model is intended.
     */
    @Nullable
    private ResourceLocation applyEntityModel(EntityLivingBase entityObj) {
        if (this.animatable == null) {
            return null;
        }
        this.animatable.setEntity(entityObj);

        EntityModelData npcOverride = NPCData.getData(entityObj);
        if (isComplete(npcOverride)) {
            return applyOverride(npcOverride.getModelId(), npcOverride.getTextureId());
        }

        ExtendedModelInfo eep = playerModelInfo(entityObj);
        if (isComplete(eep)) {
            return applyOverride(eep.getModelId(), eep.getSelectTexture());
        }

        // No usable override: never fall through to the previous entity's state (R-05).
        this.animatable.setMainModel(CustomPlayerModel.DEFAULT_MAIN_MODEL);
        this.animatable.setTexture(CustomPlayerModel.DEFAULT_TEXTURE);
        return null;
    }

    private ResourceLocation applyOverride(ResourceLocation modelId, ResourceLocation textureId) {
        ResourceLocation main = ModelIdUtil.getMainId(modelId);
        this.animatable.setMainModel(main);
        this.animatable.setTexture(textureId);
        return main;
    }

    /**
     * The player's own selection, or {@code null} for a non-player entity.
     * <p>
     * Pure accessor; it exists so the two callers above can read the EEP fields without building an
     * {@link EntityModelData} just to read them back (N-7).
     */
    @Nullable
    private static ExtendedModelInfo playerModelInfo(EntityLivingBase entityObj) {
        return entityObj instanceof EntityPlayer player ? ExtendedModelInfo.get(player) : null;
    }

    /**
     * Whether an override carries both halves. N-1: the NPC path can only be null-free because
     * {@code NPCData} rejects nulls, and a half-filled EEP can still exist (the client's own selection
     * packet maps an empty texture string to {@code null}), so both are validated here as well.
     */
    /**
     * The texture for this render: the event's override when a listener supplied one, otherwise whatever the model
     * provider resolves.
     * <p>
     * The engine asks the renderer for this on every bind ({@code GeoReplacedEntityRenderer#getEntityTexture}), which
     * is exactly the seam upstream uses for its own {@code textureOverride}. Keeping the override here rather than on
     * the animatable is what tells a texture that is deliberately outside the model (the player's own skin for
     * {@code steve}/{@code alex}, a companion mod's own) apart from a pack texture that simply has not been uploaded -
     * the renderer is the only place that knows the difference.
     */
    @Override
    public ResourceLocation getTextureLocation(Object instance) {
        ResourceLocation override = this.textureOverride;
        return override != null ? override : super.getTextureLocation(instance);
    }

    private static boolean isComplete(@Nullable EntityModelData override) {
        return override != null && override.getModelId() != null && override.getTextureId() != null;
    }

    private static boolean isComplete(@Nullable ExtendedModelInfo eep) {
        return eep != null && eep.getModelId() != null && eep.getSelectTexture() != null;
    }

    private static boolean isModelAvailable(ResourceLocation main) {
        // A model whose geometry is parked but not yet built still counts as available: it will be drawn, with the
        // built-in default standing in for it for the few frames the build takes. Reporting it as missing here would
        // instead make the entity fall back to its own vanilla renderer and never come back.
        return main != null && ClientModelManager.isModelAvailable(main);
    }

    private void warnMissingModel(ResourceLocation location, EntityLivingBase entityObj) {
        if (warnedMissingModels.add(location)) {
            ysmu.LOG.warn(
                "YSM model {} is not loaded on this client; entity {} keeps its own renderer",
                location,
                entityObj);
        }
    }

    // @Override
    // public RenderType getRenderType(Object animatable, float partialTick, PoseStack poseStack, @Nullable
    // MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, int packedLight, ResourceLocation texture) {
    // return RenderType.entityTranslucent(texture);
    // }

    // @Override
    // public boolean shouldShowName(Entity entity) {
    // double distance = this.entityRenderDispatcher.distanceToSqr(entity);
    // float renderDistance = entity.isDiscrete() ? 32.0F : 64.0F;
    // if (distance >= (double) (renderDistance * renderDistance)) {
    // return false;
    // } else {
    // Minecraft minecraft = Minecraft.getInstance();
    // LocalPlayer player = minecraft.player;
    // if (player == null) {
    // return false;
    // }
    // boolean invisible = !entity.isInvisibleTo(player);
    // if (entity != player) {
    // Team team1 = entity.getTeam();
    // Team team2 = player.getTeam();
    // if (team1 != null) {
    // Team.Visibility team$visibility = team1.getNameTagVisibility();
    // return switch (team$visibility) {
    // case ALWAYS -> invisible;
    // case NEVER -> false;
    // case HIDE_FOR_OTHER_TEAMS ->
    // team2 == null ? invisible : team1.isAlliedTo(team2) && (team1.canSeeFriendlyInvisibles() || invisible);
    // case HIDE_FOR_OWN_TEAM -> team2 == null ? invisible : !team1.isAlliedTo(team2) && invisible;
    // };
    // }
    // }
    // return Minecraft.renderNames() && entity != minecraft.getCameraEntity() && invisible && !entity.isVehicle();
    // }
    // }

    @Override
    protected void func_96449_a(EntityLivingBase entity, double x, double y, double z, String displayName,
        float scale, double distanceSq) {
        if (entity instanceof EntityPlayer player && distanceSq < 100.0D) {
            Scoreboard scoreboard = player.getWorldScoreboard();
            ScoreObjective objective = scoreboard.func_96539_a(2);
            if (objective != null) {
                Score score = scoreboard.func_96529_a(player.getCommandSenderName(), objective);
                String scoreText = score.getScorePoints() + " " + objective.getDisplayName();
                double scoreY = player.isPlayerSleeping() ? y - 1.5D : y;
                this.func_147906_a(player, scoreText, x, scoreY, z, 64);
                y += this.getFontRendererFromRenderManager().FONT_HEIGHT * 1.15F * scale;
            }
        }
        super.func_96449_a(entity, x, y, z, displayName, scale, distanceSq);
    }

    @Override
    public float getWidthScale(Object animatable) {
        if (this.animatable != null) {
            return this.animatable.getWidthScale();
        }
        return super.getWidthScale(animatable);
    }

    @Override
    public float getHeightScale(Object animatable) {
        if (this.animatable != null) {
            return this.animatable.getHeightScale();
        }
        return super.getHeightScale(animatable);
    }

    /**
     * The host owns {@code render_layers_first}: a model that declares it has its held item and armor drawn before the
     * model instead of after it, because the model geometry covers them otherwise. The flag reaches the client model
     * table from the loaded pack ({@code ClientModelManager.RENDER_LAYERS_FIRST}); upstream reads the same per-model
     * setting and orders its layer pass by it ({@code geckolib3/geo/GeoReplacedEntityRenderer.java:92,98,118}).
     */
    @Override
    public boolean shouldRenderLayersFirst(Object animatable) {
        return this.animatable != null && this.animatable.shouldRenderLayersFirst();
    }

    /**
     * The host answers the question upstream asks its baked model state before it chooses a render type:
     * {@code GeoModelState#hasTranslucentVertices}, i.e. {@code nativeState.getTranslucentVertexCount() != 0}
     * ({@code com/elfmcys/ysm/geckolib3/geo/animated/GeoModelState.java:73-74}), which the engine asks for through the
     * hook of the same name. Upstream's count is native and unreadable here, so {@code CustomPlayerEntity} answers
     * from the texture that is about to be bound - the same input upstream's bake works from.
     */
    @Override
    public boolean hasTranslucentVertices(Object animatable) {
        return this.animatable != null && this.animatable.hasTranslucentVertices();
    }

    public CustomPlayerEntity getCustomPlayerEntity() {
        return this.animatable;
    }

    @Nullable
    public GeoModel getGeoModel() {
        return geoModel;
    }

    /**
     * Lets go of the last geometry this renderer drew when the engine's cache releases it, so a torn-down model is
     * not kept reachable from the renderer alone. Called from {@code CustomPlayerModel.onGeoModelReleased}, i.e. from
     * {@code ClientModelManager.releaseEngineCaches}.
     */
    public void onGeoModelReleased(GeoModel released) {
        if (this.geoModel == released) {
            this.geoModel = null;
        }
    }

    /**
     * Drops the per-entity animation state this renderer accumulated on its shared animatable.
     * <p>
     * The world path keys {@code AnimationFactory}'s data map by the rendered entity's unique id
     * ({@code GeoReplacedEntityRenderer#doRender} passes {@code getUniqueID(entity)}), so every entity this renderer
     * ever drew leaves one {@code AnimationData} - the fixed controller pool, its bone snapshots, bone animation
     * queues and per-controller keyframe-location caches - behind. The engine never removes one: {@code
     * AnimationFactory#dispose} had no production caller. At a connection boundary the data describes models that
     * are being dropped anyway, so the renderer disposes it together with them (see
     * {@code ClientModelManager.releaseEngineCaches}). This does not cover an entity leaving mid-session; that needs
     * a prunable entity-id key and a grace window, recorded in .agent/perf-optimization-round.md.
     */
    public void disposeAnimationData() {
        if (this.animatable != null && this.animatable.getFactory() != null) {
            this.animatable.getFactory()
                .dispose();
        }
    }
}
