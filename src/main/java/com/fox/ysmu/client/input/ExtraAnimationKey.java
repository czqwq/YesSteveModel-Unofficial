package com.fox.ysmu.client.input;

import com.fox.ysmu.client.gui.AnimationRouletteScreen;
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

/**
 * The eight extra-animation key bindings: key {@code i} acts on the {@code i}-th <b>root</b> wheel entry of the local
 * player's model.
 *
 * <p>That is upstream's own mapping. Its {@code ExtraAnimationKey} takes the root {@code extra_animation} entry at the
 * binding's index and dispatches on its <em>key</em>: {@code "#return"} stops the animation, a
 * {@code "#<classifyId>"} entry opens the roulette inside that sub-page, and anything else is played by name
 * ({@code tmp/YesSteveModel-dev-1.20/.../client/input/ExtraAnimationKey.java:58-85}). The wheel draws the binding
 * hints next to the same entries, and only on its first root page
 * ({@code client/gui/AnimationRouletteScreen.java:751-755}).</p>
 *
 * <p>This port used to send the slot index and let the server spell the name as {@code "extra" + index}, which could
 * only reach the legacy {@code extra0..extra7} vocabulary - a pack that declares named keys (LaPluma 2.5.3: {@code HI},
 * {@code WHAT}, {@code Bird}, ...) had no working binding at all. The dispatch now lives in
 * {@link AnimationRouletteScreen#activateRootSlot(int)}, so a key press and a click on the same sector do the same
 * thing by construction.</p>
 */
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
            AnimationRouletteScreen.activateRootSlot(EXTRA_ANIMATION_KEYS.indexOf(key));
            return;
        }
    }
}
