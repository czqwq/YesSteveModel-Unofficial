package com.fox.ysmu.client.roaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shapes a pack uses for a config form (see {@code .agent/phase15-roaming-variables.md}, Milestone 5). The screen
 * writes the variable these helpers name into {@link ClientRoamingStore}, so a helper that mis-reads a program would
 * silently write a setting the pack never asked for.
 */
class RoamingProgramTest {

    @Test
    void readsAPlainVariableName() {
        assertEquals("v.roaming.player_size", RoamingProgram.variableName("v.roaming.player_size"));
        assertEquals("v.roaming.head_size", RoamingProgram.variableName("  v.roaming.head_size  "));
        assertEquals("v.roaming.backpack", RoamingProgram.variableName("v.roaming.backpack;"));
        assertTrue(RoamingProgram.isUsable("v.roaming.elytra"));
    }

    @Test
    void readsTheTargetOfAnAssignment() {
        // The shape wine_fox uses for its radio labels.
        assertEquals("v.roaming.cloth", RoamingProgram.variableName("v.roaming.cloth=1;"));
        assertEquals("v.roaming.a", RoamingProgram.variableName("v.roaming.a=1-v.roaming.b;"));
    }

    @Test
    void refusesAnythingThatIsNotAVariableName() {
        assertNull(RoamingProgram.variableName(null));
        assertNull(RoamingProgram.variableName(""));
        assertNull(RoamingProgram.variableName("   "));
        assertNull(RoamingProgram.variableName(";;"));
        // A bare name is not a full MoLang name; the stores reject it too.
        assertNull(RoamingProgram.variableName("player_size"));
        assertNull(RoamingProgram.variableName("v.roaming.a+b"));
        assertNull(RoamingProgram.variableName("math.max(1,2)"));
        // A comparison must not be read as the name on its left.
        assertNull(RoamingProgram.variableName("v.roaming.eye==1"));
        assertNull(RoamingProgram.variableName("v.roaming.eye!=1"));
        assertFalse(RoamingProgram.isUsable("v.roaming.eye==1"));
    }

    @Test
    void readsNumericAssignmentsOnly() {
        assertEquals(1d, RoamingProgram.assignedValue("v.roaming.cloth=1;"));
        assertEquals(0d, RoamingProgram.assignedValue("v.roaming.x=0"));
        assertEquals(0.5d, RoamingProgram.assignedValue("v.roaming.x = 0.5 ;"));
        assertEquals(-2d, RoamingProgram.assignedValue("v.roaming.x=-2"));

        // An expression needs the variables it reads, and a comparison is not an assignment.
        assertTrue(Double.isNaN(RoamingProgram.assignedValue("v.roaming.a=1-v.roaming.b;")));
        assertTrue(Double.isNaN(RoamingProgram.assignedValue("v.roaming.eye==1")));
        assertTrue(Double.isNaN(RoamingProgram.assignedValue("v.roaming.x")));
        assertTrue(Double.isNaN(RoamingProgram.assignedValue("v.roaming.x=")));
        assertTrue(Double.isNaN(RoamingProgram.assignedValue(null)));
    }
}
