package software.bernie.geckolib3.core.molang;

import java.util.function.DoubleSupplier;

/**
 * A {@link LazyVariable} for {@code v.} Molang variables that scopes reads
 * and writes to the per-(player, model) frame context.
 * <p>
 * {@link #get()} delegates to the host-mod {@link ScopedVariableStore} hook,
 * which looks up the value in the current frame's per-model scope. If no frame
 * context is active or the variable has not been set in the current scope, the
 * fallback is {@code 0.0} — a neutral default. This prevents cross-model
 * contamination via the global {@link MolangParser#VARIABLES} map: each
 * model's {@code v.} variables live in their own scope, isolated by
 * {@code (playerId, modelId)} key.
 * <p>
 * {@link #set(double)} writes through to the frame's scope when a frame
 * context is active. Outside a frame context (e.g. during model
 * initialisation), the value is stored in the global {@code VARIABLES} map
 * via the parent {@link LazyVariable#set(double)} for compatibility, but
 * {@link #get()} will never read it — cross-model reads always default to 0.
 * <p>
 * The store is injected by the host mod through the static {@link #store}
 * field (inverted control — this vendored file has no dependency on mod code).
 * A {@code null} store means "no frame scope active": reads/writes fall back
 * to the global {@code VARIABLES} map.
 * <p>
 * YSMU 本拓扑的补充：引擎自己带有 {@code MolangPhysicsRuntime}，它就是这里真正生效的
 * 作用域，所以 {@link #store} 未接线时两个方法都回落到它（而不是直接丢弃写入或读成
 * 0）——行为与移植前一致。SOURCE 把引擎内嵌进 mod 时由宿主
 * {@code AnimationRegister} 接线，桥本身只是那次内嵌的产物。
 */
public class ScopedMolangVariable extends LazyVariable {

    /**
     * Host-mod-injected hook: per-(player, model) {@code v.} variable scope
     * storage. Set once at mod init; read-only afterwards. {@code null} → the
     * variable falls back to the global VARIABLES map (LazyVariable behavior).
     */
    public interface ScopedVariableStore {
        boolean contains(String name);
        double get(String name, double fallback);
        boolean set(String name, double value);
    }

    /**
     * Host-mod-injected hook: model-isolated fallback for {@link #get()} when
     * the current model scope has no such variable. The host records which
     * model wrote each global {@code v.} entry; a value written by a *different*
     * model (cross-model residue, e.g. 14_momo reading another model's
     * {@code v.roaming.a}) must return 0 instead of the stale global value.
     * Set once at mod init; {@code null} → plain global fallback (no isolation).
     */
    public interface GlobalFallback {
        double get(String name, double fallback);
    }

    /**
     * Host-mod-injected hook: called when a write could <b>not</b> go to a model scope
     * (no frame context active) and therefore lands in the global {@code VARIABLES} map.
     * The host uses it to record that this global entry belongs to no model, so the
     * model-isolated read fallback can refuse to serve it to any model. Set once at mod
     * init; {@code null} → no recording (plain global behavior).
     */
    public interface UnscopedWriteSink {
        void onUnscopedWrite(String name);
    }

    public static volatile ScopedVariableStore store = null;

    /** Host-mod hook for global-fallback writes; see {@link UnscopedWriteSink}. */
    public static volatile UnscopedWriteSink unscopedWriteSink = null;

    /** Model-isolated global fallback (see {@link GlobalFallback}). */
    public static volatile GlobalFallback globalFallback = null;

    public ScopedMolangVariable(String name, double value) {
        super(name, () -> value);
    }

    public ScopedMolangVariable(String name, DoubleSupplier fallbackSupplier) {
        super(name, fallbackSupplier);
    }

    @Override
    public void set(double value) {
        ScopedVariableStore s = store;
        if (s == null) {
            // 本拓扑的活作用域是引擎自带的 MolangPhysicsRuntime（SOURCE 内嵌布局里由宿主
            // 用 store 接线）。未接线时保持移植前的行为，而不是把这次写入丢掉。
            if (!MolangPhysicsRuntime.setVariable(getName(), value)) {
                super.set(value);
            }
            return;
        }
        if (!s.set(getName(), value)) {
            // 落到全局 VARIABLES：这个值不属于任何模型，通知宿主记一笔，读取侧才能挡住它。
            UnscopedWriteSink sink = unscopedWriteSink;
            if (sink != null) {
                sink.onUnscopedWrite(getName());
            }
            super.set(value);
        }
    }

    @Override
    public double get() {
        // During a render frame, read from the per-(player, model) scope to
        // keep v. variables isolated across models. If the variable has not
        // been set in this model's scope yet, fall through to the global
        // VARIABLES value (set by an earlier set() outside a frame context,
        // e.g. model init or test evaluation), but only if it was written by
        // the *current* model — values written by another model's timeline
        // (cross-model residue, e.g. 14_momo reading another model's
        // v.roaming.a) are filtered out to 0 by the GlobalFallback hook.
        // Roaming/wheel variables are injected into the scope every frame by
        // the host mod, so they always take the frame path.
        ScopedVariableStore s = store;
        if (s == null) {
            // 同上：引擎自带的作用域未接线时就是活路径。回落值是本变量自己注册时的取值
            // （super.get()），不是另存的供应商 —— 后者只在 set 路径上被赋值，用
            // (name, double) 构造出来的变量上它是 null，读一次就 NPE（四个测试同时挂在这里）。
            return MolangPhysicsRuntime.getVariable(getName(), super.get());
        }
        if (s.contains(getName())) {
            return s.get(getName(), 0.0);
        }
        GlobalFallback g = globalFallback;
        if (g != null) {
            return g.get(getName(), super.get());
        }
        return super.get();
    }
}
