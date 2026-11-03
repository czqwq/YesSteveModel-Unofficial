package com.fox.ysmu.network.message;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.input.ServerSwitchState;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the server-side switches a client has to know about. Today that is one flag,
 * {@link Config#CAN_SWITCH_MODEL}.
 *
 * <h3>What upstream this stands in for</h3>
 * Upstream has no such packet, and that is the point: it registers {@code ServerConfig} as
 * {@code ModConfig.Type.SERVER} ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/YesSteveModel.java:54})
 * and Forge ships the server config file to each client on login, which is why
 * {@code client/input/PlayerModelScreenKey.java:41} and {@code client/input/AnimationRouletteKey.java:46} can read a
 * <em>server</em> value locally. 1.7.10's {@code Configuration} has no exchange of any kind, so the port's minimal
 * slice of that mechanism is this packet: the one flag those two consumers read, sent at the same moment Forge's
 * config sync happens (login), and written into the same place the synced file would have written it
 * ({@link Config#CAN_SWITCH_MODEL}). The transport is port-specific (1.7.10 only); the behaviour it carries is
 * upstream's exactly.
 *
 * <h3>Why the handshake answers it</h3>
 * {@link HandshakeMessage} is the client's one "I run YSMU and speak this wire format" statement, sent once per join
 * ({@code client/ClientEventHandler.onClientPlayerJoinWorld:234}) and once more after every world load or dimension
 * change, because both create a fresh local player entity. A matching handshake therefore also means "a YSMU peer is
 * on the other end of this connection", which is the port's stand-in for upstream's
 * {@code NetworkHandler.isRemoteChannelPresent()} - see {@code client/input/ServerSwitchState}.
 */
public class S2CServerConfig implements IMessage {

    private boolean canSwitchModel = true;

    public S2CServerConfig() {}

    public S2CServerConfig(boolean canSwitchModel) {
        this.canSwitchModel = canSwitchModel;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10 (NetworkHandler.java:98-107): a decoder that throws is turned into a disconnect by FML, so a truncated
        // payload is read as the upstream default (true) instead.
        try {
            this.canSwitchModel = buf.isReadable() ? buf.readBoolean() : true;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM server-config packet: {}", e.toString());
            this.canSwitchModel = true;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.canSwitchModel);
    }

    /**
     * Whether the server allows model switching. The reference declares no getter; this one exists so the
     * single-boolean payload can be pinned by a round-trip test, the same reason {@code SetWelcomeConfig.isShow()}
     * carries its own.
     */
    public boolean isCanSwitchModel() {
        return this.canSwitchModel;
    }

    public static class Handler implements IMessageHandler<S2CServerConfig, IMessage> {

        @Override
        public IMessage onMessage(S2CServerConfig message, MessageContext ctx) {
            // The server instantiates every registered handler; this one is client-only work.
            if (ctx.side == Side.SERVER) {
                return null;
            }
            try {
                boolean canSwitchModel = message.canSwitchModel;
                // The client half lives in client/input/ServerSwitchState (it reads Minecraft.theWorld), so the
                // handler only does the client-thread hop and one static call with a plain boolean. That lambda body
                // is the only reference to the client class, so instantiating this handler on a dedicated server
                // never loads it.
                DeferredWork.client(() -> ServerSwitchState.accept(canSwitchModel));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle YSM server-config packet", e);
            }
            return null;
        }
    }
}
