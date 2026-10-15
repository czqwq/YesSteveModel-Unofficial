package com.fox.ysmu.client.input;

import com.fox.ysmu.client.gui.AnimationRouletteScreen;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

@EventBusSubscriber(side = Side.CLIENT)
public class AnimationRouletteKey {
    public static final KeyBinding ANIMATION_ROULETTE_KEY =
        new KeyBinding("key.yes_steve_model.animation_roulette.desc", Keyboard.KEY_Z, "key.category.yes_steve_model");

    /**
     * The extra-animation movement lock's key, upstream's {@code LOCK_ROULETTE_KEY}
     * ({@code tmp/YesSteveModel-dev-1.20/.../client/input/AnimationRouletteKey.java:30-35}) - the same class that
     * holds the wheel's own key upstream, which is why it lives here.
     *
     * <p>Upstream binds it to {@code ALT+L} through a {@code KeyModifier}. 1.7.10's {@link KeyBinding} has no modifier
     * concept at all (its constructor takes a bare key code, {@code KeyBinding.java:82-91}), so the port binds the
     * plain key. {@code L} is free in vanilla 1.7.10's default bindings, and the binding is remappable in the Controls
     * screen, so a conflict is the user's to resolve exactly as for any other key.</p>
     *
     * <p>Registered from {@code ClientProxy.init} beside {@link #ANIMATION_ROULETTE_KEY}, the port's single key
     * registration point; {@code ClientRegistry.registerKeyBinding} is what puts a binding into
     * {@code gameSettings.keyBindings} so the Controls screen shows it and it can be rebound
     * ({@code ClientRegistry.java:47-50}).</p>
     */
    public static final KeyBinding LOCK_ROULETTE_KEY =
        new KeyBinding("key.yes_steve_model.lock_roulette.desc", Keyboard.KEY_L, "key.category.yes_steve_model");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        // CU-04: 必须先消费 isPressed()（pressTime 是一次性信号；界面打开期间不消费，会在界面关闭后的
        // 下一个按键事件上补触发），再判断界面是否打开：1.7.10 的按键状态与 KeyInputEvent 都不受 GUI 影响。
        boolean pressed = ANIMATION_ROULETTE_KEY.isPressed();
        // D1 移动锁定：与上游同一条形状 —— PlayerMoveEvent.java:28-29 在键盘事件上直接把
        // LOCK_EXTRA_ANIMATION 取反，**不判断界面**（轮盘开着时按锁定键，按钮上的文案要立刻跟着变）。
        // 这里同样先消费 pressTime（CU-04 的那条一次性信号），再取反。
        boolean lockPressed = LOCK_ROULETTE_KEY.isPressed();
        if (lockPressed) {
            WheelAnimationLock.switchLock();
        }
        if (!pressed || mc.currentScreen != null || mc.thePlayer == null) {
            return;
        }
        mc.displayGuiScreen(new AnimationRouletteScreen());
    }
}
