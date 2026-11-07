package com.fox.ysmu.client.animation.molang;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.molang.expressions.MolangAssignment;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.molang.expressions.MolangMultiStatement;

/**
 * Evaluates the MoLang programs of the timeline / custom-instruction keyframes an animation controller dispatches.
 *
 * <p>
 * The programs arrive parsed. Upstream's bake step turns every instruction keyframe's source into
 * {@code EventKeyFrame<IValue[]>} ({@code AnimationProtoMapper.java:77-87}) and its runtime only evaluates them
 * ({@code InstructionKeyFrameExecutor.java:18-35}: {@code for (IValue value : values) value.eval(evaluator);}); this
 * port does the same with {@code Animation#instructionKeyframes(MolangParser)}
 * and this class is the host's counterpart of that evaluator loop.
 *
 * <p>
 * It used to carry the source text instead and re-tokenised/re-parsed it on every dispatch, with a text-keyed cache
 * that could not cover an instruction whose statements did not all parse. A spark profile of the client thread
 * (168 stuttering ticks, 65.6 s sampled) put that fallback path at 10.1 s - 15.4 % of the window - inside
 * {@code MolangParser.parseExpression} alone, with the mclib tokenizer ({@code isOperator},
 * {@code breakdownChars}, regex {@code String.matches}/{@code String.split}) as its self time. With the parse moved to
 * the animation's first use, a normal frame evaluates the graph and allocates nothing.
 *
 * <p>
 * <b>Runtime failures still go through this class.</b> A program that parsed can still throw while evaluating (a
 * division by a yet-unset variable, a function that rejects its argument). Those are reported here, once per distinct
 * instruction, with the per-second counter that keeps "failed once at startup" distinguishable from "fails every
 * frame"; they are <em>not</em> parse problems (a parse failure already became a constant 0 on the animation, reported
 * once by {@code MolangParser#reportParseFailure}).
 */
public final class MolangInstructionExecutor {

