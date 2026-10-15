package com.fox.ysmu.client.input;

/**
 * The extra-animation "movement lock": while it is on, moving does not stop the extra animation the wheel started.
 *
 * <p>Upstream keeps exactly this state, with exactly these two accessors, as a private static field of the class that
 * also owns the move-stop handler and the key binding:
 * {@code tmp/YesSteveModel-dev-1.20/.../client/event/PlayerMoveEvent.java:21} declares
 * {@code private static boolean LOCK_EXTRA_ANIMATION}, {@code :70-76} expose {@code switchLock()} / {@code isLocked()},
 * {@code :28-29} toggles it from {@code LOCK_ROULETTE_KEY} and {@code :44-46} makes the tick handler return early
 * while it is set. The wheel's button reads the same pair
 * ({@code client/gui/AnimationRouletteScreen.java:177-186} switches the label between
 * {@code gui.yes_steve_model.roulette.lock_on} and {@code ...lock_off} on every draw).</p>
 *
 * <p><b>Why the flag does not live in {@code ClientEventHandler}</b> - the port's move-stop handler is
 * {@code ClientEventHandler.onKeyboardInput} ({@code :528-543}), not a {@code PlayerMoveEvent}, so upstream's
 * "same class" placement has no counterpart here; and that class is shared with another writer in this batch, so the
 * lock's state and the one-line gate that reads it are split on purpose. This class is that state, with upstream's
 * {@code switchLock}/{@code isLocked} names and one port-only addition: {@link #reset()}.</p>
 *
 * <p><b>{@link #reset()} is a recorded divergence.</b> Upstream never clears the flag - it is a plain static that
 * outlives a session. This port clears it through the single session-cleanup entry that already owns every other piece
 * of client runtime state ({@code ClientEventHandler#clearClientRuntimeState}), because the lock is a decision about
 * "the animation I am watching right now" and carrying it into the next server's first animation would be residue
 * from a session that is gone. The port's cleanup entry is documented as the one place for exactly that kind of
 * state.</p>
 *
 * <p><b>Why the state is a class of its own.</b> It is deliberately free of Minecraft and LWJGL references: the unit
 * JVM has no LWJGL ({@code .agent/phase37-unit-jvm-test-repair.md}), so a class whose static initialiser touches
 * {@code org.lwjgl.input.Keyboard} cannot even be loaded there. Keeping the flag here is what makes the lock's
 * semantics testable, and it is why {@link AnimationRouletteKey} only holds the bindings.</p>
 *
 * <p>The lock is <b>client-local</b> and never travels: the reference branch carries it as a
 * {@code PENDING_ROAMING["lock_wheel"]} entry
 * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/WheelPlaybackScope.java:57-60}), i.e. it
 * puts a client-only UI toggle into the roaming variable map whose authority is the server. This port does not: the
 * roaming channel ({@code C2SRoamingChanges} / {@code S2CRoamingState}) stays what the model's settings panel owns,
 * and this class references neither.</p>
 */
public final class WheelAnimationLock {

    /**
     * Upstream's {@code LOCK_EXTRA_ANIMATION} ({@code PlayerMoveEvent.java:21}), default off like upstream's field.
     */
    private static boolean locked;

    private WheelAnimationLock() {}

    /** Upstream {@code PlayerMoveEvent.switchLock()} ({@code :70-72}); the wheel's button and the key both call it. */
    public static void switchLock() {
        locked = !locked;
    }

    /** Upstream {@code PlayerMoveEvent.isLocked()} ({@code :74-76}), read by the wheel button and the move-stop gate. */
    public static boolean isLocked() {
        return locked;
    }

    /** Port-only: clears the lock on session end; see the class javadoc for why upstream does not have this. */
    public static void reset() {
        locked = false;
    }
}
