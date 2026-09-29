package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * An OpenYSM pack script ({@code functions/*.molang}) evaluated with the statement subset the 1.7.10 MoLang runtime
 * can express.
 * <p>
 * Pack scripts are controller-style code, not plain expressions: the Wine Fox pack's
 * {@code halo_battery_indicator.molang} is
 *
 *     t.halo_no=args[0];
 *     t.halo_no==4 ? {
 *         return ysm.food_level>10;
 *     } : { return 0; };
 *
 * which needs statement lists, assignments, {@code return} and Bedrock's block ternary
 * {@code cond ? { ... } : cond ? { ... } : { ... }}. This class parses exactly those, plus {@code //} comments, and
 * evaluates the expressions through the engine's parser (so {@code args[i]} and string literals work).
 * <p>
 * What is deliberately <b>not</b> here: the {@code name@hook} binding (OpenYSM runs some scripts as controller /
 * init / update hooks) and the {@code ctrl.*} script API. A script that calls an unported function parses to a
 * failure that is reported once per name and evaluates to 0, instead of taking the model down.
 */
public final class PackFunctionScript {

    private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final String RETURN = "return";

    private final List<Statement> statements;
    private final Set<String> assignedVariables;

    private PackFunctionScript(List<Statement> statements, Set<String> assignedVariables) {
        this.statements = statements;
        this.assignedVariables = assignedVariables;
    }

    /** Parses a script body; returns {@code null} when the body is empty. */
    public static PackFunctionScript parse(String source) {
        if (StringUtils.isBlank(source)) {
            return null;
        }
        List<Statement> statements = parseStatements(stripComments(source));
        return statements.isEmpty() ? null : new PackFunctionScript(statements, collectAssignedVariables(source));
    }

    /**
     * The variables the script assigns to.
     * <p>
     * The engine treats an assignment to a name it does not know as a <b>single-statement</b> local, so
     * {@code t.halo_no=args[0];} followed by {@code t.halo_no==4 ? ...} would read 0 - each statement here is parsed
     * on its own (blocks make a single parse impossible). Registering these names on the parser first makes the
     * assignment land in the shared variable, which is what the script expects; {@code PackUserFunctions} does that
     * when it loads the script.
     */
    public Set<String> assignedVariables() {
        return Collections.unmodifiableSet(this.assignedVariables);
    }

    /**
     * Registers the script's own {@code t.*} / {@code v.*} variables on the given parser; call once before
     * {@link #evaluate}. Names the host already registered (queries, {@code ysm.*}) are left alone.
     */
    public void registerVariables(MolangParser parser) {
        for (String name : this.assignedVariables) {
            String normalized = name.startsWith("variable.") ? "v." + name.substring("variable.".length()) : name;
            if (!normalized.startsWith("t.") && !normalized.startsWith("v.")) {
                continue;
            }
            if (!MolangParser.VARIABLES.containsKey(normalized)) {
                parser.register(new LazyVariable(normalized, 0));
            }
        }
    }

    /** @return the script's return value, or 0 when it runs to the end without returning. */
    public double evaluate(MolangParser parser) {
        Double result = run(this.statements, parser);
        return result == null ? 0 : result;
    }

    private static Double run(List<Statement> statements, MolangParser parser) {
        for (Statement statement : statements) {
            Double value = statement.run(parser);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** One runnable statement; {@code run} returns a value only for a statement that returns. */
    private interface Statement {

        Double run(MolangParser parser);
    }

    private static final class Expression implements Statement {

        private final String text;
        private IValue compiled;
        private boolean failed;

        private Expression(String text) {
            this.text = text;
        }

        /** The compiled expression, or {@code null} when it cannot be parsed (reported once). */
        IValue compile(MolangParser parser) {
            if (this.failed) {
                return null;
            }
            if (this.compiled == null) {
                try {
                    this.compiled = parser.parseExpression(this.text);
                } catch (Exception e) {
                    this.failed = true;
                    warnOnce(this.text, e);
                    return null;
                }
            }
            return this.compiled;
        }

        double compileValue(MolangParser parser) {
            IValue value = compile(parser);
            return value == null ? 0 : value.get();
        }

        @Override
        public Double run(MolangParser parser) {
            compileValue(parser);
            return null;
        }
    }

    private static final class Return implements Statement {

        private final Expression expression;

        private Return(String text) {
            this.expression = new Expression(text);
        }

        @Override
        public Double run(MolangParser parser) {
            return this.expression.compileValue(parser);
        }
    }

    /** {@code cond ? { ... } : cond ? { ... } : { ... }}: the first matching branch runs. */
    private static final class Ternary implements Statement {

        private final List<Branch> branches;

        private Ternary(List<Branch> branches) {
            this.branches = branches;
        }

        @Override
        public Double run(MolangParser parser) {
            for (Branch branch : this.branches) {
                if (branch.condition != null && branch.condition.compileValue(parser) == 0) {
                    continue;
                }
                Double value = PackFunctionScript.run(branch.body, parser);
                if (value != null) {
                    return value;
                }
                if (branch.condition == null) {
                    return null;
                }
            }
            return null;
        }
    }

    private static final class Branch {

        private final Expression condition;
        private final List<Statement> body;

        private Branch(String condition, List<Statement> body) {
            this.condition = condition == null ? null : new Expression(condition);
            this.body = body;
        }
    }

    /** Compiles on first use and reports the first failure per reason. */
    private static void warnOnce(String text, Exception failure) {
        String key = failure.getMessage() == null ? failure.getClass()
            .getName() : failure.getMessage();
        if (!WARNED.add(key)) {
            return;
        }
        ysmu.LOG.warn(
            "OpenYSM pack script statement '{}' cannot be evaluated in the 1.7.10 runtime: {}. "
                + "The statement is skipped; further occurrences of this reason are not logged.",
            text,
            key);
    }

    // ---------------------------------------------------------------- parsing

    private static List<Statement> parseStatements(String source) {
        List<Statement> statements = new ArrayList<>();
        for (String text : splitStatements(source)) {
            Statement statement = parseStatement(text);
            if (statement != null) {
                statements.add(statement);
            }
        }
        return statements;
    }

    private static Statement parseStatement(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isReturn(trimmed)) {
            return new Return(trimmed.substring(RETURN.length()).trim());
        }
        int question = indexOfTopLevel(trimmed, '?');
        if (question >= 0 && startsWithBlock(trimmed, question + 1)) {
            return parseTernary(trimmed);
        }
        return new Expression(trimmed);
    }

    private static boolean isReturn(String text) {
        return text.startsWith(RETURN) && text.length() > RETURN.length()
            && !Character.isLetterOrDigit(text.charAt(RETURN.length()))
            && text.charAt(RETURN.length()) != '_';
    }

    private static boolean startsWithBlock(String text, int from) {
        int index = from;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() && text.charAt(index) == '{';
    }

    private static Statement parseTernary(String text) {
        List<Branch> branches = new ArrayList<>();
        String rest = text.trim();
        while (true) {
            int question = indexOfTopLevel(rest, '?');
            if (question < 0 || !startsWithBlock(rest, question + 1)) {
                warnOnce(text, new IllegalStateException("malformed block ternary"));
                return new Expression(text);
            }
            String condition = rest.substring(0, question)
                .trim();
            int open = skipWhitespace(rest, question + 1);
            int close = matchingBrace(rest, open);
            if (close < 0) {
                warnOnce(text, new IllegalStateException("unbalanced brace"));
                return new Expression(text);
            }
            branches.add(new Branch(condition, parseStatements(rest.substring(open + 1, close))));
            rest = rest.substring(close + 1)
                .trim();
            if (rest.isEmpty()) {
                break;
            }
            if (!rest.startsWith(":")) {
                warnOnce(text, new IllegalStateException("trailing text after a block ternary"));
                break;
            }
            rest = rest.substring(1)
                .trim();
            if (rest.startsWith("{")) {
                int elseClose = matchingBrace(rest, 0);
                if (elseClose < 0) {
                    warnOnce(text, new IllegalStateException("unbalanced brace"));
                    return new Expression(text);
                }
                branches.add(new Branch(null, parseStatements(rest.substring(1, elseClose))));
                break;
            }
        }
        return new Ternary(branches);
    }

    private static int skipWhitespace(String text, int from) {
        int index = from;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /** Index of {@code target} outside strings and outside {@code {}} nesting, or -1. */
    private static int indexOfTopLevel(String text, char target) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == target && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Splits on {@code ;} outside strings and {@code {}} nesting. */
    private static List<String> splitStatements(String source) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == '{') {
                depth++;
                current.append(c);
            } else if (c == '}') {
                depth--;
                current.append(c);
            } else if (c == ';' && depth == 0) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        statements.add(current.toString());
        return statements;
    }

    /** Every {@code name = ...} target in the source, ignoring comparisons and string literals. */
    private static Set<String> collectAssignedVariables(String source) {
        Set<String> names = new java.util.LinkedHashSet<>();
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c != '=') {
                continue;
            }
            char previous = i > 0 ? source.charAt(i - 1) : 0;
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (next == '=' || previous == '=' || previous == '!' || previous == '<' || previous == '>') {
                continue;
            }
            int start = i;
            while (start > 0) {
                char p = source.charAt(start - 1);
                if (Character.isLetterOrDigit(p) || p == '_' || p == '.') {
                    start--;
                } else {
                    break;
                }
            }
            if (start < i) {
                names.add(source.substring(start, i));
            }
        }
        return names;
    }

    /** Removes {@code // ...} comments; quotes are respected. */
    private static String stripComments(String source) {        StringBuilder out = new StringBuilder(source.length());
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
