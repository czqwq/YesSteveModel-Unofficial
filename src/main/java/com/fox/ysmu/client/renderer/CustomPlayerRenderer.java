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

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

public class CustomPlayerRenderer extends GeoReplacedEntityRenderer<CustomPlayerEntity> {

    private GeoModel geoModel;
    private final Set<ResourceLocation> warnedMissingModels = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("all")
    public CustomPlayerRenderer() {
        super(new CustomPlayerModel(), new CustomPlayerEntity());
        addLayer(new CustomPlayerItemInHandLayer<>(this));
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
        if (MinecraftForge.EVENT_BUS.post(
            new SpecialPlayerRenderEvent(
                entityObj,
                this.animatable,
                ModelIdUtil.getModelIdFromMainId(this.animatable.getMainModel())))) {
            // The render event was cancelled, so another listener owns this entity's frame.
            return true;
        }
        GeoModel geoModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(location);
        if (geoModel == null) {
            warnMissingModel(location, entityObj);
            return false;
        }
        this.geoModel = geoModel;
        super.doRender(entityObj, x, y, z, entityYaw, partialTicks);
        return true;
    }

    /**
     * Whether this client currently has a drawable YSM model for the entity's override.
     * <p>
     * This asks about the model the entity actually requested, not the default model that
     * {@link CustomPlayerEntity#getMainModel()} substitutes for a missing one. Asking about the substituted
     * location would always answer {@code true} on a client that has the default model and so would never
     * reveal that the entity's own model is absent.
     */
    public boolean hasModelFor(EntityLivingBase entityObj) {
        // Pure query: must not touch the shared animatable, or asking whether an entity can be drawn would
        // itself change what the next entity renders with.
        return isModelAvailable(requestedModel(entityObj));
    }

    /**
     * Resolves the override an entity asks for, without touching the shared animatable.
     *
     * @return the override, or {@code null} when the entity has none.
     */
    @Nullable
    private static EntityModelData resolveOverride(EntityLivingBase entityObj) {
        EntityModelData override = NPCData.getData(entityObj);
        if (override != null) {
            // NPC / non-player override, looked up by entity rather than by UUID.
            return override;
        }
        if (entityObj instanceof EntityPlayer player) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null && eep.getModelId() != null) {
                return new EntityModelData(eep.getModelId(), eep.getSelectTexture());
            }
        }
        return null;
    }

    /**
     * The main model id the entity asks for, before any default substitution.
     *
     * @return the requested main model id, or {@code null} when the entity has no override.
     */
    @Nullable
    private static ResourceLocation requestedModel(EntityLivingBase entityObj) {
        EntityModelData override = resolveOverride(entityObj);
        return override == null ? null : ModelIdUtil.getMainId(override.getModelId());
    }

    /**
     * Applies the entity's model/texture to the shared animatable; render path only. Returns the main model
     * id the entity actually requested, before any default substitution.
     *
     * @return the requested main model id, or {@code null} when the entity has no override and the default
     *         model is intended.
     */
    @Nullable
    private ResourceLocation applyEntityModel(EntityLivingBase entityObj) {
        if (this.animatable == null) {
            return null;
        }
        this.animatable.setEntity(entityObj);
        EntityModelData override = resolveOverride(entityObj);
        if (override == null) {
            return null;
        }
        ResourceLocation main = ModelIdUtil.getMainId(override.getModelId());
        this.animatable.setMainModel(main);
        this.animatable.setTexture(override.getTextureId());
        return main;
    }

    private static boolean isModelAvailable(ResourceLocation main) {
        return main != null && GeckoLibCache.getInstance()
            .getGeoModels()
            .containsKey(main);
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

    public CustomPlayerEntity getCustomPlayerEntity() {
        return this.animatable;
    }

    @Nullable
    public GeoModel getGeoModel() {
        return geoModel;
    }
}
