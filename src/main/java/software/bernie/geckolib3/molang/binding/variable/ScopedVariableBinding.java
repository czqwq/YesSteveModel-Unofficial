package software.bernie.geckolib3.molang.binding.variable;

import software.bernie.geckolib3.molang.binding.ScopedObject;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.util.StringPool;
import software.bernie.geckolib3.molang.runtime.AssignableVariable;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.binding.ObjectBinding;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;

import org.jetbrains.annotations.NotNull;

public class ScopedVariableBinding implements ObjectBinding, ScopedObject {
    private final Int2ReferenceOpenHashMap<ScopedVariable> variableMap = new Int2ReferenceOpenHashMap<>();

    @Override
    public Object getProperty(String name) {
        return variableMap.computeIfAbsent(StringPool.computeIfAbsent(name), ScopedVariable::new);
    }

    public void resetScoped() {
        variableMap.clear();
    }

    private static class ScopedVariable implements AssignableVariable {
        private final int name;

        private ScopedVariable(int name) {
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object evaluate(final @NotNull ExecutionContext<?> context) {
            return ((IContext<Object>) context.entity()).scopedStorage().getScoped(name);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void assign(@NotNull ExecutionContext<?> context, Object value) {
            ((IContext<Object>) context.entity()).scopedStorage().setScoped(name, value);
        }
    }
}
