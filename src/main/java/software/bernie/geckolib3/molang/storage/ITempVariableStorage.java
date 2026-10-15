package software.bernie.geckolib3.molang.storage;

public interface ITempVariableStorage {
    Object getTemp(int address);
    void setTemp(int address, Object value);
}
