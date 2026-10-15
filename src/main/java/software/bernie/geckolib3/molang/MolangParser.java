package software.bernie.geckolib3.molang;

import java.util.Map;

import software.bernie.geckolib3.molang.binding.PrimaryBinding;
import software.bernie.geckolib3.molang.parser.ParseException;
import software.bernie.geckolib3.molang.value.FloatValue;
import software.bernie.geckolib3.molang.value.IValue;
import software.bernie.geckolib3.molang.value.MolangValue;

/**
 * Parses a MoLang expression into an evaluatable {@link IValue} and evaluates it against the bindings the host
 * installed.
 * <p>
 * This is the modern 1.20.1 parser. The legacy {@code software.bernie.geckolib3.core.molang.MolangParser} that the
 * rest of the 1.7.10 engine still uses is a different implementation and is kept alongside it.
 */
public class MolangParser {
    private final MolangEngine engine;
    private final PrimaryBinding primaryBinding;

    public MolangParser(Map<String, Object> extraBindings) {
        primaryBinding = new PrimaryBinding(extraBindings);
        engine = MolangEngine.fromCustomBinding(primaryBinding);
    }

    public IValue parseExpression(String molangExpression) {
        return parseExpression(molangExpression, false);
    }

    public IValue parseExpression(String molangExpression, boolean isUserFunc) {
        try {
            return parseExpressionUnsafe(molangExpression, isUserFunc);
        } catch (Exception e) {
            MolangDebug.logParseFailure(molangExpression, e);
            return FloatValue.ZERO;
        }
    }

    public IValue parseExpressionUnsafe(String molangExpression, boolean isUserFunc) throws ParseException {
        MolangValue value = new MolangValue(engine.parse(isUserFunc ? filterComment(molangExpression) : molangExpression), isUserFunc);
        primaryBinding.resetTransient();
        return value;
    }

    // C 风格注释
    private static String filterComment(String exp) {
        StringBuilder result = new StringBuilder(exp.length());
        boolean blockComment = false;
        boolean lineComment = false;
        boolean string = false;
        for (int i = 0; i < exp.length(); i++) {
            char c = exp.charAt(i);
            if (string) {
                if (c == '\'') {
                    string = false;
                }
                result.append(c);
                continue;
            }
            if (lineComment) {
                if (c == '\r' || c == '\n') {
                    lineComment = false;
                    result.append('\n');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && i + 1 < exp.length()) {
                    char next = exp.charAt(i + 1);
                    if (next == '/') {
                        blockComment = false;
                        i++;
                    }
                }
                continue;
            }
            if (c == '\'') {
                string = true;
                result.append('\'');
                continue;
            }
            if (c == '/' && i + 1 < exp.length()) {
                char next = exp.charAt(i + 1);
                if (next == '/') {
                    lineComment = true;
                    i++;
                    continue;
                } else if (next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
            }
            result.append(c);
        }

        return result.toString();
    }

    public IValue getConstant(double value) {
        return new FloatValue((float) value);
    }

    public void reset() {
        primaryBinding.resetScoped();
    }
}
