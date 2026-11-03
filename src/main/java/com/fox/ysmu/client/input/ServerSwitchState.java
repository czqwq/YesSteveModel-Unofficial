package com.fox.ysmu.client.input;

import com.fox.ysmu.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/**
 * The client's copy of the two facts upstream's {@code PlayerModelScreenKey} and {@code AnimationRouletteKey} read:
 * whether a remote YSM channel is present, and the server's {@code CanSwitchModel} value.
 *
 * <h3>The two upstream expressions this class serves</h3>
 * <ul>
 * <li>{@code client/input/PlayerModelScreenKey.java:41-44} - with a remote channel present and switching disabled,
 * the model-screen key opens {@code ConfigScreen} instead of {@code PlayerModelScreen};</li>
 * <li>{@code client/input/AnimationRouletteKey.java:45-46} - the roulette is allowed when there is no remote channel
 * <em>or</em> switching is allowed.</li>
 * </ul>
 * Both read {@code NetworkHandler.isRemoteChannelPresent()}, which on 1.20 is {@code CHANNEL.isRemotePresent(...)} -
 * a per-connection query of whether the other side registered the mod's channel - plus
 * {@code ServerConfig.CAN_SWITCH_MODEL.get()}, a value Forge synced from the server's config file
 * ({@link Config#CAN_SWITCH_MODEL} carries the same reasoning).
 *
 * <h3>Why this class has to exist in the port</h3>
 * Neither ingredient exists on 1.7.10: FML has no channel-registry exchange to ask
 * ({@code NetworkRegistry} only has the outbound handlers, and {@code isRemotePresent} is a 1.13+ API), and
 * {@code Configuration} has no server-to-client exchange. So the port derives both from the one login-time fact it
 * does have: the reply to {@link com.fox.ysmu.network.message.HandshakeMessage}. A YSMU server always answers that
 * handshake with {@link com.fox.ysmu.network.message.S2CServerConfig}, so "the reply arrived" is the port's
 * {@code isRemoteChannelPresent()} and the flag it carries is the server's {@code CanSwitchModel}.
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
 * from a previous server is never consulted.
 */
public final class ServerSwitchState {

    /**
     * The world the last {@link com.fox.ysmu.network.message.S2CServerConfig} reply arrived for, or {@code null} when
     * no server has answered since the client last loaded a world.
     */
    private static volatile World receivedFor;

    private ServerSwitchState() {}

    /**
     * Records the server's answer. Called on the client thread from
     * {@link com.fox.ysmu.network.message.S2CServerConfig.Handler}, so {@code Minecraft.theWorld} is this client's
     * current world.
     */
    public static void accept(boolean canSwitchModel) {
        Minecraft mc = Minecraft.getMinecraft();
        receivedFor = mc == null ? null : mc.theWorld;
        Config.CAN_SWITCH_MODEL = canSwitchModel;
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
}
