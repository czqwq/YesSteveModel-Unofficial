package com.fox.ysmu.util;

import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import com.fox.ysmu.Config;
import com.fox.ysmu.client.audio.YSMSoundManager;
import com.fox.ysmu.client.gui.ModelPreviewAnimationState;
import com.fox.ysmu.client.gui.ModelPreviewRegistry;
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
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.vector.Quaternion;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.render.ModelPoseSnapshot;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.compat.Axis;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.Utils;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.ysmu;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.RenderContext;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.GeoStats;

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

    /**
     * 缩略图烘焙的完整入口：允许"姿态与上次真正画出去的相同就跳过几何提交"，并把离屏 FBO 的清除推迟到
     * 确实要画的那一刻。
     *
     * <p>参考分支 {@code util/RenderUtil.java:445-514}（{@code clearBeforeDraw} 的 javadoc 在
     * {@code :529-538}）。两个参数都是必需的，理由与参考相同：</p>
     * <ul>
     *   <li>{@code clearBeforeDraw} 只在**确实要画**之前调用一次（见 {@code renderModel}）。先清 FBO 再发现
     *       姿态没变而跳过，就会留下一张空白缩略图 —— 这是这套缓存最典型的失败模式。</li>
     *   <li>{@code allowPoseSkip} 只对"一个 tile 一个模型"的缩略图页打开。贴图页同一个主模型有 N 张贴图，
     *       共用同一个签名 key，打开只会互相顶掉（参考 {@code :532-533} 的同一句说明）。</li>
     * </ul>
     *
     * <p>{@code preview} 是这一格自己的预览实体，由调用方持有：上游在 {@code PlayerModelScreen.java:56} 就是
     * 每格一个 {@code CustomGuiPlayerEntity}（{@code CatalogModelButton.java:41} 持有它，{@code :129} 用它驱动
     * preview/hover/focus 通道），所以格子的身份跨帧稳定，而且
     * {@link CustomGuiPlayerEntity#determineImmutableContext} 会在这一格上生效 —— 上游正是靠它让一帧内只算一次
     * 姿势（{@code client/gui/CustomGuiPlayerEntity.java:43-46}）。它的渲染器是这一格自己的
     * （{@link CustomGuiPlayerEntity#getPreviewRenderer()}），因为 {@code AnimationController} 的 model fetcher 对
     * 这个 animatable 返回的就是它自己的 {@code CustomPlayerModel}
     * （{@code client/entity/CustomGuiPlayerEntity.java:32-36}），骨骼必须和控制器解析到的模型是同一个。
     *
     * @param hovered         {@code true} 时这一格播 hover 通道；{@code false} 回到 preview 通道
     * @param focused         {@code true} 时这一格播 focus 通道（贴图页把"已选中"当 focus 传下来）
     * @param clearBeforeDraw 调用方传进来的 FBO 清除动作，可为 {@code null}
     * @param allowPoseSkip   是否允许姿态签名命中时跳过几何提交
     * @return {@code true} = 这一帧真的提交了几何；{@code false} = 姿态没变、复用了 FBO 里的画面（或模型不可用）。
     *         调用方据此决定要不要把耗时喂给 {@code PreviewRefreshPolicy}：跳过时不喂，否则估计成本会塌到零、
     *         刷新频率被推到上限，而每次 tick 的开销反而成倍增加（参考 {@code :351-356}）。
     */
    public static boolean renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer owner,
        ResourceLocation modelId, ResourceLocation textureId, boolean hovered, boolean focused,
        CustomGuiPlayerEntity preview, Runnable clearBeforeDraw, boolean allowPoseSkip) {
        if (owner == null || preview == null || Minecraft.getMinecraft().theWorld == null) {
            return false;
        }
        preview.prepare(owner, modelId);
        ModelPreviewAnimationState.forModel(modelId)
            .apply(preview.getPreviewInfo(), hovered, focused, Minecraft.getSystemTime());
        // Opening a GUI pauses single-player and the engine refuses to advance an animatable that has not opted in -
        // without this the tile shows a frozen pose.
        preview.getFactory()
            .getOrCreateAnimationData(preview.hashCode()).shouldPlayWhilePaused = true;
        // A GUI preview poses the shared GeoModel with the pack's preview animation and the world must never inherit
        // that pose; the restore lives inside renderModel, because this path draws through IGeoRenderer#render rather
        // than CustomPlayerRenderer#doRenderModel and so never reaches that renderer's own bracket.
        //
        // The bracket carries two more things: this frame's pass (inventoryPass, which renderModel attaches to the
        // event it builds) and C-05's emission gate. `CustomPlayerRenderer.isPreviewRendering()` is what stops a
        // preview from spawning world particles, and without this the tile path - which does run the model's
        // controllers and timelines - was the one preview that still did.
        CustomPlayerRenderer.beginPreviewRender(RenderContext.inventoryPass());
        try {
            return renderModel(pPosX, pPosY, pScale, preview.getQueryPlayer(), modelId, textureId,
                preview.getPreviewRenderer(), preview, clearBeforeDraw, allowPoseSkip);
        } finally {
            CustomPlayerRenderer.endPreviewRender();
        }
    }

    /** {@link #renderEntityInInventory(int, int, int, EntityPlayer, ResourceLocation, ResourceLocation, boolean,
     * boolean, CustomGuiPlayerEntity, Runnable, boolean)} without the thumbnail-bake pair; see the javadoc there. */
    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer owner,
        ResourceLocation modelId, ResourceLocation textureId, boolean hovered, boolean focused,
        CustomGuiPlayerEntity preview) {
        renderEntityInInventory(pPosX, pPosY, pScale, owner, modelId, textureId, hovered, focused, preview, null,
            false);
    }

    /** Screen-owned form preview. Only its fake player is posed; the world owner supplies roaming identity. */
    public static boolean renderFormPreview(int x, int y, int scale, EntityPlayer owner,
        ResourceLocation modelId, ResourceLocation textureId, CustomGuiPlayerEntity preview) {
        return renderEntityInInventory(x, y, scale, owner, modelId, textureId, false, false, preview, null, false);
    }

    /** 作废某模型的姿态签名（见 {@link PreviewPoseCache}）：FBO 被重建、翻页/换模型、悬停或 GUI 动画切换后
     *  调用，下一次渲染必然执行。参考 {@code util/RenderUtil.java:369-373}。 */
    public static void invalidatePreviewPose(ResourceLocation modelId) {
        PreviewPoseCache.invalidate(modelId);
    }

    /** 全部作废（模型重载 / 断线时用）。参考 {@code util/RenderUtil.java:375-378}。 */
    public static void clearPreviewPoseCache() {
        PreviewPoseCache.clear();
    }

    /**
     * 近似连续姿态 + 精确显隐 + 几何/贴图身份（参考 {@code util/RenderUtil.java:380-397}）。
     * <p>
     * 几何身份用 {@code System.identityHashCode(model)}：换模型或重新解析会换掉 {@code GeoModel} 对象本身，
     * 所以它是"这次画的是不是同一份几何"最便宜的判据；贴图身份用 {@code textureId}。骨骼逐根交给
     * {@link PreviewPoseCache#appendBone}（1/64 量化，显隐与零缩放单独编码）。
     */
    private static long previewPoseSignature(AnimatedGeoModel provider, GeoModel model, ResourceLocation textureId) {
        long h = 1125899906842597L;
        h = h * 31 + System.identityHashCode(model);
        if (textureId != null) {
            h = h * 31 + textureId.hashCode();
        }
        if (provider != null) {
            // The wildcard local is load-bearing: `AnimationProcessor<T extends IAnimatable>` is **generic**, and
            // `AnimatedGeoModel`'s own field/accessor is raw, so `getModelRendererList()` reached through either raw
            // type erases its return to a raw `List` whose element javac reads as `Object` (measured: "incompatible
            // types: Object cannot be converted to IBone"). `AnimationProcessor<?>` keeps the method's own
            // `List<IBone>` signature, which is what it declares (`AnimationProcessor.java:570`, the same list as
            // `getBones()`).
            software.bernie.geckolib3.core.processor.AnimationProcessor<?> processor = provider
                .getAnimationProcessor();
            for (IBone bone : processor.getModelRendererList()) {
                h = PreviewPoseCache.appendBone(h, bone);
            }
        }
        return h;
    }

    private static boolean renderModel(double pPosX, double pPosY, float pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, GeoReplacedEntityRenderer renderer,
        CustomPlayerEntity entity, Runnable clearBeforeDraw, boolean allowPoseSkip) {
        entity.setMainModel(ModelIdUtil.getMainId(modelId));
        entity.setTexture(textureId);

        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // 与上游 RenderUtil:324-334 逐字对齐：位置/缩放/Z 轴翻转 180°；声明 disable_preview_rotation 的模型
        // 改为不俯仰并上移 5.5，否则俯仰 -10°。本移植原本在这里用的是自加的 -25° 三轴倾斜。
        boolean disablePreviewRotation = ModelPreviewRegistry
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
        //
        // rendered = 这次调用是否真的提交了几何（姿态签名命中时跳过）；由共用的绘制体返回，
        // 见 {@link #drawPreviewModel}。
        boolean rendered = false;
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
            rendered = drawPreviewModel(
                modelId,
                textureId,
                renderer,
                entity,
                clearBeforeDraw,
                allowPoseSkip,
                Minecraft.getMinecraft().timer.renderPartialTicks);
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
        return rendered;
    }

    /**
     * Draws one preview animatable - the model its caller has already put on it - and puts the frame's pose back
     * afterwards.
     * <p>
     * <b>This is the single preview draw the catalog and settings-panel previews go through</b>
     * ({@link #renderModel}). What
     * makes a preview safe is therefore written once, not once per caller: the pose brace that stops a preview from
     * leaving its pose on the shared {@code GeoModel} (D-01 below), the frame's event carrying the pass the caller's
     * preview bracket declared, the animatable's own hash code as the engine's unique id, the texture the model
     * resolves for itself, and the render type every draw in this port asks for
     * ({@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:18-39}).
     * <p>
     * What the two callers genuinely differ in stays with them, because it belongs to their placement rather than to
     * the draw: {@link #renderModel} applies the tile's translate/scale/rotation and its own state isolation and
     * rotates by the tile's fixed {@code yRotGui}, while the paper doll is placed by {@link #renderPlayerEntity}, is
     * rotated by the live body yaw, and isolates nothing - the entity it poses is the player being previewed, not a
     * stand-in.
     *
     * @param partialTicks the frame's partial tick. For a catalog tile this is upstream's value, the one
     *                     {@code CatalogModelButton.java:157-158} passes as {@code Minecraft.getInstance()
     *                     .getFrameTime()}; the two limb values stay 0 because that is what upstream computes there
     *                     too - it reads them off the preview entity's own walk animation
     *                     ({@code AnimatableEntity.java:287-296}), and a preview entity never walks
     * @param allowPoseSkip whether a catalog tile's pose signature may skip the geometry submission; the HUD doll
     *                      passes {@code false} - it has no thumbnail to keep and is drawn once per bake
     * @param clearBeforeDraw the caller's FBO clear, run only once the draw is really going to happen; may be
     *                        {@code null}
     * @return {@code true} when geometry was submitted
     */
    /**
     * Whether a preview draw has a geometry it may submit for {@code modelLocation}: either that model is really
     * installed (published, with its geometry in the engine cache) or the built-in fallback is available to stand in
     * for it.
     * <p>
     * The same question the world path answers in {@link CustomPlayerRenderer#canDrawFor}, and it has to be asked here
     * because this path reaches {@code AnimatedGeoModel#getModel} from inside the GUI's draw pass: with an unusable
     * fallback that call throws {@code GeoModelException}, and nothing between here and the client's main loop catches
     * it - {@code GuiScreen} draws its buttons without a try, and {@code Minecraft}'s game loop only guards
     * {@code OutOfMemoryError}. The catalog tiles already ask a narrower version of this question themselves
     * ({@code ModelButton}, {@code TextureButton}); this is the same rule for every caller, including the settings
     * panel's form preview, which has no gate of its own.
     */
    private static boolean canDrawPreview(ResourceLocation modelLocation) {
        if (ClientModelManager.isFallbackAvailable()) {
            return true;
        }
        return ClientModelManager.isModelPublished(modelLocation)
            && GeckoLibCache.getInstance()
                .getGeoModels()
                .get(modelLocation) != null;
    }

    private static boolean drawPreviewModel(ResourceLocation modelId, ResourceLocation textureId,
        GeoReplacedEntityRenderer renderer, CustomPlayerEntity entity, Runnable clearBeforeDraw, boolean allowPoseSkip,
        float partialTicks) {
        boolean[] rendered = { false };
        withGuiEntityLighting(() -> {
            AnimatedGeoModel provider = renderer.getGeoModelProvider();
            ResourceLocation modelLocation = provider.getModelLocation(entity);
            // See canDrawPreview: asking the provider for a model this client cannot draw would throw out of the GUI's
            // draw pass, and the settings panel's form preview reaches this path without the publication gate the
            // catalog tiles have.
            if (!canDrawPreview(modelLocation)) {
                return;
            }
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
                AnimationEvent<CustomPlayerEntity> predicate = new AnimationEvent<>(
                    entity,
                    0,
                    0,
                    partialTicks,
                    false,
                    Collections.emptyList());
                // The pass this frame belongs to, attached to the event the way the engine's own renderer attaches
                // it to the events it builds (CustomPlayerRenderer#buildAnimationEvent). The bracket the caller
                // opened is what says which pass this is - the inventory pass for the two GUI callers, the paper
                // doll for the HUD.
                predicate.setRenderContext(CustomPlayerRenderer.currentPreviewContext());
                if (renderer.getGeoModelProvider() instanceof IAnimatableModel) {
                    ((IAnimatableModel<CustomPlayerEntity>) renderer.getGeoModelProvider()).setLivingAnimations(entity, entity.hashCode(), predicate);
                }
                // 姿态签名与上次真正画出去的相同 → 跳过几何提交，FBO 里的画面直接复用
                // （此时**不能**清 FBO，清除动作在下面才执行）。只跳过几何：上面那句
                // setLivingAnimations 照常跑 —— 眨眼/呼吸这类变化要先 tick 才可能被看见
                // （参考 util/RenderUtil.java:662-672 的同一条注释与顺序）。
                if (allowPoseSkip) {
                    GeoStats.notePoseCheck();
                    if (PreviewPoseCache.shouldSkip(
                        ModelIdUtil.getMainId(modelId),
                        previewPoseSignature(provider, model, textureId))) {
                        GeoStats.notePoseSkip();
                        return;
                    }
                }
                if (clearBeforeDraw != null) {
                    clearBeforeDraw.run();
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
                rendered[0] = true;
            } finally {
                pose.restore();
            }
        });
        return rendered[0];
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
     * <p>
     * The four {@link Config#HUD_FOLLOW_MODE} modes live in this method (reference branch
     * {@code util/RenderUtil.java:727-797}, its mode dispatch at {@code :740-792}). They only rewrite the two head
     * fields - and the outer yaw this method applies - for the duration of the draw, and all four fields are put
     * back in the {@code finally} below, so the world render never inherits a preview's orientation.
     * <p>
     * <b>Upstream has no follow mode at all</b>: its paper doll always rotates by the interpolated body yaw plus
     * {@code yawOffset} ({@code com/elfmcys/ysm/util/RenderUtil.java:455}), which is this port's mode 0. The feature
     * and all of its tuning therefore come from the reference branch; its on-screen direction can only be settled in
     * a running client (the reference itself iterated on it three times - see the "v2/v3" note in its mode-1
     * comment), so the shape is ported as-is rather than re-derived.
     */
    public static void renderPlayerEntity(EntityPlayer player, double posX, double posY, float scale, float yawOffset,
        double z, float partialTicks) {
        if (player != Minecraft.getMinecraft().thePlayer) return;  // 不知道为什么如果不加这句，额外玩家会渲染串了
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();

        // HUD follow mode: capture the real rotation fields, restore them in the finally
        // (reference util/RenderUtil.java:727-731 for the capture and :805-809 for the restore). The body pair is
        // captured as well even though only the head pair is ever written, so "this method leaves the entity's
        // rotation fields exactly as it found them" is visible in one place.
        float savePrevBody = player.prevRenderYawOffset;
        float saveBody = player.renderYawOffset;
        float savePrevHead = player.prevRotationYawHead;
        float saveHead = player.rotationYawHead;
        try {
            // V = 视角偏航, B = 身体偏航。参考分支在这里对两者做 prev->current 的线性插值
            // （util/RenderUtil.java:734-737），本仓改为直接读当前字段——原因见下面的 PORT DIFFERENCE。
            //
            // PORT DIFFERENCE (reference :734-737 interpolates prev->current): the values below are the *current*
            // fields, not an interpolation of them, and that is what makes these modes exact in this port. The
            // preview path here syncs prev* = current before the draw
            // (ClientEventHandler#syncPreviousRotationsToPreview), so the renderer's own interpolated body yaw
            // (GeoReplacedEntityRenderer#resolveBodyYaw -> Interpolations.lerpYaw) IS `renderYawOffset`, and its
            // head value IS `rotationYawHead`. Modes 1-3 cancel that inner `180 - B` against the outer rotation, so
            // an interpolated B here would leave a `(1 - partialTicks) * deltaB` residual in the body's on-screen
            // angle - exactly the 20 Hz sawtooth these modes exist to remove.
            float viewYaw = player.rotationYaw;
            float bodyYaw = player.renderYawOffset;
            float outerYaw = viewYaw;

            int mode = Config.HUD_FOLLOW_MODE;
            ensureFollowMode(mode);
            if (mode == Config.HUD_FOLLOW_VANILLA_SMOOTH) {
                // 阈值区间内平滑：offset 单状态自适应低通（低速强平滑消除 20Hz 锯齿，
                // 高速近直通保持原版手感）。外层 = realB + offset，内层 = 180 - realB 精确抵消
                // → 屏幕身体 = 180+yawOffset+offset（平滑）。
                // 头部：只覆写 H = B + offset（不动 B）。头骨 = B - H = -offset（平滑），
                // 屏幕头部 = 180+yawOffset 完全固定（同 vanilla），query.head_y_rotation = offset
                // 连续无 20Hz 抖动；query.body_y_rotation 仍为真实 B，不破坏身体动画过渡（v2/v3 教训）。
                float offset = consumePolledFollowOrUpdate(hudFollowState, viewYaw, bodyYaw);
                outerYaw = MathHelper.wrapAngleTo180_float(bodyYaw + offset);
                float headDisplay = MathHelper.wrapAngleTo180_float(bodyYaw + offset);
                player.prevRotationYawHead = headDisplay;
                player.rotationYawHead = headDisplay;
            } else if (mode == Config.HUD_FOLLOW_YSM) {
                // 外层改用身体偏航：外层 (B) 与内层 (180-B) 精确抵消 → 身体锁死朝向屏幕。
                // 头部字段覆写为 B - clamp(V-B)：管线内取反后头骨旋转 = clamp(V-B)，
                // 即头部随视角 1:1 转动（限制 ±60°，同 YSM 2.6.5 纸娃娃），query.head_y_rotation 语义也恢复正确。
                outerYaw = bodyYaw;
                float headYaw = MathHelper.clamp_float(
                    MathHelper.wrapAngleTo180_float(viewYaw - bodyYaw),
                    -HUD_YSM_HEAD_MAX_OFFSET,
                    HUD_YSM_HEAD_MAX_OFFSET);
                float headDisplay = MathHelper.wrapAngleTo180_float(bodyYaw - headYaw);
                player.prevRotationYawHead = headDisplay;
                player.rotationYawHead = headDisplay;
            } else if (mode == Config.HUD_FOLLOW_YSM_SMOOTH) {
                // ysm 平滑：身体锁死朝向屏幕（同 ysm），头部参照真实身体 B——连续差值
                // V−B 直接 clamp ±60（不经 wrap180 → 永不翻转，无需方向锁）。不自动归中：
                // 站立时头部停在限位；前进时原版 renderYawOffset 追赶镜头方向，头部随之归中。
                float headYaw = consumePolledFollowOrUpdate(ysmHeadState, viewYaw, bodyYaw);
                outerYaw = bodyYaw;
                float headDisplay = MathHelper.wrapAngleTo180_float(bodyYaw - headYaw);
                player.prevRotationYawHead = headDisplay;
                player.rotationYawHead = headDisplay;
            }

            GL11.glTranslatef((float) (posX + scale * 0.5), (float) (posY + scale * 2), (float) z);
            GL11.glScalef(-scale, scale, scale);
            GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(outerYaw + yawOffset, 0.0F, 1.0F, 0.0F);

            GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);

            GL11.glTranslatef(0.0F, player.yOffset, 0.0F);
            withGuiEntityLighting(
                () -> RenderManager.instance.renderEntityWithPosYaw(player, 0.0D, 0.0D, 0.0D, 0.0F, partialTicks));
        } finally {
            player.prevRenderYawOffset = savePrevBody;
            player.renderYawOffset = saveBody;
            player.prevRotationYawHead = savePrevHead;
            player.rotationYawHead = saveHead;
            GL11.glPopMatrix();
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        }
    }

    // ── HUD paperdoll follow-mode helpers (reference util/RenderUtil.java:820-1000) ──

    /** 原版平滑模式的 offset 最大幅值（°），沿用 vanilla 的 ±75° 阈值
     *  （renderYawOffset 始终被 vanilla 约束在 rotationYaw±75° 内）。 */
    private static final float HUD_SMOOTH_MAX_OFFSET = 75.0F;
    /** ysm 平滑模式的头部随视角最大幅值（°）。YSM 2.6.5 纸娃娃已从 ±85 改为 ±60
     *  （±85 在大幅转向时屏幕旋转过大）。 */
    private static final float HUD_YSM_HEAD_MAX_OFFSET = 60.0F;
    /** 原版平滑模式对 offset 的基础低通时间常数（s），消除 20Hz 锯齿。 */
    private static final float HUD_SMOOTH_OFFSET_TC = 0.12F;
    /** 对身体偏航 B 的低通时间常数（s）：B 只在 tick 更新（20Hz 步进），先低通 B 再算
     *  target = V - Bs，从源头消除 offset 的 20Hz 锯齿；渲染层仍用真实 B 与内层 180-B
     *  精确抵消（屏幕身体 = offset），因此不会引入「两个独立低通信号相减」导致的振荡。 */
    private static final float HUD_SMOOTH_BODY_TC = 0.08F;
    /** B 偏离 bodySmooth 超过该值（°）视为「身体实际在转动」（侧移/转向/走位），此时改用
     *  HUD_SMOOTH_BODY_FAST_TC 快速跟踪，避免纸娃娃身体转向明显迟滞；仅 tick 步进抖动
     *  （偏离小）时仍走 HUD_SMOOTH_BODY_TC 强平滑消除 20Hz 锯齿。 */
    private static final float HUD_SMOOTH_BODY_FAST_DEG = 2.0F;
    /** 身体快速转动时 bodySmooth 的低通时间常数（s）。 */
    private static final float HUD_SMOOTH_BODY_FAST_TC = 0.03F;
    /** 单帧推进的 dt 上限（s）：超过视为中等帧卡顿，本帧跳过推进（卡顿期间 B 也未推进，
     *  无需追赶），避免 bodySmooth/offsetSmooth 一帧瞬移造成的 5-9° 跳变。 */
    private static final float HUD_SMOOTH_MAX_DT = 0.1F;
    /** dt 超过该值（s）视为长时间暂停（切窗口/菜单/Alt-Tab）：此时重同步到当前值，
     *  用户不在看，瞬移无感；防止暂停期间服务器 tick 推进 B 后状态永久失配。 */
    private static final float HUD_SMOOTH_RESYNC_DT = 1.0F;
    /** offset 偏差达到该值（°）时低通时间常数减半（高速时接近直通，保持原版手感）。 */
    private static final float HUD_FAST_DEVIATION_DEG = 20.0F;
    /** target 与 offsetSmooth 偏差超过该值（°）视为「大幅反向跳变」：快速反向甩动使
     *  target 从一侧限位跳到另一侧限位（如 +60 → -60）。此时改用慢追时间常数，让头部平滑转回，
     *  避免一帧快追 120°+（屏幕上身体锁死时即头部猛甩的「闪」）。 */
    private static final float HUD_SMOOTH_SLOW_FLIP_DEG = 90.0F;
    /** 大幅反向跳变时的慢追时间常数（s）：正常快速转头（偏差 <90°）仍用 HUD_SMOOTH_OFFSET_TC
     *  快追保持跟手手感；跨侧跳变用此值平滑转回。 */
    private static final float HUD_SMOOTH_SLOW_FLIP_TC = 0.4F;
    /** 同一渲染帧内两次 update() 调用（renderPlayerEntity 与 pollHudDisplayBodyYaw 双路径）
     *  的 dt 上限（s）。两次调用若跨毫秒，第二次会以相同 V/B 重新推进，状态机（低通与
     *  时间戳）会被二次推进。dt < 该值视为同帧双路径，跳过。 */
    private static final float HUD_SMOOTH_DUP_DT = 0.003F;
    /** 上次渲染时使用的跟随模式，用于切换模式时重置平滑状态。 */
    private static int lastFollowMode = -1;
    /** 最近一次 pollHudDisplayBodyYaw 的平滑跟随角（模式 1 为身体 offset、模式 3 为头部偏航；
     *  非平滑模式为 NaN），供 renderPlayerEntity 消费。 */
    private static float lastPolledFollow = Float.NaN;
    private static long lastPolledFollowTime = 0L;
    /** How long a value polled by {@link #pollHudDisplayBodyYaw} still belongs to the frame being drawn (ms). */
    private static final long HUD_POLL_CONSUME_MS = 100L;

    /**
     * 帧率无关的平滑状态机（原版平滑/ysm 平滑共用）：对「跟随角 = V - B」做单一状态的
     * 偏差自适应低通。目标值 = 连续差值 V - Bs 直接 clamp ±maxOffset（沿用 vanilla/ysm
     * 阈值）。单一状态低通不会出现「两个独立低通信号相减」在反向/阈值附近产生的振荡与截断
     * 锯齿；偏差大时时间常数缩小（高速近直通，保持原版手感），偏差小时强平滑（消除 20Hz 锯齿）。
     * <p>
     * 高速旋转天然免翻转：V 与 B 都是**连续累积角度**（renderYawOffset 与 rotationYaw 一样
     * 不经 lerpYaw 的 wrap180），连续差值 V−B 直接 clamp 是单调的——|V−B| 瞬态越过 ±180°
     * （快速甩动、B 追赶前）只会把目标钳在 ±maxOffset，永不会翻转到另一侧（旧的
     * clamp(wrap180(V−B)) 会在跨 ±180° 时翻转，需要方向锁修复，该机制现已删除）。
     * 因此本状态机不需要任何方向锁定。
     * <p>
     * 不自动归中：站立时 B 不动 → 跟随角停在限位；前进时原版 renderYawOffset 追赶镜头方向
     * （30%/tick）→ V−B 回落 → 跟随角归中。已知极限：站立时单帧甩动 >360° 时 renderYawOffset
     * 按 mod 360 对齐，连续差值 V−B 可能停在 >maxOffset 处（跟随角保持限位），需回头转动
     * 才能归中——属「无人工归中」的刻意取舍。
     * <p>
     * Ported verbatim from the reference ({@code util/RenderUtil.java:883-960}); every divisor and early return
     * was re-read against the reference before keeping it, and none of them needed a change: the two time
     * constants are non-zero (0.08/0.03), {@code HUD_FAST_DEVIATION_DEG} is non-zero (20), the duplicate-frame
     * early return deliberately leaves {@code lastTimeMs} alone so the next frame measures from the first call of
     * this frame, and the stall branch re-bases the clock while skipping one advance.
     */
    private static final class HudFollowState {
        private final float maxOffset;
        /** 低通后的连续身体偏航（去除 20Hz tick 步进，不 wrap180，与 V 同参考系）。 */
        private float bodySmooth;
        /** 低通后的跟随角（屏幕可见值：模式 1 为身体 offset，模式 3 为头部偏航）。 */
        private float offsetSmooth;
        private boolean initialized;
        private long lastTimeMs;

        HudFollowState(float maxOffset) {
            this.maxOffset = maxOffset;
        }

        void reset() {
            initialized = false;
        }

        /**
         * @param rawViewYaw  视角偏航 V（本仓传当前字段，见 {@link #renderPlayerEntity} 的 PORT DIFFERENCE；
         *                    连续累积，不经 wrap180）
         * @param realBodyYaw 真实身体偏航 B（同样是当前字段，逐 tick 更新）
         * @return 低通后的 clamp(V - Bs, ±maxOffset)，其中 Bs 为低通后的连续身体偏航；
         *         连续差值单调 clamp，永不翻转，不自动归中（归中靠原版身体追赶）
         */
        float update(float rawViewYaw, float realBodyYaw) {
            long now = Minecraft.getSystemTime();
            if (!initialized || lastTimeMs == 0L) {
                initialized = true;
                lastTimeMs = now;
                bodySmooth = realBodyYaw;
                offsetSmooth = MathHelper.clamp_float(rawViewYaw - bodySmooth, -maxOffset, maxOffset);
                return offsetSmooth;
            }
            float dt = (now - lastTimeMs) / 1000.0F;
            if (dt < HUD_SMOOTH_DUP_DT) {
                // 同一渲染帧内重复调用（renderPlayerEntity 与 pollHudDisplayBodyYaw 双路径）：
                // 跳过，避免状态机一帧推进两次（低通与时间戳二次推进）。dt < 3ms 视为同帧。
                return offsetSmooth;
            }
            lastTimeMs = now;
            if (dt > HUD_SMOOTH_RESYNC_DT) {
                // 长时间暂停：重同步（用户不在看，瞬移无感）
                bodySmooth = realBodyYaw;
                offsetSmooth = MathHelper.clamp_float(rawViewYaw - bodySmooth, -maxOffset, maxOffset);
                return offsetSmooth;
            }
            if (dt > HUD_SMOOTH_MAX_DT) {
                // 中等帧卡顿：本帧不推进（卡顿期间 B 也未推进），下一帧起正常平滑继续，绝不瞬移
                return offsetSmooth;
            }
            // 先低通身体偏航 B（20Hz tick 步进 → 平滑）：V 与 B 均为连续累积角度，直接差值
            // 无 wrap180 路径歧义（甩动后 B 连续追上 V 时差值自然回落，跟随角归中）。
            // 自适应：B 实际转动（侧移/走位，|dB| 大）时用小时间常数快速跟踪，身体转向不迟滞；
            // 仅 tick 步进抖动（|dB| 小）时用大时间常数强平滑，消除 20Hz 锯齿。
            float dB = realBodyYaw - bodySmooth;
            float bodyTC = Math.abs(dB) > HUD_SMOOTH_BODY_FAST_DEG ? HUD_SMOOTH_BODY_FAST_TC
                : HUD_SMOOTH_BODY_TC;
            float alphaB = 1.0F - (float) Math.exp(-dt / bodyTC);
            bodySmooth += dB * alphaB;
            // 连续差值 V − Bs 直接 clamp（单调，不 wrap180 → 永不翻转，无需方向锁）
            float target = MathHelper.clamp_float(rawViewYaw - bodySmooth, -maxOffset, maxOffset);
            // 偏差自适应低通：偏差越大时间常数越小（高速近直通），正常快速转头跟手。
            // 例外：偏差超过 HUD_SMOOTH_SLOW_FLIP_DEG（快速反向甩动使 target 从一侧跳到
            // 另一侧，如 +60 → -60）时改用大时间常数慢追，避免一两帧内猛甩 120°+ 的「闪」。
            float delta = target - offsetSmooth;
            if (Math.abs(delta) < 0.001F) {
                offsetSmooth = target;
                return offsetSmooth;
            }
            float dev = Math.abs(delta);
            float t = dev > HUD_SMOOTH_SLOW_FLIP_DEG ? HUD_SMOOTH_SLOW_FLIP_TC
                : HUD_SMOOTH_OFFSET_TC / (1.0F + dev / HUD_FAST_DEVIATION_DEG);
            float alpha = 1.0F - (float) Math.exp(-dt / t);
            offsetSmooth = offsetSmooth + delta * alpha;
            return offsetSmooth;
        }
    }

    private static final HudFollowState hudFollowState = new HudFollowState(HUD_SMOOTH_MAX_OFFSET);
    /** ysm 平滑模式的头部状态机：身体锁屏，头部参照真实身体 B（±60°，无自动归中，天然免翻转）。 */
    private static final HudFollowState ysmHeadState = new HudFollowState(HUD_YSM_HEAD_MAX_OFFSET);

    /** 切换跟随模式时重置平滑状态，避免旧模式的残留值造成跳变。 */
    private static void ensureFollowMode(int mode) {
        if (lastFollowMode != mode) {
            lastFollowMode = mode;
            hudFollowState.reset();
            ysmHeadState.reset();
        }
    }

    /**
     * 每帧轮询（即使 FBO 不重渲染也调用）：推进当前平滑模式的状态机。返回该模式下的
     * 平滑跟随角（原版平滑 = 屏幕可见身体 offset，ysm 平滑 = 头部偏航）；非平滑模式返回 NaN。
     * 结果存入 lastPolledFollow 供 renderPlayerEntity 消费（不覆写任何实体字段）。
     * <p>
     * Reference {@code util/RenderUtil.java:981-1000}. Its only caller there is the HUD preview cache, and row 44
     * has landed, so that is this port's only caller too: {@code client/renderer/HudPreviewCache#render} calls this
     * once per frame whether or not it re-bakes, and the return value is <b>not</b> used for invalidation - angle
     * tracking goes through the cache's own rate policy (the reference's {@code :333-335} says the same). With the
     * cache switched off the caller returns before reaching this method and {@link #renderPlayerEntity} advances the
     * state machine itself through {@code consumePolledFollowOrUpdate}; either way the low-pass advances exactly once
     * per frame. Deleting the call would freeze modes 1 and 3 while a cached frame is on screen.
     * <p>
     * V and B are read from the current fields for the same reason {@link #renderPlayerEntity} does (see the port
     * difference noted there): both paths feed one state machine, and they have to feed it the same numbers.
     */
    public static float pollHudDisplayBodyYaw(EntityPlayer player, float partialTicks) {
        int mode = Config.HUD_FOLLOW_MODE;
        ensureFollowMode(mode);
        float viewYaw = player.rotationYaw;
        float bodyYaw = player.renderYawOffset;
        float follow;
        if (mode == Config.HUD_FOLLOW_VANILLA_SMOOTH) {
            // 原版平滑：平滑后的屏幕可见身体 offset
            follow = hudFollowState.update(viewYaw, bodyYaw);
        } else if (mode == Config.HUD_FOLLOW_YSM_SMOOTH) {
            // ysm 平滑：平滑后的头部随视角偏航（参照真实身体 B，归中靠原版身体追赶）
            follow = ysmHeadState.update(viewYaw, bodyYaw);
        } else {
            lastPolledFollow = Float.NaN;
            return Float.NaN;
        }
        lastPolledFollow = follow;
        lastPolledFollowTime = Minecraft.getSystemTime();
        return follow;
    }

    /**
     * The smoother's input for one mode-1/mode-3 frame: the value {@link #pollHudDisplayBodyYaw} already advanced
     * this frame, or a fresh step when nobody polled. The reference writes these seven lines twice, once per smooth
     * mode (mode 1 at {@code util/RenderUtil.java:750-758}, mode 3 at {@code :779-787}); they are extracted here so
     * the two modes cannot drift apart.
     */
    private static float consumePolledFollowOrUpdate(HudFollowState state, float viewYaw, float bodyYaw) {
        if (!Float.isNaN(lastPolledFollow)
            && Minecraft.getSystemTime() - lastPolledFollowTime < HUD_POLL_CONSUME_MS) {
            float polled = lastPolledFollow;
            lastPolledFollow = Float.NaN;
            return polled;
        }
        return state.update(viewYaw, bodyYaw);
    }

    private static Quaternion j2l(Quaternionf jomlQuat) {
        return Utils.j2l(jomlQuat);
    }
}
