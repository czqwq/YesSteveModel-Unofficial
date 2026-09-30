package com.fox.ysmu.client.input;

import com.fox.ysmu.client.gui.ExtraPlayerConfigScreen;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

@EventBusSubscriber(side = Side.CLIENT)
public class ExtraPlayerConfigKey {
    public static final KeyBinding EXTRA_PLAYER_RENDER_KEY =
        new KeyBinding("key.yes_steve_model.open_extra_player_render.desc", Keyboard.KEY_P, "key.category.yes_steve_model");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        // CU-04: 先消费 isPressed()（pressTime 是一次性信号），再判断界面与玩家状态。
        boolean pressed = EXTRA_PLAYER_RENDER_KEY.isPressed();
        if (!pressed) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null) {
            return;
        }
        boolean isAltKeyDown = Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
        if (isAltKeyDown) {
            mc.displayGuiScreen(new ExtraPlayerConfigScreen());
        }
    }
}
