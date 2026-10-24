package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelConfigProgramBindingTest {
    @Test
    void radioReadUsesDeclaredLabelIndexNotAssignedLiteral() {
        assertEquals(1, ModelConfigScreen.radioIndex(0.75d, 3));
        assertEquals(0, ModelConfigScreen.radioIndex(0d, 3));
        assertEquals(-1, ModelConfigScreen.radioIndex(3d, 3));
        assertEquals(-1, ModelConfigScreen.radioIndex(-1d, 3));
        assertEquals(-1, ModelConfigScreen.radioIndex(Double.NaN, 3));
        assertEquals(-1, ModelConfigScreen.radioIndex(Double.POSITIVE_INFINITY, 3));
    }
}
