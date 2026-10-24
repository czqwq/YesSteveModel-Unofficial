package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TextureRegistrationState} - the pure state machine behind the release mechanism, ported from upstream's own
 * test ({@code tmp/YesSteveModel-dev-1.20/src/test/java/com/elfmcys/ysm/client/texture/TextureRegistrationStateTest.java},
 * all three of its cases) and extended to the branches that test does not reach: an explicit {@code activate} while the
 * slot is already active, release before the platform registration ever happened (DISCARD), the delay counting down to
 * exactly zero, and the idempotence of {@code shouldRelease}/{@code markReleased}.
 *
 * <p>No existing assertion is weakened: upstream's three cases are kept with their names' intent intact.
 */
class TextureRegistrationStateTest {

    @Test
    void replacementBeforeOldCleanupKeepsTheRegisteredIdAndIgnoresCleanup() {
        Object id = new Object();
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(id);
        TextureRegistrationState.Activation old = state.activate(1);
        assertTrue(state.markRegistered(old.token()));

        TextureRegistrationState.Activation replacement = state.activate(1);

        assertSame(id, state.id());
        assertTrue(replacement.registered(), "the platform still holds the texture, so no second registration is due");
        assertEquals(TextureRegistrationState.ReleaseResult.IGNORED, state.release(old.token()));
        assertTrue(state.tickRemoval().isEmpty());
        assertTrue(state.isActive(replacement.token()));
    }

    @Test
    void currentCleanupReleasesOnlyAfterTheConfiguredDelay() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation current = state.activate(1);
        assertTrue(state.markRegistered(current.token()));

        assertEquals(TextureRegistrationState.ReleaseResult.DELAYED, state.release(current.token()));
        assertTrue(state.tickRemoval().isEmpty(), "the delay has not run out yet");
        TextureRegistrationState.Token removal = state.tickRemoval().orElseThrow();
        assertTrue(state.shouldRelease(removal));
        assertTrue(state.markReleased(removal));
        assertFalse(state.shouldRelease(removal), "a second release is refused");
    }

    @Test
    void staleRemovalCannotAffectReplacement() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation registered = state.activate(0);
        assertTrue(state.markRegistered(registered.token()));

        assertEquals(TextureRegistrationState.ReleaseResult.DELAYED, state.release(registered.token()));
        TextureRegistrationState.Token pendingRemoval = state.tickRemoval().orElseThrow();
        TextureRegistrationState.Activation replacement = state.activate(0);

        assertFalse(state.shouldRelease(pendingRemoval));
        assertTrue(replacement.registered());
        assertTrue(state.isActive(replacement.token()));
    }

    /**
     * Release before the platform was ever told about the texture: there is nothing to free later, so the slot is
     * discarded immediately instead of waiting out a delay for a texture that does not exist.
     */
    @Test
    void releaseBeforeRegistrationDiscardsTheSlot() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation activation = state.activate(1);

        assertEquals(TextureRegistrationState.ReleaseResult.DISCARD, state.release(activation.token()));
        assertTrue(state.tickRemoval().isEmpty(), "a discarded slot has nothing to release");
        assertFalse(state.shouldRelease(activation.token()));
    }

    /**
     * The delay counts down one tick per call and fires on the tick the count <b>reaches</b> zero: a delay of 3 means
     * three silent ticks and the fourth returns the removal nonce (upstream's own arithmetic, {@code :59-69}).
     */
    @Test
    void theDelayCountsDownToExactlyZero() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation activation = state.activate(3);
        assertTrue(state.markRegistered(activation.token()));
        assertEquals(TextureRegistrationState.ReleaseResult.DELAYED, state.release(activation.token()));

        for (int tick = 0; tick < 3; tick++) {
            assertTrue(state.tickRemoval().isEmpty(), "tick " + (tick + 1) + " of the 3-tick delay must be silent");
        }
        TextureRegistrationState.Token removal = state.tickRemoval().orElseThrow();

        assertTrue(state.shouldRelease(removal));
    }

    /** While the slot is active there is never anything to remove, however many ticks pass. */
    @Test
    void anActiveSlotNeverBecomesDue() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation activation = state.activate(0);
        TextureRegistrationState.Token token = activation.token();
        assertTrue(state.markRegistered(token));

        assertTrue(state.tickRemoval().isEmpty());
        assertTrue(state.isActive(token));
        assertFalse(state.shouldRelease(token), "an active slot must never be released");
    }

    /**
     * A removal that is already pending is not re-issued: this is what keeps {@code markReleased} idempotent even when
     * the manager queues the same registration twice.
     */
    @Test
    void aQueuedRemovalIsNotIssuedTwice() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation activation = state.activate(0);
        assertTrue(state.markRegistered(activation.token()));
        state.release(activation.token());

        TextureRegistrationState.Token removal = state.tickRemoval().orElseThrow();
        assertTrue(state.tickRemoval().isEmpty(), "the removal is already pending");
        assertTrue(state.markReleased(removal));
        assertTrue(state.tickRemoval().isEmpty(), "a released slot has nothing left to release");
        assertFalse(state.markReleased(removal), "markReleased is idempotent");
    }

    /** A slot the platform never registered cannot be marked registered, and stays discardable. */
    @Test
    void markRegisteredRefusesAStaleOrRepeatedToken() {
        TextureRegistrationState<Object> state = new TextureRegistrationState<>(new Object());
        TextureRegistrationState.Activation first = state.activate(0);
        TextureRegistrationState.Activation stale = state.activate(0);

        assertFalse(state.markRegistered(first.token()), "the token no longer owns the slot");
        assertTrue(state.markRegistered(stale.token()));
        assertFalse(state.markRegistered(stale.token()), "already registered");
    }
}
