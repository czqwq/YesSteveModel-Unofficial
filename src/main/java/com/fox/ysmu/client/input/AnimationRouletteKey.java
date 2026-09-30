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

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        // CU-04: 必须先消费 isPressed()（pressTime 是一次性信号；界面打开期间不消费，会在界面关闭后的
        // 下一个按键事件上补触发），再判断界面是否打开：1.7.10 的按键状态与 KeyInputEvent 都不受 GUI 影响。
        boolean pressed = ANIMATION_ROULETTE_KEY.isPressed();
        if (!pressed || mc.currentScreen != null || mc.thePlayer == null) {
            return;
        }
        mc.displayGuiScreen(new AnimationRouletteScreen());
    }
}
