package com.fox.ysmu.client.renderer;

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
import com.fox.ysmu.client.entity.CustomPlayerEntity;
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

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
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
     * {@link #beginPreviewRender()}/{@link #endPreviewRender()} and this renderer restores the pose in between.
     */
    private static int previewRenderDepth;

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
     * null and this method could never report {@code false} for the case it exists to report.
     *
     * @return {@code true} when a model was drawn or another listener claimed the frame; {@code false} when
     *         the requested model is unavailable to this client.
     */
    public boolean doRenderModel(EntityLivingBase entityObj, double x, double y, double z, float entityYaw,
        float partialTicks) {
        return doRenderModel(entityObj, x, y, z, entityYaw, partialTicks, false);
    }

    private boolean doRenderModel(EntityLivingBase entityObj, double x, double y, double z, float entityYaw,
        float partialTicks, boolean fallBackToDefault) {
        ResourceLocation requested = applyEntityModel(entityObj);
        ResourceLocation location;
        if (requested == null) {
            if (!fallBackToDefault) {
                // No override at all, so there is no YSM model for an external renderer to claim.
                return false;
            }
            location = this.modelProvider.getModelLocation(this.animatable);
        } else if (isModelAvailable(requested)) {
            location = requested;
        } else if (fallBackToDefault) {
            warnMissingModel(requested, entityObj);
            location = CustomPlayerModel.DEFAULT_MAIN_MODEL;
        } else {
            warnMissingModel(requested, entityObj);
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
        GeoModel geoModel;
        if (ClientModelManager.ensureGeometry(location)) {
            geoModel = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(location);
        } else {
            // Synced, but its geometry is still being built on the loader thread. Draw the built-in default for the
            // few frames that takes: reporting a model that is on its way as missing would drop this entity to its
            // vanilla renderer and it would never come back.
            geoModel = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(CustomPlayerModel.DEFAULT_MAIN_MODEL);
        }
        if (geoModel == null) {
            warnMissingModel(location, entityObj);
            return false;
        }
        this.geoModel = geoModel;
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

    /** Marks the start of a preview render (model GUI, information screen, HUD overlay). */
    public static void beginPreviewRender() {
        previewRenderDepth++;
    }

    /** Marks the end of a preview render; safe with nesting and in a {@code finally}. */
    public static void endPreviewRender() {
        if (previewRenderDepth > 0) {
            previewRenderDepth--;
        }
    }

    /** Whether a preview render is currently in progress. */
    public static boolean isPreviewRendering() {
        return previewRenderDepth > 0;
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
     * {@link #isPublishedFor} is the narrower question, for callers that want to know whether the real model can be
     * drawn right now.
     */
    public boolean hasModelFor(EntityLivingBase entityObj) {
        // Pure query: must not touch the shared animatable, or asking whether an entity can be drawn would
        // itself change what the next entity renders with.
        return isModelAvailable(requestedModel(entityObj));
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
}
