package com.fox.ysmu.client.input;

import com.fox.ysmu.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/**
 * The client's copy of the three facts upstream reads for its model screens and its roaming report policy:
 * whether a remote YSM channel is present, the server's {@code CanSwitchModel} value, and the server's roaming-sync
 * switch (upstream {@code ServerConfig.LOW_BANDWIDTH_USAGE}, delivered in {@code ServerHello.sync_roaming}).
 *
 * <h3>The upstream expressions this class serves</h3>
 * <ul>
 * <li>{@code client/input/PlayerModelScreenKey.java:41-44} - with a remote channel present and switching disabled,
 * the model-screen key opens {@code ConfigScreen} instead of {@code PlayerModelScreen};</li>
 * <li>{@code client/input/AnimationRouletteKey.java:45-46} - the roulette is allowed when there is no remote channel
 * <em>or</em> switching is allowed.</li>
 * <li>{@code network/forge/SessionProtocolHandler.java:63-75} - the client learns whether roaming is part of its report
 * policy from the server's {@code ServerHello.sync_roaming}, which the server derives from the server-side
 * {@code ServerConfig.LOW_BANDWIDTH_USAGE} ({@code config/ServerConfig.java:63};
 * {@code PlayerStateReportPolicy.java:23-28}). The port cannot read that server config, so the same bit travels in
 * {@link com.fox.ysmu.network.message.S2CServerConfig} (review RB-6).</li>
 * </ul>
 * Those read {@code NetworkHandler.isRemoteChannelPresent()}, which on 1.20 is {@code CHANNEL.isRemotePresent(...)} -
 * a per-connection query of whether the other side registered the mod's channel - plus
 * {@code ServerConfig.CAN_SWITCH_MODEL.get()}, a value Forge synced from the server's config file
 * ({@link Config#CAN_SWITCH_MODEL} carries the same reasoning).
 *
 * <h3>Why this class has to exist in the port</h3>
 * None of the ingredients exists on 1.7.10: FML has no channel-registry exchange to ask
 * ({@code NetworkRegistry} only has the outbound handlers, and {@code isRemotePresent} is a 1.13+ API), and
 * {@code Configuration} has no server-to-client exchange. So the port derives all three from the one login-time fact
 * it does have: the reply to {@link com.fox.ysmu.network.message.HandshakeMessage}. A YSMU server always answers that
 * handshake with {@link com.fox.ysmu.network.message.S2CServerConfig}, so "the reply arrived" is the port's
 * {@code isRemoteChannelPresent()} and the flags it carries are the server's {@code CanSwitchModel} and roaming-sync
 * switch.
 *
 * <h3>Why the world object, and not just a boolean</h3>
 * A connection-identity token should change when the client attaches to a different server. 1.7.10's closest one is
 * the {@link World} object: the client replaces it on every world load ({@code Minecraft.loadWorld:2249-2301}) and on
 * a dimension change ({@code NetHandlerPlayClient.handleRespawn:988-1000} builds a new {@code WorldClient}), and each
 * new world re-sends the handshake because {@code client/ClientEventHandler.onClientPlayerJoinWorld} answers
 * {@code EntityJoinWorldEvent} for the fresh {@code EntityClientPlayerMP}
 * ({@code ClientEventHandler.java:224-234}; {@code Minecraft.setDimensionAndSpawnPlayer:2393-2398} re-creates and
 * spawns that entity), so the reply - and this state - is re-established within a tick. Joining a server that does
 * not run YSMU instead leaves the state empty, which is exactly the "no remote channel" branch, and a flag left over
 * from a previous server is never consulted. The same world token gates the roaming-sync switch: between two worlds
 * (e.g. a dimension change before the fresh handshake answer) the switch reads as its default, {@code true}.
 */
public final class ServerSwitchState {

    /**
     * The world the last {@link com.fox.ysmu.network.message.S2CServerConfig} reply arrived for, or {@code null} when
     * no server has answered since the client last loaded a world.
     */
    private static volatile World receivedFor;

    /**
     * The server's roaming-sync switch, as delivered for {@link #receivedFor}. Default {@code true}: a server that
     * never answered has no roaming state to sync, and the "no remote channel" gate already stops the reporter.
     */
    private static volatile boolean roamingSyncEnabled = true;

    private ServerSwitchState() {}

    /** Kept for callers that only carry the switch flag; equivalent to {@code accept(canSwitchModel, true)}. */
    public static void accept(boolean canSwitchModel) {
        accept(canSwitchModel, true);
    }

    /**
     * Records the server's answer. Called on the client thread from
     * {@link com.fox.ysmu.network.message.S2CServerConfig.Handler}, so {@code Minecraft.theWorld} is this client's
     * current world.
     *
     * @param canSwitchModel     upstream {@code ServerConfig.CAN_SWITCH_MODEL}
     * @param roamingSyncEnabled upstream's "roaming is part of the report policy" bit
     *                           ({@code ServerHello.sync_roaming} = {@code !LOW_BANDWIDTH_USAGE})
     */
    public static void accept(boolean canSwitchModel, boolean roamingSyncEnabled) {
        Minecraft mc = Minecraft.getMinecraft();
        receivedFor = mc == null ? null : mc.theWorld;
        Config.CAN_SWITCH_MODEL = canSwitchModel;
        ServerSwitchState.roamingSyncEnabled = roamingSyncEnabled;
    }

    /**
     * The port's {@code NetworkHandler.isRemoteChannelPresent()}: true when the server this client is currently
     * attached to has answered the handshake - i.e. when the remote side runs YSMU.
     */
    public static boolean isRemoteChannelPresent() {
        World world = receivedFor;
        if (world == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.theWorld == world;
    }

    /**
     * The port's {@code ServerHello.sync_roaming} bit: whether the server wants roaming reports from this connection.
     * <p>
     * Reads {@code true} when no answer has arrived for the current world (default), so the value of a previous server
     * is never consulted and the immutable default is the permissive one - the "no remote channel" gate already keeps
     * the reporter quiet in that window. Upstream's reader is
     * {@code network/forge/SessionProtocolHandler.java:63-75} / {@code PlayerStateReportPolicy.java:23-28}; this is the
     * 1.7.10 carrier for the same server-side {@code LOW_BANDWIDTH_USAGE} value (review RB-6).
     */
    public static boolean isRoamingSyncEnabled() {
        World world = receivedFor;
        if (world == null) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.theWorld != world || roamingSyncEnabled;
    }
}
