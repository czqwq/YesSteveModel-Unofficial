package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * Dispatches the {@code timeline} instructions of a projectile's active animations - the {@code ysm.particle(...)} a
 * pack writes for a flight trail or an impact splash.
 *
 * <p>A player's timeline is driven by the engine's controller callback every frame
 * ({@code AnimationController.processCurrentAnimation}); a projectile never goes through a controller, so this path
 * had no timeline dispatch at all and a pack's projectile particles never once ran.</p>
 *
 * <p><b>Clock.</b> Each animation's timeline starts when the state that owns it was entered, which is what makes a
 * {@code post_main} / {@code post_ground} instruction at t=0 fire on the entry frame instead of being skipped - the
 * entity's total age passed those 0.2-second clips long ago. A looping animation ({@code parallel1}, say) dispatches
 * on its own period throughout. The clock advances by the <em>entity-age delta</em>, so a second render inside one
 * tick contributes zero and cannot double-dispatch; a large jump after the projectile was out of frame is bounded by
 * {@link TimelineEventScheduler}'s catch-up limit.</p>
 */
public final class ProjectileTimelineRuntime {

    /** Per-frame dispatch ceiling; the same bound the player path uses, so a short-period timeline cannot flood. */
    public static final int MAX_DISPATCHES_PER_FRAME = 1024;

    private static int frameBudget = MAX_DISPATCHES_PER_FRAME;

    /** The scheduler state of one projectile animation. */
    private static final class State {

        TimelineEventScheduler scheduler;
        String programKey = "";
        /** The entity age at the previous frame; NaN means there is no baseline yet. */
        double lastAge = Double.NaN;
        /** Ticks accumulated since the last restart, reported to the scheduler as its position. */
        double elapsed;
        /** The active animation names as one string; a change means the program must be rebuilt. */
        String namesKey = "";
        Program program;
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    /**
     * One-line-per-subject diagnostics, gated on {@code DebugAnimation}. This is the only direct evidence that a
     * model's timeline dispatched at all - a spawned particle leaves no other trace in the log.
     */
    private static final Set<String> LOGGED_PROGRAM = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_FIRST_DISPATCH = ConcurrentHashMap.newKeySet();

    private static final TimelineEventScheduler.Sink SINK = instructions -> {
        if (frameBudget <= 0) {
            return;
        }
        frameBudget--;
        MolangInstructionExecutor.noteTimelineExecution("projectile", instructions);
        MolangInstructionExecutor.execute(instructions);
    };

    private ProjectileTimelineRuntime() {}

    /** Resets the per-frame dispatch budget; called once per render frame by the host. */
    public static void beginRenderFrame() {
        frameBudget = MAX_DISPATCHES_PER_FRAME;
    }

    /** Drops a projectile's state when it leaves the world. */
    public static void forget(int entityId, ResourceLocation animId) {
        STATES.remove(key(entityId, animId));
    }

    /** Drops every projectile's state, for a reload. */
    public static void clear() {
        STATES.clear();
        LOGGED_PROGRAM.clear();
        LOGGED_FIRST_DISPATCH.clear();
    }