    private static final Set<String> WARNED_INSTRUCTIONS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Controller names already reported as having run a timeline instruction; see {@link #noteTimelineExecution}. */
    private static final Set<String> LOGGED_TIMELINE = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private MolangInstructionExecutor() {}

    /**
     * Diagnostic ({@code DebugController}, one line per controller): this controller's timeline actually fired.
     * <p>
     * This is the line between "the animation is playing" and "the MoLang inside its timeline really ran". Without
     * it, whether a timeline dispatched can only be inferred from the variables it wrote - which is what made the
     * projectile timeline (whose only observable effect is a spawned particle) so hard to confirm.
     */
    public static void noteTimelineExecution(String controllerName, MolangExpression program) {
        if (!com.fox.ysmu.Config.DEBUG_CONTROLLER || controllerName == null
            || !LOGGED_TIMELINE.add(controllerName)) {
            return;
        }
        int chars = program == null ? 0 : program.toString()
            .length();
        ysmu.LOG.info("[YSMU-TL] controller '{}' fired a timeline instruction ({} chars)", controllerName, chars);
    }

    /** Drops the once-per-controller timeline log, for a model cache reload. */
    public static void clearTimelineLog() {
        LOGGED_TIMELINE.clear();
    }

    /**
     * Evaluates one timeline entry's parsed program.
     *
     * <p>
     * {@code attribution} is a diagnostic label (controller/animation name) carried into the failure line so the log
     * names WHERE a malformed instruction came from. Locals of the parsed program are zeroed first: a statement that
     * assigns a bare name (neither {@code v.*} nor an already-registered global) keeps that name in the program's
     * {@code MolangMultiStatement.locals}, and a reused parse must start that name at 0 on every execution, exactly
     * as the per-dispatch parse it replaces did.
     */
    public static void execute(MolangExpression program, String attribution) {
        if (program == null) {
            return;
        }
        resetStatementLocals(program);
        try {
            program.get();
            noteInstructionExecuted();
            noteAssignmentOwners(program);
        } catch (Exception e) {
            warnExecutionFailure(program, e, attribution);
        }
    }

    /**
     * Clears the statement-local variables a previous execution left behind, so a reused parse starts from the same
     * state a fresh parse did.
     *
     * <p>
     * A statement that assigns a name which is neither {@code v.*} nor an existing global keeps that name in its
     * {@code MolangMultiStatement.locals} map ({@code MolangParser.parseOneLine}, the non-{@code v.} branch). Parsing
     * per emission gave those a fresh zero-valued {@link LazyVariable} every time; reusing the parse keeps the object,
     * so the value is zeroed here instead.
     */
    private static void resetStatementLocals(MolangExpression program) {
        if (program instanceof MolangMultiStatement multi) {
            zeroLocals(multi);
        }
    }

    private static void zeroLocals(MolangMultiStatement multi) {
        if (!multi.locals.isEmpty()) {
            for (LazyVariable local : multi.locals.values()) {
                local.setLiteral(0.0D);
            }
        }
    }

    /**
     * Records which model's timeline last wrote a global {@code v.*} name, so the debug overlay's {@code @model}
     * column can show where a value that outlives its frame came from. This is the one producer for that column in
     * the reference branch ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../MolangInstructionExecutor.java:340}).
     * <p>
     * Called only after the program really evaluated, and the engine ignores the call unless the debug feature has
     * asked for captures, so a normal frame pays nothing for it. A timeline entry is one multi-statement program, so
     * its top-level statements are walked for assignments - the same set the per-statement executor used to report.
     */
    private static void noteAssignmentOwners(MolangExpression program) {
        if (program instanceof MolangMultiStatement multi) {
            for (MolangExpression expression : multi.expressions) {
                noteAssignmentOwner(expression);
            }
            return;
        }
        noteAssignmentOwner(program);
    }

    private static void noteAssignmentOwner(MolangExpression program) {
        if (!(program instanceof MolangAssignment assignment)) {
            return;
        }
        MolangPhysicsRuntime.noteGlobalVarOwner(assignment.variable.getName());
    }

    public static void clearWarnings() {
        WARNED_INSTRUCTIONS.clear();
        FAILURE_COUNTS.clear();
        FAILURE_KEYS.clear();
    }

    /**
     * DIAGNOSTIC (temporary): the first failure of each distinct instruction is reported once, which is why the log
     * cannot say whether a failure happens on one frame or on every frame. This additionally counts the repeats and
     * prints one line per second, so "the instruction fails once" and "the instruction fails 60 times a second" are
     * distinguishable - the difference between a startup hiccup and a per-frame defect.
     */
    private static final java.util.Map<String, long[]> FAILURE_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * The instruction text used as a {@link #FAILURE_COUNTS} key, computed once per program.
     * <p>
     * A runtime failure can repeat every frame, so building the text with {@link MolangExpression#toString()} on each
     * one would replace one per-frame cost with another. Keyed by identity, which is what the parsed program is.
     */
    private static final java.util.Map<MolangExpression, String> FAILURE_KEYS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static long failureLogAt;
    private static long executedCount;

    private static void warnExecutionFailure(MolangExpression program, Exception e, String attribution) {
        String instruction = key(program);
        long[] counter = FAILURE_COUNTS.computeIfAbsent(instruction, ignored -> new long[1]);
        counter[0]++;
        if (WARNED_INSTRUCTIONS.add(instruction)) {
            ysmu.LOG
                .warn("Failed to execute OpenYSM timeline Molang instruction '{}' from [{}]: {}", instruction,
                    attribution == null ? "?" : attribution, e.getMessage());
        }
        long now = System.currentTimeMillis();
        if (now - failureLogAt >= 1000L) {
            failureLogAt = now;
            StringBuilder line = new StringBuilder();
            for (java.util.Map.Entry<String, long[]> entry : FAILURE_COUNTS.entrySet()) {
                line.append('[')
                    .append(entry.getValue()[0])
                    .append("x ")
                    .append(entry.getKey())
                    .append("] ");
            }
            ysmu.LOG.warn(
                "timeline instruction failures so far (executed={} distinct={}): {}",
                executedCount,
                FAILURE_COUNTS.size(),
                line.toString()
                    .trim());
        }
    }

    /** The instruction text used to deduplicate failures; a parsed program prints its own source. */
    private static String key(MolangExpression program) {
        return FAILURE_KEYS.computeIfAbsent(program, parsed -> {
            String text = parsed.toString();
            return text.length() > 512 ? text.substring(0, 512) + "..." : text;
        });
    }

    /** DIAGNOSTIC (temporary): how many instructions this client has actually run, for the failure line above. */
    public static void noteInstructionExecuted() {
        executedCount++;
    }
}
