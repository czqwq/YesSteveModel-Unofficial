package com.fox.ysmu.client.roaming;

import java.util.Map;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SRoamingChanges;
import com.fox.ysmu.ysmu;

/**
 * Reports the local player's roaming-variable changes to the server.
 * <p>
 * A <em>roaming variable</em> is a named float belonging to one model of one player; the model's {@code 模型设置}
 * panel writes them and {@link ClientRoamingStore} keeps them locally. The server is the authority (upstream keeps the
 * same data in {@code RoamingVariableStore} and echoes it back), so every local change has to be reported once.
 * <p>
 * This is driven from the client tick rather than from the settings screen, for the same reason upstream flushes from
 * its own tick: a change then still reaches the server when the screen is closed in the same frame, and a future
 * writer cannot forget to flush.
 */
public final class ClientRoamingSync {

    private ClientRoamingSync() {}

    /** Sends every pending change, at most one message per model. Safe to call every tick. */
    public static void flushPending() {
        if (!ClientRoamingStore.hasPending()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            // Not connected to a world; keep the changes pending instead of dropping them.
            return;
        }
        Map<Integer, Map<String, Double>> pending = ClientRoamingStore.takePending();
        for (Map.Entry<Integer, Map<String, Double>> entry : pending.entrySet()) {
            try {
                NetworkHandler.CHANNEL.sendToServer(C2SRoamingChanges.of(entry.getKey(), entry.getValue()));
            } catch (Exception e) {
                // A send can fail while the connection is going away; keep the change for the next attempt.
                ClientRoamingStore.markPending(entry.getKey(), entry.getValue());
                ysmu.LOG.warn("Failed to report a YSM roaming change; it stays pending", e);
            }
        }
    }
}
