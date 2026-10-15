package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.input.WheelAnimationLock;

/**
 * The extra-animation movement lock: upstream's {@code LOCK_EXTRA_ANIMATION}
 * ({@code tmp/YesSteveModel-dev-1.20/.../client/event/PlayerMoveEvent.java:21,70-76}).
 *
 * <p>What is pinned here is the state machine the wheel's button, the lock key and the move-stop gate all read. The
 * gate itself lives in {@code ClientEventHandler.onKeyboardInput} and the key handling in
 * {@code AnimationRouletteKey.onKeyboardInput}; both need a live client, so their half is a real-client experiment
 * (see this batch's report).</p>
 *
 * <p>The lock must stay a plain client-local flag: nothing here touches the roaming store or any packet, which is the
 * distinction from the reference branch's {@code PENDING_ROAMING["lock_wheel"]} that this port deliberately does not
 * copy. That property is structural (this class imports neither), and the fact that the whole class is loadable in a
 * unit JVM with no LWJGL is the second half of it.</p>
 */
class WheelAnimationLockTest {

    @AfterEach
    void reset() {
        WheelAnimationLock.reset();
    }

    /** Upstream's field default is {@code false} ({@code PlayerMoveEvent.java:21}), so an untouched lock does not block. */
    @Test
    void theLockStartsOff() {
        assertFalse(WheelAnimationLock.isLocked());
    }

    /** {@code switchLock()} toggles, which is what both the button and the key call. */
    @Test
    void switchLockTogglesBothWays() {
        WheelAnimationLock.switchLock();
        assertTrue(WheelAnimationLock.isLocked(), "first switch locks");

        WheelAnimationLock.switchLock();
        assertFalse(WheelAnimationLock.isLocked(), "second switch unlocks");
    }

    /**
     * The port-only session cleanup: the lock belongs to the session that set it, and the port clears every other
     * piece of client runtime state at the same boundary ({@code ClientEventHandler#clearClientRuntimeState}). Upstream
     * never clears it; the divergence is recorded on the class.
     */
    @Test
    void resetClearsALockedStateAndIsIdempotent() {
        WheelAnimationLock.switchLock();
        assertTrue(WheelAnimationLock.isLocked());

        WheelAnimationLock.reset();
        assertFalse(WheelAnimationLock.isLocked());

        WheelAnimationLock.reset();
        assertFalse(WheelAnimationLock.isLocked(), "clearing twice is not a toggle");
    }
}
