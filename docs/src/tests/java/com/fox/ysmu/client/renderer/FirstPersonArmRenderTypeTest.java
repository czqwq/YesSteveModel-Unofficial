package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.YsmRenderType;
import software.bernie.geckolib3.model.provider.GeoModelProvider;

/**
 * The state the first-person arm is drawn with, which is the one part of this fix that is a pure decision rather than
 * a GL call: {@code FirstPersonHandRenderer#armRenderType} asks the same {@code IGeoRenderer#getRenderType} the world
 * path and the preview path ask, so the arm cannot be given a state of its own again.
 *
 * <p>
 * The regression this pins: commit 67f8044 replaced {@code disableCull()} in
 * {@code FirstPersonHandRenderer#prepareCustomArmState} with {@code disableAlpha() + enableCull()} - upstream's
 * {@code CustomTranslucentRenderType} state, which upstream's arm renderer really does use unconditionally
 * ({@code client/renderer/CustomFirstPersonArmRenderer.java:39}). For an opaque model that is the opposite of what the
 * world path draws the same geometry with ({@code RenderType.entityCutoutNoCull}): alpha test off, so a texel with
 * alpha 0 is not discarded, and culling on, so a face of a flat or single-layer piece is dropped.
 *
 * <p>
 * {@code getRenderType} is a default method on the interface, so the whole table can be driven without a client.
 */
class FirstPersonArmRenderTypeTest {

    private static final ResourceLocation TEXTURE = new ResourceLocation("ysmu", "default/default.png");

    private static IGeoRenderer<Object> renderer() {
        return new IGeoRenderer<Object>() {

            @Override
            public GeoModelProvider getGeoModelProvider() {
                return null;
            }

            @Override
            public ResourceLocation getTextureLocation(Object instance) {
                return TEXTURE;
            }
        };
    }

    @Test
    void anOpaqueArmIsDrawnLikeUpstreamsEntityCutoutNoCull() {
        YsmRenderType type = FirstPersonHandRenderer.armRenderType(renderer(), TEXTURE, false);

        assertNotNull(type);
        assertEquals(TEXTURE, type.getTexture());
        assertTrue(type.isAlphaTest(), "an alpha-0 texel must be discarded, not left to show the unlit far face");
        assertTrue(type.getAlphaRef() > 0.0F, "the alpha test needs a reference");
        assertFalse(type.isBlend(), "cutout does not blend");
        assertFalse(type.isCull(), "no culling, so the arm's mirrored winding cannot drop a face");
    }

    @Test
    void aTranslucentArmKeepsUpstreamsTranslucentBranch() {
        YsmRenderType type = FirstPersonHandRenderer.armRenderType(renderer(), TEXTURE, true);

        assertNotNull(type);
        assertTrue(type.isBlend(), "a translucent draw blends");
        assertTrue(type.isCull(), "a translucent draw culls, which is what removes a flat decal's back face");
        assertFalse(type.isAlphaTest(), "translucency is blending, not an alpha test");
    }

    @Test
    void theArmAsksTheSameQuestionAsTheWorldPath() {
        // The defect was a state of the arm path's own. Whatever the model answers, both paths must get the same
        // predicate: culling, blending and the alpha test all read off one answer.
        for (boolean translucent : new boolean[] { false, true }) {
            YsmRenderType world = renderer().getRenderType(TEXTURE, true, false, translucent);
            YsmRenderType arm = FirstPersonHandRenderer.armRenderType(renderer(), TEXTURE, translucent);

            assertNotNull(world);
            assertNotNull(arm);
            assertEquals(world.isCull(), arm.isCull(), "culling must not differ between the arm and the world");
            assertEquals(world.isBlend(), arm.isBlend(), "blending must not differ between the arm and the world");
            assertEquals(world.isAlphaTest(), arm.isAlphaTest(), "the alpha test must not differ");
            assertEquals(world.getAlphaRef(), arm.getAlphaRef());
        }
    }
}
