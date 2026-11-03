package com.fox.ysmu.client.renderer.layer;

import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntitySkullRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.authlib.GameProfile;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.GeoLayerRenderer;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.util.RenderUtils;

/**
 * Draws the item in the player's head slot on the model's head bone - a pumpkin, a skull, anything wearable.
 * <p>
 * Upstream has the same layer ({@code client/renderer/layer/CustomPlayerHeadLayer.java:26-50}) and renders any
 * non-armor item through 1.20's {@code ItemDisplayContext.HEAD}. 1.7.10 has no display contexts; its own head-slot
 * rendering lives in {@code RenderPlayer#renderEquippedItems} and covers exactly two shapes, blocks and skulls.
 * {@link #renderHeadItem} is that branch with its constants, and the only change is that the item hangs off this
 * model's head bone instead of {@code bipedHead.postRender(0.0625F)}, so it follows the animated head.
 * <p>
 * Without this layer the head slot is invisible on a YSM model: YSMU cancels {@code RenderPlayerEvent.Pre}
 * ({@code ClientEventHandler#onRender}), so the vanilla {@code renderEquippedItems} that would draw it never runs.
 */
public class CustomPlayerHeadLayer<T extends EntityLivingBase & IAnimatable> extends GeoLayerRenderer<T> {

    /** The head slot of a 1.7.10 inventory: 0 boots, 1 legs, 2 chest, 3 head. */
    private static final int HEAD_SLOT = 3;

    /**
     * The humanoid head locator group; a pack expresses an extra or transformed head by numbering the bone
     * ({@code Head2}/{@code Head3}/{@code Head5}) and the group rule strips the trailing digits. Upstream's head layer
     * draws the head-slot item at <b>every</b> member of that group
     * ({@code tmp/YesSteveModel-dev-1.20/.../client/renderer/layer/CustomPlayerHeadLayer.java:26-50}: the
     * {@code visitLocatorGroup(PlayerLocator.get().head, ...)} callback at {@code :44} runs once per locator), and this
     * port used to take only the exact-named {@code Head}.
     */
    private static final String HEAD_LOCATOR_GROUP = "Head";

    public CustomPlayerHeadLayer(IGeoRenderer<T> entityRendererIn) {
        super(entityRendererIn);
    }

    @Override
    public void render(T entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
        float netHeadYaw, float headPitch, Color renderColor) {
        if (!(entity instanceof EntityPlayer player)) {
            return;
        }
        ItemStack head = player.inventory.armorInventory[HEAD_SLOT];
        if (head == null || head.getItem() == null) {
            return;
        }
        // A helmet belongs to the armor pass; upstream skips the same case (CustomPlayerHeadLayer.java:39-41).
        if (head.getItem() instanceof ItemArmor) {
            return;
        }
        GeoModel geoModel = entityRenderer.getGeoModel();
        if (geoModel == null) {
            return;
        }
        // Every member of the group, like upstream's visitLocatorGroup; empty means the model has no head locator at
        // all, which is the same "draw nothing" the single exact-name lookup answered before.
        List<GeoBone> headBones = geoModel.locatorGroup(HEAD_LOCATOR_GROUP);
        if (headBones.isEmpty()) {
            return;
        }

        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            for (GeoBone headBone : headBones) {
                // One matrix per head: the transform below is relative to the current matrix, so without this every
                // extra head would be drawn on top of the previous one's accumulated chain.
                GL11.glPushMatrix();
                try {
                    applyHeadBoneTransform(headBone);
                    renderHeadItem(player, head);
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glPopMatrix();
        }
    }

    /**
     * Puts the origin at the head bone's pivot with its rotation applied, which is what {@code bipedHead.postRender}
     * does for the vanilla model and what {@link #renderHeadItem}'s constants were authored against. The ancestors are
     * transformed first so a head nested under another bone still lands in the right place.
     */
    private void applyHeadBoneTransform(GeoBone headBone) {
        GeoBone[] path = entityRenderer.getPathFromRoot(headBone);
        for (int i = 0; i < path.length - 1; i++) {
            RenderUtils.prepMatrixForBone(path[i]);
        }
        GeoBone last = path[path.length - 1];
        RenderUtils.translateMatrixToBone(last);
        RenderUtils.translateToPivotPoint(last);
        RenderUtils.rotateMatrixAroundBone(last);
        RenderUtils.scaleMatrixForBone(last);
    }

    /**
     * Vanilla {@code RenderPlayer#renderEquippedItems}'s head branch, constants included. 1.7.10 draws only blocks and
     * skulls there - a plain item worn on the head has no renderer at all - so this is the whole of it rather than a
     * generic "draw the item" call that would guess an orientation the platform never defined.
     */
    private static void renderHeadItem(EntityPlayer player, ItemStack head) {
        if (head.getItem() instanceof ItemBlock) {
            IItemRenderer customRenderer = MinecraftForgeClient
                .getItemRenderer(head, IItemRenderer.ItemRenderType.EQUIPPED);
            boolean is3D = customRenderer != null && customRenderer.shouldUseRenderHelper(
                IItemRenderer.ItemRenderType.EQUIPPED,
                head,
                IItemRenderer.ItemRendererHelper.BLOCK_3D);
            if (is3D || RenderBlocks.renderItemIn3d(Block.getBlockFromItem(head.getItem()).getRenderType())) {
                float scale = 0.625F;
                GL11.glTranslatef(0.0F, -0.25F, 0.0F);
                GL11.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);
                GL11.glScalef(scale, -scale, -scale);
            }
            RenderManager.instance.itemRenderer.renderItem(player, head, 0);
        } else if (head.getItem() == Items.skull) {
            float scale = 1.0625F;
            GL11.glScalef(scale, -scale, -scale);
            GameProfile profile = null;
            if (head.hasTagCompound()) {
                NBTTagCompound tag = head.getTagCompound();
                if (tag.hasKey("SkullOwner", 10)) {
                    profile = NBTUtil.func_152459_a(tag.getCompoundTag("SkullOwner"));
                } else if (tag.hasKey("SkullOwner", 8)) {
                    String owner = tag.getString("SkullOwner");
                    if (owner != null && !owner.isEmpty()) {
                        profile = new GameProfile((UUID) null, owner);
                    }
                }
            }
            TileEntitySkullRenderer.field_147536_b
                .func_152674_a(-0.5F, 0.0F, -0.5F, 1, 180.0F, head.getItemDamage(), profile);
        }
    }
}
