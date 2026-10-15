package com.fox.ysmu.client.renderer.layer;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.fox.ysmu.compat.EtFuturumCompat;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.GeoLayerRenderer;
import software.bernie.geckolib3.geo.IGeoRenderer;

/**
 * Draws the elytra wings on a YSM model, which 1.7.10 only has at all through Et Futurum Requiem.
 * <p>
 * Upstream has the same layer - {@code client/renderer/layer/CustomPlayerElytraLayer.java} - and hangs a vanilla
 * {@code ElytraModel} off the model's {@code PlayerLocator.elytra} locator with {@code translate(0,1.5,0)},
 * {@code ZP 180}, {@code scale(2,2,2)}. Those numbers describe 1.20's elytra model in that locator's space, so they
 * are not the right constants here: ETFR ships the 1.7.10 wing model and its own renderer, and that renderer draws in
 * entity space with the wing geometry's own rotation points. Calling it is therefore the faithful port, and it keeps
 * one implementation of the wing in the mod that owns it.
 * <p>
 * The one adjustment is the scale: {@code IGeoRenderer#renderEarly} draws the model itself at
 * {@code (widthScale, heightScale, widthScale)}, so the wings have to be scaled the same way or they would not match
 * the body they hang off. Those two factors come from the renderer, not from a constant here.
 * <p>
 * The layer is also what advances ETFR's wing angles for this port, because ETFR's own entry point
 * ({@code RenderPlayerEvent.SetArmorModel}) never fires once {@code RenderPlayerEvent.Pre} is cancelled.
 */
public class EtFuturumElytraLayer<T extends EntityLivingBase & IAnimatable> extends GeoLayerRenderer<T> {

    public EtFuturumElytraLayer(IGeoRenderer<T> entityRendererIn) {
        super(entityRendererIn);
    }

    @Override
    public void render(T entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
        float netHeadYaw, float headPitch, Color renderColor) {
        if (!EtFuturumCompat.isLoaded() || !(entity instanceof EntityPlayer player)) {
            return;
        }
        if (EtFuturumCompat.getEquippedElytra(player) == null) {
            return;
        }

        GL11.glPushMatrix();
        try {
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            float width = entityRenderer.getWidthScale(entity);
            float height = entityRenderer.getHeightScale(entity);
            GL11.glScalef(width, height, width);
            // ETFR's own layer: it picks the texture, draws both wings and adds the glint. It draws at the current
            // matrix origin, which is entity space here just as it is where ETFR calls it from.
            EtFuturumCompat.renderWings(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, 0.0625F);
        } finally {
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glPopMatrix();
        }
    }
}
