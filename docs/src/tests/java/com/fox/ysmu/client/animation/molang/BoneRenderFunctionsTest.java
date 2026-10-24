package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.geo.render.built.GeoBone;

/**
 * The per-bone render functions upstream exposes to packs: {@code bone_color}, {@code bone_transparency} and
 * {@code bone_glow} ({@code com/elfmcys/ysm/client/animation/molang/YSMBinding.java:82-85}, implementations in
 * {@code functions/BoneRenderFunction.java:52-94}).
 * <p>
 * These live here rather than in the engine repository because YSMU is where the packs that call them are exercised,
 * the same reason {@link MolangParserCompatibilityTest} gives.
 */
class BoneRenderFunctionsTest {

    @Test
    void theThreeNamesAreRegisteredUnderTheNamesPacksCall() {
        MolangParser parser = new MolangParser();

        for (String name : new String[] { "bone_color", "bone_transparency", "bone_glow" }) {
            assertNotNull(parser.functions.get(name), name + " must resolve to a function class");
        }
    }

    @Test
    void theyParseWithUpstreamsArityAndEvaluateWithoutABone() throws Exception {
        MolangParser parser = new MolangParser();

        // Upstream's argument counts: colour is bone + r + g + b, the other two are bone + value.
        IValue color = parser.parseExpression("bone_color('RightArm', 255, 128, 0)");
        IValue transparency = parser.parseExpression("bone_transparency('RightArm', 128)");
        IValue glow = parser.parseExpression("bone_glow('RightArm', 15)");

        // With no animation frame open there is no bone to find. Upstream ignores that case instead of raising, so
        // these must evaluate quietly - a pack that names a bone this model does not have still animates.
        assertEquals(0.0D, color.get(), 0.0D);
        assertEquals(0.0D, transparency.get(), 0.0D);
        assertEquals(0.0D, glow.get(), 0.0D);
    }

    @Test
    void outOfRangeValuesAreClampedTheWayUpstreamClampsThem() throws Exception {
        MolangParser parser = new MolangParser();

        // Parsing must succeed for out-of-range literals: upstream clamps inside the function rather than rejecting
        // the call, and a pack that writes 300 for a channel must not lose the whole animation channel.
        for (String expression : new String[] { "bone_color('Head', 300, -5, 999)", "bone_transparency('Head', 300)",
            "bone_glow('Head', 99)", "bone_glow('Head', -9)" }) {
            assertNotNull(parser.parseExpression(expression), expression);
        }
    }

    @Test
    void theBoneCarriesWhiteOpaqueAndNotEmissiveUntilSomethingSetsThem() {
        GeoBone bone = new GeoBone();

        // The defaults are what the renderer multiplies by, so a model that calls none of these functions must be
        // untouched: white, opaque, not emissive.
        assertEquals(0xFFFFFF, bone.getRenderColor());
        assertEquals(255, bone.getRenderTransparency());
        assertEquals(-1, bone.getRenderGlow());
        assertEquals(1.0F, bone.getRenderRed(), 0.0F);
        assertEquals(1.0F, bone.getRenderGreen(), 0.0F);
        assertEquals(1.0F, bone.getRenderBlue(), 0.0F);
        assertEquals(1.0F, bone.getRenderAlpha(), 0.0F);
    }

    @Test
    void colourIsPackedAndClampedLikeUpstreamsPackedAttribute() {
        GeoBone bone = new GeoBone();

        bone.setRenderColor(255, 0, 0);
        assertEquals(1.0F, bone.getRenderRed(), 0.0F);
        assertEquals(0.0F, bone.getRenderGreen(), 0.0F);
        assertEquals(0.0F, bone.getRenderBlue(), 0.0F);

        bone.setRenderColor(0, 255, 0);
        assertEquals(0.0F, bone.getRenderRed(), 0.0F);
        assertEquals(1.0F, bone.getRenderGreen(), 0.0F);

        bone.setRenderColor(0, 0, 255);
        assertEquals(1.0F, bone.getRenderBlue(), 0.0F);

        // Each channel is masked to a byte, so a caller cannot shift one channel into another.
        bone.setRenderColor(256, 256, 256);
        assertEquals(0x000000, bone.getRenderColor());
    }

    @Test
    void glowIsMinusOneOrZeroToFifteen() {
        GeoBone bone = new GeoBone();

        bone.setRenderGlow(0);
        assertEquals(0, bone.getRenderGlow(), "0 is a valid glow level, not 'off'");

        bone.setRenderGlow(15);
        assertEquals(15, bone.getRenderGlow());

        bone.setRenderGlow(99);
        assertEquals(15, bone.getRenderGlow(), "upstream clamps the level to 15");

        bone.setRenderGlow(-1);
        assertEquals(-1, bone.getRenderGlow(), "-1 means not emissive");

        bone.setRenderGlow(-9);
        assertEquals(-1, bone.getRenderGlow());
    }

    @Test
    void resettingReturnsABoneToItsDefaultRenderState() {
        GeoBone bone = new GeoBone();
        bone.setRenderColor(10, 20, 30);
        bone.setRenderTransparency(4);
        bone.setRenderGlow(12);

        bone.resetRenderState();

        // The renderer is told to clear these once per animation frame, because unlike upstream - which rebuilds its
        // attribute array every frame - this bone is cached per model and would otherwise stay emissive forever.
        assertEquals(0xFFFFFF, bone.getRenderColor());
        assertEquals(255, bone.getRenderTransparency());
        assertEquals(-1, bone.getRenderGlow());
    }

    @Test
    void transparencyIsMaskedToAByte() {
        GeoBone bone = new GeoBone();

        bone.setRenderTransparency(128);
        assertEquals(128, bone.getRenderTransparency());
        assertTrue(bone.getRenderAlpha() > 0.49F && bone.getRenderAlpha() < 0.51F, "128/255 is about a half");

        bone.setRenderTransparency(300);
        assertEquals(44, bone.getRenderTransparency(), "masked to a byte, like upstream's packed attribute");
    }
}
