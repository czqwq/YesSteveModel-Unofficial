package com.fox.ysmu.client.renderer.layer;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.fox.ysmu.client.compat.tacz.TACZCompat;
import com.fox.ysmu.compat.BackhandCompat;
import cpw.mods.fml.common.Optional;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.GeoLayerRenderer;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.util.RenderUtils;
import xonin.backhand.compat.IOffhandRenderOptOut;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

// This optional marker tells Backhand not to render this layer twice; behavior calls stay behind BackhandCompat.
@Optional.Interface(iface = "xonin.backhand.compat.IOffhandRenderOptOut", modid = "backhand")
public class CustomPlayerItemInHandLayer<T extends EntityLivingBase & IAnimatable> extends GeoLayerRenderer<T>
    implements IOffhandRenderOptOut {

    /**
     * Upstream's humanoid hand locator groups ({@code client/model/locator/PlayerLocator.java:14-15}); a numbered bone
     * ({@code LeftHandLocator2}) belongs to the same group. {@code LeftHandLocator} is the off hand and
     * {@code RightHandLocator} the main hand, exactly as upstream names them.
     */
    private static final String LEFT_HAND_LOCATOR = "LeftHandLocator";
    private static final String RIGHT_HAND_LOCATOR = "RightHandLocator";

    private final ItemRenderer itemRenderer;

    public CustomPlayerItemInHandLayer(IGeoRenderer<T> entityRendererIn) {
        super(entityRendererIn);
        this.itemRenderer = new ItemRenderer(Minecraft.getMinecraft());
    }

    @Override
    public void render(T entity, float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch, Color renderColor) {
        GeoModel geoModel = entityRenderer.getGeoModel();
        if (geoModel == null) return;

        // 模型可以没有 extra_info（YSM 自己就容忍 extraInfo == null：ClientModelManager 会打印
        // hasExtraInfo=false 并照常注册几何）。玩家路径上这个 NPE 一直被 Geckolib 的 try/catch 吞掉，
        // 放宽到非玩家后女仆也会走到这里，所以不再靠别人的 catch 兜底。
        // N-4：properties 本身也按可选处理，避免几何构建器未填该字段时在层里 NPE。
        ExtraInfo extraInfo = geoModel.properties == null ? null : geoModel.properties.getExtraInfo();
        boolean isVanilla = extraInfo != null
            && (Objects.equals(extraInfo.getName(), "Steve") || Objects.equals(extraInfo.getName(), "Alex"));
        // 主手物品统一走 entity.getHeldItem()：玩家的 getHeldItem() 就是主手，玩家路径行为不变；
        // 非玩家实体（穿 YSM 模型的 TLM 女仆）同样由它给出同步过来的主手物品。
        ItemStack mainHandItem = entity.getHeldItem();
        // 副手是玩家专属：BackhandCompat 读的是玩家自己的副手扩展，非玩家实体没有这一槽位，故为 null。
        ItemStack offhandItem = entity instanceof EntityPlayer player ? BackhandCompat.getOffhandItem(player) : null;
        // 配置隐藏的副手物品（如 Extra Utilities 除叶子斧）在模型手持层也不渲染
        // (reference branch CustomPlayerItemInHandLayer.java:86)。放在这里而不是绘制点上：本类只在
        // 这一个地方读副手，置空后下面 `offhandItem != null` 与左臂物品绘制同时不再成立。
        if (offhandItem != null && BackhandCompat.isHiddenOffhandItem(offhandItem)) {
            offhandItem = null;
        }
        if (mainHandItem != null || offhandItem != null) {
            GlStateManager.pushMatrix();
            // R-04：矩阵栈与 pop 必须成对——物品渲染抛异常时也要回滚，否则后续整帧的渲染状态会错乱。
            try {
                if (mainHandItem != null) {
                    // TacZ muzzle-flash/shell visibility while the custom model draws the held gun; upstream brackets
                    // the same draw in client/renderer/layer/CustomPlayerItemInHandLayer.java:37-40.
                    TACZCompat.openFlashShellRender(entity, mainHandItem);
                    try {
                        renderArmWithItemAtLocatorGroup(entity, mainHandItem, geoModel, RIGHT_HAND_LOCATOR,
                            geoModel.rightHandBones, true, isVanilla);
                    } finally {
                        TACZCompat.stopFlashShellRender(mainHandItem);
                    }
                }
                // A gun in the off hand is drawn at the model's own Pistol/Rifle locator instead of the hand
                // (upstream :42-55). Upstream's else-branch still draws the main-hand stack at the off hand there,
                // which is a bug; the port skips that duplicate draw.
                boolean offhandGun = TACZCompat.isGun(offhandItem);
                if (!offhandGun) {
                    renderArmWithItemAtLocatorGroup(entity, offhandItem, geoModel, LEFT_HAND_LOCATOR,
                        geoModel.leftHandBones, false, isVanilla);
                }
                TACZCompat.renderOffsetHand(this, offhandItem, entity, geoModel);
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    /**
     * Draws {@code stack} once at <b>every</b> locator of one hand group - upstream's item-in-hand layer draws the
     * held stack at each member of the group rather than at one bone: its
     * {@code data.modelState.visitLocatorGroup(PlayerLocator.rightHand/leftHand, ...)} callback runs once per locator
     * ({@code tmp/YesSteveModel-dev-1.20/.../client/renderer/layer/CustomPlayerItemInHandLayer.java:31-77}, the
     * per-locator draw at {@code :61-78}). A pack expresses a second pair of hands by numbering the locator
     * ({@code LeftHandLocator2}/{@code RightHandLocator2}); the group rule strips the trailing digits
     * ({@code GeoLocatorType#getByBoneName:33-47}), so taking only the exact-named chain left the second pair empty.
     * <p>
     * Each locator needs the whole root-to-locator transform chain. The single-locator case keeps the prebuilt
     * exact-name chain ({@link GeoModel#leftHandBones}/{@link GeoModel#rightHandBones}) so the common model allocates
     * nothing per frame; a model with several locators resolves each chain through
     * {@code IGeoRenderer#getPathFromRoot}. An empty group falls back to that same chain, so a geometry built by
     * another {@code IGeoBuilder} behaves exactly as before.
     * <p>
     * Only the group's top-most members are drawn at ({@link GeoModel#locatorGroupRoots}): a member nested under
     * another member already inherits its ancestor's transform, so drawing the stack there too would duplicate the
     * item at an offset (the same rule the head rotation/layer follow).
     *
     * @param locatorGroup group base name without the numeric suffix; {@code LeftHandLocator} is the off hand and
     *                     {@code RightHandLocator} the main hand, matching upstream's {@code PlayerLocator}
     */
    protected void renderArmWithItemAtLocatorGroup(EntityLivingBase entity, ItemStack stack, GeoModel geoModel,
        String locatorGroup, List<GeoBone> fallbackChain, boolean isMainHand, boolean isVanilla) {
        if (stack == null || stack.getItem() == null) {
            return;
        }
        List<GeoBone> locators = geoModel.locatorGroupRoots(locatorGroup);
        if (locators.isEmpty() || (locators.size() == 1 && !fallbackChain.isEmpty())) {
            // No group at all, or the single member is the exact-named locator GeoBuilder already chained.
            renderArmWithItem(entity, stack, fallbackChain, isMainHand, isVanilla);
            return;
        }
        for (GeoBone locator : locators) {
            renderArmWithItem(entity, stack, Arrays.asList(entityRenderer.getPathFromRoot(locator)), isMainHand,
                isVanilla);
        }
    }

    /**
     * Draws one held TacZ gun at the model's {@code PistolLocator}/{@code RifleLocator} group - the port's replacement
     * for upstream's {@code TacCompatInner#renderOffhandGun} draw
     * ({@code client/compat/tacz/TacCompatInner.java:58-87}, a {@code Minecraft#getItemRenderer().renderStatic(...,
     * ItemDisplayContext.FIXED, ...)} call). The transforms are upstream's, applied in the locator's bone frame;
     * {@code pistol} selects its branch.
     * <p>
     * 1.7.10 has no {@code ItemDisplayContext}: TacZ's own item renderer maps {@code FIXED} to its in-frame
     * {@code ENTITY} branch (its {@code AnimateGeoItemRenderer#renderItem} reads the vanilla
     * {@code RenderItem#renderInFrame} flag, which is the 1.7.10 equivalent of passing {@code FIXED}), and that branch
     * expects the frame's {@code S(0.5)} to be already applied - so it is applied here.
     */
    public void renderGunAtLocatorGroup(EntityLivingBase entity, ItemStack stack, GeoModel geoModel,
        String locatorGroup, boolean pistol) {
        if (stack == null || stack.getItem() == null) {
            return;
        }
        List<GeoBone> locators = geoModel.locatorGroupRoots(locatorGroup);
        if (locators.isEmpty()) {
            return;
        }
        IItemRenderer renderer = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.ENTITY);
        if (renderer == null || !renderer.handleRenderType(stack, IItemRenderer.ItemRenderType.ENTITY)) {
            return;
        }
        for (GeoBone locator : locators) {
            GL11.glPushMatrix();
            try {
                applyHeldItemRenderState();
                applyBoneTransform(Arrays.asList(entityRenderer.getPathFromRoot(locator)));
                if (pistol) {
                    GL11.glTranslatef(0.0F, -0.125F, 0.0F);
                    GL11.glScalef(0.65F, 0.65F, 0.65F);
                    GL11.glRotatef(-90.0F, 0.0F, 1.0F, 0.0F);
                    GL11.glRotatef(90.0F, 0.0F, 0.0F, 1.0F);
                } else {
                    GL11.glScalef(0.65F, 0.65F, 0.65F);
                    GL11.glRotatef(-180.0F, 0.0F, 1.0F, 0.0F);
                }
                GL11.glScalef(0.5F, 0.5F, 0.5F);
                boolean previousInFrame = RenderItem.renderInFrame;
                RenderItem.renderInFrame = true;
                try {
                    renderer.renderItem(IItemRenderer.ItemRenderType.ENTITY, stack, entity);
                } finally {
                    RenderItem.renderInFrame = previousInFrame;
                }
            } finally {
                restoreHeldItemRenderState();
                GL11.glPopMatrix();
            }
        }
    }

    /** The vanilla held-item GL state baseline; see the comment in renderArmWithItem for why it is restored here. */
    private void applyHeldItemRenderState() {
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void restoreHeldItemRenderState() {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    /**
     * 手部渲染方法
     * @param bones 对应的骨骼列表 (左手或右手)
     * @param isMainHand 是否为主手 (用于镜像处理)
     */
    protected void renderArmWithItem(EntityLivingBase entity, ItemStack stack, List<GeoBone> bones,
                                     boolean isMainHand, boolean isVanilla) {
        // stack.getItem() == null counts as an empty hand, and the check has to be here rather than later: a TLM maid's
        // main hand arrives over the network, where "empty" is decoded as a stack whose item is null rather than as a
        // null stack. Dereferencing getItem() further down would throw after the two glPushMatrix calls below and never
        // reach their pops, leaking two GL matrices every frame for the rest of the session.
        if (stack == null || stack.getItem() == null || bones.isEmpty()) return;

        GL11.glPushMatrix();
        // R-04：所有状态改动都放进 try/finally——物品渲染（MinecraftForgeClient/RenderBlocks）对畸形物品
        // 可能抛错，一旦漏掉 pop/回滚会让后续整帧的矩阵与剔除状态错乱。
        try {
            // 原版 1.7.10 手持物品的渲染状态（RenderPlayer.renderEquippedItems:313-414 就是在这些状态
            // 下调用 ItemRenderer.renderItem 的）：实体渲染期间 alpha test 一直是开着的
            // （RendererLivingEntity.doRender:163 在每个渲染 pass 前 glEnable(GL_ALPHA_TEST)，
            // alpha func 是 Minecraft 启动时设的 GL_GREATER/0.1F，Minecraft.java:575-576；
            // EntityRenderer 在渲染实体前也把它设回 0.1，EntityRenderer.java:1289），混合是关着的
            // （RendererLivingEntity.java:223；需要混合的物品由 ItemRenderer.renderItem 自己开）。
            // 本层挂在模型之后（GeoReplacedEntityRenderer#renderLayers），而模型绘制结束时会按 RenderType
            // 的语义把 alpha test 关掉，所以这里必须自己设回原版基线：否则 2D 物品的透明像素不会被剔除，
            // 世界里画成一整片方片（透明像素写出贴图 alpha-0 处残留的 RGB），HUD 纸娃娃烘进 FBO 时则写成
            // alpha 0 的洞、透出天空。dev-1.20.1 的物品层不写这些，因为 1.20 的 ItemInHandRenderer 用
            // RenderType，每次绘制都自带 alpha 测试状态（见 .agent/phase56-held-item-alpha-state.md）。
            applyHeldItemRenderState();
            if (!isVanilla) {
                GL11.glScalef(0.7F, 0.7F, 0.7F);
            }
            applyBoneTransform(bones);
            if (!isMainHand) {
                GL11.glScalef(-1, 1, 1);
                GL11.glFrontFace(GL11.GL_CW); // 修正镜像导致的面剔除反转
            }

            if (isMainHand && TACZCompat.isGun(stack)) {
                renderTacGunInUpstreamHandFrame(entity, stack);
            } else {
                doRenderItem(entity, stack);
            }
        } finally {
            if (!isMainHand) {
                GL11.glFrontFace(GL11.GL_CCW);
            }
            restoreHeldItemRenderState();
            GL11.glPopMatrix();
        }
    }

    protected void applyBoneTransform(List<GeoBone> bones) {
        int size = bones.size();
        for (int i = 0; i < size - 1; i++) {
            RenderUtils.prepMatrixForBone(bones.get(i));
        }
        GeoBone lastBone = bones.get(size - 1);
        RenderUtils.translateMatrixToBone(lastBone);
        RenderUtils.translateToPivotPoint(lastBone);
        RenderUtils.rotateMatrixAroundBone(lastBone);
        RenderUtils.scaleMatrixForBone(lastBone);
    }

    /**
     * Draws a <b>main-hand</b> TacZ gun in third person in the upstream YSM 1.20 hand frame.
     *
     * <p>
     * The vanilla 1.7.10 path this port replaces ({@code RenderPlayer#renderEquippedItems} + {@code ForgeHooksClient})
     * is what TacZ's 1.7.10 {@code IItemRenderer} compensates against: it right-multiplies
     * {@code inv(F_1.7.10)·F_1.20} and that only cancels when the caller really is vanilla {@code RenderPlayer}. The
     * port's own calibrated item transforms (see {@link #doRenderItem}) are tuned for the YSM hand frame and are not
     * that frame, so the compensation left the gun at a {@code T(1.25,-0.27,-0.22)} offset with a rotation - the
     * "gun floating in front of the chest" symptom. This method applies upstream YSM's own frame
     * ({@code CustomPlayerItemInHandLayer.java:68-70}: locator → {@code T(0,-1/16,-0.1)} → {@code Rx(-90)}, then
     * {@code ItemRenderer}'s unconditional {@code T(-0.5,-0.5,-0.5)}) and pre-multiplies the inverse compensation, so
     * the gun ends up exactly in that frame. Derivation and numeric check:
     * {@code TacCompatInner#mainhandGunFrameFix()} / {@code tmp/_probe/TacGunFrameProbe.java}.
     */
    private void renderTacGunInUpstreamHandFrame(EntityLivingBase entity, ItemStack stack) {
        GL11.glTranslatef(0.0F, -0.0625F, -0.1F);
        GL11.glRotatef(-90.0F, 1.0F, 0.0F, 0.0F);
        GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
        GL11.glMultMatrix(TACZCompat.mainhandGunFrameFix());
        itemRenderer.renderItem(entity, stack, 0);
    }

    private void doRenderItem(EntityLivingBase entity, ItemStack itemInHand) {
        // 鱼竿显示为木棍是玩家专属逻辑：fishEntity 是玩家自己的浮标实体，EntityLivingBase 上没有它。
        if (entity instanceof EntityPlayer player && player.fishEntity != null
            && itemInHand.getItem() == Items.fishing_rod) {
            itemInHand = new ItemStack(Items.stick);
        }

        IItemRenderer customRenderer = MinecraftForgeClient.getItemRenderer(itemInHand, IItemRenderer.ItemRenderType.EQUIPPED);
        boolean is3D = (customRenderer != null && customRenderer.shouldUseRenderHelper(IItemRenderer.ItemRenderType.EQUIPPED, itemInHand, IItemRenderer.ItemRendererHelper.BLOCK_3D));
        boolean isBlock = itemInHand.getItem() instanceof ItemBlock && RenderBlocks.renderItemIn3d(Block.getBlockFromItem(itemInHand.getItem()).getRenderType());
        if (is3D || isBlock) {
            GL11.glTranslatef(0.0F, 0.0F, -0.3125F);
            GL11.glRotatef(-20.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
            float f2 = 0.375F; // 0.5 * 0.75
            GL11.glScalef(f2, f2, -f2);
        } else if (itemInHand.getItem() == Items.bow) {
            GL11.glTranslatef(-0.1F, 0.125F, 0.3125F);
            GL11.glRotatef(-15.0F, 0.0F, 1.0F, 0.0F);
            GL11.glScalef(0.625F, 0.625F, 0.625F);
            GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
        } else if (itemInHand.getItem().isFull3D()) {
            if (itemInHand.getItem().shouldRotateAroundWhenRendering()) {
                GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
                GL11.glTranslatef(0.0F, -0.125F, 0.0F);
            }
            // 使用中的物品（格挡姿态）读的是玩家的 itemInUseCount，而物品使用状态只存在于 EntityPlayer
            // 上（EntityLivingBase 没有 itemInUse/itemInUseCount），所以这个分支仅对玩家成立。
            if (entity instanceof EntityPlayer player && player.getItemInUseCount() > 0
                && itemInHand.getItemUseAction() == EnumAction.block) {
                GL11.glTranslatef(0.05F, 0.0F, -0.1F);
                GL11.glRotatef(-50.0F, 0.0F, 1.0F, 0.0F);
                GL11.glRotatef(-10.0F, 1.0F, 0.0F, 0.0F);
                GL11.glRotatef(-60.0F, 0.0F, 0.0F, 1.0F);
            }
            GL11.glTranslatef(0.0F, -0.1F, 0.0F);
            GL11.glScalef(0.625F, 0.625F, 0.625F);
            GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
        } else {
            GL11.glTranslatef(0.175F, 0.0875F, -0.15F);
            GL11.glScalef(0.375F, 0.375F, 0.375F);
            GL11.glRotatef(60.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(-60.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(20.0F, 0.0F, 0.0F, 1.0F);
        }

        // 统一处理单层和多层渲染
        int passes = 1;
        if (itemInHand.getItem().requiresMultipleRenderPasses()) {
            passes = itemInHand.getItem().getRenderPasses(itemInHand.getItemDamage());
        }
        for (int k = 0; k < passes; ++k) {
            int color = itemInHand.getItem().getColorFromItemStack(itemInHand, k);
            float r = (float) (color >> 16 & 255) / 255.0F;
            float g = (float) (color >> 8 & 255) / 255.0F;
            float b = (float) (color & 255) / 255.0F;
            GL11.glColor4f(r, g, b, 1.0F);
            itemRenderer.renderItem(entity, itemInHand, k);
        }
    }
}

