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
}
