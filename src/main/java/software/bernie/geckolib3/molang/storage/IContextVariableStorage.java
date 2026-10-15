package software.bernie.geckolib3.molang.storage;

public interface IContextVariableStorage {
    Object getContext(int name);
    void setContext(int name, Object value);
}
