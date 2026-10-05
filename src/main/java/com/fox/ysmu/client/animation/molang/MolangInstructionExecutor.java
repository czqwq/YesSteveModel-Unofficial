package com.fox.ysmu.client.animation.molang;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
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
        if (StringUtils.isBlank(instructions)) {
            return;
        }
        MolangParser parser = GeckoLibCache.getInstance().parser;
        Iterable<String> statements;
        try {
            statements = MolangParser.splitStatements(instructions);
        } catch (MolangException e) {
            warnExecutionFailure(instructions, e);
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
            } catch (Exception e) {
                // 只有真失败了才说"这个函数没注册"：以前是在解析**前**拿字面名去查 parser.functions，
                // 于是被 MolangParser.rewriteVectorFunction 重写掉的向量名（ysm.bone_rot('X').x 这类）
                // 会先被报成"未注册"，紧接着同一条指令又解析、执行成功 —— 一条自相矛盾的日志。
                // 现在这两步都建立在失败事实上：真失败才报告，报告的名字也限于真正缺的那些。
                warnUnregisteredFunctions(parser, trimmed);
                warnExecutionFailure(trimmed, e);
            }
        }
    }

    public static void clearWarnings() {
        WARNED_INSTRUCTIONS.clear();
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

    private static void warnExecutionFailure(String instruction, Exception e) {
        if (WARNED_INSTRUCTIONS.add(instruction)) {
            ysmu.LOG
                .warn("Failed to execute OpenYSM timeline Molang instruction '{}': {}", instruction, e.getMessage());
        }
    }
}
