package com.fox.ysmu.client;

import java.util.concurrent.atomic.AtomicBoolean;

import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.input.*;
import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;

import com.fox.ysmu.CommonProxy;
import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.client.sync.LocalModelLoader;
import com.fox.ysmu.command.CommandYsmClient;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.network.message.SyncPlayerMotionState;
import com.fox.ysmu.network.message.SyncStarModels;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;
import com.fox.ysmu.util.ThreadTools;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;

public class ClientProxy extends CommonProxy {

    private static CustomPlayerRenderer CUSTOM_PLAYER_RENDERER;

    /**
     * 一次性守卫：本地/内置模型目录的启动期注册只跑一次（reload、二次 init 都不得重复提交）。
     * 上游同一件事由 {@code ClientModelService#start()} 自己的 {@code INSTANCE != null} 判定承担
     * （{@code tmp/YesSteveModel-dev-1.20/.../model/service/ClientModelService.java:185-193}）。
     */
    private static final AtomicBoolean LOCAL_MODELS_STARTED = new AtomicBoolean();

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // The earliest client-only entry point: record the client thread here so the model manager's off-thread
        // install guard is meaningful from mod init, not only from the first resource reload or tick.
        ClientModelManager.markClientThread();
        // Optional-mod adapters that contribute Molang bindings must be initialized before registerVariables below:
        // the TacZ adapter replaces the nine ctrl.tac_* defaults with live values (upstream's CtrlBinding shape).
        com.fox.ysmu.client.compat.tacz.TACZCompat.init();
        com.fox.ysmu.client.compat.AngelicaPbrCompat.init();
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
        // 客户端 setup 阶段就启动本地/内置模型目录的注册，让它们在进世界之前就可用，而不是只等进世界后的
        // 服务器同步——这是上游形态：dev-1.20 的 ClientSetupEvent.java:109 在 FMLClientSetupEvent 里调
        // ClientModelService#start()，一次性构造目录（内置 default + 本地目录 model/catalog/**，例如
        // ReloadableModelCatalog.java:84 用 builtins.contract() 建 ModelSourceResolver）并交给客户端引擎。
        // 1.7.10 没有 FMLClientSetupEvent，对应点就是这里：CommonProxy.preInit 已在此前跑完
        // ServerModelManager.reloadPacks()，本地扫描索引 OPEN_YSM_SYNC_INFO 与本地密钥都已就绪。
        // 会话层（服务器目录）不动：它仍由进世界后的 SessionCollectionPublication 送达
        // （ClientSessionRuntime.java:64-65,147,158）。
        startLocalModelRegistration();

        // 注册可选模组依赖检测。当控制器使用某模组的变量（如 ctrl.tac_*），
        // 但该模组未加载时，运行时自动跳过对应控制器。
        // (reference branch ClientProxy.java:37-60)
        // TacZ 的 1.7.10 移植（github.com/czqwq/TacZ）真实存在：装了就由 client/compat/tacz 适配器提供
        // ctrl.tac_* 的值与动画通道；没装时这里的检测让引用这些变量的控制器整条跳过。
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
        // D1: registering the lock key puts it in the controls menu so it can be rebound and persists; the key
        // itself still works unregistered, which is why upstream gets away with declaring it without this line.
        ClientRegistry.registerKeyBinding(AnimationRouletteKey.LOCK_ROULETTE_KEY);
        ExtraAnimationKey.registerKeyBindings();
        ClientRegistry.registerKeyBinding(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
        ClientRegistry.registerKeyBinding(PlayerModelScreenKey.PLAYER_MODEL_KEY);
        // /ysmclient (alias /ysmc) is a client-only command for every player, so it is registered unconditionally.
        // Config.DEBUG_MOLANG_OVERLAY gates the Molang debug cluster only - the DebugOverlay, the engine's per-frame
        // variable snapshot and the four debug packet consumers - not this command.
        // It goes to ClientCommandHandler rather than the server dispatcher, and it needs a name of its own:
        // registering a client-side "ysm" would swallow every /ysm call before it could reach the server.
        // CommandYsmClient's javadoc carries the 1.7.10 alias/name rationale.
        ClientCommandHandler.instance.registerCommand(new CommandYsmClient());
        // Developer-only auto-capture harness (flat world -> select models -> screenshots -> exit). Inert unless
        // YSMU_DEBUG_CAPTURE is set in the client process; see ModelDebugCapture's javadoc for the variables.
        com.fox.ysmu.client.debug.ModelDebugCapture.startIfEnabled();
    }

    /**
     * 客户端 setup 阶段触发一次本地/内置模型注册，入口就是 {@code /ysmclient load} 用的那个
     * （{@link LocalModelLoader#registerLocalModels()}）。
     * <p>
     * 调用方式与命令路径一致（{@code CommandYsmClient#processLoad:152-155}）：解析/注册较重，丢到
     * {@link ThreadTools#THREAD_POOL} 后台线程；真正的注册再由
     * {@code OpenYsmModelSyncClient.parseAndRegisterModel:480-489} 用 {@code Minecraft.func_152344_a}
     * 交回客户端线程执行。init 阶段 {@code Minecraft.renderEngine}（{@code Minecraft.java:523}）与
     * {@code mcSoundHandler}（{@code :527}）都还没构造，所以绝不能在 init 线程上直接注册。
     * <p>
     * 与命令路径的唯一差别是不回聊天消息：这一步是自动启动，此时也还没有玩家。
     * 已注册的模型由 {@code LocalModelLoader} 自己按 {@code MODELS.containsKey(baseId)} 跳过
     * （{@code LocalModelLoader.java:53-56}），因此与之后进世界的服务器同步不互相覆盖。
     */
    private static void startLocalModelRegistration() {
        if (!LOCAL_MODELS_STARTED.compareAndSet(false, true)) {
            return;
        }
        ThreadTools.THREAD_POOL.submit(() -> {
            LocalModelLoader.registerLocalModels();
        });
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
        // Packet 11 carries the movement flags plus the two gameplay facts 1.7.10 only sends to the player themself
        // (food/experience, see SyncPlayerMotionState's javadoc); all three land in the one per-UUID table.
        RemotePlayerMotionStates.update(
            message.getPlayerId(),
            message.getFlags(),
            message.getFoodLevel(),
            message.getExperienceLevel());
    }
}
