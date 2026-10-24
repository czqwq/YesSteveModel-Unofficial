package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * The scale expressions a real pack writes for its glow bones, evaluated by the parser that actually runs them.
 * <p>
 * {@code wine_fox/05_magical/animations/arm.animation.json} drives the {@code ysmGlowmofazhen*} bones - the flat
 * emissive quads that make up the staff's charge circle - with expressions of exactly this shape:
 *
 * <pre>
 * "scale": { "0.0": [0, 0, 0],
 *            "1.25": ["v.roaming.C==0?1.2:0", "v.roaming.C==0?1.2:0", "v.roaming.C==0?1:0"] }
 * </pre>
 *
 * A scale that evaluates to something other than the intended 0 or 1.2 is what turns a small glyph into a quad
 * stretched across the screen with its texture sampled into static. Animation-file expressions are evaluated by the
 * legacy {@code software.bernie.geckolib3.core.molang.MolangParser} (a {@code MathBuilder} subclass), not by the
 * engine's newer MoLang VM, so this is the parser under test.
 */
class ChargeCircleScaleExpressionTest {

    private static double evaluate(String expression, Double roamingC) throws Exception {
        MolangParser parser = new MolangParser();
        if (roamingC != null) {
            parser.setValue("v.roaming.C", roamingC);
        }
        IValue parsed = parser.parseExpression(expression);
        return parsed.get();
    }

    @Test
    void theChargeCircleScaleFollowsTheRoamingSwitch() throws Exception {
        assertEquals(1.2D, evaluate("v.roaming.C==0?1.2:0", 0.0D), 1.0E-9D, "C==0 must show the circle at 1.2");
        assertEquals(0.0D, evaluate("v.roaming.C==0?1.2:0", 1.0D), 1.0E-9D, "C!=0 must collapse the circle");
        assertEquals(3.0D, evaluate("v.roaming.C==0?3:0", 0.0D), 1.0E-9D);
        assertEquals(4.0D, evaluate("v.roaming.C==0?4:0", 0.0D), 1.0E-9D);
        assertEquals(1.0D, evaluate("v.roaming.C==0?1:0", 0.0D), 1.0E-9D);
    }

    @Test
    void anUnsetVariableReadsAsZeroSoTheEffectDefaultsToOn() throws Exception {
        // Before the player opens the model settings panel the variable is simply absent, and Molang's answer for an
        // undefined variable is the neutral 0 - which selects the 1.2 branch, so the circle is on by default. That is
        // what the pack intends: its checkbox is labelled "turn the charge circle effect off".
        //
        // The name below is one no other test assigns. MolangParser.VARIABLES is static, so a test that assigns
        // "v.roaming.C" leaves that value behind for every later test in the same JVM; asserting on the pack's own
        // switch here would therefore pass or fail depending on which test ran first.
        assertEquals(0.0D, evaluate("v.never_assigned_by_any_test", null), 1.0E-9D);
        assertEquals(1.2D, evaluate("v.never_assigned_by_any_test==0?1.2:0", null), 1.0E-9D);
    }

    @Test
    void theScaleIsAlwaysFinite() throws Exception {
        for (double c : new double[] { 0.0D, 1.0D, -1.0D, 0.5D, 1000.0D, -1000.0D }) {
            double scale = evaluate("v.roaming.C==0?1.2:0", c);
            assertTrue(Double.isFinite(scale), "C=" + c + " produced " + scale);
            assertTrue(scale >= 0.0D && scale <= 3.0D, "C=" + c + " produced an out-of-range scale " + scale);
        }
    }

    @Test
    void aScalarScaleIsNotAnExpression() throws Exception {
        // The same pack also writes bare numbers for this channel ("scale": 0 / "scale": 1, 197 occurrences). They are
        // JSON literals handled by the animation loader, so what matters here is that a plausible literal is finite
        // and that the expression form above is the only place a pack can produce a weird number.
        for (String literal : new String[] { "0", "1", "-1", "1.2" }) {
            assertTrue(Double.isFinite(evaluate(literal, 0.0D)), literal);
        }
    }
}
