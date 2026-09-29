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
    void booleanLiteralsBehaveAsOneAndZero() throws Exception {
        // AnimationRegister registers them in the client; the raw parser answering 0 for `true` is what made
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
