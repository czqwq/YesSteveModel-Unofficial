package software.bernie.geckolib3.molang.storage;

public interface IScopedVariableStorage {
    Object getScoped(int name);
    void setScoped(int name, Object value);
}
