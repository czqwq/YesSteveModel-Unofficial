package software.bernie.geckolib3.geo;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import com.eliotlash.mclib.utils.Interpolations;
import com.google.common.collect.Lists;
import com.mojang.realmsclient.gui.ChatFormatting;

import software.bernie.example.config.ConfigHandler;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.model.DummyVanilaModel;
import software.bernie.geckolib3.model.provider.data.EntityModelData;

import static com.eliotlash.mclib.utils.MathHelper.wrapDegrees;

@SuppressWarnings({ "rawtypes", "unchecked" })
public abstract class GeoReplacedEntityRenderer<T extends IAnimatable> extends RendererLivingEntity
    implements IGeoRenderer {

    protected final AnimatedGeoModel<T> modelProvider;
    protected T animatable;
    protected final List<GeoLayerRenderer> layerRenderers = Lists.newArrayList();
    private IAnimatable currentAnimatable;
    protected float widthScale = 1;
    protected float heightScale = 1;
    private static Map<Class<? extends IAnimatable>, GeoReplacedEntityRenderer> renderers = new ConcurrentHashMap<>();

    static {
        AnimationController.addModelFetcher((IAnimatable object) -> {
            GeoReplacedEntityRenderer renderer = renderers.get(object.getClass());
            return renderer == null ? null : renderer.getGeoModelProvider();
        });
    }

    public GeoReplacedEntityRenderer(AnimatedGeoModel<T> modelProvider, T animatable) {
        super(new DummyVanilaModel(), 0);
        this.renderManager = RenderManager.instance;
        this.modelProvider = modelProvider;
        this.animatable = animatable;
        this.currentAnimatable = animatable;
    }

    public static void registerReplacedEntity(Class<? extends IAnimatable> itemClass,
        GeoReplacedEntityRenderer renderer) {
        renderers.put(itemClass, renderer);
    }

    public static GeoReplacedEntityRenderer getRenderer(Class<? extends IAnimatable> item) {
        return renderers.get(item);
    }

    @Override
    public void doRender(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks) {
        if (!(entity instanceof EntityLivingBase)) return;
        // Ensure currentAnimatable is synced for getEntityTexture()
        if (this.animatable != null) {
            this.currentAnimatable = this.animatable;
        }
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, z);

            AnimationEvent predicate = buildAnimationEvent(entity, partialTicks, this.animatable);
            // The four locals the layer pass below needs are the event's own inputs. The two head values are carried
            // negated inside the entity data (see buildAnimationEvent, which keeps the engine's signs), so they are
            // negated back here rather than computed a second time.
            EntityModelData entityModelData = (EntityModelData) predicate.getExtraData()
                .get(0);
            float limbSwing = predicate.getLimbSwing();
            float limbSwingAmount = predicate.getLimbSwingAmount();
            float netHeadYaw = -entityModelData.netHeadYaw;
            float headPitch = -entityModelData.headPitch;

            float f7 = this.handleRotationFloat(entity, partialTicks);
            this.applyRotations(entity, f7, this.resolveBodyYaw(entity, partialTicks, entityModelData.isSitting),
                partialTicks);

            GeoModel model = modelProvider.getModel(modelProvider.getModelLocation(animatable));
            if (modelProvider instanceof IAnimatableModel) {
                ((IAnimatableModel<T>) modelProvider)
                    .setLivingAnimations(animatable, this.getUniqueID(entity), predicate);
            }

            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(0, 0.01f, 0);
                Minecraft.getMinecraft().renderEngine.bindTexture(getEntityTexture(entity));
                Color renderColor = getRenderColor(entity, partialTicks);
                boolean layersFirst = shouldRenderLayersFirst(animatable);
                if (layersFirst) {
                    renderLayers(model, entity, limbSwing, limbSwingAmount, partialTicks, f7, netHeadYaw, headPitch,
                        renderColor);
                }

                if (!entity.isInvisibleToPlayer(Minecraft.getMinecraft().thePlayer)) {
                    // Upstream asks for the render type here and hands it to the draw
                    // (com/elfmcys/ysm/geckolib3/geo/GeoReplacedEntityRenderer.java:88-90). 1.7.10 has no
                    // shouldEntityAppearGlowing, so the glowing flag is always false. The translucency question goes
                    // to the animatable, which is the host that owns this model's textures.
                    YsmRenderType renderType = getRenderType(
                        getEntityTexture(entity),
                        true,
                        false,
                        hasTranslucentVertices(animatable));
                    render(
                        model,
                        entity,
                        renderType,
                        partialTicks,
                        (float) renderColor.getRed() / 255f,
                        (float) renderColor.getGreen() / 255f,
                        (float) renderColor.getBlue() / 255f,
                        (float) renderColor.getAlpha() / 255);
                }

                if (!layersFirst) {
                    renderLayers(model, entity, limbSwing, limbSwingAmount, partialTicks, f7, netHeadYaw, headPitch,
                        renderColor);
                }
                if (entity instanceof EntityLiving) {
                    Entity leashHolder = ((EntityLiving) entity).getLeashedToEntity();
                    if (leashHolder != null) {
                        this.renderLeash((EntityLiving) entity, x, y, z, entityYaw, partialTicks);
                    }
                }
                RenderHurtColor.render(this, model, entity, entity, partialTicks);
            } catch (Exception e) {
                // Report instead of swallowing: a failure here leaves the entity with no GeckoLib frame at all, and
                // the debug flag alone used to make that indistinguishable from an empty model.
                reportRenderFailure("entity " + entity + " with model " + model, e);
                if (ConfigHandler.debugPrintStacktraces) {
                    e.printStackTrace();
                }
            } finally {
                GlStateManager.popMatrix();
            }
        } catch (Exception e) {
            reportRenderFailure("entity " + entity, e);
            if (ConfigHandler.debugPrintStacktraces) {
                e.printStackTrace();
            }
        } finally {
            GlStateManager.popMatrix();
        }

        this.passSpecialRender(entity, x, y, z);
    }

    /**
     * The entity's body yaw for this frame, sitting-aware: the entity's own interpolated yaw, or - when a sitting
     * entity's head is clamped against the seat it is riding - the yaw left after that clamp. This is the value
     * {@link #applyRotations} rotates by.
     *
     * <p>
     * Split out of {@link #doRender} unchanged (the same computation, with the head yaw passed in) so that the frame's
     * event and the frame's draw cannot disagree about the body yaw. Engine edit recorded in
     * {@code .agent/phase22-first-person-arm-pose.md} and {@code PROVENANCE.md}.
     */
    protected float resolveBodyYaw(EntityLivingBase entity, float partialTicks, boolean shouldSit) {
        float f = Interpolations.lerpYaw(entity.prevRenderYawOffset, entity.renderYawOffset, partialTicks);
        if (!shouldSit || !(entity.ridingEntity instanceof EntityLivingBase livingentity)) {
            return f;
        }
        float headYaw = Interpolations.lerpYaw(entity.prevRotationYawHead, entity.rotationYawHead, partialTicks);
        f = Interpolations.lerpYaw(livingentity.prevRenderYawOffset, livingentity.renderYawOffset, partialTicks);
        float f3 = wrapDegrees(headYaw - f);
        if (f3 < -85.0F) {
            f3 = -85.0F;
        }
        if (f3 >= 85.0F) {
            f3 = 85.0F;
        }
        f = headYaw - f3;
        if (f3 * f3 > 2500.0F) {
            f += f3 * 0.2F;
        }
        return f;
    }

    /**
     * Builds the animation event for one rendered frame of {@code entity}, over {@code eventAnimatable}.
     *
     * <p>
     * Extracted from {@link #doRender} unchanged so that a second animatable bound to the same entity can be ticked
     * with the same per-frame values: the port's first-person arm is its own animatable
     * ({@code com.fox.ysmu.client.entity.CustomFirstPersonArmEntity}) and is posed by the same event its body would
     * get. Upstream has exactly this shape - one event builder per entity, used by both the body and the arm
     * ({@code geckolib3/model/AnimatableEntity.java:280-339} builds it, {@code :341-390} ticks with it, and
     * {@code client/entity/CustomFirstPersonArmEntity.java:105-108} is the arm's own setup). Engine edit recorded in
     * {@code .agent/phase22-first-person-arm-pose.md} and {@code PROVENANCE.md}.
     *
     * @param eventAnimatable the animatable this event is for; the renderer's own for a world draw, the arm's own for
     *                        a first-person arm tick
     */
    public AnimationEvent<T> buildAnimationEvent(EntityLivingBase entity, float partialTicks, T eventAnimatable) {
        // TODO: entity.isPassenger() looks redundant here
        boolean shouldSit = /* entity.isPassenger() && */ (entity.ridingEntity != null
            && entity.ridingEntity.shouldRiderSit());
        EntityModelData entityModelData = new EntityModelData();
        entityModelData.isSitting = shouldSit;
        entityModelData.isChild = entity.isChild();

        float f1 = Interpolations.lerpYaw(entity.prevRotationYawHead, entity.rotationYawHead, partialTicks);
        float f = this.resolveBodyYaw(entity, partialTicks, shouldSit);
        float netHeadYaw = f1 - f;
        if (!shouldSit || !(entity.ridingEntity instanceof EntityLivingBase)) {
            // 修复头部旋转角度突变问题
            netHeadYaw = wrapDegrees(netHeadYaw);
        }

        float headPitch = Interpolations.lerp(entity.prevRotationPitch, entity.rotationPitch, partialTicks);
        /*
         * TODO: vanilla mobs can't sleep in beds in 1.12.2 and below if
         * (entity.getPose() == Pose.SLEEPING) { Direction direction =
         * entity.getBedDirection(); if (direction != null) { float f4 =
         * entity.getEyeHeight(Pose.STANDING) - 0.1F; stack.translate((double) ((float)
         * (-direction.getXOffset()) * f4), 0.0D, (double) ((float)
         * (-direction.getZOffset()) * f4)); } }
         */

        float limbSwingAmount = 0.0F;
        float limbSwing = 0.0F;
        if (!shouldSit && entity.isEntityAlive()) {
            limbSwingAmount = Interpolations.lerp(entity.prevLimbSwingAmount, entity.limbSwingAmount, partialTicks);
            limbSwing = entity.limbSwing - entity.limbSwingAmount * (1.0F - partialTicks);
            if (entity.isChild()) {
                limbSwing *= 3.0F;
            }

            if (limbSwingAmount > 1.0F) {
                limbSwingAmount = 1.0F;
            }
        }
        entityModelData.headPitch = -headPitch;
        // 与上游一致：净头部偏航写进帧数据前夹到 ±85°（dev-1.20
        // AnimatableEntity.java:326-327 的 `-Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85)`）。
        // 骑乘分支的 resolveBodyYaw 已经让 netHeadYaw 落在 ±85 内；非骑乘分支上面只 wrap 不 clamp，
        // 于是 ysm.head_yaw / query.head_x_rotation 以及 codeAnimation 给 Head 骨骼加的偏航会拿到
        // >85° 的值（远程玩家查询层自己另有一次 ±85 夹取，只盖住了查询名字，盖不住帧数据本身）。
        entityModelData.netHeadYaw = -MathHelper.clamp_float(netHeadYaw, -85.0F, 85.0F);

        return new AnimationEvent<>(
            eventAnimatable,
            limbSwing,
            limbSwingAmount,
            partialTicks,
            !(limbSwingAmount > -0.15F && limbSwingAmount < 0.15F),
            Collections.singletonList(entityModelData));
    }

    /**
     * Draws every registered layer (held item, armor, back attachments) for one frame.
     * <p>
     * 附加层（手持物、护甲等）不再只对玩家开放：穿 YSM 模型的女仆不是 EntityPlayer，但同样需要
     * 把物品挂在她自己的骨骼上画出来。因此这里放宽到任何 EntityLivingBase。
     * 安全跳过条件：这一帧必须同时有 animatable 和可用的 geoModel。缺任一项时层没有骨架可挂
     * （取骨骼或贴图时会 NPE），所以宁可不画；层自身也应各自检查自己的 geoModel 是否可用。
     * <p>
     * Called before or after the model depending on {@link #shouldRenderLayersFirst(Object)}.
     */
    private void renderLayers(GeoModel model, EntityLivingBase entity, float limbSwing, float limbSwingAmount,
        float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, Color renderColor) {
        if (animatable == null || model == null) {
            return;
        }
        for (GeoLayerRenderer layerRenderer : this.layerRenderers) {
            layerRenderer.render(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch,
                renderColor);
        }
    }

    protected void preRenderCallback(EntityLivingBase entitylivingbaseIn, float partialTickTime) {}

    @Nullable
    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return getTextureLocation(currentAnimatable);
    }

    @Override
    public AnimatedGeoModel getGeoModelProvider() {
        return this.modelProvider;
    }

    @Override
    public float getWidthScale(Object animatable) {
        return this.widthScale;
    }

    @Override
    public float getHeightScale(Object entity) {
        return this.heightScale;
    }

    protected void applyRotations(EntityLivingBase entityLiving, float ageInTicks, float rotationYaw,
        float partialTicks) {
        if (!entityLiving.isPlayerSleeping()) {
            GlStateManager.rotate(180.0F - rotationYaw, 0, 1, 0);
        }

        if (entityLiving.deathTime > 0) {
            float f = ((float) entityLiving.deathTime + partialTicks - 1.0F) / 20.0F * 1.6F;
            f = MathHelper.sqrt_float(f);
            if (f > 1.0F) {
                f = 1.0F;
            }

            GlStateManager.rotate(f * this.getDeathMaxRotation(entityLiving), 0, 0, 1);
        }
        else if (entityLiving.isPlayerSleeping()) {
            float sleepRotation = 0.0F;
            if (entityLiving instanceof EntityPlayer player) {
                if (player.playerLocation != null) {
                    try {
                        int direction = player.worldObj.getBlockMetadata(
                            player.playerLocation.posX,
                            player.playerLocation.posY,
                            player.playerLocation.posZ
                        ) & 3;
                        // 床的方向
                        switch (direction) {
                            case 0: sleepRotation = 180.0F; break;
                            case 1: sleepRotation = 90.0F; break;
                            case 2: sleepRotation = 0.0F; break;
                            case 3: sleepRotation = 270.0F; break;
                        }

                    } catch (Exception e) {
                        sleepRotation = 180.0F - rotationYaw;
                    }
                }
            }

            GlStateManager.rotate(sleepRotation, 0, 1, 0);
            GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);//旋转玩家
            GlStateManager.translate(0.0F, -1.8F, 0.0F); //向后偏移1.8
        }
        else if ((entityLiving instanceof EntityLiving && ((EntityLiving) entityLiving).hasCustomNameTag())
            || entityLiving instanceof EntityPlayer) {
                String s = ChatFormatting.stripFormatting(entityLiving.getCommandSenderName());
                if (("Dinnerbone".equals(s) || "Grumm".equals(s)) && (!(entityLiving instanceof EntityPlayer))) {
                    GlStateManager.translate(0.0D, (double) (entityLiving.height + 0.1F), 0.0D);
                    GlStateManager.rotate(180, 0, 0, 1);
                }
            }
    }

    protected boolean isVisible(EntityLivingBase livingEntityIn) {
        return !livingEntityIn.isInvisible();
    }

    @SuppressWarnings("unused")
    private static float getFacingAngle(EnumFacing facingIn) {
        switch (facingIn) {
            case SOUTH:
                return 90.0F;
            case WEST:
                return 0.0F;
            case NORTH:
                return 270.0F;
            case EAST:
                return 180.0F;
            default:
                return 0.0F;
        }
    }

    protected float getDeathMaxRotation(EntityLivingBase entityLivingBaseIn) {
        return 90.0F;
    }

    /**
     * Returns where in the swing animation the living entity is (from 0 to 1). Args
     * : entity, partialTickTime
     */
    protected float getSwingProgressForGeo(EntityLivingBase livingBase, float partialTickTime) {
        return livingBase.getSwingProgress(partialTickTime);
    }

    /**
     * Defines what float the third param in setRotationAngles of ModelBase is
     */
    protected float handleRotationFloat(EntityLivingBase livingBase, float partialTicks) {
        return (float) livingBase.ticksExisted + partialTicks;
    }

    public final boolean addLayer(GeoLayerRenderer<? extends EntityLivingBase> layer) {
        return this.layerRenderers.add(layer);
    }

    @Override
    public ResourceLocation getTextureLocation(Object instance) {
        return this.modelProvider.getTextureLocation(this.animatable);
    }

    protected void renderLeash(EntityLiving entityLivingIn, double x, double y, double z, float entityYaw,
        float partialTicks) {
        Entity entity = entityLivingIn.getLeashedToEntity();

        if (entity != null) {
            y = y - (1.6D - (double) entityLivingIn.height) * 0.5D;
            Tessellator tessellator = Tessellator.instance;
            double d0 = this.interpolateValue(
                (double) entity.prevRotationYaw,
                (double) entity.rotationYaw,
                (double) (partialTicks * 0.5F)) * 0.01745329238474369D;
            double d1 = this.interpolateValue(
                (double) entity.prevRotationPitch,
                (double) entity.rotationPitch,
                (double) (partialTicks * 0.5F)) * 0.01745329238474369D;
            double d2 = Math.cos(d0);
            double d3 = Math.sin(d0);
            double d4 = Math.sin(d1);

            if (entity instanceof EntityHanging) {
                d2 = 0.0D;
                d3 = 0.0D;
                d4 = -1.0D;
            }

            double d5 = Math.cos(d1);
            double d6 = this.interpolateValue(entity.prevPosX, entity.posX, (double) partialTicks) - d2 * 0.7D
                - d3 * 0.5D * d5;
            double d7 = this.interpolateValue(
                entity.prevPosY + (double) entity.getEyeHeight() * 0.7D,
                entity.posY + (double) entity.getEyeHeight() * 0.7D,
                (double) partialTicks) - d4 * 0.5D - 0.25D;
            double d8 = this.interpolateValue(entity.prevPosZ, entity.posZ, (double) partialTicks) - d3 * 0.7D
                + d2 * 0.5D * d5;
            double d9 = this.interpolateValue(
                (double) entityLivingIn.prevRenderYawOffset,
                (double) entityLivingIn.renderYawOffset,
                (double) partialTicks) * 0.01745329238474369D + (Math.PI / 2D);
            d2 = Math.cos(d9) * (double) entityLivingIn.width * 0.4D;
            d3 = Math.sin(d9) * (double) entityLivingIn.width * 0.4D;
            double d10 = this.interpolateValue(entityLivingIn.prevPosX, entityLivingIn.posX, (double) partialTicks)
                + d2;
            double d11 = this.interpolateValue(entityLivingIn.prevPosY, entityLivingIn.posY, (double) partialTicks);
            double d12 = this.interpolateValue(entityLivingIn.prevPosZ, entityLivingIn.posZ, (double) partialTicks)
                + d3;
            x = x + d2;
            z = z + d3;
            double d13 = (double) ((float) (d6 - d10));
            double d14 = (double) ((float) (d7 - d11));
            double d15 = (double) ((float) (d8 - d12));
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            tessellator.startDrawing(5);// , DefaultVertexFormats.POSITION_COLOR);

            for (int j = 0; j <= 24; ++j) {
                float f = 0.5F;
                float f1 = 0.4F;
                float f2 = 0.3F;

                if (j % 2 == 0) {
                    f *= 0.7F;
                    f1 *= 0.7F;
                    f2 *= 0.7F;
                }

                float f3 = (float) j / 24.0F;
                tessellator.setColorRGBA_F(f, f1, f2, 1.0F);
                tessellator.addVertex(
                    x + d13 * (double) f3 + 0.0D,
                    y + d14 * (double) (f3 * f3 + f3) * 0.5D + (double) ((24.0F - (float) j) / 18.0F + 0.125F),
                    z + d15 * (double) f3);
                tessellator.setColorRGBA_F(f, f1, f2, 1.0F);
                tessellator.addVertex(
                    x + d13 * (double) f3 + 0.025D,
                    y + d14 * (double) (f3 * f3 + f3) * 0.5D + (double) ((24.0F - (float) j) / 18.0F + 0.125F) + 0.025D,
                    z + d15 * (double) f3);
            }
            software.bernie.geckolib3.util.TessellatorBufferKeep.draw(tessellator);
            tessellator.startDrawing(5);

            for (int k = 0; k <= 24; ++k) {
                float f4 = 0.5F;
                float f5 = 0.4F;
                float f6 = 0.3F;

                if (k % 2 == 0) {
                    f4 *= 0.7F;
                    f5 *= 0.7F;
                    f6 *= 0.7F;
                }

                float f7 = (float) k / 24.0F;
                tessellator.setColorRGBA_F(f4, f5, f6, 1.0F);
                tessellator.addVertex(
                    x + d13 * (double) f7 + 0.0D,
                    y + d14 * (double) (f7 * f7 + f7) * 0.5D + (double) ((24.0F - (float) k) / 18.0F + 0.125F) + 0.025D,
                    z + d15 * (double) f7);
                tessellator.setColorRGBA_F(f4, f5, f6, 1.0F);
                tessellator.addVertex(
                    x + d13 * (double) f7 + 0.025D,
                    y + d14 * (double) (f7 * f7 + f7) * 0.5D + (double) ((24.0F - (float) k) / 18.0F + 0.125F),
                    z + d15 * (double) f7 + 0.025D);
            }

            software.bernie.geckolib3.util.TessellatorBufferKeep.draw(tessellator);
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.enableCull();
        }
    }

    private double interpolateValue(double start, double end, double pct) {
        return start + (end - start) * pct;
    }
}
