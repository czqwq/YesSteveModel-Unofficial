package software.bernie.geckolib3.core.molang.expressions;

import java.io.Serializable;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.Operation;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import software.bernie.geckolib3.core.molang.MolangParser;

public abstract class MolangExpression implements IValue, Serializable {

    private static final long serialVersionUID = 42L;

    /**
     * The parser that built this expression.
     * <p>
     * YSMU: {@code transient}. Nothing reads it back - the whole engine only assigns it in this constructor - and a
     * {@code MolangParser} is live parser state (variable tables, registered functions) that must never be serialised
     * into a cache file. Marking it transient is what lets the parsed animation graph round-trip through Java
     * serialisation; see {@code .agent/plan-bake-cache-and-structured-bridge.md}.
     */
    public transient MolangParser context;

    public MolangExpression(MolangParser context) {
        this.context = context;
    }

    public static boolean isZero(MolangExpression expression) {
        return isConstant(expression, 0);
    }

    public static boolean isOne(MolangExpression expression) {
        return isConstant(expression, 1);
    }

    public static boolean isConstant(MolangExpression expression, double x) {
        if (expression instanceof MolangValue value) {
            return value.value instanceof Constant && Operation.equals(value.value.get(), x);
        }
        return false;
    }

    public static boolean isExpressionConstant(MolangExpression expression) {
        if (expression instanceof MolangValue value) {
            return value.value instanceof Constant;
        }
        return false;
    }

    public JsonElement toJson() {
        return new JsonPrimitive(this.toString());
    }
}
