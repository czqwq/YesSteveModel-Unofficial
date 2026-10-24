package com.fox.ysmu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;

/**
 * Guards the "optional mod absent" half of the Et Futurum Requiem elytra bridge.
 * <p>
 * ETFR is a player-optional mod and is not on the test classpath, which is exactly the case the bridge exists for: it
 * is reached reflectively, so every entry point has to answer its default instead of throwing when the classes are
 * missing. The entity argument is {@code null} on purpose - the bridge must return before it ever dereferences it.
 * <p>
 * The other half (ETFR present, values real) can only be checked in a game with the mod installed.
 */
class EtFuturumCompatTest {

    @Test
    void theBridgeDegradesToDefaultsWhenEtFuturumIsAbsent() {
        assumeFalse(EtFuturumCompat.isLoaded(), "Et Futurum Requiem is installed; this covers its absence");

        assertFalse(EtFuturumCompat.isElytraFlying(null));
        assertNull(EtFuturumCompat.getEquippedElytra(null));
        assertEquals(0.0D, EtFuturumCompat.elytraRotX(null), 0.0D);
        assertEquals(0.0D, EtFuturumCompat.elytraRotY(null), 0.0D);
        assertEquals(0.0D, EtFuturumCompat.elytraRotZ(null), 0.0D);
        // A no-op, not a throw: this runs for every player on every render pass from EtFuturumElytraLayer.
        EtFuturumCompat.renderWings(null, 0F, 0F, 0F, 0F, 0.0625F);
    }
}
