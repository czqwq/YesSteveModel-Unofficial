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
}
