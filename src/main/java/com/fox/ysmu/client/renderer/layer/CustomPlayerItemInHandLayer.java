package com.fox.ysmu.client.renderer.layer;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderBlocks;
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

import java.util.List;
import java.util.Objects;

// This optional marker tells Backhand not to render this layer twice; behavior calls stay behind BackhandCompat.
@Optional.Interface(iface = "xonin.backhand.compat.IOffhandRenderOptOut", modid = "backhand")
public class CustomPlayerItemInHandLayer<T extends EntityLivingBase & IAnimatable> extends GeoLayerRenderer<T>
    implements IOffhandRenderOptOut {

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
        ExtraInfo extraInfo = geoModel.properties.getExtraInfo();
        boolean isVanilla = extraInfo != null
            && (Objects.equals(extraInfo.getName(), "Steve") || Objects.equals(extraInfo.getName(), "Alex"));
        // 主手物品统一走 entity.getHeldItem()：玩家的 getHeldItem() 就是主手，玩家路径行为不变；
        // 非玩家实体（穿 YSM 模型的 TLM 女仆）同样由它给出同步过来的主手物品。
        ItemStack mainHandItem = entity.getHeldItem();
        // 副手是玩家专属：BackhandCompat 读的是玩家自己的副手扩展，非玩家实体没有这一槽位，故为 null。
        ItemStack offhandItem = entity instanceof EntityPlayer player ? BackhandCompat.getOffhandItem(player) : null;
        if (mainHandItem != null || offhandItem != null) {
            GlStateManager.pushMatrix();
            renderArmWithItem(entity, mainHandItem, geoModel.rightHandBones, true, isVanilla);
            renderArmWithItem(entity, offhandItem, geoModel.leftHandBones, false, isVanilla);
            GlStateManager.popMatrix();
        }
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
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_CULL_FACE);
        if (!isVanilla) {
            GL11.glScalef(0.7F, 0.7F, 0.7F);
        }
        applyBoneTransform(bones);
        if (!isMainHand) {
            GL11.glScalef(-1, 1, 1);
            GL11.glFrontFace(GL11.GL_CW); // 修正镜像导致的面剔除反转
        }

        doRenderItem(entity, stack);

        if (!isMainHand) {
            GL11.glFrontFace(GL11.GL_CCW);
        }
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPopMatrix();
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
