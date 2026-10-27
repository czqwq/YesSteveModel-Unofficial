package com.fox.ysmu.client.animation.molang;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangAssignment;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.molang.expressions.MolangMultiStatement;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Runs the MoLang statements carried by OpenYSM animation timelines / custom instruction keyframes.
 *
 * <p>
 * A-06①: when this diagnosis was written, the 1.7.10 runtime only registered the math functions,
 * {@code ysm.first_order}/{@code ysm.second_order}/{@code ysm.bone_*} (engine side), plus
 * {@code query.position_delta} and {@code ctrl.hold} (host side); OpenYSM's other instructions -
 * {@code ctrl.set_animation}/{@code ctrl.reset}/..., {@code ysm.play_sound}/{@code particle}/... - had no
 * implementation here and surfaced only as a generic "Failed to execute ..." line after {@code MathBuilder} threw
 * "Function 'x' couldn't be found!". <b>That is no longer the state of the table</b>: the host registers
 * {@code ctrl.*}, {@code query.*} and {@code ysm.*} in {@code AnimationRegister.registerFunctions} (plus the
 * {@code ctrl.set_animation}/{@code set_beginning_transition_length}/{@code reset}/{@code indicate_reload} entries
 * in {@code CtrlScriptBinding.register}), and the engine registers {@code math.*} and the
 * {@code ysm.bone_*}/{@code ysm.first_order}/{@code ysm.second_order}/{@code bone_color|transparency|glow} family in
 * {@code MolangParser.doCoreRemaps}. The names are diagnosed once per function name, <b>after</b> the instruction
 * really failed - a name probed before parsing reports functions the parser supports through a rewrite (the vector
 * bone queries) as missing, which is the false positive this ordering removes. The warning therefore reports only
 * names that are still genuinely absent, and it reads the registered table off the parser instead of repeating a
 * hand-kept copy of it (the copy that used to be there outlived the registrations by several phases).
 *
 * <p>
 * Not part of this: the load-time path. A failing keyframe expression inside an animation file is handled by the
 * engine ({@code JsonAnimationUtils} catches per channel) and by the host's per-file catch in
 * {@code ClientModelManager}, so A-06② is an engine-side fix and is deliberately not repeated here.
 */
public final class MolangInstructionExecutor {

