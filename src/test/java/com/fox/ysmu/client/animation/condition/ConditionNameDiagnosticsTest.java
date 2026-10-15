package com.fox.ysmu.client.animation.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the "this name can never be produced on 1.7.10" notes A-11 added.
 * <p>
 * The point of these notes is that the vocabulary check cannot see the difference: {@code trident} is a real kind in
 * {@code InnerClassify.KNOWN_TYPE_KEYWORDS} (it is matched into {@code spear}), so without a note a pack that writes
 * {@code use_mainhand:trident} gets no warning at all while its animation never plays - the silent failure this whole
 * audit keeps finding. The check is offline: the lookup is pure.
 */
class ConditionNameDiagnosticsTest {

    @Test
    void namesWhoseKindCannotBeProducedCarryAReason() {
        assertTrue(ConditionNameDiagnostics.unproducibleKindReason("use_mainhand:trident").contains("spear"));
        assertTrue(ConditionNameDiagnostics.unproducibleKindReason("swing:trident").contains("spear"));
        assertFalse(ConditionNameDiagnostics.unproducibleKindReason("use_mainhand:toot_horn").isEmpty());
        assertFalse(ConditionNameDiagnostics.unproducibleKindReason("hold_mainhand:brush").isEmpty());
        // Case does not matter, because a pack may write the kind either way.
        assertFalse(ConditionNameDiagnostics.unproducibleKindReason("use_offhand:Toot_Horn").isEmpty());
    }

