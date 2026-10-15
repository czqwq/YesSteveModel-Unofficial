package software.bernie.geckolib3.molang.binding.variable;

import software.bernie.geckolib3.molang.binding.ScopedObject;
import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.storage.IForeignVariableStorage;
import software.bernie.geckolib3.molang.util.StringPool;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.Variable;
import software.bernie.geckolib3.molang.runtime.binding.ObjectBinding;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;

import org.jetbrains.annotations.NotNull;

public class ForeignVariableBinding implements ObjectBinding, ScopedObject {
    private final Int2ReferenceOpenHashMap<ForeignVariable> variableMap = new Int2ReferenceOpenHashMap<>();

    @Override
    public Object getProperty(String name) {
        return variableMap.computeIfAbsent(StringPool.computeIfAbsent(name), ForeignVariable::new);
    }

    public void resetScoped() {
        variableMap.clear();
    }

    private static class ForeignVariable implements Variable {
        private final int name;

        private ForeignVariable(int name) {
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object evaluate(final @NotNull ExecutionContext<?> context) {
            IForeignVariableStorage storage = ((IContext<Object>) context.entity()).foreignStorage();
            if(storage != null) {
                return storage.getPublic(name);
            } else {
                return null;
            }
        }
    }
}
