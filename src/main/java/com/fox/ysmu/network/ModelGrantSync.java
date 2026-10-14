package com.fox.ysmu.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.api.ModelAuthorization;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.message.S2CModelGrants;

/**
 * Sends the authorization facts to clients - the port's sender half of upstream's
 * {@code SessionProtocolHandler.refreshGrants} ({@code network/forge/SessionProtocolHandler.java:339-341} for the
 * entry point, {@code :368-388} for the publish) and of the two collections it publishes
 * ({@code network/forge/SessionCollectionPublication.java:71-77} for the grants, {@code :749-759} for each entry's
 * access).
 * <p>
 * <b>When it is sent.</b> Upstream refreshes grants when a player joins the world
 * ({@code event/CapabilityEvent.java:177-178}) and whenever the grant set changes
 * ({@code SessionProtocolHandler.java:383-386}, which is what every {@code /ysm auth} mutation ends in). The port has
 * no session object to hold the published catalog, so the packet always carries the full state and the two senders
 * are {@link #send(EntityPlayer)} (login, and one player after a grant change) and {@link #sendAll()} (every online
 * player, for a {@code /ysm reload} that moved models between scan roots).
 * <p>
 * <b>What is sent.</b> The restricted set is {@code ServerModelManager.authModelIds()} - every registered model whose
 * effective source is the {@code auth} root, i.e. the port's {@code AUTHORIZED} entries. The grant set is the
 * player's own table, filtered to models this server actually has: upstream publishes only grants the session's
 * catalog contains ({@code SessionProtocolHandler.java:377-378} intersects the capability with the catalog, and
 * {@code SessionCollectionPublication.java:190} filters again on the way out), so a stale grant for a model that was
 * deleted does not reach the client.
 * <p>
 * No file IO, no parsing and no encryption happens here - the payload is a few dozen short strings, built on the
 * caller's (server) thread from in-memory registries, which is what the packet-path rule requires.
 */
public final class ModelGrantSync {

    private ModelGrantSync() {}

    /**
     * Sends one player their current authorization state. Call on the server thread: it reads the player's EEP and
     * the model registry.
     */
    public static void send(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        NetworkHandler.sendToClientPlayer(new S2CModelGrants(restrictedIds(), grantedIds(player)), player);
    }

    /**
     * Sends every online player their current state, for a reload that changed which models are restricted. Mirrors
     * the iteration {@code NetworkHandler.sendToTrackingPlayersExcept} uses for the same accessor.
     */
    public static void sendAll() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return;
        }
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
            send(player);
        }
    }

    /** The restricted internal model ids, recomputed at send time so a reload is reflected without extra state. */
    private static List<String> restrictedIds() {
        return new ArrayList<>(ServerModelManager.authModelIds());
    }

    /**
     * The player's granted ids as the wire's bare internal ids. {@link ModelAuthorization#grantsOf(EntityPlayer)} is
     * {@code null} when the table is not attached, which is not "no grants" (see {@code ModelAuthorization.canSelect})
     * but does mean there is nothing to publish.
     */
    private static List<String> grantedIds(EntityPlayer player) {
        Set<ResourceLocation> grants = ModelAuthorization.grantsOf(player);
        if (grants == null || grants.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(grants.size());
        for (ResourceLocation modelId : grants) {
            if (modelId != null && ServerModelManager.hasModel(modelId)) {
                ids.add(modelId.getResourcePath());
            }
        }
        return ids;
    }
}
