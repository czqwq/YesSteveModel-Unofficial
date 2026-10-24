package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * The two real defects in the vendored mclib math functions
 * ({@code src/main/java/com/eliotlash/mclib/math/functions/utility/**}), and the four upstream names that were
 * missing from the {@code math.*} table. Evidence and the upstream citations are in
 * {@code .agent/phase27-mclib-math-defects.md}; the comparison report is {@code tmp/math-function-divergences.md}.
 *
 * <p>Every test here is bounded by {@link Timeout}, because the defect being pinned is an <b>infinite loop</b>, not
 * a wrong value: without the bound a regression hangs the whole suite instead of failing it. The bodies in the
 * vendored snapshot never incremented their counter
 * ({@code DieRoll.java:24-28} / {@code DieRollInteger.java:24-28}), so any call whose first argument was {@code > 0}
 * spun forever on the render thread.</p>
 */
class MclibMathFunctionDefectsTest {

    /**
     * {@code math.die_roll} sums {@code arg0} samples of {@code [arg1, arg2)} - upstream's shape, ported into
     * {@code com/eliotlash/mclib/math/functions/utility/DieRoll.java:get()}.
     */
    @Test
    @Timeout(5)
    void dieRollTerminatesAndStaysInsideItsRange() throws Exception {
        MolangParser parser = new MolangParser();

        // The exact call that used to hang: arg0 = 1 > 0, so the accumulation loop never ended.
        double single = parser.parseExpression("math.die_roll(1,0,1)")
            .get();
        assertTrue(single >= 0.0d && single < 1.0d, "die_roll(1,0,1) must land in [0,1), was " + single);

        for (int trial = 0; trial < 64; trial++) {
            double rolled = parser.parseExpression("math.die_roll(3,1,7)")
                .get();
            assertTrue(rolled >= 3.0d && rolled < 21.0d, "die_roll(3,1,7) must be in [3,21), was " + rolled);
        }

        // The swap branch (min > max) and a count large enough that the old loop could never have finished.
        double swapped = parser.parseExpression("math.die_roll(5,7,1)")
            .get();
        assertTrue(swapped >= 5.0d && swapped < 35.0d, "die_roll(5,7,1) must be in [5,35), was " + swapped);
        assertEquals(
            0.0d,
            parser.parseExpression("math.die_roll(0,1,7)")
                .get(),
            0.0d,
            "zero rolls must sum to zero");

        // The bare short name is the same table entry (MathBuilder.java:63).
        double alias = parser.parseExpression("roll(2,0,1)")
            .get();
        assertTrue(alias >= 0.0d && alias < 2.0d, "roll(2,0,1) must be in [0,2), was " + alias);
    }

    /** {@code math.die_roll_integer} is upstream's own body, which is not the same shape as {@code die_roll}. */
    @Test
    @Timeout(5)
    void dieRollIntegerTerminatesAndAnswersIntegers() throws Exception {
        MolangParser parser = new MolangParser();

        // The second call that used to hang.
        double single = parser.parseExpression("math.die_roll_integer(1,1,6)")
            .get();
        assertTrue(single >= 1.0d && single <= 5.0d, "die_roll_integer(1,1,6) must be in [1,5], was " + single);

        for (int trial = 0; trial < 64; trial++) {
            double rolled = parser.parseExpression("math.die_roll_integer(3,1,7)")
                .get();
            assertEquals(Math.rint(rolled), rolled, 0.0d, "the integer form must sum integers, was " + rolled);
            assertTrue(rolled >= 3.0d && rolled <= 18.0d, "die_roll_integer(3,1,7) must be in [3,18], was " + rolled);
        }

        double swapped = parser.parseExpression("math.die_roll_integer(4,6,1)")
            .get();
        assertEquals(Math.rint(swapped), swapped, 0.0d, "the swapped form must still sum integers");
        assertTrue(swapped >= 4.0d && swapped <= 20.0d, "die_roll_integer(4,6,1) must be in [4,20], was " + swapped);
        assertEquals(
            0.0d,
            parser.parseExpression("math.die_roll_integer(0,1,7)")
                .get(),
            0.0d,
            "zero rolls must sum to zero");
        // A degenerate span answers the single value: upstream's nextInt(0) would throw and kill the channel.
        assertEquals(
            6.0d,
            parser.parseExpression("math.die_roll_integer(2,3,3)")
                .get(),
            0.0d,
            "min == max must answer that value, not throw");
    }

    /**
     * The four names upstream's {@code MathBinding} declares and this port's table did not
     * (tmp/YesSteveModel-dev-1.20 .../builtin/MathBinding.java:54-57). Without them the lookup in
     * {@code MathBuilder.createFunction} failed, {@code MolangParser.parseOneLine} gave up on the <em>whole</em>
     * statement and the channel silently evaluated to 0.
     */
    @Test
    void theFourUpstreamOnlyNamesAreRegistered() {
        MolangParser parser = new MolangParser();

        for (String name : new String[] { "math.roll", "math.rolli", "math.hermite", "math.randomi" }) {
            assertNotNull(parser.functions.get(name), name + " must resolve to a function class");
        }
        // They share the implementations the long names already used, rather than being a third copy.
        assertEquals(parser.functions.get("math.die_roll"), parser.functions.get("math.roll"));
        assertEquals(parser.functions.get("math.die_roll_integer"), parser.functions.get("math.rolli"));
        assertEquals(parser.functions.get("math.hermite_blend"), parser.functions.get("math.hermite"));
        assertEquals(parser.functions.get("math.random_integer"), parser.functions.get("math.randomi"));
        // The short names are what existing packs call; registering the math.* copies must not consume them.
        for (String shortName : new String[] { "roll", "rolli", "hermite", "randomi" }) {
            assertNotNull(parser.functions.get(shortName), shortName + " must stay registered");
        }
        // A name nothing registers still has to be absent, so the four assertions above are not vacuous.
        assertNull(parser.functions.get("math.not_a_function"));
    }

    /**
     * Parse-and-evaluate proof for the four names: a name that is in the table but cannot be evaluated would still
     * make {@code MolangInstructionExecutor} report {@code Function 'math.roll' couldn't be found!} and drop the
     * instruction. Every value is asserted finite, which a {@code NaN} body would not be.
     */
    @Test
    @Timeout(5)
    void theFourUpstreamOnlyNamesParseAndEvaluate() throws Exception {
        MolangParser parser = new MolangParser();

        double roll = parser.parseExpression("math.roll(2,0,1)")
            .get();
        assertTrue(roll >= 0.0d && roll < 2.0d, "math.roll(2,0,1) must be in [0,2), was " + roll);

        double rolli = parser.parseExpression("math.rolli(3,1,7)")
            .get();
        assertEquals(Math.rint(rolli), rolli, 0.0d, "math.rolli(3,1,7) must be an integer, was " + rolli);
        assertTrue(rolli >= 3.0d && rolli <= 18.0d, "math.rolli(3,1,7) must be in [3,18], was " + rolli);

        // Upstream HermitBlend.java:8-11: ceil, then floor the cubic. 0.2 -> ceil 1 -> 3-2 = 1.
        assertEquals(
            1.0d,
            parser.parseExpression("math.hermite(0.2)")
                .get(),
            0.0d,
            "math.hermite must be the same ceil+floor body as math.hermite_blend");
        assertEquals(
            1.0d,
            parser.parseExpression("math.hermite_blend(0.2)")
                .get(),
            0.0d,
            "the long name must not have moved");

        double randomi = parser.parseExpression("math.randomi(2,5)")
            .get();
        assertEquals(Math.rint(randomi), randomi, 0.0d, "math.randomi(2,5) must be an integer, was " + randomi);
        assertTrue(randomi >= 2.0d && randomi <= 4.0d, "math.randomi(2,5) must be in [2,4], was " + randomi);

        // Inside a larger expression, i.e. the position where a missing name killed the statement.
        double nested = parser.parseExpression("math.max(math.rolli(2,0,4),0)")
            .get();
        assertTrue(nested >= 0.0d && nested <= 6.0d, "a nested math.rolli must evaluate, was " + nested);
        // And as the assignment shape a timeline writes: `math.rolli(1,0,1)` is always 0, and the statement after
        // the `;` must read the assignment back.
        double assigned = parser.parseExpression("v.bv=math.rolli(1,0,1);v.bv")
            .get();
        assertEquals(
            0.0d,
            assigned,
            0.0d,
            "the timeline assignment shape must reach the variable, not fail the statement");
        assertEquals(
            0.0d,
            parser.parseExpression("v.bv")
                .get(),
            0.0d,
            "the variable must still hold what the assignment wrote");
    }

    /**
     * The settled half of the audit, pinned so it is not "fixed" again: the <em>live</em> {@code math.sin} /
     * {@code math.cos} are the degrees implementations. {@code MolangParser.java:98-99} overwrite the short keys
     * with {@code CosDegrees}/{@code SinDegrees}, and the {@code remap} at {@code :153}/{@code :190} then moves
     * those to {@code math.*} ({@code remap} is {@code :217-222}: it removes the short key), so the radian
     * {@code classic/Sin.java}/{@code classic/Cos.java} are unreachable. Built-in content is authored in degrees
     * (e.g. {@code assets/ysmu/builtin/default/animations/tlm.animation.json:35} {@code math.sin(-90+...)}).
     */
    @Test
    void sinAndCosStayDegreesOnTheLivePath() throws Exception {
        MolangParser parser = new MolangParser();

        assertEquals(
            1.0d,
            parser.parseExpression("math.sin(90)")
                .get(),
            1.0E-9d,
            "math.sin must take degrees; radians would answer 0.894");
        assertEquals(
            0.0d,
            parser.parseExpression("math.cos(90)")
                .get(),
            1.0E-9d,
            "math.cos must take degrees; radians would answer -0.448");
        assertEquals(
            -1.0d,
            parser.parseExpression("math.sin(-90)")
                .get(),
            1.0E-9d,
            "the negative-degree phase the built-in tlm animation writes");

        // The radian originals are gone from the table, which is what makes the two classes above dead code.
        assertNull(parser.functions.get("sin"), "the short name is remapped away, not left in place");
        assertNull(parser.functions.get("cos"), "the short name is remapped away, not left in place");
        assertFalse(
            com.eliotlash.mclib.math.functions.classic.Sin.class.equals(parser.functions.get("math.sin")),
            "math.sin must not be the radian classic/Sin");
        assertFalse(
            com.eliotlash.mclib.math.functions.classic.Cos.class.equals(parser.functions.get("math.cos")),
            "math.cos must not be the radian classic/Cos");
    }
}
