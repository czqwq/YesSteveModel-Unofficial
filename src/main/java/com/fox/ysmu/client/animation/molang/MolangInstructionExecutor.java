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
 * A-06①: the 1.7.10 runtime only registers the math functions, {@code ysm.first_order}/{@code ysm.second_order}/
 * {@code ysm.bone_*} (engine side), plus {@code query.position_delta} and {@code ctrl.hold} (host side). OpenYSM's
 * other instructions - {@code ctrl.set_animation}/{@code ctrl.reset}/..., {@code ysm.play_sound}/{@code particle}/...
 * - have no implementation here, and used to surface only as a generic "Failed to execute ..." line after
 * {@code MathBuilder} threw "Function 'x' couldn't be found!". The names are now diagnosed **before** parsing, once
 * per function name, so a pack author can see which instruction was dropped and why.
 *
 * <p>
 * Not part of this: the load-time path. A failing keyframe expression inside an animation file is handled by the
 * engine ({@code JsonAnimationUtils} catches per channel) and by the host's per-file catch in
 * {@code ClientModelManager}, so A-06② is an engine-side fix and is deliberately not repeated here.
 */
public final class MolangInstructionExecutor {

    private static final Set<String> WARNED_INSTRUCTIONS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private MolangInstructionExecutor() {}

    public static void execute(String instructions) {
        if (StringUtils.isBlank(instructions)) {
            return;
        }
        MolangParser parser = GeckoLibCache.getInstance().parser;
        Iterable<String> statements;
        try {
            statements = MolangParser.splitStatements(instructions);
        } catch (MolangException e) {
            warnOnce(instructions, e);
            return;
        }
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            warnUnregisteredFunctions(parser, trimmed);
            try {
                IValue result = parser.parseExpression(trimmed);
                if (result == null) {
                    // 解析结果为 null，跳过，不执行 .get()，避免 NPE
                    continue;
                }
                result.get();
            } catch (Exception e) {
                warnOnce(trimmed, e);
            }
        }
    }

    public static void clearWarnings() {
        WARNED_INSTRUCTIONS.clear();
    }

    /**
     * Reports every function call in {@code statement} that the parser has no entry for, once per function name.
     */
    private static void warnUnregisteredFunctions(MolangParser parser, String statement) {
        for (String name : collectFunctionNames(statement)) {
            if (parser.functions.containsKey(name)) {
                continue;
            }
            if (!WARNED_INSTRUCTIONS.add("function:" + name)) {
                continue;
            }
            ysmu.LOG.warn(
                "OpenYSM Molang function '{}' is not registered in the 1.7.10 runtime, so instruction '{}' is ignored"
                    + " (function not ported; registered functions are math.*, ysm.first_order, ysm.second_order,"
                    + " ysm.bone_*, query.position, query.position_delta and ctrl.hold)",
                name,
                statement);
        }
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

    private static void warnOnce(String instruction, Exception e) {
        if (WARNED_INSTRUCTIONS.add(instruction)) {
            ysmu.LOG
                .warn("Failed to execute OpenYSM timeline Molang instruction '{}': {}", instruction, e.getMessage());
        }
    }
}
