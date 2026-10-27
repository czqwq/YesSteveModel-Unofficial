package com.fox.ysmu.client.renderer;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.fox.ysmu.client.ClientProxy;
import com.fox.ysmu.client.render.AppliedModelRegistry;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.util.AnimatableCacheUtil;
import com.fox.ysmu.util.ModelIdUtil;
import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.glu.Project;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.AnimationDomain;
import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.compat.BackhandCompat;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.YsmRenderType;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class FirstPersonHandRenderer {

    private static final String RIGHT_ARM = "RightArm";
    private static final float HAND_SWING_SCALE = 0.8F;
    /**
     * Where the arm model's shoulder is placed in the vanilla biped arm's frame. Upstream's own constants
     * ({@code CustomFirstPersonArmRenderer}: {@code poseStack.translate(±0.25, 1.8, 0)} then {@code scale(-1, -1, 1)}),
     * with the y value expressed as the built-in models' shoulder pivot (29 px) - upstream's {@code 1.8} is that same
     * anchor to two decimals. See {@link #alignGeckoArmToVanillaBipedArm}.
     */
    private static final float GECKO_ARM_TO_BIPED_X = -0.25F;
    private static final float GECKO_ARM_SHOULDER_TO_BIPED_ORIGIN_Y = 29.0F / 16.0F;
    private static final float GECKO_ARM_TO_BIPED_Z = 0.0F;
    private static final float GECKO_ARM_ROLL_DEGREES = 180.0F;

    private FirstPersonHandRenderer() {}

    public static boolean shouldRenderCustomHand(Minecraft mc, EntityPlayer player, ItemRenderer itemRenderer) {
        return !Config.DISABLE_SELF_MODEL
            && !Config.DISABLE_SELF_HANDS
            && mc != null
            && mc.entityRenderer != null
            && mc.playerController != null
            && player instanceof EntityPlayerSP
            && itemRenderer != null
            && mc.theWorld != null
            && mc.renderViewEntity != null
            && !mc.gameSettings.hideGUI
            && mc.gameSettings.thirdPersonView == 0
            && !player.isInvisible()
            && !mc.renderViewEntity.isPlayerSleeping()
            && !mc.playerController.enableEverythingIsScrewedUpMode()
            && mc.entityRenderer.debugViewDirection <= 0
            && mc.entityRenderer.cameraZoom == 1.0D
            && !BackhandCompat.isRenderingOffhand(player)
            && itemRenderer.itemToRender == null;
    }

    /**
     * 便捷查询：模型是否存在可渲染的右臂骨骼。渲染路径不再调用它（R-09：骨骼只在
     * {@link #getCustomHandRenderContext} 里解析一次并随 context 复用），保留为公开辅助方法。
     */
    public static boolean hasRenderableRightArm(GeoModel geoModel) {
        return findRightArmBone(geoModel).isPresent();
    }

    public static boolean tryRender(RenderHandEvent event, Minecraft mc, EntityPlayer player,
        ItemRenderer itemRenderer) {
        CustomHandRenderContext context = getCustomHandRenderContext(mc, player, itemRenderer);
        if (context == null) {
            return false;
        }

        event.setCanceled(true);
        render(
            event,
            mc,
            player,
            itemRenderer,
            context.renderer,
            context.customPlayer,
            context.rightArm,
            context.texture);
        return true;
    }

    /**
     * Whether this frame's arm is drawn by {@link #tryRender}, i.e. whether the vanilla hand on screen is replaced by
     * the model's own arm.
     * <p>
     * The only caller is the Angelica shader path, whose own pass cannot draw the arm (see
     * {@code MixinItemRenderer#ysmu$suppressVanillaHandInShaderPass}) and therefore only has to keep the host's
     * vanilla arm out of the frame. The predicate resolves exactly the context {@link #tryRender} draws with, so the
     * two can never disagree: {@code false} leaves the vanilla hand alone, {@code true} means the arm is guaranteed
     * to be drawn by the event path in this same frame.
     */
    public static boolean willRenderCustomHand(Minecraft mc, ItemRenderer itemRenderer) {
        return getCustomHandRenderContext(mc, mc == null ? null : mc.thePlayer, itemRenderer) != null;
    }

    public static void render(RenderHandEvent event, Minecraft mc, EntityPlayer player, ItemRenderer itemRenderer,
        CustomPlayerRenderer renderer, CustomPlayerEntity customPlayer, GeoBone rightArm, ResourceLocation texture) {
        float partialTicks = event.partialTicks;
        EntityRenderer entityRenderer = mc.entityRenderer;

        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        setupFirstPersonHandCamera(event, mc, entityRenderer, partialTicks);

        GL11.glPushMatrix();
        try {
            entityRenderer.hurtCameraEffect(partialTicks);
            if (mc.gameSettings.viewBobbing) {
                entityRenderer.setupViewBobbing(partialTicks);
            }

            entityRenderer.enableLightmap((double) partialTicks);
            try {
                renderFirstPersonItems(
                    mc,
                    player,
                    itemRenderer,
                    renderer,
                    customPlayer,
                    rightArm,
                    texture,
                    partialTicks,
                    // 副手必须恰好画一次：半透明副手由 Angelica 的 translucent hand pass 画，其余由这里画。
                    com.fox.ysmu.client.compat.AngelicaCompat.shouldDrawOffhandInHandEvent());
            } finally {
                entityRenderer.disableLightmap((double) partialTicks);
            }
        } finally {
            GL11.glPopMatrix();
        }

        renderVanillaOverlays(mc, entityRenderer, itemRenderer, partialTicks);
    }

    private static void setupFirstPersonHandCamera(RenderHandEvent event, Minecraft mc,
        EntityRenderer entityRenderer, float partialTicks) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();

        if (mc.gameSettings.anaglyph) {
            GL11.glTranslatef((float) (-(event.renderPass * 2 - 1)) * 0.07F, 0.0F, 0.0F);
        }

        if (entityRenderer.cameraZoom != 1.0D) {
            GL11.glTranslatef((float) entityRenderer.cameraYaw, (float) (-entityRenderer.cameraPitch), 0.0F);
            GL11.glScaled(entityRenderer.cameraZoom, entityRenderer.cameraZoom, 1.0D);
        }

        float farPlane = entityRenderer.farPlaneDistance > 0.0F
            ? entityRenderer.farPlaneDistance
            : (float) (mc.gameSettings.renderDistanceChunks * 16);
        Project.gluPerspective(
            entityRenderer.getFOVModifier(partialTicks, false),
            (float) mc.displayWidth / (float) mc.displayHeight,
            0.05F,
            farPlane * 2.0F);

        if (mc.playerController.enableEverythingIsScrewedUpMode()) {
            GL11.glScalef(1.0F, 0.6666667F, 1.0F);
        }

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        if (mc.gameSettings.anaglyph) {
            GL11.glTranslatef((float) (event.renderPass * 2 - 1) * 0.1F, 0.0F, 0.0F);
        }
    }

    private static void renderFirstPersonItems(Minecraft mc, EntityPlayer player, ItemRenderer itemRenderer,
        CustomPlayerRenderer renderer, CustomPlayerEntity customPlayer, GeoBone rightArm, ResourceLocation texture,
        float partialTicks, boolean renderOffhand) {
        applyVanillaHandLighting(player, partialTicks);
        applyVanillaArmViewSmoothing(player, partialTicks);
        setupLightmap(mc, player);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        try {
            renderCustomEmptyMainHand(
                mc,
                player,
                itemRenderer,
                renderer,
                customPlayer,
                rightArm,
                texture,
                partialTicks);
            // 配置隐藏的副手物品（如 Extra Utilities 除叶子斧）在第一人称不渲染
            // (reference branch FirstPersonHandRenderer.java:206).
            if (renderOffhand && !BackhandCompat.isHiddenOffhandItem(BackhandCompat.getOffhandItem(player))) {
                BackhandCompat.renderOffhand(partialTicks);
            }
        } finally {
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            RenderHelper.disableStandardItemLighting();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void applyVanillaHandLighting(EntityPlayer player, float partialTicks) {
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;

        GL11.glPushMatrix();
        GL11.glRotatef(pitch, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(yaw, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GL11.glPopMatrix();
    }

    private static void applyVanillaArmViewSmoothing(EntityPlayer player, float partialTicks) {
        EntityPlayerSP playerSp = (EntityPlayerSP) player;
        float armPitch = playerSp.prevRenderArmPitch + (playerSp.renderArmPitch - playerSp.prevRenderArmPitch)
            * partialTicks;
        float armYaw = playerSp.prevRenderArmYaw + (playerSp.renderArmYaw - playerSp.prevRenderArmYaw)
            * partialTicks;

        GL11.glRotatef((player.rotationPitch - armPitch) * 0.1F, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef((player.rotationYaw - armYaw) * 0.1F, 0.0F, 1.0F, 0.0F);
    }

    private static void setupLightmap(Minecraft mc, EntityPlayer player) {
        int light = mc.theWorld.getLightBrightnessForSkyBlocks(
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ),
            0);
        OpenGlHelper.setLightmapTextureCoords(
            OpenGlHelper.lightmapTexUnit,
            (float) (light % 65536),
            (float) (light / 65536));
    }

    /**
     * Ticks the first-person arm once for this frame, so the bone this frame draws is posed by its own controllers
     * instead of sitting at its bind pose.
     *
     * <p>
     * The gap this closes: the arm's {@code GeoModel} is read straight out of the engine's cache
     * ({@link #getCustomHandRenderContext}, {@code GeckoLibCache#getGeoModels}), and a {@code GeoModel}'s bones only
     * enter an {@code AnimationProcessor} through {@code AnimatedGeoModel#getModel} - which the port only ever called
     * with the <em>main</em> model id (the world path: {@code GeoReplacedEntityRenderer#doRender}). Upstream instead
     * ticks a {@code CustomFirstPersonArmEntity} of its own every frame and draws the result. See
     * {@code CustomFirstPersonArmEntity#tickFirstPersonArm}.
     *
     * <p>
     * The event is the frame's own, built by the renderer for <em>this</em> animatable
     * ({@code GeoReplacedEntityRenderer#buildAnimationEvent}), so the arm reads the same limb and head values, and
     * publishes the same MoLang values, as the body does in the same frame.
     */
    private static void poseFirstPersonArm(CustomPlayerRenderer renderer, CustomPlayerEntity customPlayer,
        EntityPlayer player, float partialTicks) {
        if (!(customPlayer instanceof CustomFirstPersonArmEntity arm)) {
            return;
        }
        if (!(renderer.getGeoModelProvider() instanceof CustomPlayerModel provider)) {
            return;
        }
        AnimationEvent<CustomPlayerEntity> event = renderer.buildAnimationEvent(player, partialTicks, arm);
        arm.tickFirstPersonArm(provider, event);
    }

    private static void renderCustomEmptyMainHand(Minecraft mc, EntityPlayer player, ItemRenderer itemRenderer,
        CustomPlayerRenderer renderer, CustomPlayerEntity customPlayer, GeoBone rightArm, ResourceLocation texture,
        float partialTicks) {
        // Pose the arm before drawing it, which is what upstream's arm renderer does
        // (client/renderer/CustomFirstPersonArmRenderer.java:27-28: checkModelUpdate() then update(partialTick)).
        poseFirstPersonArm(renderer, customPlayer, player, partialTicks);
        GL11.glPushMatrix();
        try {
            applyVanillaEmptyHandTransform(player, itemRenderer, partialTicks);
            // R-04 契约：这里刻意不恢复纹理绑定——shouldRenderCustomHand 要求 thirdPersonView == 0 且未睡觉，
            // 因此本路径收尾的 renderVanillaOverlays 必然走到 itemRenderer.renderOverlays(partialTicks)
            // 并自行改绑纹理。若将来在两者之间插入绘制代码，必须先自行绑定纹理。
            // The texture is the one resolved for this frame (an event override, else the model's own), not the
            // entity's raw selection: the arm follows the same rule as the body, as upstream's arm renderer does.
            mc.getTextureManager()
                .bindTexture(texture);
            // The state this draw gets is the state the world path draws this same geometry with, not a state of the
            // arm path's own; see armRenderType.
            YsmRenderType renderType = armRenderType(renderer, texture, customPlayer.hasTranslucentVertices());
            prepareCustomArmState(renderType);
            try {
                alignGeckoArmToVanillaBipedArm();
                GeoModel armModel = GeckoLibCache.getInstance().getGeoModels().get(ModelIdUtil.getArmId(
                    ModelIdUtil.getModelIdFromMainId(customPlayer.getMainModel())));
                renderer.renderMaterialRoot(customPlayer, armModel, rightArm, texture, renderType);
            } finally {
                restoreCustomArmState();
            }
        } finally {
            GL11.glPopMatrix();
        }
    }

    private static void applyVanillaEmptyHandTransform(EntityPlayer player, ItemRenderer itemRenderer,
        float partialTicks) {
        float equippedProgress = itemRenderer.prevEquippedProgress
            + (itemRenderer.equippedProgress - itemRenderer.prevEquippedProgress) * partialTicks;
        float swing = player.getSwingProgress(partialTicks);
        float swingSin = MathHelper.sin(swing * (float) Math.PI);
        float swingRootSin = MathHelper.sin(MathHelper.sqrt_float(swing) * (float) Math.PI);

        GL11.glTranslatef(
            -swingRootSin * 0.3F,
            MathHelper.sin(MathHelper.sqrt_float(swing) * (float) Math.PI * 2.0F) * 0.4F,
            -swingSin * 0.4F);
        GL11.glTranslatef(
            0.8F * HAND_SWING_SCALE,
            -0.75F * HAND_SWING_SCALE - (1.0F - equippedProgress) * 0.6F,
            -0.9F * HAND_SWING_SCALE);
        GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);

        swingSin = MathHelper.sin(swing * swing * (float) Math.PI);
        swingRootSin = MathHelper.sin(MathHelper.sqrt_float(swing) * (float) Math.PI);
        GL11.glRotatef(swingRootSin * 70.0F, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-swingSin * 20.0F, 0.0F, 0.0F, 1.0F);

        GL11.glTranslatef(-1.0F, 3.6F, 3.5F);
        GL11.glRotatef(120.0F, 0.0F, 0.0F, 1.0F);
        GL11.glRotatef(200.0F, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);
        GL11.glTranslatef(5.6F, 0.0F, 0.0F);
    }

    /**
     * The render type this arm is drawn with: the same ask the world path makes for the same model and the same
     * texture, through the same {@code IGeoRenderer#getRenderType} the world path and the preview path call
     * ({@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:33-39}; the port's callers are
     * {@code GeoReplacedEntityRenderer.java:180-184} and {@code RenderUtil.java:492-493}).
     * <p>
     * It is deliberately <em>not</em> upstream's arm answer taken on its own. Upstream hands its first-person arm
     * {@code CustomTranslucentRenderType} unconditionally
     * ({@code client/renderer/CustomFirstPersonArmRenderer.java:39}), which wraps {@code RenderType.entityTranslucent}:
     * blending and back-face culling, no alpha test. Upstream's world path asks the model instead and answers
     * {@code RenderType.entityCutoutNoCull} - alpha test, no blending, no culling - for a model without a translucent
     * vertex. In 1.20 the type applies its own state, so the arm's different answer is invisible there; 1.7.10 has no
     * {@code RenderType}, this path draws the bone directly, and hardcoding the arm's answer gave an opaque model
     * alpha test off (a texel with alpha 0 is not discarded: the black patch over the unlit inside of the far face)
     * and culling on (a face of a flat or single-layer piece is dropped: the see-through holes). The arm is that
     * model's own geometry, so the model's answer is the one that must be used - the same geometry drawn two ways in
     * one frame is the defect.
     */
    static YsmRenderType armRenderType(IGeoRenderer<?> renderer, ResourceLocation texture, boolean translucent) {
        return renderer.getRenderType(texture, true, false, translucent);
    }

    /**
     * Applies the chosen type with {@code IGeoRenderer#render}'s own rules ({@code IGeoRenderer.java:77-95}), because
     * this path draws the bone directly instead of going through {@code render}.
     */
    private static void prepareCustomArmState(YsmRenderType type) {
        if (type.isCull()) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        if (type.isAlphaTest()) {
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, type.getAlphaRef());
        } else {
            GlStateManager.disableAlpha();
        }
        if (type.isBlend()) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        // render() enables texture 2D for its draw (:95). This path binds the texture itself and must do the same:
        // the vanilla hand pass it replaces does not enable it either.
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Leaves the 1.7.10 baseline for the vanilla passes that follow this one: no blend, alpha test on, culling on.
     * <p>
     * The alpha test half is not cosmetic. The pass this method closes is followed - still inside
     * {@link #renderFirstPersonItems} - by {@link BackhandCompat#renderOffhand}, which draws the off-hand item through
     * the vanilla {@code ItemRenderer}. Vanilla's 2D item drawing never enables the alpha test itself; it relies on
     * the game-wide state ({@code Minecraft.java:575-576}: {@code glEnable(GL_ALPHA_TEST)} with
     * {@code GL_GREATER/0.1F}, kept on for entity rendering at {@code RendererLivingEntity.java:163}). With the test
     * disabled, a transparent texel of the item texture is not discarded but written with the RGB the texture happens
     * to carry under its alpha-0 pixels - the off-hand item reads as a flat sheet instead of its sprite. See
     * {@code .agent/phase56-held-item-alpha-state.md}.
     */
    private static void restoreCustomArmState() {
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableCull();
        // 镜像绕序的复位：见 alignGeckoArmToVanillaBipedArm，与 CustomPlayerItemInHandLayer.java:113 的写法一致。
        GL11.glFrontFace(GL11.GL_CCW);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Places the arm model where the vanilla biped arm sits, in the same shape upstream uses
     * ({@code CustomFirstPersonArmRenderer}: translate to the shoulder, then {@code scale(-1, -1, 1)}).
     * <p>
     * One deliberate difference: 1.7.10's first-person camera looks down {@code -Z} where 1.20's looks down
     * {@code +Z}, so the arm needs one more half-turn about Y - which is what {@link #GECKO_ARM_ROLL_DEGREES} is. It
     * is applied <b>about the origin</b>, exactly like upstream's scale: a rotation about any other point also
     * translates, and that translation is whatever the pivot happens to be. The arm used to be rolled about the
     * centre of its own bounding box, which made the offset a property of each pack's geometry (0.23 blocks for the
     * built-in default arm, more for an arm whose cubes sit off the bone axis, e.g. {@code LaPluma2.5.3} whose arm
     * cubes live in child bones spanning x -2.6..+4.2 px) - the arm was drawn displaced from the hand and from where
     * the pack authored it. See {@code .agent/phase46-first-person-arm-alignment.md}.
     */
    private static void alignGeckoArmToVanillaBipedArm() {
        GL11.glTranslatef(
            GECKO_ARM_TO_BIPED_X,
            GECKO_ARM_SHOULDER_TO_BIPED_ORIGIN_Y,
            GECKO_ARM_TO_BIPED_Z);
        GL11.glScalef(1.0F, -1.0F, 1.0F);
        // 镜像导致的面剔除反转：模型矩阵从这里起是负行列式（det = -1），而 GL 的正反面按屏幕空间绕序判定，
        // 于是模型自己的正面到这一步成了背面。修正方式与镜像物品完全相同（CustomPlayerItemInHandLayer.java:107，
        // 同一句中文注释），只在镜像生效期间改绕序；不剔除的分支（cutout/no-cull）不受影响，剔除的分支
        // （translucent）才不会把该看见的正面剔掉、只留远侧内壁。复位见 restoreCustomArmState。
        GL11.glFrontFace(GL11.GL_CW);
        GL11.glRotatef(GECKO_ARM_ROLL_DEGREES, 0.0F, 1.0F, 0.0F);
    }

    private static void renderRightArmBone(CustomPlayerRenderer renderer, GeoBone rightArm,
        CustomPlayerEntity customPlayer) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_QUADS);
        renderer.renderRecursively(tessellator, customPlayer, rightArm, 1.0F, 1.0F, 1.0F, 1.0F);
        tessellator.draw();
    }

    private static Optional<GeoBone> findRightArmBone(GeoModel geoModel) {
        if (geoModel == null) {
            return Optional.empty();
        }
        Optional<GeoBone> rightArm = geoModel.getTopLevelBone(RIGHT_ARM);
        return rightArm.isPresent() ? rightArm : geoModel.getBone(RIGHT_ARM);
    }

    private static void renderVanillaOverlays(Minecraft mc, EntityRenderer entityRenderer, ItemRenderer itemRenderer,
        float partialTicks) {
        if (mc.gameSettings.thirdPersonView == 0 && !mc.renderViewEntity.isPlayerSleeping()) {
            itemRenderer.renderOverlays(partialTicks);
            entityRenderer.hurtCameraEffect(partialTicks);
        }

        if (mc.gameSettings.viewBobbing) {
            entityRenderer.setupViewBobbing(partialTicks);
        }
    }

    private static CustomHandRenderContext getCustomHandRenderContext(Minecraft mc, EntityPlayer player,
        ItemRenderer itemRenderer) {
        if (!shouldRenderCustomHand(mc, player, itemRenderer)) {
            return null;
        }

        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep == null) {
            return null;
        }
        ResourceLocation requested = eep.getModelId();
        if (requested == null) {
            return null;
        }
        // The arm model is built together with the rest of the model, on demand. Ask for it here so a player who is
        // holding nothing but looking at their own hand still gets the model built; the lookup below stays null-safe
        // for the few frames before that finishes.
        com.fox.ysmu.client.ClientModelManager.ensureGeometry(requested);
        // ...but the arm geometry outlives a connection in the engine's cache while this connection's textures do
        // not, and this path binds the model's texture directly (see renderCustomEmptyMainHand). A stale arm from the
        // previous server would therefore bind a texture that has not been uploaded, so the custom hand waits for
        // this connection to publish the model and the vanilla hand is used until then.
        //
        // The arm follows the *body's applied model*, which is upstream's own wiring: its arm entity is told the body's
        // model hash and reads the body's texture name and texture id back
        // (client/entity/CustomFirstPersonArmEntity.java:22,59-66,74-76), so the two never show different models. While
        // the requested model is Pending or Failed, the arm therefore uses what the body is showing
        // (AppliedModelRegistry); with nothing applied yet it stays vanilla, which is this port's equivalent of
        // upstream's arm target simply not being loaded.
        ResourceLocation modelId = requested;
        ResourceLocation textureId = eep.getSelectTexture();
        if (!com.fox.ysmu.client.ClientModelManager.isModelPublished(requested)) {
            AppliedModelRegistry.Applied applied = AppliedModelRegistry.applied(player);
            if (!com.fox.ysmu.client.ClientModelManager.hasModelContent(requested) || applied == null
                || !com.fox.ysmu.client.ClientModelManager.isModelPublished(applied.mainModelId())) {
                return null;
            }
            modelId = applied.mainModelId();
            textureId = applied.textureId();
        }
        GeoModel geoModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(ModelIdUtil.getArmId(modelId));
        // R-09: resolve the right-arm bone once here and reuse it for the whole frame (the render path no
        // longer calls hasRenderableRightArm/findRightArmBone a second time).
        Optional<GeoBone> rightArm = findRightArmBone(geoModel);
        if (!rightArm.isPresent()) {
            return null;
        }
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
        if (renderer == null) {
            return null;
        }
        CustomPlayerEntity customPlayer = getCustomHandPlayer(modelId, textureId, player);
        if (customPlayer == null) {
            return null;
        }
        SpecialPlayerRenderEvent renderEvent = new SpecialPlayerRenderEvent(player, customPlayer, modelId);
        if (MinecraftForge.EVENT_BUS.post(renderEvent)) {
            return null;
        }
        // The arm uses the same rule as the body, which is what upstream's CustomFirstPersonArmRenderer does: an
        // event-supplied override wins, otherwise the model's own texture. Binding the entity's selected id directly
        // would skip that rule and could bind a texture this client never registered.
        ResourceLocation texture = renderEvent.getTextureLocationOverride() != null
            ? renderEvent.getTextureLocationOverride()
            : CustomPlayerModel.textureFor(customPlayer);
        return new CustomHandRenderContext(renderer, customPlayer, rightArm.get(), texture);
    }

    /**
     * The animatable this arm draws with: {@link CustomFirstPersonArmEntity}, i.e. the model read from the
     * first-person animation domain rather than the model proper's ({@link AnimationDomain}). Upstream's counterpart
     * is {@code CustomFirstPersonArmEntity} and its {@code fpArmAnimations} store
     * ({@code model/resource/client/render/ModelRenderTargetLoader.java:251-253}).
     * <p>
     * It is cached under the model's {@code arm} id, not under the model id a GUI preview uses, so the arm can never
     * inherit a pose or a model a preview left on that animatable. Since the catalog tiles moved to their own
     * {@code CustomGuiPlayerEntity} instances, this cache holds the arm and nothing else: no screen invalidates it any
     * more, so the entry lives until {@code CacheBuilder}'s five-minute idle expiry and the arm's animation clock is
     * not reset by opening or paging through the model browser.
     * <p>
     * The main model id is still set on it, because the things this arm asks of its animatable are about the model
     * (scale, layer order, roaming namespace), not about which file its animation comes from.
     *
     * @param textureId the texture this model is drawn with - the player's selection, or the applied model's own
     *                  texture while a newer selection is still Pending or Failed; see the caller
     */
    private static CustomPlayerEntity getCustomHandPlayer(ResourceLocation modelId, ResourceLocation textureId,
        EntityPlayer player) {
        ResourceLocation firstPersonKey = ModelIdUtil.getArmId(modelId);
        IAnimatable animatable;
        try {
            animatable = AnimatableCacheUtil.ANIMATABLE_CACHE.get(firstPersonKey, () -> {
                CustomPlayerEntity entity = new CustomFirstPersonArmEntity();
                entity.setTexture(textureId);
                return entity;
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        if (animatable instanceof CustomPlayerEntity customPlayer) {
            customPlayer.setPlayer(player);
            customPlayer.setMainModel(ModelIdUtil.getMainId(modelId));
            customPlayer.setTexture(textureId);
            return customPlayer;
        }
        return null;
    }

    private static final class CustomHandRenderContext {
        private final CustomPlayerRenderer renderer;
        private final CustomPlayerEntity customPlayer;
        /** R-09: the right-arm bone resolved once for this frame. */
        private final GeoBone rightArm;
        /** The texture this arm must bind, resolved once for this frame; see the caller. */
        private final ResourceLocation texture;

        private CustomHandRenderContext(CustomPlayerRenderer renderer, CustomPlayerEntity customPlayer,
            GeoBone rightArm, ResourceLocation texture) {
            this.renderer = renderer;
            this.customPlayer = customPlayer;
            this.rightArm = rightArm;
            this.texture = texture;
        }
    }
}
