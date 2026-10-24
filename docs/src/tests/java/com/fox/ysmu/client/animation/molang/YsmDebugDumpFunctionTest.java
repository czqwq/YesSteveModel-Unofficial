package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * The five {@code ysm.dump_*} debug functions.
 *
 * <p>wiki (molang/ref) and upstream 1.20 register two of them as functions -
 * {@code dump_equipped_item(slotType)}, {@code dump_relative_block(x,y,z)}
 * ({@code tmp/YesSteveModel-dev-1.20/.../YSMBinding.java:67-68}) - and three as bare **variables** that dump when read
 * ({@code YSMBinding.java:69-71}). The port used to have none of the five, so a pack naming one either killed its
 * whole keyframe expression (the two parenthesised names: {@code Function 'x' couldn't be found!}) or silently read 0
 * (the three bare ones: an unknown variable).</p>
 *
 * <p>What is pinned here: each name is registered in the shape its documented call site uses, each parses, and each
 * answers 0 - which is upstream's answer too (its dump functions return {@code null}, i.e. 0 in this runtime) while
 * animation debug mode is off, the default in a test JVM ({@code Config.DEBUG_ANIMATION == false}, so
 * {@link MolangDebugOutput#isEnabled()} is false and nothing is read from a Minecraft entity or
 * {@code Loader}).</p>
 */
class YsmDebugDumpFunctionTest {

    /** The same installation {@code ClientProxy#init} performs: the registrar hook, then a fresh parser. */
    private static MolangParser parserWithHostFunctions() {
        AnimationRegister.registerMolangHooks();
        return new MolangParser();
    }

    @Test
    void theTwoParenthesisedNamesAreRegisteredFunctions() {
        MolangParser parser = parserWithHostFunctions();
        for (String name : new String[] { "ysm.dump_equipped_item", "ysm.dump_relative_block" }) {
            assertNotNull(parser.functions.get(name), name + " must resolve to a function class");
        }
    }

    @Test
    void parenthesisedCallsParseAndAnswerZeroWhileDebugIsOff() throws Exception {
        MolangParser parser = parserWithHostFunctions();

        assertEquals(
            0.0d,
            parser.parseExpression("ysm.dump_equipped_item('mainhand')")
                .get(),
            0.0d);
        assertEquals(
            0.0d,
            parser.parseExpression("ysm.dump_relative_block(0, 0, 0)")
                .get(),
            0.0d);
        // 0 arguments is allowed by getRequiredArguments() == 0; both names may be written bare-parenthesised.
        assertEquals(
            0.0d,
            parser.parseExpression("ysm.dump_equipped_item()")
                .get(),
            0.0d);
    }

    @Test
    void theThreeBareNamesAreRegisteredAsVariablesNotFunctions() {
        // wiki writes these bare, and upstream registers them with var()/entityVar(); a function-only registration
        // would make `ysm.dump_mods` an unknown variable that silently reads 0 and never dumps.
        MolangParser parser = new MolangParser();
        YsmDebugDumpFunction.registerDumpVariables(parser);

        for (String name : new String[] { "ysm.dump_mods", "ysm.dump_effects", "ysm.dump_biome" }) {
            assertTrue(MolangParser.VARIABLES.containsKey(name), name + " must be registered as a variable");
            assertNotNull(MolangParser.VARIABLES.get(name), name + " must have a live supplier");
            assertEquals(0.0d, MolangParser.VARIABLES.get(name)
                .get(), 0.0d, name + " answers 0 after dumping");
            assertFalse(parser.functions.containsKey(name), name + " must not be a function: wiki writes it bare");
        }
    }

    @Test
    void aBareVariableReadParses() {
        MolangParser parser = parserWithHostFunctions();
        YsmDebugDumpFunction.registerDumpVariables(parser);

        assertDoesNotThrow(
            () -> parser.parseExpression("ysm.dump_mods")
                .get());
        // The shape a timeline really carries: the bare read inside a statement.
        assertDoesNotThrow(
            () -> parser.parseExpression("v.t=ysm.dump_biome;v.t")
                .get());
    }

    /**
     * The diagnostic probe ({@code MolangInstructionExecutor.unregisteredFunctions}) must not report any of the two
     * function names: it runs after a real failure, and a name it lists is a name a reader will go looking for.
     * The three variable names are not candidates at all - the probe only collects identifiers followed by {@code (}.
     */
    @Test
    void theUnregisteredFunctionProbeFindsNoGap() {
        MolangParser parser = parserWithHostFunctions();

        assertTrue(
            MolangInstructionExecutor
                .unregisteredFunctions(parser, "ysm.dump_equipped_item('mainhand')")
                .isEmpty(),
            "ysm.dump_equipped_item must not be reported as missing");
        assertTrue(
            MolangInstructionExecutor
                .unregisteredFunctions(parser, "ysm.dump_relative_block(0, 0, 0)")
                .isEmpty(),
            "ysm.dump_relative_block must not be reported as missing");
        // A variable read has no parentheses, so the probe cannot name it either way - the shape is the guarantee.
        assertTrue(
            MolangInstructionExecutor
                .unregisteredFunctions(parser, "ysm.dump_mods")
                .isEmpty());
    }
}
