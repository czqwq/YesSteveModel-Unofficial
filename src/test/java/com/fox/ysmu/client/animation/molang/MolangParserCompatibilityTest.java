package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * Engine parser behaviour that OpenYSM packs depend on and that the 1.7.10 port got wrong. These live here (rather
 * than in the engine repository) because YSMU is where the failing model packs are exercised; the engine jar is on
 * this test classpath.
 */
class MolangParserCompatibilityTest {

    /** Records the name it was called under, so the {@code q.} alias can be observed. */
    public static class EchoFunction extends Function {

        static String calledAs;

        public EchoFunction(IValue[] values, String name) throws Exception {
            super(values, name);
            calledAs = name;
        }

        @Override
        public double get() {
            return getArg(0) + 1;
        }
    }

    @Test
    void qPrefixResolvesToQueryFunctions() throws Exception {
        MolangParser parser = new MolangParser();
        parser.functions.put("query.echo", EchoFunction.class);

        IValue byQuery = parser.parseExpression("query.echo(1)");
        assertEquals(2.0D, byQuery.get(), 0.0D);

        // `q.` is normalised for variables already; function names used to be looked up verbatim, so a pack writing
        // q.position(...) never saw the host's query.position.
        IValue byShortPrefix = parser.parseExpression("q.echo(1)");
        assertEquals(2.0D, byShortPrefix.get(), 0.0D);
        assertEquals("query.echo", EchoFunction.calledAs);
    }

    @Test
    void mathAliasesPointAtRealFunctions() {
        MolangParser parser = new MolangParser();

        for (String name : new String[] { "math.random", "math.random_integer", "math.die_roll",
            "math.die_roll_integer", "math.hermite_blend", "math.clamp", "math.mod" }) {
            assertNotNull(parser.functions.get(name), name + " must resolve to a function class");
        }
        assertFalse(parser.functions.containsKey("random_integer"), "the wrong source name must not be remapped");
        assertFalse(parser.functions.containsKey("hermite_blend"), "the wrong source name must not be remapped");
        // math.pi is a constant, not a function
        assertFalse(parser.functions.containsKey("math.pi"));
    }

    @Test
    void mathRandomIntegerEvaluates() throws Exception {
        MolangParser parser = new MolangParser();

        double value = parser.parseExpression("math.random_integer(1, 5)")
            .get();

        assertTrue(value >= 1 && value <= 5, "expected 1..5, got " + value);
    }

    @Test
    void remapIgnoresMissingSources() {
        MolangParser parser = new MolangParser();

        parser.remap("definitely_not_registered", "math.nope");

        assertFalse(
            parser.functions.containsKey("math.nope"),
            "a missing source must not leave a null entry that later reports 'couldn't be found'");
    }

    @Test
    void unknownFunctionsStillFail() {
        MolangParser parser = new MolangParser();

        assertThrows(Exception.class, () -> parser.parseExpression("nope(1)"));
    }

    @Test
    void equalityOperatorParses() throws Exception {
        // `==` used to throw StringIndexOutOfBoundsException inside breakdownChars.
        MolangParser parser = new MolangParser();

        assertEquals(1.0D, parser.parseExpression("4==4")
            .get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("4==5")
            .get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("4!=5")
            .get(), 0.0D);
        assertEquals(1.0D, parser.parseExpression("2<=2")
            .get(), 0.0D);
    }

    @Test
    void nullCoalescingOperatorParses() throws Exception {
        // Pack animation channels use Bedrock's `??`: `"scale": "v.player_size??1"` must scale by 1 until the player
        // picks a size. Without the operator the two '?' characters stayed in the token buffer, parseSymbols saw an
        // empty symbol ("Index -1 out of bounds for length 0"), the channel evaluated to 0 and the whole model
        // collapsed to a point - invisible in game while its geometry, texture and registration were all fine.
        MolangParser parser = new MolangParser();

        assertEquals(1.0D, parser.parseExpression("1??2")
            .get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("0??2")
            .get(), 0.0D);
        // An unset v.* variable reads 0, so the fallback applies.
        assertEquals(1.0D, parser.parseExpression("v.player_size??1")
            .get(), 0.0D);
        // Once the model's own config form sets it, the variable wins.
        parser.parseExpression("v.player_size=2.5")
            .get();
        assertEquals(2.5D, parser.parseExpression("v.player_size??1")
            .get(), 0.0D);
        // The same expression through the animation-file entry point.
        assertEquals(
            1.0D,
            parser.parseJson(new com.google.gson.JsonPrimitive("v.never_set_size??1"))
                .get(),
            0.0D);
    }

