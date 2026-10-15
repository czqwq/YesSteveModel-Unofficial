package software.bernie.geckolib3.molang.context;

import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.runtime.ExpressionEvaluator;
import software.bernie.geckolib3.molang.runtime.Function;
import software.bernie.geckolib3.molang.storage.IContextVariableStorage;
import software.bernie.geckolib3.molang.value.IValue;

public class AnimationContext implements IContextVariableStorage {
    private IMolangSoundManager soundManager;
    private float animTime;
    private Int2ObjectOpenHashMap<Object> variableStorage;
    private int deferCount = 0;
    private ReferenceArrayList<ReferenceArrayList<Object>> deferFunctionArgs;

    public void setAnimTime(float animTime) {
        this.animTime = animTime;
    }

    public float animTime() {
        return animTime;
    }

    public IMolangSoundManager soundManager() {
        if (soundManager == null) {
            soundManager = MolangSoundManagers.create();
        }
        return soundManager;
    }

    @Override
    public Object getContext(int name) {
        if (variableStorage != null) {
            return variableStorage.get(name);
        }
        return null;
    }

    @Override
    public void setContext(int name, Object value) {
        if (variableStorage == null) {
            variableStorage = new Int2ObjectOpenHashMap<>();
        }
        variableStorage.put(name, value);
    }

    public void defer(ExecutionContext<?> ctx, int name, Function.ArgumentCollection args, int argsOffset) {
        if (deferFunctionArgs == null) {
            deferFunctionArgs = new ReferenceArrayList<>();
        }
        int index = deferCount++;
        ReferenceArrayList<Object> argValues;
        if (deferFunctionArgs.size() <= index) {
            argValues = new ReferenceArrayList<>(args.size() - argsOffset);
            deferFunctionArgs.add(argValues);
        } else {
            argValues = deferFunctionArgs.get(index);
        }
        argValues.size(args.size() - argsOffset);
        for (int i = argsOffset; i < args.size(); i++) {
            argValues.set(i - argsOffset, args.getValue(ctx, i));
        }
    }

    public void reset(ExpressionEvaluator<MolangContext<?>> evaluator) {
        if (deferCount > 0) {
            MolangContext<?> ctx = evaluator.entity();
            if (ctx.animatableEntity() instanceof IMolangDeferHandler animatable) {
                List<IValue> deferHandler = animatable.getMolangDeferHandler();
                if (deferHandler != null) {
                    ctx.setAllowEmitting(true);
                    for (int i = deferCount - 1; i >= 0; i--) {
                        for (IValue func : deferHandler) {
                            ReferenceArrayList<Object> args = deferFunctionArgs.get(i);
                            ctx.callUserFunction(evaluator, func, args);
                        }
                    }
                    ctx.setAllowEmitting(false);
                }
            }
            deferCount = 0;
        }
        if (variableStorage != null) {
            variableStorage.clear();
        }
    }
}