    /**
     * Dispatches this frame's timeline instructions.
     *
     * @param entityId the projectile entity id (state isolation)
     * @param animId the projectile's animation id, i.e. the {@code files.projectiles} entry
     * @param file that animation's {@link AnimationFile}
     * @param activeAnims the names active this frame; the order only affects dispatch order
     * @param ageInTicks the entity's age including the partial tick
     */
    public static void dispatch(int entityId, ResourceLocation animId, AnimationFile file, List<String> activeAnims,
        double ageInTicks) {
        if (animId == null || file == null || activeAnims == null || activeAnims.isEmpty()) {
            return;
        }
        String stateKey = key(entityId, animId);
        State state = STATES.computeIfAbsent(stateKey, ignored -> new State());

        String namesKey = String.join(",", activeAnims);
        Program program = namesKey.equals(state.namesKey) && state.program != null
            ? state.program
            : buildProgram(file, activeAnims);
        state.namesKey = namesKey;
        state.program = program;

        if (program.contributors.isEmpty()) {
            state.scheduler = null;
            state.programKey = "";
            state.lastAge = ageInTicks;
            if (Config.DEBUG_ANIMATION && LOGGED_PROGRAM.add(stateKey + "|empty")) {
                ysmu.LOG.info(
                    "[YSMU-PROJ-TL] {} entity={}: no timeline instructions in {}",
                    animId,
                    entityId,
                    activeAnims);
            }
            return;
        }

        // A change in the active set means a state change, so the timeline clock restarts at 0. That restart is
        // exactly what lets a "play once on entry" animation emit its t=0 instruction.
        if (!program.key.equals(state.programKey) || state.scheduler == null) {
            state.scheduler = new TimelineEventScheduler();
            state.scheduler.configure(program.contributors, true);
            state.programKey = program.key;
            state.elapsed = 0.0d;
            state.lastAge = ageInTicks;
        }

        double delta = Double.isNaN(state.lastAge) ? 0.0d : ageInTicks - state.lastAge;
        state.lastAge = ageInTicks;
        if (!(delta > 0.0d)) {
            // A second render inside the same tick: the position has not moved, so nothing is due again.
            delta = 0.0d;
        }
        state.elapsed += delta;
        int dispatched = state.scheduler.advanceFrame(state.elapsed, delta, SINK);

        if (Config.DEBUG_ANIMATION) {
            if (LOGGED_PROGRAM.add(stateKey)) {
                ysmu.LOG.info(
                    "[YSMU-PROJ-TL] {} entity={}: {} contributor(s), {} event(s), active={}",
                    animId,
                    entityId,
                    program.contributors.size(),
                    state.scheduler.getTrackedEventCount(),
                    activeAnims);
            }
            if (dispatched > 0 && LOGGED_FIRST_DISPATCH.add(stateKey)) {
                ysmu.LOG.info(
                    "[YSMU-PROJ-TL] {} entity={}: first dispatch -> {} instruction(s)",
                    animId,
                    entityId,
                    dispatched);
            }
        }
    }

    private static String key(int entityId, ResourceLocation animId) {
        return entityId + "|" + animId;
    }

    /** A built scheduler program plus a content key that changes with the set or the events. */
    private static final class Program {

        final List<TimelineEventScheduler.Contributor> contributors;
        final String key;

        Program(List<TimelineEventScheduler.Contributor> contributors, String key) {
            this.contributors = contributors;
            this.key = key;
        }
    }

    private static Program buildProgram(AnimationFile file, List<String> activeAnims) {
        List<TimelineEventScheduler.Contributor> contributors = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        // The longest period, used as a fallback for an animation whose own period cannot be derived; without it a
        // contributor with period 0 would fire its events exactly once.
        double longest = 0.0d;
        for (String name : activeAnims) {
            Animation animation = file.animations.get(name);
            if (animation == null || animation.customInstructionKeyframes == null
                || animation.customInstructionKeyframes.isEmpty()) {
                continue;
            }
            longest = Math.max(longest, OpenYsmPlayerControllerRuntime.playbackLengthTicks(animation));
        }
        for (String name : activeAnims) {
            Animation animation = file.animations.get(name);
            if (animation == null || animation.customInstructionKeyframes == null
                || animation.customInstructionKeyframes.isEmpty()) {
                continue;
            }
            double period = OpenYsmPlayerControllerRuntime.playbackLengthTicks(animation);
            if (!(period > 0.0d) && longest > 0.0d) {
                period = longest;
            }
            boolean loops = animation.loop == ILoopType.EDefaultLoopTypes.LOOP;
            List<TimelineEventScheduler.Event> events = new ArrayList<>();
            for (EventKeyFrame<String> keyFrame : animation.customInstructionKeyframes) {
                if (keyFrame == null) {
                    continue;
                }
                Double tick = keyFrame.getStartTick();
                events.add(new TimelineEventScheduler.Event(tick == null ? 0.0d : tick, keyFrame.getEventData()));
                key.append(name)
                    .append('@')
                    .append(tick == null ? 0.0d : tick)
                    .append(';');
            }
            key.append(name)
                .append('#')
                .append(period)
                .append('#')
                .append(loops)
                .append('|');
            contributors.add(TimelineEventScheduler.contributor(name, period, loops, events));
        }
        return new Program(contributors, key.toString());
    }
}
