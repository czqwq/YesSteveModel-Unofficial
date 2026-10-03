package com.fox.ysmu.util;

import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import com.fox.ysmu.client.ClientProxy;
import com.fox.ysmu.client.audio.YSMSoundManager;
import com.fox.ysmu.client.gui.ModelPreviewAnimationState;
import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.vector.Quaternion;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.render.ModelPoseSnapshot;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.compat.Axis;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.Utils;
import com.fox.ysmu.ysmu;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;

@SuppressWarnings("all")
public final class RenderUtil {

    private static final float GUI_LIGHTMAP_BRIGHTNESS = 240.0F;

    /**
     * Whether the diagnostic below reports. {@code -Dtlmd.gl.diag=true}, the same switch the port's own diagnostic
     * uses, so one run answers for both sides.
     */
    public static void withGuiEntityLighting(Runnable renderAction) {
        GuiEntityLightingState state = GuiEntityLightingState.capture();
        try {
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            setLightmapTextureEnabled(true);
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                GUI_LIGHTMAP_BRIGHTNESS,
                GUI_LIGHTMAP_BRIGHTNESS);
            renderAction.run();
        } finally {
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                state.brightnessX,
                state.brightnessY);
            setLightmapTextureEnabled(state.lightmapTextureEnabled);
            setEnabled(GL12.GL_RESCALE_NORMAL, state.rescaleNormalEnabled);
            setEnabled(GL11.GL_COLOR_MATERIAL, state.colorMaterialEnabled);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void setLightmapTextureEnabled(boolean enabled) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        setEnabled(GL11.GL_TEXTURE_2D, enabled);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    private static final class GuiEntityLightingState {

        private final float brightnessX;
        private final float brightnessY;
        private final boolean lightmapTextureEnabled;
        private final boolean rescaleNormalEnabled;
        private final boolean colorMaterialEnabled;

        private GuiEntityLightingState(float brightnessX, float brightnessY, boolean lightmapTextureEnabled,
            boolean rescaleNormalEnabled, boolean colorMaterialEnabled) {
            this.brightnessX = brightnessX;
            this.brightnessY = brightnessY;
            this.lightmapTextureEnabled = lightmapTextureEnabled;
            this.rescaleNormalEnabled = rescaleNormalEnabled;
            this.colorMaterialEnabled = colorMaterialEnabled;
        }

        private static GuiEntityLightingState capture() {
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            boolean lightmapTextureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            return new GuiEntityLightingState(
                OpenGlHelper.lastBrightnessX,
                OpenGlHelper.lastBrightnessY,
                lightmapTextureEnabled,
                GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL),
                GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL));
        }
    }

    public static void renderTextureScreenEntity(float pPosX, float pPosY, float pScale, float pitch, float yaw,
        EntityPlayer player, ResourceLocation modelId, ResourceLocation textureId, boolean showGround,
        Consumer<CustomPlayerEntity> consumer) {
        if (player == null) {
            return;
        }
        try {
            IAnimatable animatable = AnimatableCacheUtil.TEXTURE_GUI_CACHE.get(modelId, CustomPlayerEntity::new);
            if (animatable instanceof CustomPlayerEntity entity) {
                consumer.accept(entity);

                entity.setMainModel(ModelIdUtil.getMainId(modelId));
                entity.setTexture(textureId);

                GlStateManager.pushMatrix();
                GlStateManager.matrixMode(GL11.GL_MODELVIEW);
                GlStateManager.translate(pPosX, pPosY, 1050.0D);
                GlStateManager.scale(1.0F, 1.0F, -1.0F);

                GlStateManager.pushMatrix();
                GlStateManager.translate(0.0D, 0.0D, 1000.0D);
                GlStateManager.scale(pScale, pScale, pScale);
                GlStateManager.translate(0, 0.8, 0);
                Quaternionf zp = Axis.ZP.rotationDegrees(180.0F);
                Quaternionf xp = Axis.XP.rotationDegrees(-10 + pitch);
                zp.mul(xp);
                GlStateManager.rotate(j2l(zp)); // poseStack.mulPose

                // 保存玩家原始状态
                float yBodyRot = player.renderYawOffset;
                float yRot = player.rotationYaw;
                float xRot = player.rotationPitch;
                float yHeadRotO = player.prevRotationYawHead;
                float yHeadRot = player.rotationYawHead;
                //Pose pose = player.getPose();

                // 修改玩家状态用于渲染
                player.renderYawOffset = -yaw;
                player.rotationYaw = 180; // setYRot
                player.rotationPitch = 0; // setXRot
                player.rotationYawHead = player.rotationYaw;
                player.prevRotationYawHead = player.rotationYaw;

                try {
                    RenderHelper.enableGUIStandardItemLighting();
                    RenderManager dispatcher = RenderManager.instance;

                    xp.conjugate();
                    //dispatcher.overrideCameraOrientation(xp);
                    //dispatcher.setRenderShadow(false);

                    withGuiEntityLighting(() -> {
                        GlStateManager.pushMatrix();
                        try {
                            if (entity.hasPreviewAnimation("sleep")) {
                                GlStateManager.rotate(j2l(Axis.YP.rotationDegrees(yaw - 90)));
                                GlStateManager.translate(0.5, 0.5625, 0);
                                // TODO sleep和sneak要处理下
                                // player.setPose(Pose.SLEEPING);
                            }
                            if (entity.hasPreviewAnimation("swim") || entity.hasPreviewAnimation("swim_stand")) {
                                // player.setPose(Pose.SWIMMING);
                            }
                            if (entity.hasPreviewAnimation("sneak") || entity.hasPreviewAnimation("sneaking")) {
                                // player.setPose(Pose.CROUCHING);
                            }
                            if (entity.hasPreviewAnimation("sit")) {
                                GlStateManager.translate(0, -0.5, 0);
                            }
                            if (entity.hasPreviewAnimation("ride")) {
                                GlStateManager.translate(0, 0.85, 0);
                            }
                            if (entity.hasPreviewAnimation("ride_pig")) {
                                GlStateManager.translate(0, 0.3125, 0);
                            }
                            if (entity.hasPreviewAnimation("boat")) {
                                GlStateManager.translate(0, -0.45, 0);
                            }
                            // renderer.doRender();
                            try {
                                renderExtraEntity(yaw, player, entity, dispatcher);
                            } catch (ExecutionException e) {
                                throw new RuntimeException(e);
                            }
                        } finally {
                            GlStateManager.popMatrix(); // 弹出动画位移矩阵
                        }
                        if (showGround) {
                            if (entity.hasPreviewAnimation("sleep")) {
                                renderBed(pScale, pitch, yaw);
                            }
                            renderGround(pScale, pitch, yaw);
                        }
                    });
                } finally {
                    // 恢复玩家状态
                    player.renderYawOffset = yBodyRot;
                    player.rotationYaw = yRot;
                    player.rotationPitch = xRot;
                    player.prevRotationYawHead = yHeadRotO;
                    player.rotationYawHead = yHeadRot;
                    // player.setPose(pose);

                    GlStateManager.popMatrix(); // 弹出模型变换矩阵
                    GlStateManager.popMatrix(); // 弹出视图变换矩阵
                    RenderHelper.disableStandardItemLighting();
                }
            }
        } catch (ExecutionException e) {
            ysmu.LOG.warn("Failed to render the YSM player preview entity", e);
        }
    }

    // 创建一个全局的RenderBlocks实例以提高效率
    private static final RenderBlocks renderBlocks = new RenderBlocks();

    private static void renderBed(float scale, float pitch, float yaw) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0D, 0.0D, 1000.0D);
        GlStateManager.scale(scale, scale, scale);
        GlStateManager.translate(0, 0.8, 0);
        Quaternionf zp = Axis.ZP.rotationDegrees(180.0F);
        Quaternionf xp = Axis.XP.rotationDegrees(-10 + pitch);
        zp.mul(xp);
        GlStateManager.rotate(j2l(zp));

        GlStateManager.rotate(j2l(Axis.YP.rotationDegrees(yaw + 180)));
        GlStateManager.translate(-0.5, 0, 0.5);
        // Minecraft.getInstance().getBlockRenderer().renderSingleBlock(Blocks.RED_BED.defaultBlockState(), poseStack,
        // bufferSource, 0xf000f0, OverlayTexture.NO_OVERLAY);
        RenderManager.instance.renderEngine.bindTexture(new ResourceLocation("textures/entity/bed/red.png"));
        renderBlocks.renderBlockAsItem(Blocks.bed, 0, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void renderGround(float scale, float pitch, float yaw) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.0D, 0.0D, 1000.0D);
        GlStateManager.scale(scale, scale, scale);
        GlStateManager.translate(0, 0.8, 0);
        Quaternionf zp = Axis.ZP.rotationDegrees(180.0F);
        Quaternionf xp = Axis.XP.rotationDegrees(-10 + pitch);
        zp.mul(xp);
        GlStateManager.rotate(j2l(zp));

        GlStateManager.rotate(j2l(Axis.YP.rotationDegrees(yaw)));
        GlStateManager.translate(-1.5, -1, -2.5);
        RenderManager.instance.renderEngine.bindTexture(new ResourceLocation("textures/atlas/blocks.png"));
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(0, 0, 1);
                renderBlocks.renderBlockAsItem(Blocks.grass, 0, 1.0F);
                GlStateManager.popMatrix();
            }
            GlStateManager.translate(1, 0, -3);
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(-1, 1, 1);
        renderBlocks.renderBlockAsItem(Blocks.tallgrass, 1, 1.0F); // metadata 1 for grass
        GlStateManager.popMatrix();

        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, 1);
        renderBlocks.renderBlockAsItem(Blocks.red_flower, 0, 1.0F); // metadata 0 for poppy (red tulip)
        GlStateManager.popMatrix();

        GlStateManager.popMatrix();
    }

    private static void renderExtraEntity(float yaw, EntityPlayer player, CustomPlayerEntity playerEntity,
        RenderManager dispatcher) throws ExecutionException {
        if (playerEntity.hasPreviewAnimation("ride")) {
            // Entity entity = AnimatableCacheUtil.ENTITIES_CACHE.get(EntityType.getKey(EntityType.HORSE), () ->
            // EntityType.HORSE.create(player.level()));
            // renderExtraEntity(yaw, player, dispatcher, entity);
            return;
        }
        if (playerEntity.hasPreviewAnimation("ride_pig")) {
            // Entity entity = AnimatableCacheUtil.ENTITIES_CACHE.get(EntityType.getKey(EntityType.PIG), () ->
            // EntityType.PIG.create(player.level()));
            // renderExtraEntity(yaw, player, dispatcher, entity);
            return;
        }
        if (playerEntity.hasPreviewAnimation("boat")) {
            // Entity entity = AnimatableCacheUtil.ENTITIES_CACHE.get(EntityType.getKey(EntityType.BOAT), () ->
            // EntityType.BOAT.create(player.level()));
            // renderExtraEntity(yaw, player, dispatcher, entity);
            return;
        }
    }

    private static void renderExtraEntity(float yaw, EntityPlayer player, RenderManager dispatcher, Entity entity) {
        GlStateManager.pushMatrix();
        GlStateManager.rotate(j2l(Axis.YP.rotationDegrees(yaw)));
        double yOffset = -entity.getMountedYOffset();
        dispatcher.renderEntityWithPosYaw(entity, 0, yOffset, 0, 0, 1.0f);
        GlStateManager.popMatrix();
    }

    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, Consumer<CustomPlayerEntity> consumer) {
        if (player == null) {
            return;
        }
        try {
            CustomPlayerRenderer renderer = ClientProxy.getInstance();
            IAnimatable animatable = AnimatableCacheUtil.ANIMATABLE_CACHE.get(modelId, CustomPlayerEntity::new);
            if (animatable instanceof CustomPlayerEntity entity) {
                consumer.accept(entity);
                // A GUI preview poses the shared model with the pack's preview animation and the world must never
                // inherit that pose (this port shares one model where upstream keeps a separate GUI entity); the pose
                // restore lives inside renderModel, because this path draws through IGeoRenderer#render rather than
                // CustomPlayerRenderer#doRenderModel and so never reaches that renderer's own bracket.
                //
                // The bracket here is for a different reader: C-05's emission gate. `CustomPlayerRenderer
                // .isPreviewRendering()` is what stops a preview from spawning world particles, and without this the
                // tile path - which does run the model's controllers and timelines - was the one preview that still
                // did.
                CustomPlayerRenderer.beginPreviewRender();
                try {
                    renderModel(
                        (double) pPosX,
                        (double) pPosY,
                        (float) pScale,
                        player,
                        modelId,
                        textureId,
                        renderer,
                        entity);
                } finally {
                    CustomPlayerRenderer.endPreviewRender();
                }
            }
        } catch (ExecutionException e) {
            ysmu.LOG.warn("Failed to render the YSM player model in the inventory GUI", e);
        }
    }

    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId) {
        renderEntityInInventory(pPosX, pPosY, pScale, player, modelId, textureId, false, false);
    }

    /**
     * Draws a model preview and drives its GUI animation channels, mirroring upstream's catalog card.
     * <p>
     * {@code hovered}/{@code focused} feed {@link ModelPreviewAnimationState}, which decides the preview, hover and
     * focus animation names the entity's controllers then play. The animatable is also allowed to keep ticking while
     * the game is paused, because opening a GUI pauses single-player and the engine refuses to advance an animatable
     * that has not opted in - without it the preview would show a frozen pose.
     */
    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, boolean hovered, boolean focused) {
        renderEntityInInventory(pPosX, pPosY, pScale, player, modelId, textureId, entity -> {
            ModelPreviewAnimationState.forModel(modelId)
                .apply(entity.getPreviewInfo(), hovered, focused, Minecraft.getSystemTime());
            entity.getFactory()
                .getOrCreateAnimationData(entity.hashCode()).shouldPlayWhilePaused = true;
        });
    }

    private static void renderModel(double pPosX, double pPosY, float pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, GeoReplacedEntityRenderer renderer,
        CustomPlayerEntity entity) {
        entity.setMainModel(ModelIdUtil.getMainId(modelId));
        entity.setTexture(textureId);

        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // 与上游 RenderUtil:324-334 逐字对齐：位置/缩放/Z 轴翻转 180°；声明 disable_preview_rotation 的模型
        // 改为不俯仰并上移 5.5，否则俯仰 -10°。本移植原本在这里用的是自加的 -25° 三轴倾斜。
        boolean disablePreviewRotation = com.fox.ysmu.client.gui.ModelPreviewRegistry
            .previewRotationDisabled(modelId);
        // 上游 RenderUtil:360 的 yRotGui 同时喂给两个地方：身体/头部角度，以及
        // GeoReplacedEntityRenderer:71 的 setupRotations（LivingEntityRenderer 的实体朝向）。
        // 本项目预览走的是 GeckoLib 的 IGeoRenderer.render，它只遍历 topLevelBones、完全不读实体朝向
        // （世界路径才会经 doRender:128 调 applyRotations:236），所以 setupRotations 的那层
        // Ry(180 - yBodyRot) 必须在预览路径上自己补，否则 disable_preview_rotation=false 的模型
        // 会少掉上游的 -20° 旋转：包的 gui 动画把角色和自带边框一起放到了远离锚点的位置，缺了这
        // 20° 整组就会在格子里偏心/偏下（=true 的模型 Ry(0)，所以看不出差别）。
        float yRotGui = disablePreviewRotation ? 180.0F : 200.0F;
        GL11.glPushMatrix();
        GL11.glTranslatef((float) pPosX, (float) pPosY, 100.0F);
        if (disablePreviewRotation) {
            GL11.glTranslatef(0.0F, 5.5F, 0.0F);
        }
        GL11.glScalef(pScale, pScale, -pScale);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F); // 将模型从倒置状态翻转过来
        GL11.glRotatef(disablePreviewRotation ? 0.0F : -10.0F, 1.0F, 0.0F, 0.0F); // 上游的俯仰角
        GL11.glRotatef(180.0F - yRotGui, 0.0F, 1.0F, 0.0F); // 上游 setupRotations 的实体朝向

        // 保存玩家状态
        float yBodyRot = player.renderYawOffset;
        float yBodyRotO = player.prevRenderYawOffset;
        float yRot = player.rotationYaw;
        float xRot = player.rotationPitch;
        float yHeadRotO = player.prevRotationYawHead;
        float yHeadRot = player.rotationYawHead;

        // 0-3 是盔甲
        ItemStack[] itemStacks = new ItemStack[6];
        itemStacks[0] = player.inventory.armorItemInSlot(3); // 头盔
        itemStacks[1] = player.inventory.armorItemInSlot(2);
        itemStacks[2] = player.inventory.armorItemInSlot(1);
        itemStacks[3] = player.inventory.armorItemInSlot(0);
        itemStacks[4] = player.inventory.getCurrentItem();
        itemStacks[5] = BackhandCompat.getOffhandItem(player);
        // S-05:所有会改动玩家/GL 状态的步骤都放进 try,保证任何异常(包括光照与矩阵
        // 调用抛出的异常)都不会把玩家物品栏、旋转角度或 GL 矩阵栈留在被污染的状态。
        //
        // 同时抑制本次 GUI 预览的音效播放：模型只在按钮上被悬停时，关键帧音效不该响起来
        // （SOURCE RenderUtil.java:542/701 用 YSMSoundManager.setPreviewRendering 把整个预览
        //  方法括起来）。这里括住同一段渲染（模型求值 + 提交），true 紧贴配对的 try，
        //  所以任何抛出路径都不会把标志留在 true 上。
        YSMSoundManager.setPreviewRendering(true);
        try {
            // The tile draws a detached preview entity, which has no player of its own; naming the owner here is what
            // lets the model read the same v.roaming.* values it would read in the world (see
            // CustomPlayerEntity#getMolangVariables). Cleared in the finally below.
            entity.setPreviewOwner(player);
            // 清空玩家物品以避免在模型上渲染
            player.inventory.mainInventory[player.inventory.currentItem] = null;
            BackhandCompat.setOffhandItem(player, null);
            for (int i = 0; i < 4; i++) {
                player.inventory.armorInventory[i] = null;
            }

            // 设置渲染状态：与上游 RenderUtil:360-368 一致——yBodyRot/yBodyRotO 与身体、头部使用同一个
            // yRotGui（上游把 yRotGui 同时写进 yBodyRot 和 yBodyRotO，供 setupRotations 使用）。
            player.renderYawOffset = yRotGui;
            player.prevRenderYawOffset = yRotGui;
            player.rotationYaw = yRotGui;
            player.rotationPitch = 0;
            player.rotationYawHead = yRotGui;
            player.prevRotationYawHead = yRotGui;

            GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
            withGuiEntityLighting(() -> {
                AnimatedGeoModel provider = renderer.getGeoModelProvider();
                ResourceLocation modelLocation = provider.getModelLocation(entity);
                GeoModel model = provider.getModel(modelLocation);
                // D-01: this path draws through IGeoRenderer#render instead of CustomPlayerRenderer#doRenderModel,
                // and the renderer's own preview brace (previewRenderDepth -> ModelPoseSnapshot) is read only
                // inside doRenderModel. The bracket around this call therefore never captured anything, so the
                // tile's preview pose was left on the shared GeoModel for whichever path draws that model next
                // (the world render and the HUD overlay both skip bones their animation does not drive).
                // Upstream needs no brace here because a preview owns a dedicated entity; this port shares one
                // model, so the tile has to put the pose back itself.
                ModelPoseSnapshot pose = ModelPoseSnapshot.capture(model);
                try {
                    // C-07: the frame's partial tick, which is what upstream hands a catalog tile
                    // (`CatalogModelButton.java:157-158` passes `Minecraft.getInstance().getFrameTime()` into
                    // `RenderUtil.renderModelInGui`). The two limb values stay 0 because that is what upstream would
                    // compute here too, not for convenience: it reads them off the preview entity's own walk animation
                    // (`AnimatableEntity.java:287-296`), that entity is a stand-in which never walks, and no GUI or
                    // preview code drives it.
                    AnimationEvent<CustomPlayerEntity> predicate = new AnimationEvent<>(
                        entity,
                        0,
                        0,
                        Minecraft.getMinecraft().timer.renderPartialTicks,
                        false,
                        Collections.emptyList());
                    if (renderer.getGeoModelProvider() instanceof IAnimatableModel) {
                        ((IAnimatableModel<CustomPlayerEntity>) renderer.getGeoModelProvider()).setLivingAnimations(entity, entity.hashCode(), predicate);
                    }
                    Minecraft.getMinecraft().getTextureManager().bindTexture(provider.getTextureLocation(entity));
                    diagnosePreviewPose(modelId, model);
                    // The preview asks for a render type the way every upstream draw does - visible, not glowing, and
                    // translucent when the model's art is - and hands it to the draw
                    // (com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:18-39).
                    renderer.render(
                        model,
                        entity,
                        renderer.getRenderType(provider.getTextureLocation(entity), true, false,
                            entity.hasTranslucentVertices()),
                        0,
                        1.0f,
                        1.0f,
                        1.0f,
                        1.0f);
                } finally {
                    pose.restore();
                }
            });
        } finally {
            YSMSoundManager.setPreviewRendering(false);
            RenderHelper.disableStandardItemLighting();
            GL11.glPopMatrix();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            // The preview entity is cached and reused for every tile, so the owner must not survive this frame.
            entity.setPreviewOwner(null);

            // 恢复状态
            player.renderYawOffset = yBodyRot;
            player.prevRenderYawOffset = yBodyRotO;
            player.rotationYaw = yRot;
            player.rotationPitch = xRot;
            player.prevRotationYawHead = yHeadRotO;
            player.rotationYawHead = yHeadRot;

            player.inventory.armorInventory[3] = itemStacks[0];
            player.inventory.armorInventory[2] = itemStacks[1];
            player.inventory.armorInventory[1] = itemStacks[2];
            player.inventory.armorInventory[0] = itemStacks[3];
            player.inventory.mainInventory[player.inventory.currentItem] = itemStacks[4];
            BackhandCompat.setOffhandItem(player, itemStacks[5]);
        }
    }

    /**
     * TEMPORARY (see {@code .agent/phase15-roaming-variables.md}). Logs what a preview tile is about to draw: every
     * top-level bone's scale and position, plus the bone named {@code Root} when the model has one, because those are
     * the two things that can move a whole tile - a root bone scaled to 0 (the model collapses) and a root bone offset
     * that flips between two authored positions (the model jumps out of the tile and back).
     * <p>
     * Throttled to one line a second so it can be left on while looking at a screen. Set to {@code false}, or delete
     * this field with {@link #diagnosePreviewPose}, once the movement is identified.
     */
    private static final boolean DIAGNOSE_PREVIEW_POSE = true;

    private static long lastPreviewPoseLogAt;

    /** TEMPORARY, see {@link #DIAGNOSE_PREVIEW_POSE}. Never throws: a diagnostic must not fail a frame. */
    private static void diagnosePreviewPose(ResourceLocation modelId, GeoModel model) {
        if (!DIAGNOSE_PREVIEW_POSE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPreviewPoseLogAt < 1000L) {
            return;
        }
        lastPreviewPoseLogAt = now;
        try {
            StringBuilder line = new StringBuilder();
            for (software.bernie.geckolib3.geo.render.built.GeoBone root : model.topLevelBones) {
                line.append(root.getName())
                    .append("[s=")
                    .append(root.getScaleX())
                    .append('/')
                    .append(root.getScaleY())
                    .append('/')
                    .append(root.getScaleZ())
                    .append(" p=")
                    .append(root.getPositionX())
                    .append('/')
                    .append(root.getPositionY())
                    .append('/')
                    .append(root.getPositionZ())
                    .append("] ");
            }
            for (software.bernie.geckolib3.geo.render.built.GeoBone bone : model.topLevelBones) {
                for (software.bernie.geckolib3.geo.render.built.GeoBone child : bone.childBones) {
                    if ("Root".equals(child.getName())) {
                        line.append("Root[s=")
                            .append(child.getScaleX())
                            .append(" p=")
                            .append(child.getPositionX())
                            .append('/')
                            .append(child.getPositionY())
                            .append('/')
                            .append(child.getPositionZ())
                            .append("] ");
                    }
                }
            }
            ysmu.LOG.info("preview pose model={} {}", modelId, line.toString()
                .trim());
        } catch (Throwable ignored) {
            // Diagnostic only.
        }
    }

    /**
     * Draws the "extra player" overlay - the paper doll the HUD shows.
     * <p>
     * {@code partialTicks} is the frame's, not a constant: upstream passes one down the same path
     * ({@code RenderUtil#renderExtraPlayerEntity}, called with {@code mc.getFrameTime()} from
     * {@code client/gui/overlay/ExtraPlayerScreen.java}) and its engine even compensates when a caller hands it a
     * literal {@code 1f} ({@code geckolib3/model/AnimatableEntity.java:284}). Passing {@code 1.0F} here used to make
     * the paper doll a *different* render state from the world's at the same tick - same animation data, different
     * {@code partialTick} - which is what kept the engine's per-frame de-duplication from firing and exposed the
     * two-processes-per-frame defect behind "third person sprint does not animate".
     */
    public static void renderPlayerEntity(EntityPlayer player, double posX, double posY, float scale, float yawOffset,
        double z, float partialTicks) {
        if (player != Minecraft.getMinecraft().thePlayer) return;  // 不知道为什么如果不加这句，额外玩家会渲染串了
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef((float) (posX + scale * 0.5), (float) (posY + scale * 2), (float) z);
            GL11.glScalef(-scale, scale, scale);
            GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(player.rotationYaw + yawOffset, 0.0F, 1.0F, 0.0F);

            GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);

            GL11.glTranslatef(0.0F, player.yOffset, 0.0F);
            withGuiEntityLighting(
                () -> RenderManager.instance
                    .renderEntityWithPosYaw(player, 0.0D, 0.0D, 0.0D, 0.0F, partialTicks));
        } finally {
            GL11.glPopMatrix();
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
    }

    private static Quaternion j2l(Quaternionf jomlQuat) {
        return Utils.j2l(jomlQuat);
    }
}
