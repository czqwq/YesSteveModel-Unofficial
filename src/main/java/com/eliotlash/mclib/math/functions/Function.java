package com.eliotlash.mclib.math.functions;

import com.eliotlash.mclib.math.IValue;
import software.bernie.geckolib3.core.molang.MolangStringPool;

public abstract class Function implements IValue {

    protected IValue[] args;
    protected String name;

    public Function(IValue[] values, String name) throws Exception {
        if (values.length < this.getRequiredArguments()) {
            String message = String.format(
                "Function '%s' requires at least %s arguments. %s are given!",
                this.getName(),
                this.getRequiredArguments(),
                values.length);
            throw new Exception(message);
        }
        this.args = values;
        this.name = name;
    }

    /**
     * 获取第 N 个参数的值
     */
    public double getArg(int index) {
        if (index < 0 || index >= this.args.length) {
            return 0;
        }
        return this.args[index].get();
    }

    /**
     * The text of argument {@code index} when the call site wrote a string literal ({@code 'text'}), or {@code null}
     * for a missing argument or the empty id.
     * <p>
     * {@code MolangParser#breakdown} replaces every string literal with its {@link MolangStringPool} id before the
     * expression is parsed, so a literal arrives here as a number and this turns it back into text - the same way
     * {@code MolangPhysicsRuntime} recovers a bone name. A numeric argument that happens to equal a pooled id is
     * therefore indistinguishable from a literal; packs only use literals where a name is expected, and id
     * {@code 0} (empty) never resolves.
     */
    public String getStringArg(int index) {
        if (index < 0 || index >= this.args.length) {
            return null;
        }
        return MolangStringPool.get((int) this.args[index].get());
    }

    /** Number of arguments the call site passed, so a function can implement optional parameters. */
    public int getArgumentCount() {
        return this.args == null ? 0 : this.args.length;
    }

    @Override

    public String toString() {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < this.args.length; i++) {
            args.append(this.args[i].toString());
            if (i < this.args.length - 1) {
                args.append(", ");
            }
        }
        return this.getName() + "(" + args + ")";
    }

    /**
     * 获取函数名
     */
    public String getName() {
        return this.name;
    }

    /**
     * 获取此函数所需的最小参数量
     */

    public int getRequiredArguments() {
        return 0;
    }
}
