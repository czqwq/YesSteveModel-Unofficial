package com.fox.ysmu.client.texture;

import java.util.Optional;

/**
 * One texture slot's registration lifecycle, a faithful port of upstream's
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/client/texture/TextureRegistrationState.java:5-97}
 * (state fields at {@code :6-12}, {@code activate} at {@code :22-29}, {@code release} at {@code :47-57},
 * {@code tickRemoval} at {@code :59-69}, {@code shouldRelease}/{@code markReleased} at {@code :71-83}).
 *
 * <p>A slot is one <em>texture identity</em> (this port keys it by the model's own texture
 * {@code ResourceLocation}; upstream keys it by its synthetic {@code textures/<counter>} id) that can be registered,
 * released and registered again. The state machine is what makes the two halves of the mechanism safe:
 *
 * <ul>
 *   <li><b>{@code activate} hands out a nonce</b> ({@code current}), so a delayed release queued for one registration
 *       can never act on a later one: {@code release(old)} / {@code shouldRelease(old)} answer
 *       {@link ReleaseResult#IGNORED}/{@code false} outside their own activation.</li>
 *   <li><b>{@code DISCARD}</b> is release-before-register: nothing was ever handed to the platform, so there is
 *       nothing to release later and the slot can be forgotten immediately.</li>
 *   <li><b>{@code DELAYED}</b> is release-after-register: the platform still owns a texture, so the physical release
 *       waits {@code delayTicks} ticks (upstream's re-registration grace).</li>
 *   <li><b>{@code releasePending}</b> stops {@code tickRemoval} from allocating a second removal nonce for a slot
 *       whose release is already queued, which is what keeps {@code markReleased} idempotent.</li>
 * </ul>
 *
 * <p>Pure state: no GL, no Minecraft, no clock of its own - the tick count is supplied by the caller.
 */
final class TextureRegistrationState<I> {

    /** The slot's identity, never changed after construction (upstream {@code :14-20}). */
    private final I id;
    /** The activation currently owning the slot, or {@code null} once released and fully reclaimed. */
    private Token current;
    /** Whether the slot is currently registered (an owner is using it). */
    private boolean active;
    /** Whether the platform actually holds a texture for this slot, i.e. whether a physical release is needed. */
    private boolean registered;
    /** A removal nonce is already queued for the current (inactive) activation; see {@link #tickRemoval()}. */
    private boolean releasePending;
    /** Ticks a physical release waits after the slot stops being active (captured per activation). */
    private int delayTicks;
    /** Ticks still to wait; counted down by {@link #tickRemoval()}. */
    private int remainingRemovalTicks;

    TextureRegistrationState(I id) {
        this.id = id;
    }

    I id() {
        return id;
    }

    /**
     * Starts (or restarts) the slot. Resets every per-activation value - including {@code releasePending}, so a
     * re-registration cancels a queued removal - and reports whether the platform already holds a texture, which is
     * what tells the caller it must not register a second time (upstream {@code :22-29}).
     */
    Activation activate(int delayTicks) {
        current = new Token();
        active = true;
        releasePending = false;
        this.delayTicks = delayTicks;
        remainingRemovalTicks = 0;
        return new Activation(current, registered);
    }

    /** Whether {@code token} is the activation that currently owns the slot. */
    boolean isActive(Token token) {
        return active && current == token;
    }

    /**
     * Records that the platform registration for {@code token} succeeded. Refuses a stale or already-registered
     * token, so the caller can detect a lost-ownership race instead of silently double-registering.
     */
    boolean markRegistered(Token token) {
        if (!isActive(token) || registered) {
            return false;
        }
        registered = true;
        return true;
    }

    /** Releases the current activation; see {@link #release(Token)}. */
    ReleaseResult releaseCurrent() {
        return release(current);
    }

    /**
     * Stops the slot being active. A slot the platform never registered is dropped outright ({@link
     * ReleaseResult#DISCARD}); otherwise the physical release is deferred by this activation's delay
     * (upstream {@code :47-57}).
     */
    ReleaseResult release(Token token) {
        if (!isActive(token)) {
            return ReleaseResult.IGNORED;
        }
        active = false;
        if (!registered) {
            return ReleaseResult.DISCARD;
        }
        remainingRemovalTicks = delayTicks;
        return ReleaseResult.DELAYED;
    }

    /**
     * Advances the delay of an inactive, registered slot, one tick per call.
     *
     * @return empty while there is nothing to do (still active, never registered, or a removal is already queued),
     *         empty while the delay is still counting down, and the removal nonce on the tick the count reaches zero
     *         (upstream {@code :59-69}).
     */
    Optional<Token> tickRemoval() {
        if (active || !registered || releasePending) {
            return Optional.empty();
        }
        if (remainingRemovalTicks > 0) {
            remainingRemovalTicks--;
            return Optional.empty();
        }
        releasePending = true;
        return Optional.of(current);
    }

    /**
     * Whether {@code token} may be released physically right now. Allocation-free on purpose - unlike
     * {@code tickRemoval} it can be asked repeatedly with the nonce the caller already holds.
     */
    boolean shouldRelease(Token token) {
        return !active && registered && releasePending && current == token;
    }

    /**
     * Completes the release, making the slot reclaimable.
     *
     * @return whether this call did it; {@code false} for a stale nonce or a second call, which is the idempotence
     *         {@code tick} relies on when one registration is queued twice.
     */
    boolean markReleased(Token token) {
        if (!shouldRelease(token)) {
            return false;
        }
        registered = false;
        releasePending = false;
        current = null;
        return true;
    }

    enum ReleaseResult {
        IGNORED,
        DISCARD,
        DELAYED
    }

    /** What {@link #activate} reports: the activation's nonce plus whether the platform already holds a texture. */
    record Activation(Token token, boolean registered) {
    }

    /**
     * The per-activation nonce. Identity, not value, is the point: it is what makes a stale cleanup event or a stale
     * removal request harmless (upstream {@code :94-97}).
     */
    static final class Token {
        private Token() {
        }
    }
}
