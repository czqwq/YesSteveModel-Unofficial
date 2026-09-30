package com.fox.ysmu.client.input;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetPlayAnimation;
import com.google.common.collect.Lists;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

import java.util.List;

@EventBusSubscriber(side = Side.CLIENT)
public class ExtraAnimationKey {
    public static final List<KeyBinding> EXTRA_ANIMATION_KEYS = Lists.newArrayList();

    public static void registerKeyBindings() {
        for (int i = 0; i <= 7; i++) {
            String name = String.format("key.yes_steve_model.extra_animation.%d.desc", i);
            KeyBinding keyMapping = new KeyBinding(name, Keyboard.KEY_NONE, "key.category.yes_steve_model");
            ClientRegistry.registerKeyBinding(keyMapping);
            EXTRA_ANIMATION_KEYS.add(keyMapping);
        }
    }

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        // CU-04: isPressed() 先消费（pressTime 是一次性信号），再判断界面；
        // GUI 打开时（聊天框打字、容器界面）不再发送动画包。
        for (KeyBinding key : EXTRA_ANIMATION_KEYS) {
            if (!key.isPressed()) {
                continue;
            }
            if (mc.currentScreen != null || mc.thePlayer == null) {
                return;
            }
            NetworkHandler.CHANNEL.sendToServer(new SetPlayAnimation(EXTRA_ANIMATION_KEYS.indexOf(key)));
            return;
        }
    }
}
