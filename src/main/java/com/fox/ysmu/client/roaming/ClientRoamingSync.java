package com.fox.ysmu.client.roaming;

import java.util.Collections;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.client.input.ServerSwitchState;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SRoamingChanges;
import com.fox.ysmu.ysmu;

/**
 * Reports the local player's roaming-variable changes to the server, and asks for the saved values once a world or a
 * model is entered.
 * <p>
 * A <em>roaming variable</em> is a named float belonging to one model of one player; the model's {@code 模型设置}
 * panel writes them and {@link ClientRoamingStore} keeps them locally. The server is the authority (upstream keeps the
 * same data in {@code RoamingVariableStore} and echoes it back), so every local change has to be reported once.
 * <p>
 * This is driven from the client tick rather than from the settings screen, for the same reason upstream flushes from
 * its own tick: a change then still reaches the server when the screen is closed in the same frame, and a future
 * writer cannot forget to flush.
 * <p>
 * <b>Why the request half exists.</b> The server is authoritative but nothing used to push its values to a client:
 * logging in only registers the save data and changing a model only writes the selection
 * ({@code event/CommonEventHandler.java:434}, {@code network/message/SetModelAndTexture.java:133-148}), so after a
 * relog or a model change the client's {@link ClientRoamingStore} was empty and the model rendered with the values
 * its pack self-initialises (for example {@code v.roaming.player_size=v.roaming.player_size?v.roaming.player_size:1})
 * until the player moved a control. The empty-delta packet is the port's request for that state: the port's handler
 * treats a zero-entry {@code C2SRoamingChanges} as "send me the authoritative map for this key" and answers with
 * {@code S2CRoamingState.broadcast} without writing anything, so no new packet id and no protocol bump are needed.
 * This is a port-specific semantic recorded in {@code AGENTS.md} and
 * {@code .agent/phase64-model-config-screen-review.md}; the reply is what makes the panel show saved values after a
 * relog or a model change, which upstream gets from its session state handshake.
 */
public final class ClientRoamingSync {

    /**
     * The world the last state request was sent for, or {@code null} when the client is not in a world. Compared by
     * identity because 1.7.10 builds a new {@link World} on every world load and dimension change
     * ({@code Minecraft.loadWorld:2249-2301}, {@code NetHandlerPlayClient.handleRespawn:988-1000}), which is the
     * port's connection-identity token (the same one {@code input/ServerSwitchState} uses).
     */
    private static World requestedForWorld;
    /** The model whose state was last requested; {@code null} before the first request of a world. */
    private static ResourceLocation requestedModel;
    /** The roaming key that model had at request time, so a late payload with a different key requests again. */
    private static int requestedKey;

    private ClientRoamingSync() {}

    /** Sends the saved-state request when the world or the player's model changed, then every pending change. */
    public static void flushPending() {
        requestSavedState();
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

    /**
     * Asks the server for this player's saved values of the model they are drawing, at most once per (world, model,
     * key).
     * <p>
     * The request is an empty {@code C2SRoamingChanges}: the server recognises the zero-entry delta as "send me the
     * authoritative map" (see the class javadoc). Nothing is sent before the model's roaming key is known, because
     * {@link ClientRoamingKeys#keyFor} would otherwise hand over the id-derived fallback rather than the hash-derived
     * key the saved values live under; the tick that sees the payload arrive sends the request then. Nothing is sent
     * to a server that never answered the YSMU handshake either - there is no handler there to answer, and
     * {@code ServerSwitchState} is the port's per-connection equivalent of upstream's
     * {@code NetworkHandler.isRemoteChannelPresent()}.
     */
    private static void requestSavedState() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer local = mc.thePlayer;
        if (local == null || mc.theWorld == null) {
            // Forgetting the token is what makes re-entering a world (or relogging) request again.
            requestedForWorld = null;
            requestedModel = null;
            return;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(local);
        ResourceLocation modelId = info == null ? null : info.getModelId();
        if (modelId == null) {
            requestedModel = null;
            return;
        }
        if (!ClientRoamingKeys.isKnown(modelId)) {
            requestedModel = null;
            return;
        }
        if (!ServerSwitchState.isRemoteChannelPresent()) {
            // No YSMU server answered the handshake (or a dimension change is between the two worlds), so there is
            // nobody that could answer. The token is left alone, so the first tick after the handshake reply sends.
            return;
        }
        int modelKey = ClientRoamingKeys.keyFor(modelId);
        if (mc.theWorld == requestedForWorld && modelId.equals(requestedModel) && modelKey == requestedKey) {
            return;
        }
        // Record the attempt before sending: a failing send must not make the next tick retry forever, and the next
        // world or model change re-requests anyway.
        requestedForWorld = mc.theWorld;
        requestedModel = modelId;
        requestedKey = modelKey;
        try {
            NetworkHandler.CHANNEL.sendToServer(C2SRoamingChanges.of(modelKey, Collections.emptyMap()));
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to request the YSM roaming state for {}", modelId, e);
        }
    }
}
