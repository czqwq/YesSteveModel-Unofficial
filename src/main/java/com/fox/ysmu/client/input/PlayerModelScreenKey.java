package com.fox.ysmu.client.input;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.gui.DisclaimerScreen;
import com.fox.ysmu.client.gui.PlayerModelScreen;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

@EventBusSubscriber(side = Side.CLIENT)
public class PlayerModelScreenKey {
    public static final KeyBinding PLAYER_MODEL_KEY =
        new KeyBinding("key.yes_steve_model.player_model.desc", Keyboard.KEY_Y, "key.category.yes_steve_model");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        // CU-04: 先消费 isPressed()（KeyBinding.pressTime 是一次性信号），再判断界面与玩家状态：
        // 1.7.10 的按键状态与 KeyInputEvent 都不受 GUI 影响（Minecraft.java:1839/1964）。
        boolean pressed = PLAYER_MODEL_KEY.isPressed();
        if (!pressed) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // CU-03: thePlayer == null（主菜单/已断开）时打开模型界面会在渲染时 NPE，
        // 因此这里必须先判空，并把玩家显式传给界面（不再走无参构造器 / 不把 null 交给 ModelSelectionTarget.of）。
        if (mc.currentScreen != null || mc.thePlayer == null) {
            return;
        }
        boolean isAltKeyDown = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        if (!isAltKeyDown) {
            return;
        }
        if (Config.DISCLAIMER_SHOW) {
            mc.displayGuiScreen(new DisclaimerScreen());
        } else {
            mc.displayGuiScreen(new PlayerModelScreen(mc.thePlayer));
        }
    }
}
