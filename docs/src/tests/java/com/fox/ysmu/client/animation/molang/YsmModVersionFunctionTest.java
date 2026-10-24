package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.mod_version(modid)} - the shape decision behind its return value.
 *
 * <p>wiki (molang/ref): "返回客户端安装的指定模组的版本", i.e. a **string**; upstream returns the mod's Maven version
 * string and {@code null} when the mod is absent ({@code functions/ModVersion.java:14-23}, registered at
 * {@code YSMBinding.java:73} with {@code validateArgumentSize == 1}). This engine's
 * {@code com.eliotlash.mclib.math.functions.Function.get()} is a bare {@code double}, so the string is returned the
 * way this port already returns the block name from {@code ysm.relative_block_name}: as a
 * {@link MolangStringPool} id ({@code YsmRelativeBlockNameFunction.java:50}). The reference branch instead
 * registered a constant-0 stub, which would make the one documented use - a version comparison - forever false.</p>
 *
 * <p>What is pinned here: the pure version-string-to-id mapping (the part a unit JVM can host), that a pooled string
 * literal in a pack expression lands on the same id, the upstream arity, and registration. The FML lookup itself
 * ({@code Loader.instance().getIndexedModList().get(modId).getVersion()}) only answers after FML has started and is
 * exercised in game; asking for an unknown mod is pinned to 0 - the "mod not found" answer upstream gives as
 * {@code null}.</p>
 */
class YsmModVersionFunctionTest {

    private static MolangParser parserWithHostFunctions() {
        AnimationRegister.registerMolangHooks();
        return new MolangParser();
    }

    @Test
    void aVersionStringBecomesThePooledStringId() {
        assertEquals((double) MolangStringPool.intern("2.6.5"), YsmModVersionFunction.versionId("2.6.5"), 0.0d);
        // Surrounding whitespace is not part of the version.
        assertEquals((double) MolangStringPool.intern("2.6.5"), YsmModVersionFunction.versionId("  2.6.5  "), 0.0d);
        assertTrue(
            MolangStringPool.isStringId((int) YsmModVersionFunction.versionId("2.6.5")),
            "the return value must be in the pooled-string range, not a version number");
    }

    @Test
    void anUnknownOrEmptyVersionIsZero() {
        // Upstream answers null for an unknown mod (ModVersion.java:20-23); 0 is this runtime's "no string".
        assertEquals(0.0d, YsmModVersionFunction.versionId(null), 0.0d);
        assertEquals(0.0d, YsmModVersionFunction.versionId(""), 0.0d);
        assertEquals(0.0d, YsmModVersionFunction.versionId("   "), 0.0d);
        assertEquals(0.0d, YsmModVersionFunction.versionId(YsmModVersionFunction.modVersion(null)), 0.0d);
        assertEquals(0.0d, YsmModVersionFunction.versionId(YsmModVersionFunction.modVersion("")), 0.0d);
        // A blank modId never reaches Loader (upstream's getAsString would be null there too).
        assertNull(YsmModVersionFunction.modVersion("  "));
    }

    /**
     * The decisive evidence for the pool-id choice: this engine pools a pack's string literals at parse time
     * ({@code MolangParser.java:963}), so {@code ysm.mod_version('ysm')=='2.6.5'} compares two ids - and it can only
     * be true if the function also returns an id, never if it returns a constant 0.
     */
    @Test
    void aPackVersionComparisonLandsOnOneId() throws Exception {
        MolangParser parser = parserWithHostFunctions();

        double literal = parser.parseExpression("'2.6.5'")
            .get();
        assertEquals(
            (double) MolangStringPool.intern("2.6.5"),
            literal,
            0.0d,
            "a string literal must be pooled at parse time");
        assertEquals(
            literal,
            YsmModVersionFunction.versionId("2.6.5"),
            0.0d,
            "the function and the pack's literal must be the same pooled id for the comparison to hold");
    }

    @Test
    void registeredAsAFunctionWithUpstreamsArity() throws Exception {
        MolangParser parser = parserWithHostFunctions();
        assertNotNull(parser.functions.get("ysm.mod_version"), "ysm.mod_version must resolve to a function class");

        // Upstream's validateArgumentSize is 1 (ModVersion.java:26-28).
        assertDoesNotThrow(() -> parser.parseExpression("ysm.mod_version('ysm')"));
        assertThrows(MolangException.class, () -> parser.parseExpression("ysm.mod_version()"));
    }

    @Test
    void theUnregisteredFunctionProbeFindsNoGap() {
        MolangParser parser = parserWithHostFunctions();

        assertTrue(
            MolangInstructionExecutor.unregisteredFunctions(parser, "ysm.mod_version('ysm')")
                .isEmpty(),
            "ysm.mod_version must not be reported as missing");
    }
}
