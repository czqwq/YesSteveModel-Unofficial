package software.bernie.geckolib3.molang.context;

import net.minecraft.util.IChatComponent;

public interface DebugSource {
    void print(String message, Object...args);

    void print(IChatComponent message);
}
