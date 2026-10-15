package software.bernie.geckolib3.molang.value;

import software.bernie.geckolib3.molang.parser.ast.Expression;
import software.bernie.geckolib3.molang.runtime.ExpressionEvaluator;

import java.util.List;

public class MolangValue implements IValue {
    private final List<Expression> expressions;
    private final boolean isUserFunc;

    public MolangValue(List<Expression> expressions , boolean isUserFunc) {
        this.expressions = expressions;
        this.isUserFunc = isUserFunc;
    }

    @Override
    public Object evalUnsafe(ExpressionEvaluator<?> evaluator) {
        return evaluator.evalMultiExpressionUnsafe(expressions, isUserFunc);
    }
}