    @Test
    void colonlessConditionalOperatorParses() throws Exception {
        // YSM/Bedrock packs also write `cond?value` without a colon (meaning `cond ? value : 0`), and they nest it
        // inside a full ternary. parseSymbols used to answer any expression that still contained a bare '?' after
        // tryTernary with the constant 0, so every one of these channels silently became 0: 艾莲·乔's cover preview
        // pinned the whole model at "-4+(!v.fm?50)" = -4 instead of sliding it to 46 for the first two seconds, and
        // walk/run Root positions plus the tac/arm swing scales were wrong the same way.
        MolangParser parser = new MolangParser();

        // `!v.fm?50`: an unset variable is 0, so !0 == 1 and the value is taken.
        assertEquals(50.0D, parser.parseExpression("!v.fm?50")
            .get(), 0.0D);
        assertEquals(46.0D, parser.parseExpression("-4+(!v.fm?50)")
            .get(), 0.0D);
        // Once the model's own initialiser (parallel1's timeline: `v.roaming.player_size=v.roaming.player_size
        // ?v.roaming.player_size:1;`) has run, the same expression answers 0.
        parser.parseExpression("v.fm=1")
            .get();
        assertEquals(0.0D, parser.parseExpression("!v.fm?50")
            .get(), 0.0D);
        assertEquals(-4.0D, parser.parseExpression("-4+(!v.fm?50)")
            .get(), 0.0D);

        // The colon-less form nested inside a real ternary, and the parenthesised chain from `walk`.
        MolangParser second = new MolangParser();
        second.parseExpression("v.hold=1")
            .get();
        second.parseExpression("v.speed=1")
            .get();
        assertEquals(-0.1D, second.parseExpression("v.hold?(v.speed?-0.1:-0.05)")
            .get(), 0.0D);
        second.parseExpression("v.hold=0")
            .get();
        assertEquals(0.0D, second.parseExpression("v.hold?(v.speed?-0.1:-0.05)")
            .get(), 0.0D);
        assertEquals(
            0.2D,
            second.parseExpression("(!v.leftbow&&!v.rightbow?(0.2))")
                .get(),
            0.0D);
        // Comparison binding: `v.attack!=5?2` must be `(attack != 5) ? 2 : 0`.
        assertEquals(2.0D, second.parseExpression("v.attack!=5?2")
            .get(), 0.0D);

        // A well-formed ternary is still claimed by tryTernary, not by the binary operator.
        assertEquals(2.0D, second.parseExpression("1?2:3")
            .get(), 0.0D);
        assertEquals(3.0D, second.parseExpression("0?2:3")
            .get(), 0.0D);
    }

    @Test
    void bracePlaceholdersEvaluateToZeroInsteadOfKillingTheChannel() throws Exception {
        // 艾莲·乔1.4.0 writes these in the position channels of its run and walk animations - 302 expressions in all -
        // and nothing in that pack defines `data3`. '{' is an illegal character for the tokenizer, so before this the
        // whole channel failed to parse and the animation lost the bones it drove: that model walked and ran without
        // the motion those channels carry while all of its numeric channels played normally.
        MolangParser parser = new MolangParser();
        parser.parseExpression("v.hold=1")
            .get();
        parser.parseExpression("v.speed=1")
            .get();
        assertEquals(
            0.24D,
            parser.parseExpression("(!v.hold?0) + (v.hold?(v.speed?0.24:{data3}))")
                .get(),
            1.0E-9D);
        // The placeholder is the neutral value, so the branch that uses it contributes nothing.
        parser.parseExpression("v.speed=0")
            .get();
        assertEquals(
            0.0D,
            parser.parseExpression("(!v.hold?0) + (v.hold?(v.speed?0.24:{data3}))")
                .get(),
            1.0E-9D);
        // Several placeholders in one expression still parse, each contributing the neutral value.
        assertEquals(
            1.0D,
            parser.parseExpression("1+{data0}+{data1}+{data2}")
                .get(),
            1.0E-9D);
    }

    @Test
    void booleanLiteralsBehaveAsOneAndZero() throws Exception {        // AnimationRegister registers them in the client; the raw parser answering 0 for `true` is what made
        // `v.north=true` assign 0.
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("true", 1));
        parser.register(new LazyVariable("false", 0));

        assertEquals(1.0D, parser.parseExpression("true")
            .get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("false")
            .get(), 0.0D);
    }

    @Test
    void ordinaryExpressionsAndAssignmentsStillWork() throws Exception {
        MolangParser parser = new MolangParser();

        assertEquals(3.0D, parser.parseExpression("1+2")
            .get(), 0.0D);
        assertEquals(2.0D, parser.parseExpression("math.max(1,2)")
            .get(), 0.0D);
        assertEquals(3.0D, parser.parseExpression("v.tmp=3")
            .get(), 0.0D);
        assertEquals(6.0D, parser.parseExpression("v.tmp*2")
            .get(), 0.0D);
    }
}