    @Test
    void kindsThePortDoesAnswerCarryNoReason() {
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason("use_mainhand:spear"));
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason("hold_mainhand:spyglass"));
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason("swing:sword"));
        // Not a condition name at all, and no name at all.
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason("idle"));
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason(null));
    }

    /**
     * The two names every model logs - the built-in default model ships both clips - are produced by the port, just
     * not by the kind vocabulary: upstream answers them from its hand predicate
     * ({@code client/animation/predicate/MainhandPredicate.java:44-46}, {@code OffhandPredicate.java:31-33}) and this
     * port does the same from {@code AnimationManager#predicateMainhandHold} / {@code #predicateOffhandHold} through
     * {@code TinkersCrossbowCompat.isCrossbowLoaded} (a TiCon crossbow with its {@code InfiTool.Loaded} NBT flag set,
     * which is 1.7.10's crossbow). A predicate is a producer, so the name classifies and is not reported - exactly
     * like {@code hold_mainhand:fishing}, the other hand-predicate name.
     */
    @Test
    void handPredicateNamesClassifyEvenThoughTheVocabularyCannotProduceThem() {
        assertTrue(ConditionNameDiagnostics.isClassifiable("hold_mainhand:charged_crossbow"));
        assertTrue(ConditionNameDiagnostics.isClassifiable("hold_offhand:charged_crossbow"));
        assertTrue(ConditionNameDiagnostics.isClassifiable("hold_mainhand:fishing"));
        assertTrue(ConditionNameDiagnostics.isClassifiable("hold_mainhand:empty"));
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason("hold_mainhand:charged_crossbow"));
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason("hold_offhand:charged_crossbow"));
        // The route is the `hold_*:` form only; a `use_*`/`swing:` name with that suffix has no producer.
        assertFalse(ConditionNameDiagnostics.isClassifiable("use_mainhand:charged_crossbow"));
        assertFalse(ConditionNameDiagnostics.isClassifiable("swing:charged_crossbow"));
    }

    /**
     * The other names a reference pack declares and the audit found in the log are {@code <action>:<kind>_<variant>}
     * forms: {@code swing:sword_up} / {@code _down} / {@code _jump} / {@code swordNormal},
     * {@code hold_mainhand:bow_Yilumi} and {@code use_mainhand:bow_Default} / {@code bow_Yilumi} / {@code bow_yilumi2}.
     *
     * <p>
     * Neither implementation selects them. Upstream builds every {@code :<kind>} condition name as
     * {@code extraPre + getClassify(item)} ({@code ConditionalHold.java:118-121}, {@code ConditionalUse.java:115-118},
     * {@code ConditionalSwing.java:115-118}), and {@code getClassify} returns one of a fixed set of kind words
     * ({@code InnerClassify.java:22-66}) - never {@code bow_Yilumi} or {@code sword_up}. So the diagnostic is telling
     * the truth, and this test pins that: teaching the vocabulary a {@code <kind>_<suffix>} rule would invent a
     * mechanism upstream does not have, which is not how a name starts playing. (The three {@code swing:sword_*}
     * clips do play on that pack - its own {@code player.parallel_5} controller state names them, a route this class
     * deliberately does not claim to see.)
     */
    @Test
    void variantSuffixedActionNamesHaveNoClassifierRouteInEitherImplementation() {
        String[] names = { "hold_mainhand:bow_Yilumi", "use_mainhand:bow_Default", "use_mainhand:bow_Yilumi",
            "use_mainhand:bow_yilumi2", "swing:sword_up", "swing:sword_down", "swing:sword_jump",
            "swing:swordNormal" };
        for (String name : names) {
            assertFalse(ConditionNameDiagnostics.isClassifiable(name), name + " has no condition-classifier route");
            // None of these is a 1.20 kind either, so the generic reason is the right one - not one of the notes.
            assertEquals("", ConditionNameDiagnostics.unproducibleKindReason(name), name);
        }
    }

    /**
     * The same family on the built-in packs: {@code wine_fox/03_astronaut} declares {@code hold_mainhand:spear_idle},
     * {@code hold_offhand:sword_idle}, {@code hold_mainhand:shield_end} and {@code swing:fishing_rod2} among others,
     * and nothing references them - not a classifier (the suffix is not a kind upstream can build) and not a
     * controller state either. So they are warned about, and the message is the generic one.
     */
    @Test
    void builtInPackVariantNamesHaveNoRouteAndCarryNoNote() {
        String[] names = { "hold_mainhand:spear_idle", "hold_mainhand:spear_not_idle", "hold_offhand:sword_idle",
            "hold_offhand:sword_not_idle", "hold_mainhand:sword_end", "hold_mainhand:shield_end",
            "hold_offhand:shield_end", "swing:fishing_rod2" };
        for (String name : names) {
            assertFalse(ConditionNameDiagnostics.isClassifiable(name), name);
            assertEquals("", ConditionNameDiagnostics.unproducibleKindReason(name), name);
            assertTrue(ConditionNameDiagnostics.needsWarning(name, true), name + " is still reported");
        }
    }

    /**
     * {@code lance} is the one suffix in the log that the port's own shipped content uses and that <em>neither</em>
     * implementation can produce. Upstream's {@code getClassify} returns a fixed set of kind words
     * ({@code InnerClassify.java:22-66}) and never {@code lance}, and 1.20.1's {@code UseAnim} has no such value, so
     * there is no mapping to port - the clips are shipped by the built-in default model's arm animations
     * ({@code builtin/default/animations/arm.animation.json}: {@code hold_mainhand:lance}, {@code hold_offhand:lance},
     * {@code swing:lance}, {@code use_mainhand:lance}, {@code use_offhand:lance}) and every model inherits them
     * through the default-animation fallback.
     *
     * <p>
     * The reason this carries a note instead of the generic message is that the reader has to be able to tell "the
     * port is missing a mapping" from "the shipped asset declares a clip nothing can select". It is the second, and
     * adding a {@code lance} kind would be the invented vocabulary rule this repository forbids. The asset even shows
     * what the author meant: {@code hold_mainhand:lance}, {@code hold_offhand:lance} and {@code swing:lance} are
     * byte-identical to their {@code spear} counterparts in that file, so {@code lance} is that pack's second name
     * for the polearm pose - reachable for a trident-like item through {@code :spear}, never through {@code lance}.
     */
    @Test
    void lanceIsProducedByNeitherImplementationAndSaysSo() {
        String[] names = { "hold_mainhand:lance", "hold_offhand:lance", "swing:lance", "use_mainhand:lance",
            "use_offhand:lance" };
        for (String name : names) {
            assertFalse(ConditionNameDiagnostics.isClassifiable(name), name);
            String reason = ConditionNameDiagnostics.unproducibleKindReason(name);
            assertFalse(reason.isEmpty(), name + " must carry the `no producer in either implementation` note");
            assertTrue(reason.contains("getClassify"), name);
            assertTrue(reason.contains("default"), name + " must name the asset that ships the clip");
        }
        // Case does not matter, because a pack may write the kind either way.
        assertFalse(ConditionNameDiagnostics.unproducibleKindReason("use_offhand:Lance").isEmpty());
        // It is not a kind this port answers, so no vocabulary rule was added for it.
        assertFalse(InnerClassify.isKnownTypeKeyword("lance"));
    }

    /**
     * A name a model merely inherits from the built-in default is reported once, against the model that declares it,
     * not once per inheriting model - which is what turned the five {@code lance} clips into 100+ warnings across the
     * wine_fox pack. The declaring model still reports it, so nothing is silenced.
     */
    @Test
    void inheritedDefaultNamesAreReportedAgainstTheDeclaringModelOnly() {
        assertFalse(
            ConditionNameDiagnostics.needsWarning("hold_mainhand:lance", false),
            "an inherited default clip is not the inheriting model's to report");
        assertTrue(
            ConditionNameDiagnostics.needsWarning("hold_mainhand:lance", true),
            "the built-in default model declares it, so it is still reported");
        // The declaration flag must not silence a name the model's own files declare, including the ones that play
        // from a controller state.
        assertTrue(ConditionNameDiagnostics.needsWarning("swing:sword_up", true));
        assertTrue(ConditionNameDiagnostics.needsWarning("hold_mainhand:spear_idle", true));
        // ... nor may it start reporting names that are fine, or non-condition names.
        assertFalse(ConditionNameDiagnostics.needsWarning("hold_mainhand:sword", true));
        assertFalse(ConditionNameDiagnostics.needsWarning("hold_mainhand:charged_crossbow", true));
        assertFalse(ConditionNameDiagnostics.needsWarning("idle", true));
        assertFalse(ConditionNameDiagnostics.needsWarning(null, true));
    }

    /** A suffix nothing in either implementation knows still warns - the guard against over-silencing. */
    @Test
    void aTrulyUnknownSuffixStillWarns() {
        String name = "hold_mainhand:definitely_not_a_kind";
        assertFalse(ConditionNameDiagnostics.isClassifiable(name));
        assertEquals("", ConditionNameDiagnostics.unproducibleKindReason(name));
        assertTrue(ConditionNameDiagnostics.needsWarning(name, true));
    }
}