    private static final Set<String> WARNED_INSTRUCTIONS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Controller names already reported as having run a timeline instruction; see {@link #noteTimelineExecution}. */
    private static final Set<String> LOGGED_TIMELINE = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * Timeline instructions already parsed, keyed by the instruction text.
     * <p>
     * This is the port's form of what upstream does at bake time: {@code AnimationProtoMapper.java:77-87} parses each
     * instruction source once into {@code EventKeyFrame<IValue[]>}
     * ({@code parser.parseExpression(sourceFrame.programs().source(), false)}) and the runtime only evaluates it
     * ({@code InstructionKeyFrameExecutor.java:18-35}, {@code value.eval(evaluator)}). Here the same split is made on
     * first execution rather than at load, because the port's instruction keyframes still carry the source string
     * ({@code JsonAnimationUtils.java:402-412}, {@code EventKeyFrame<String>}).
     * <p>
     * Without it every emission re-tokenised and re-parsed the text through mclib: {@code MolangParser.parseExpression}
     * was 10.0 s of a 71 s client-thread profile (14.2%), 99.5% of it reached from {@link #execute(String, String)}, and
     * a 12-tile model-selection page spends ~58% of each preview bake there. Instruction text is immutable per
     * keyframe, so a hit is the normal case and the cost becomes what upstream pays.
     * <p>
     * Only instructions whose every statement parsed are stored: an instruction that fails keeps the original
     * re-parse-and-report path, so a pack whose function table fills in later still starts working, and the failure
     * diagnostics in {@link #warnExecutionFailure} keep counting per occurrence. Dropped by
     * {@link #clearParsedInstructions()} when the models those instructions belong to are dropped.
     */
    private static final Map<String, PreparedInstruction> PREPARED_INSTRUCTIONS = new ConcurrentHashMap<>();

    /**
     * How many distinct parsed instructions are kept before the table is dropped wholesale.
     * <p>
     * Instructions are bounded by what the loaded packs author (the shipped built-in tree declares 95 distinct
     * instruction texts across 1777 timeline keyframes; LaPluma2.5.3 declares 72), so the cap only guards a client
     * that loads many packs without a reload in between.
     */
    private static final int MAX_PREPARED_INSTRUCTIONS = 4096;

    private MolangInstructionExecutor() {}

    /**
     * Diagnostic ({@code DebugController}, one line per controller): this controller's timeline actually fired.
     * <p>
     * This is the line between "the animation is playing" and "the Molang inside its timeline really ran". Without
     * it, whether a timeline dispatched can only be inferred from the variables it wrote - which is what made the
     * projectile timeline (whose only observable effect is a spawned particle) so hard to confirm.
     */
    public static void noteTimelineExecution(String controllerName, String instructions) {
        if (!com.fox.ysmu.Config.DEBUG_CONTROLLER || controllerName == null
            || !LOGGED_TIMELINE.add(controllerName)) {
            return;
        }
        int chars = instructions == null ? 0 : instructions.length();
        ysmu.LOG.info("[YSMU-TL] controller '{}' fired a timeline instruction ({} chars)", controllerName, chars);
    }

    /** Drops the once-per-controller timeline log, for a model cache reload. */
    public static void clearTimelineLog() {
        LOGGED_TIMELINE.clear();
    }

    public static void execute(String instructions) {
        execute(instructions, null);
    }

    /**
     * Executes one timeline instruction string. {@code attribution} is a diagnostic label (controller/animation
     * name) carried into the failure line so the log names WHERE a malformed instruction came from, not just the
     * fragment itself.
     * <p>
     * The statements are parsed once (see {@link #PREPARED_INSTRUCTIONS}) and only evaluated afterwards, which is the
     * shape upstream has after its bake step.
     */
    public static void execute(String instructions, String attribution) {
        if (StringUtils.isBlank(instructions)) {
            return;
        }
        MolangParser parser = GeckoLibCache.getInstance().parser;
        PreparedInstruction prepared = PREPARED_INSTRUCTIONS.get(instructions);
        if (prepared == null) {
            prepared = prepare(parser, instructions);
            if (prepared == null) {
                // Something in this instruction does not parse. Keep the per-call path: it is the one that reports the
                // failure, and it retries, so an instruction rejected only because its function table had not been
                // filled in yet starts working instead of staying broken for the session.
                executeUncached(parser, instructions, attribution);
                return;
            }
            if (PREPARED_INSTRUCTIONS.size() >= MAX_PREPARED_INSTRUCTIONS) {
                PREPARED_INSTRUCTIONS.clear();
            }
            PREPARED_INSTRUCTIONS.put(instructions, prepared);
        }
        executePrepared(prepared, parser, attribution);
    }

    /** Runs an instruction that could not be prepared in full. Unchanged behaviour, kept as the fallback path. */
    private static void executeUncached(MolangParser parser, String instructions, String attribution) {
        Iterable<String> statements;
        try {
            statements = MolangParser.splitStatements(instructions);
        } catch (MolangException e) {
            warnExecutionFailure(instructions, e, attribution);
            return;
        }
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                IValue result = parser.parseExpression(trimmed);
                if (result == null) {
                    // 解析结果为 null，跳过，不执行 .get()，避免 NPE
                    continue;
                }
                result.get();
                noteInstructionExecuted();
                noteAssignmentOwner(result);
            } catch (Exception e) {
                // 只有真失败了才说"这个函数没注册"：以前是在解析**前**拿字面名去查 parser.functions，
                // 于是被 MolangParser.rewriteVectorFunction 重写掉的向量名（ysm.bone_rot('X').x 这类）
                // 会先被报成"未注册"，紧接着同一条指令又解析、执行成功 —— 一条自相矛盾的日志。
                // 现在这两步都建立在失败事实上：真失败才报告，报告的名字也限于真正缺的那些。
                warnUnregisteredFunctions(parser, trimmed);
                warnExecutionFailure(trimmed, e, attribution);
            }
        }
    }

    /**
     * Evaluates an already-parsed instruction: one {@code get()} per statement, no tokenising and no parsing.
     * <p>
     * A failure here is a runtime one (a function that throws, a division by a variable that is not set yet), so it is
     * reported through the same path the uncached form uses, naming the statement text the program was parsed from.
     */
    private static void executePrepared(PreparedInstruction prepared, MolangParser parser, String attribution) {
        for (int i = 0; i < prepared.programs.length; i++) {
            MolangExpression program = prepared.programs[i];
            resetStatementLocals(program);
            try {
                program.get();
                noteInstructionExecuted();
                noteAssignmentOwner(program);
            } catch (Exception e) {
                warnUnregisteredFunctions(parser, prepared.statements[i]);
                warnExecutionFailure(prepared.statements[i], e, attribution);
            }
        }
    }

    /**
     * Clears the per-statement locals a previous execution left behind, so a reused parse starts from the same state
     * a fresh parse did.
     * <p>
     * A statement that assigns a name which is neither {@code v.*} nor an existing global keeps that name in its own
     * {@code MolangMultiStatement.locals} map ({@code MolangParser.parseOneLine}, the non-{@code v.} branch). Parsing
     * per emission gave those a fresh zero-valued {@link LazyVariable} every time; reusing the parse keeps the object,
     * so the value is zeroed here instead. Nothing else in a parsed statement carries state between evaluations.
     */
    private static void resetStatementLocals(MolangExpression program) {
        if (!(program instanceof MolangMultiStatement multi) || multi.locals.isEmpty()) {
            return;
        }
        for (LazyVariable local : multi.locals.values()) {
            local.setLiteral(0.0D);
        }
    }

    /**
     * Parses every statement of one instruction, or returns {@code null} when any of them does not parse.
     * <p>
     * Statically typed against {@link MolangParser#parseExpression(String)} so an instruction may be cached only when
     * the whole thing is understood: a partial parse would silently drop the statements that failed instead of
     * reporting them.
     */
    private static PreparedInstruction prepare(MolangParser parser, String instructions) {
        List<String> statements;
        try {
            statements = MolangParser.splitStatements(instructions);
        } catch (MolangException e) {
            return null;
        }
        java.util.ArrayList<MolangExpression> programs = new java.util.ArrayList<>(statements.size());
        java.util.ArrayList<String> texts = new java.util.ArrayList<>(statements.size());
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            MolangExpression parsed;
            try {
                parsed = parser.parseExpression(trimmed);
            } catch (Exception | LinkageError e) {
                return null;
            }
            if (parsed == null) {
                return null;
            }
            programs.add(parsed);
            texts.add(trimmed);
        }
        return new PreparedInstruction(programs.toArray(new MolangExpression[0]), texts.toArray(new String[0]));
    }

    /** Drops the parsed instructions. Called with {@link #clearWarnings()} when the models owning them are dropped. */
    public static void clearParsedInstructions() {
        PREPARED_INSTRUCTIONS.clear();
    }

    /** One instruction, parsed. See {@link #PREPARED_INSTRUCTIONS}. */
    private static final class PreparedInstruction {

        /** Parsed statements, in author order. */
        private final MolangExpression[] programs;

        /** The text each program was parsed from, so a runtime failure can name the statement that threw. */
        private final String[] statements;

        private PreparedInstruction(MolangExpression[] programs, String[] statements) {
            this.programs = programs;
            this.statements = statements;
        }
    }

    public static void clearWarnings() {
        WARNED_INSTRUCTIONS.clear();
    }

    /**
     * Records which model's timeline last wrote a global {@code v.*} name, so the debug overlay's {@code @model}
     * column can show where a value that outlives its frame came from. This is the one producer for that column in
     * the reference branch ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../MolangInstructionExecutor.java:340}),
     * moved here because this port's executor has no assignment branch of its own - the engine's parser returns a
     * {@code MolangAssignment} whose target is the variable the scope was just written through.
     * <p>
     * Called only after the statement really evaluated, and the engine ignores the call unless the debug feature has
     * asked for captures, so a normal frame pays nothing for it.
     */
    private static void noteAssignmentOwner(IValue result) {
        if (!(result instanceof MolangAssignment)) {
            return;
        }
        software.bernie.geckolib3.core.molang.MolangPhysicsRuntime
            .noteGlobalVarOwner(((MolangAssignment) result).variable.getName());
    }

    /**
     * Reports every function call in {@code statement} that the parser has no entry for, once per function name.
     * <p>
     * Called only after the statement actually failed to evaluate - see {@link #unregisteredFunctions} for why the
     * probe cannot be a plain lookup and must not run before the parse.
     */
    private static void warnUnregisteredFunctions(MolangParser parser, String statement) {
        for (String name : unregisteredFunctions(parser, statement)) {
            if (!WARNED_INSTRUCTIONS.add("function:" + name)) {
                continue;
            }
            ysmu.LOG.warn(
                "OpenYSM Molang function '{}' is not registered in the 1.7.10 runtime; instruction '{}' cannot run"
                    + " (function not ported; registered functions are {})",
                name,
                statement,
                registeredFunctionNames(parser));
        }
    }

    /**
     * The parser's own function table, sorted - the warning's list of what <em>is</em> available.
     * <p>
     * Read from the parser rather than written out here, because the hand-kept list this replaces had gone stale: it
     * still claimed {@code ctrl.set_animation}, {@code ctrl.reset}, {@code ysm.play_sound} and {@code particle} "have
     * no implementation here" long after {@code AnimationRegister.registerFunctions},
     * {@code CtrlScriptBinding.register} and the engine's {@code math.*}/{@code ysm.bone_*} registrations had added
     * them all. A derived list cannot be wrong about the table it is printed beside.
     */
    private static String registeredFunctionNames(MolangParser parser) {
        if (parser == null || parser.functions == null) {
            return "<none>";
        }
        return String.join(", ", new java.util.TreeSet<>(parser.functions.keySet()));
    }

    /**
     * The names {@code statement} calls that this runtime really cannot answer, in call order.
     * <p>
     * Consulted only after the statement failed, and that ordering is the fix for the misleading warning: the old
     * version asked <em>before</em> parsing and compared the literal name against {@code parser.functions}, so
     * {@code ysm.bone_rot('X').x} - which {@code MolangParser.rewriteOpenYsmExpression} rewrites into the registered
     * {@code ysm.bone_rot_x('X')} and then executes successfully - was reported as "not registered ... is ignored"
     * and immediately contradicted by the same instruction running.
     * <p>
     * Two kinds of name are therefore not "missing" even though {@code parser.functions} has no literal key for
     * them:
     * <ul>
     * <li>the unsuffixed vector bone queries, which the rewrite turns into the registered {@code _x}/{@code _y}/
     * {@code _z} names (and which are also registered directly, because a pack may use them in a position with no
     * member access at all) - asked through {@code MolangParser.isVectorFunctionName};</li>
     * <li>a {@code q.}-abbreviated {@code query.*} call, which {@code createFunction} normalises before looking the
     * name up ({@code MolangParser.java:221-223}); the table has no {@code q.} keys, so a literal probe reports every
     * abbreviated query as missing.</li>
     * </ul>
     * Package-visible so the name set can be asserted without a parser runtime behind it.
     */
    static Set<String> unregisteredFunctions(MolangParser parser, String statement) {
        Set<String> missing = new LinkedHashSet<>();
        for (String name : collectFunctionNames(statement)) {
            if (parser.functions.containsKey(name) || MolangParser.isVectorFunctionName(name)) {
                continue;
            }
            if (name.startsWith("q.") && parser.functions.containsKey("query." + name.substring(2))) {
                continue;
            }
            missing.add(name);
        }
        return missing;
    }

    /**
     * Candidate function names: an identifier (letter/underscore, then letters, digits, {@code . _ $ :}) followed
     * directly by {@code '('}. String literals are skipped, and the identifier is lower-cased outside them, the
     * same normalisation {@code MolangParser#parseExpression} applies.
     */
    private static Set<String> collectFunctionNames(String statement) {
        Set<String> names = new LinkedHashSet<>();
        StringBuilder identifier = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < statement.length()) {
                    i++;
                } else if (c == quote) {
                    quoted = false;
                }
                identifier.setLength(0);
                continue;
            }
            if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                identifier.setLength(0);
                continue;
            }
            if (isIdentifierStart(c) && identifier.length() == 0) {
                identifier.append(Character.toLowerCase(c));
                continue;
            }
            if (identifier.length() > 0 && isIdentifierPart(c)) {
                identifier.append(Character.toLowerCase(c));
                continue;
            }
            if (c == '(' && identifier.length() > 0) {
                names.add(identifier.toString());
            }
            identifier.setLength(0);
        }
        return names;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9') || c == '.' || c == '$' || c == ':';
    }

    /**
     * DIAGNOSTIC (temporary): the first failure of each distinct instruction is reported once, which is why the log
     * cannot say whether a failure happens on one frame or on every frame. This additionally counts the repeats and
     * prints one line per second, so "the instruction fails once" and "the instruction fails 60 times a second" are
     * distinguishable - the difference between a startup hiccup and a per-frame defect.
     */
    private static final java.util.Map<String, long[]> FAILURE_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static long failureLogAt;
    private static long executedCount;

    private static void warnExecutionFailure(String instruction, Exception e, String attribution) {
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

    /** DIAGNOSTIC (temporary): how many instructions this client has actually run, for the failure line above. */
    public static void noteInstructionExecuted() {
        executedCount++;
    }
}
