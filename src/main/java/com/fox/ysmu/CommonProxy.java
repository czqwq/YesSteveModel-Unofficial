package com.fox.ysmu;

import com.fox.ysmu.command.YsmCommand;
import com.fox.ysmu.compat.ServerUtilitiesCompat;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncPlayerMotionState;
import com.fox.ysmu.network.message.SyncStarModels;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.init(event.getSuggestedConfigurationFile());
        ServerModelManager.reloadPacks();
        ysmu.LOG.info("I am ysmu at version " + Tags.VERSION);
        if (Loader.isModLoaded("geckolib")) {
            ysmu.LOG.warn("Old geckolib-*.jar found: this build embeds the engine itself, so DELETE that jar or the two copies will conflict.");
        }
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        NetworkHandler.init();
    }

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    /**
     * 注册服务端命令，并在同一事件里把 {@code /ysm} 的各子命令权限节点登记给 ServerUtilities（若已安装）。
     *
     * <p>{@code FMLServerStartingEvent} 是合法的登记点：ServerUtilities 的 {@code PermissionAPI.registerNode}
     * 要求 loader state 严格晚于 PREINITIALIZATION（{@code PermissionAPI.java:49-51}），而该事件分发时 state 仍
     * 是 AVAILABLE、分发完才 transition 到 SERVER_STARTING（{@code Loader.java:789-790}）；ServerUtilities 自己
     * 也在这个事件里给已注册命令生成根节点，所以两者重合。细节与节点表见 {@link ServerUtilitiesCompat} 类注释。</p>
     */
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new YsmCommand());
        ServerUtilitiesCompat.install();
    }

    public void handleStarModels(SyncStarModels message) {}

    public void handlePlayerMotionState(SyncPlayerMotionState message) {}
}
