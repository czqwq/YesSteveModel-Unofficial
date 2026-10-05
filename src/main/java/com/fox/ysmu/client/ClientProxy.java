package com.fox.ysmu.client;

import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
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
        // 注册内置 empty 动画的兜底/过滤钩子（vendored AnimationFile 无反向依赖）。
        // 必须在任何模型动画被解析前注册：AnimationFile.putAnimation 在解析期丢弃模型自定义的
        // "empty"，getAnimation 在读取期用内置空动画兜底，两个钩子都是 static 字段、模块初始化后
        // 只读。这里是 FMLInitializationEvent（SOURCE ClientProxy.java:34-35 同一位置），早于
        // ClientModelManager 的首次解析（模型注册来自登录后的同步包，内建 default 则在
        // TextureStitchEvent.Post 之后的 loadDefaultModel）。
        com.fox.ysmu.client.animation.YsmBuiltinAnimations.registerHooks();

        // 注册可选模组依赖检测。当控制器使用某模组的变量（如 ctrl.tac_*），
        // 但该模组未加载时，运行时自动跳过对应控制器。
        // (reference branch ClientProxy.java:37-60)
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("tacz",
                "ctrl.tac_"));
        // 以下模组在 1.7.10 上通常不存在，注册后其控制器的变量条件
        // 会被自动检测并跳过。如需在 1.7.10 上使用这些模组，只需确保
        // Loader.isModLoaded() 返回 true 即可。
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("parcool",
                "ctrl.parcool_"));
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("slashblade",
                "ctrl.slashblade_"));
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("swem",
                "ctrl.swem_"));
        // 1.7.10 上不存在的模组：模型用它的命名空间写物品标签/条件时（例如某枪械模型写
        // equipped_item_any_tag('mainhand','irons_spellbooks:staff')）永远匹配不到。注册后
        // 控制器解析阶段就能发现它，标签路径也会报"该 mod 未安装"而不是"我们没实现"——
        // 这是"正确跳过"，不是缺口。
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("irons_spellbooks",
                "irons_spellbooks:"));
        CUSTOM_PLAYER_RENDERER = new CustomPlayerRenderer();
        GeoReplacedEntityRenderer.registerReplacedEntity(CustomPlayerEntity.class, CUSTOM_PLAYER_RENDERER);
        // ...and the first-person arm's animatable, which is a second animatable for the same player and the same
        // model provider. The engine resolves an animatable's provider by its exact class
        // (`GeoReplacedEntityRenderer#getRenderer`, `renderers.get(object.getClass())`), and
        // `AnimationController#getModel` needs it to reach the model's `getAnimation(name, animatable)`: without this
        // registration every arm controller would find no model, print "Could not find suitable model for animatable
        // of type ..." once per frame and then NPE on the dereference that follows
        // (`AnimationController.java:916-927` -> `:338`). Upstream needs no such registration because each of its
        // entities owns its own `AnimationProcessor`.
        GeoReplacedEntityRenderer.registerReplacedEntity(CustomFirstPersonArmEntity.class, CUSTOM_PLAYER_RENDERER);
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
