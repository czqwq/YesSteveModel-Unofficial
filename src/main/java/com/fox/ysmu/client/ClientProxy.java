package com.fox.ysmu.client;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.input.*;
import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.Minecraft;

import com.fox.ysmu.CommonProxy;
import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.network.message.SyncPlayerMotionState;
import com.fox.ysmu.network.message.SyncStarModels;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;

public class ClientProxy extends CommonProxy {

    private static CustomPlayerRenderer CUSTOM_PLAYER_RENDERER;

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // The earliest client-only entry point: record the client thread here so the model manager's off-thread
        // install guard is meaningful from mod init, not only from the first resource reload or tick.
        ClientModelManager.markClientThread();
        // 必须先注册 Molang 钩子：GeckoLibCache.getInstance() 首次调用即构造
        // MolangParser（new MolangParser() 内执行 doCoreRemaps），ysm.*/ctrl.*/query.*
        // 函数注册必须在该构造之前就位，否则运行时解析器缺失这些函数。
        AnimationRegister.registerMolangHooks();
        AnimationRegister.registerAnimationState();
        AnimationRegister.registerVariables();
        CUSTOM_PLAYER_RENDERER = new CustomPlayerRenderer();
        GeoReplacedEntityRenderer.registerReplacedEntity(CustomPlayerEntity.class, CUSTOM_PLAYER_RENDERER);
        ClientRegistry.registerKeyBinding(AnimationRouletteKey.ANIMATION_ROULETTE_KEY);
        ExtraAnimationKey.registerKeyBindings();
        ClientRegistry.registerKeyBinding(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
        ClientRegistry.registerKeyBinding(PlayerModelScreenKey.PLAYER_MODEL_KEY);
    }

    public static CustomPlayerRenderer getInstance() {
        return CUSTOM_PLAYER_RENDERER;
    }

    @Override
    public void handleStarModels(SyncStarModels message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            ExtendedStarModels eep = ExtendedStarModels.get(mc.thePlayer);
            if (eep != null) {
                eep.setStarModels(message.getStarModels());
            }
        }
    }

    @Override
    public void handlePlayerMotionState(SyncPlayerMotionState message) {
        RemotePlayerMotionStates.update(message.getPlayerId(), message.getFlags());
    }
}
