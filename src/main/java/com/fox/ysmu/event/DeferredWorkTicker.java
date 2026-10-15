package com.fox.ysmu.event;

import com.fox.ysmu.util.DeferredWork;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Drains {@link DeferredWork} once per tick on each side.
 * <p>
 * Server packet work is queued from the Netty thread and runs here on the server thread; client packet work
 * (for example opening a GUI) runs on the client thread. The queues are distinct, so in single-player the
 * client tick never runs server work.
 */
@EventBusSubscriber
public class DeferredWorkTicker {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DeferredWork.drainClient();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DeferredWork.drainServer();
        }
    }
}
