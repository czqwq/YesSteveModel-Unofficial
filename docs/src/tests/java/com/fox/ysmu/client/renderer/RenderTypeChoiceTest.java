package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.YsmRenderType;
import software.bernie.geckolib3.model.provider.GeoModelProvider;

/**
 * Upstream's render-type truth table, which the port now answers with the same method, parameters and branches
 * ({@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:33-39}):
 *
 * <pre>
 * visible &amp;&amp;  translucent -&gt; CustomTranslucentRenderType (blends, culls)
 * visible &amp;&amp; !translucent -&gt; RenderType.entityCutoutNoCull (alpha-tests, does not blend, does not cull)
 * !visible &amp;&amp;  glowing      -&gt; RenderType.outline (1.7.10 has no outline pass, so nothing is drawn)
 * !visible &amp;&amp; !glowing      -&gt; null
 * </pre>
 *
 * {@code getRenderType} is a default method on the interface, so the table can be driven without a client: only the
 * two accessors the interface leaves abstract are supplied.
 */
class RenderTypeChoiceTest {

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
    void aTranslucentVisibleModelIsDrawnLikeUpstreamsEntityTranslucent() {
        YsmRenderType type = renderer().getRenderType(TEXTURE, true, false, true);

        assertNotNull(type);
        assertEquals(TEXTURE, type.getTexture());
        // CustomTranslucentRenderType wraps RenderType.entityTranslucent: blending on, culling on, no alpha test.
        assertTrue(type.isBlend(), "a translucent draw blends");
        assertTrue(type.isCull(), "a translucent draw culls, which is what removes a flat decal's back face");
        assertFalse(type.isAlphaTest(), "translucency is blending, not an alpha test");
    }

    @Test
    void anOpaqueVisibleModelIsDrawnLikeUpstreamsEntityCutoutNoCull() {
        YsmRenderType type = renderer().getRenderType(TEXTURE, true, false, false);

        assertNotNull(type);
        // RenderType.entityCutoutNoCull: cutout alpha test, no blending, and culling explicitly off.
        assertTrue(type.isAlphaTest(), "cutout alpha-tests");
        assertTrue(type.getAlphaRef() > 0.0F, "the alpha test needs a reference");
        assertFalse(type.isBlend(), "cutout does not blend");
        assertFalse(type.isCull(), "NoCull is the point of this branch");
    }

    @Test
    void anInvisibleModelIsNotDrawnThroughThisPath() {
        // Upstream returns RenderType.outline when the entity glows and null otherwise. 1.7.10 has no outline pass,
        // so both answer null here - the behaviour this engine has always had for an invisible entity - and the port
        // records that as a divergence rather than inventing an outline.
        assertNull(renderer().getRenderType(TEXTURE, false, true, true));
        assertNull(renderer().getRenderType(TEXTURE, false, false, true));
        assertNull(renderer().getRenderType(TEXTURE, false, false, false));
    }

    @Test
    void theEngineDefaultAnswerIsTheCutoutBranch() {
        // Nothing is translucent unless the host says so, which keeps every model on the branch it had before the
        // render type existed.
        assertFalse(renderer().hasTranslucentVertices(new Object()));
    }
}
