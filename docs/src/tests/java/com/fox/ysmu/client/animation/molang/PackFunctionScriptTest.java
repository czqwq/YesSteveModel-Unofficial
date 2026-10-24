package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.MathBuilder;
import com.eliotlash.mclib.math.functions.Function;
import com.eliotlash.mclib.math.functions.UserFunctionArguments;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * The parser capabilities the three ported OpenYSM names need - string literals, {@code args[i]} and dynamic
 * function resolution - plus the pack-script statement subset built on top of them.
 * <p>
 * Everything here is engine-side or pure text handling, so no Minecraft object is involved: the functions that
 * actually touch the world ({@code query.is_item_name_any}, {@code ysm.particle}) are covered by the in-game check
 * in the ExecPlan instead.
 */
class PackFunctionScriptTest {

    private final MolangParser parser = new MolangParser();

    @AfterEach
    void clearArgumentFrames() {
        UserFunctionArguments.pop();
    }

    /** Test double so a string argument can be observed without a Minecraft entity. */
    public static class CaptureStringFunction extends Function {

        static String captured;

        public CaptureStringFunction(IValue[] values, String name) throws Exception {
            super(values, name);
        }

        @Override
        public double get() {
            captured = getStringArg(0);
            return getStringArg(1) == null ? 0 : 1;
        }
    }

    @Test
    void stringLiteralsReachAFunctionThroughTheStringPool() throws Exception {
        // The parser replaces a literal with its pool id before parsing, so a function recovers the text from
        // MolangStringPool (the engine's single string mechanism).
        parser.functions.put("capture", CaptureStringFunction.class);
        CaptureStringFunction.captured = null;

        IValue value = parser.parseExpression("capture('minecraft:apple', 'second')");

        assertEquals(1.0D, value.get(), 0.0D);
        assertEquals("minecraft:apple", CaptureStringFunction.captured);
    }

    @Test
    void theEmptyStringIdNeverResolvesToText() throws Exception {
        parser.functions.put("capture", CaptureStringFunction.class);
        CaptureStringFunction.captured = "set";

        parser.parseExpression("capture(0, 0)")
            .get();

        assertNull(CaptureStringFunction.captured);
    }

    @Test
    void argsIndexReadsTheCurrentCallFrame() throws Exception {
        UserFunctionArguments.push(new IValue[] { new Constant(4), new Constant(0) });

        assertEquals(4.0D, parser.parseExpression("args[0]")
            .get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("args[1]")
            .get(), 0.0D);
        // 越界答 0，不会抛
        assertEquals(0.0D, parser.parseExpression("args[7]")
            .get(), 0.0D);
    }

    @Test
    void argsWithoutACallFrameAnswerZero() throws Exception {
        assertEquals(0.0D, parser.parseExpression("args[0]")
            .get(), 0.0D);
    }

    @Test
    void dynamicResolverHandlesNamesWithNoRegisteredClass() throws Exception {
        MathBuilder builder = new MathBuilder();
        builder.setFunctionResolver(
            (name, arguments) -> name.equals("fn.double") ? new Constant(
                arguments.get(0)
                    .get() * 2)
                : null);

        assertEquals(42.0D, builder.parse("fn.double(21)")
            .get(), 0.0D);
        assertThrows(Exception.class, () -> builder.parse("fn.unknown(1)"));
    }

    @Test
    void blockTernaryPicksTheMatchingBranch() {
        PackFunctionScript script = PackFunctionScript
            .parse("t.halo_no=args[0]; t.halo_no==4 ? { return 10; } : { return 20; };");
        // Required before evaluate(): an unknown name would become a single-statement local (see the class javadoc).
        script.registerVariables(parser);

        UserFunctionArguments.push(new IValue[] { new Constant(4) });
        assertEquals(10.0D, script.evaluate(parser), 0.0D);
        UserFunctionArguments.pop();

        UserFunctionArguments.push(new IValue[] { new Constant(1) });
        assertEquals(20.0D, script.evaluate(parser), 0.0D);
    }

    @Test
    void nestedBlocksReturnFromTheOuterScript() {
        // The shape of the Wine Fox pack's halo_battery_indicator.molang
        PackFunctionScript script = PackFunctionScript.parse(
            "t.halo_no=args[0];\n" + "t.halo_no==2 ? {\n"
                + "    args[1]==1 ? { return 7; } : { return 8; };\n"
                + "} : t.halo_no==1 ? {\n"
                + "    return 9;\n"
                + "} : {\n"
                + "    return 0;\n"
                + "};\n");
        script.registerVariables(parser);

        assertEquals(7.0D, evaluateWith(script, 2, 1), 0.0D);
        assertEquals(8.0D, evaluateWith(script, 2, 0), 0.0D);
        assertEquals(9.0D, evaluateWith(script, 1), 0.0D);
        assertEquals(0.0D, evaluateWith(script, 9), 0.0D);
    }

    private double evaluateWith(PackFunctionScript script, double... arguments) {
        IValue[] values = new IValue[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            values[index] = new Constant(arguments[index]);
        }
        UserFunctionArguments.push(values);
        try {
            return script.evaluate(parser);
        } finally {
            UserFunctionArguments.pop();
        }
    }

    @Test
    void commentsAndAssignmentsAreHandled() {
        // `true` is a registered literal in the running client (AnimationRegister); the raw parser needs it too.
        parser.register(new LazyVariable("true", 1));
        PackFunctionScript script = PackFunctionScript
            .parse("// 设置内圈光环指示饱食度\n" + "v.north=true; // 北\n" + "v.north ? { return 1; } : { return 0; };");
        script.registerVariables(parser);

        assertEquals(1.0D, script.evaluate(parser), 0.0D);
    }

    @Test
    void anUnknownNameIsAStatementLocalWithoutPreRegistration() throws Exception {
        // Documents why registerVariables() exists: the engine scopes an unknown assignment to one statement.
        assertEquals(5.0D, parser.parseExpression("t.unknown=5")
            .get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("t.unknown")
            .get(), 0.0D);
    }

    @Test
    void unparsableStatementIsSkippedInsteadOfThrowing() {
        PackFunctionScript script = PackFunctionScript.parse("ctrl.set_animation('not ported'); return 5;");

        assertEquals(5.0D, script.evaluate(parser), 0.0D);
    }
}
