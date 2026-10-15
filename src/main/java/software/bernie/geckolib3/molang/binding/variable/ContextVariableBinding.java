package software.bernie.geckolib3.molang.binding.variable;

import software.bernie.geckolib3.molang.binding.ScopedObject;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.util.StringPool;
import software.bernie.geckolib3.molang.runtime.AssignableVariable;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.binding.ObjectBinding;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import org.jetbrains.annotations.NotNull;

public class ContextVariableBinding implements ObjectBinding, ScopedObject {
    private final Int2ReferenceOpenHashMap<ContextVariable> variableMap = new Int2ReferenceOpenHashMap<>();

    @Override
    public Object getProperty(String name) {
        return variableMap.computeIfAbsent(StringPool.computeIfAbsent(name), ContextVariable::new);
    }

    public void resetScoped() {
        variableMap.clear();
    }

    @SuppressWarnings("unchecked")
    private static class ContextVariable implements AssignableVariable {
        private final int name;

        private ContextVariable(int name) {
            this.name = name;
        }

        @Override
        public Object evaluate(final @NotNull ExecutionContext<?> context) {
            var storage = ((IContext<Object>) context.entity()).contextStorage();
            if (storage != null) {
                return storage.getContext(name);
            }
            return null;
        }

        @Override
        public void assign(@NotNull ExecutionContext<?> context, Object value) {
            var storage = ((IContext<Object>) context.entity()).contextStorage();
            if (storage != null) {
                storage.setContext(name, value);
            }
        }
    }
}
