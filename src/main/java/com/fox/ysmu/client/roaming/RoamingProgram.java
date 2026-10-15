package com.fox.ysmu.client.roaming;

import javax.annotation.Nullable;

import com.fox.ysmu.model.roaming.ModelRoamingLimits;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.Variable;
import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangAssignment;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.molang.expressions.MolangMultiStatement;

/**
 * Compiles a config form's read and action programs with the actual animation-file MoLang parser.
 * <p>
 * The compiled program evaluates only when its owner has established a real entity/model execution scope. Roaming
 * writes still travel through {@link ClientRoamingStore} to the existing server authority. The legacy name/literal
 * helpers remain for compatibility; they are not a substitute for executing a complete form program.
 */
public final class RoamingProgram {

    private RoamingProgram() {}

    /**
     * Compile the complete program, just as upstream AnimationRouletteScreen.java:524-530 does. Evaluation must
     * happen in the owning entity's live MoLang scope, not against a replacement variable map.
     */
    public static Compiled compile(MolangParser parser, String source) throws MolangException {
        return new Compiled(source, parser.parseExpression(source));
    }

    public static final class Compiled {
        private final String source;
        private final MolangExpression expression;

        private Compiled(String source, MolangExpression expression) {
            this.source = source;
            this.expression = expression;
        }

        public String source() {
            return this.source;
        }

        public double evaluate() {
            return this.expression.get();
        }

        /**
         * Only unambiguously side-effect-free RHS values qualify for the local roaming channel. Unknown ASTs and
         * functions go through the controlled expression broadcast too; never hide their effects as roaming-only.
         */
        public boolean hasOnlyRoamingAssignments() {
            return onlyRoamingAssignments(this.expression);
        }
    }

    private static boolean onlyRoamingAssignments(MolangExpression expression) {
        if (expression instanceof MolangMultiStatement statements) {
            if (statements.expressions.isEmpty()) {
                return false;
            }
            for (MolangExpression statement : statements.expressions) {
                if (!onlyRoamingAssignments(statement)) {
                    return false;
                }
            }
            return true;
        }
        if (expression instanceof MolangAssignment assignment) {
            String target = MolangParser.canonicalVariableName(assignment.variable.getName());
            // The shared store's admission rule also accepts other v.* values (ModelRoamingLimits.java:39-40).
            // Admission is not proof of roaming membership: upstream's local-only gate names v.roaming explicitly
            // (CustomMolangParser.java:19,75-76). Never suppress a non-roaming assignment's expression broadcast.
            return target.startsWith(ModelRoamingLimits.PREFIX)
                && target.length() > ModelRoamingLimits.PREFIX.length()
                && ModelRoamingLimits.isAcceptableName(target)
                && (assignment.expression instanceof Constant || assignment.expression instanceof Variable);
        }
        return false;
    }

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
