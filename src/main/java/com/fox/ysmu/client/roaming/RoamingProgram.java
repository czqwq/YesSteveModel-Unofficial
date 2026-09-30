package com.fox.ysmu.client.roaming;

import javax.annotation.Nullable;

import com.fox.ysmu.model.roaming.ModelRoamingLimits;

/**
 * Reads the small programs a pack puts in a config form.
 * <p>
 * A form's {@code value} (kept in {@code RawYsmModel.ConfigForm.defaultValue}) is the program that reads and writes the
 * setting, and the labels of a {@code radio} form each carry a program that assigns a value. Upstream evaluates them as
 * MoLang against its runtime; YSMU's roaming values live in {@link ClientRoamingStore} rather than in a live MoLang
 * scope, so the programs are read here and the resulting value is written to the store, which is what makes the
 * setting persist and travel to the server.
 * <p>
 * Only the shapes packs actually use are accepted - a plain variable name, or an assignment of a numeric literal to
 * one. Anything else is reported as unusable so the screen can say so once instead of silently doing nothing. See
 * {@code .agent/phase15-roaming-variables.md}, Milestone 5.
 */
public final class RoamingProgram {

    private RoamingProgram() {}

    /**
     * The roaming variable a program names, or {@code null} when it is not a usable roaming variable.
     * Accepts {@code v.roaming.x}, the same with a trailing semicolon, and an assignment such as
     * {@code v.roaming.x=1;}.
     */
    @Nullable
    public static String variableName(@Nullable String program) {
        String trimmed = trimStatement(program);
        if (trimmed.isEmpty()) {
            return null;
        }
        int assignment = assignmentIndex(trimmed);
        if (assignment > 0) {
            trimmed = trimmed.substring(0, assignment)
                .trim();
        }
        // Anything that still carries an operator is an expression, not a variable name. This is what keeps a
        // comparison such as "v.roaming.x==1" from being read as the name "v.roaming.x".
        if (trimmed.indexOf('=') >= 0 || trimmed.indexOf('<') >= 0 || trimmed.indexOf('>') >= 0
            || trimmed.indexOf('?') >= 0 || trimmed.indexOf('(') >= 0 || trimmed.indexOf('+') >= 0
            || trimmed.indexOf('-') >= 0 || trimmed.indexOf('*') >= 0 || trimmed.indexOf('/') >= 0) {
            return null;
        }
        return ModelRoamingLimits.isAcceptableName(trimmed) ? trimmed : null;
    }

    /**
     * The numeric value a program assigns, or {@code NaN} when the program is not a numeric assignment.
     * {@code v.roaming.cloth=1;} gives 1; {@code v.roaming.a=1-v.roaming.b;} gives {@code NaN}, because evaluating it
     * would need the variables it reads.
     */
    public static double assignedValue(@Nullable String program) {
        String trimmed = trimStatement(program);
        int assignment = assignmentIndex(trimmed);
        if (assignment <= 0 || assignment + 1 >= trimmed.length()) {
            return Double.NaN;
        }
        String literal = trimmed.substring(assignment + 1)
            .trim();
        try {
            return Double.parseDouble(literal);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Index of the {@code =} that assigns, or {@code -1}. The {@code =} of {@code ==}, {@code !=}, {@code <=} and
     * {@code >=} does not assign, and reading it as one would turn a comparison into a variable name.
     */
    private static int assignmentIndex(String expression) {
        for (int i = 0; i < expression.length(); i++) {
            if (expression.charAt(i) != '=') {
                continue;
            }
            boolean partOfComparison = (i > 0 && "=!<>".indexOf(expression.charAt(i - 1)) >= 0)
                || (i + 1 < expression.length() && expression.charAt(i + 1) == '=');
            if (!partOfComparison) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a program is one this class can read a variable name out of. */
    public static boolean isUsable(@Nullable String program) {
        return variableName(program) != null;
    }

    private static String trimStatement(@Nullable String program) {
        if (program == null) {
            return "";
        }
        String trimmed = program.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1)
                .trim();
        }
        return trimmed;
    }
}
