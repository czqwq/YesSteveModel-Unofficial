package software.bernie.geckolib3.molang.variable;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Variable;

public abstract class ContextVariable<TEntity> implements Variable {
    protected boolean validateContext(IContext<?> context) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Object evaluate(ExecutionContext<?> context) {
        Object entity = context.entity();
        if (entity instanceof IContext && validateContext((IContext<?>) entity)) {
            return evaluate((IContext<TEntity>) entity);
        } else {
            // 如果类型不匹配，返回 0 （false）
            return 0;
        }
    }

    public abstract Object evaluate(IContext<TEntity> context);
}
