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
 * Server to client: the server-side switches a client has to know about. Since phase78 there are two:
 * <ul>
 * <li>{@link Config#CAN_SWITCH_MODEL} - the flag the model-screen key and the roulette key read
 * ({@code client/input/PlayerModelScreenKey.java:41-44}, {@code client/input/AnimationRouletteKey.java:45-46});</li>
 * <li>{@link Config#LOW_BANDWIDTH_USAGE} - the server's roaming-report switch. Upstream registers
 * {@code ServerConfig.LOW_BANDWIDTH_USAGE} as a {@code ModConfig.Type.SERVER}
 * ({@code tmp/YesSteveModel-dev-1.20/.../config/ServerConfig.java:63}) and the client learns it from
 * {@code ServerHello.sync_roaming} ({@code network/forge/SessionProtocolHandler.java:63-75}); the client-side reporter
 * then skips the roaming segment. 1.7.10 has neither a server config sync nor a session hello, so this packet carries
 * the same fact (inverted: {@code roamingSyncEnabled == !LOW_BANDWIDTH_USAGE}). Review RB-6
 * ({@code .agent/phase78-review.md}): before this field the port let the <em>client's own</em> config file stop
 * roaming persistence, which is the wrong side of the wire.</li>
 * </ul>
 *
 * <h3>Wire layout</h3>
 * id 39, unchanged: two booleans, {@code canSwitchModel} then {@code roamingSyncEnabled} (phase78 review RB-6). The
 * second byte is part of {@code NETWORK_PROTOCOL} 13's batch - that version is introduced by the same uncommitted
 * phase, not shipped - so the layout grows within 13 rather than under 12; a peer from before phase78 is already
 * rejected by the version-13 handshake fence, and {@link #fromBytes} additionally treats a missing byte as
 * "roaming enabled" so a truncated payload can never flip the switch to false.
 *
 * <h3>What upstream this stands in for</h3>
 * Upstream has no such packet, and that is the point: it registers {@code ServerConfig} as
 * {@code ModConfig.Type.SERVER} ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/YesSteveModel.java:54})
 * and Forge ships the server config file to each client on login, which is why
 * {@code client/input/PlayerModelScreenKey.java:41} and {@code client/input/AnimationRouletteKey.java:46} can read a
 * <em>server</em> value locally. 1.7.10's {@code Configuration} has no exchange of any kind, so the port's minimal
 * slice of that mechanism is this packet: the flags those consumers read, sent at the same moment Forge's
 * config sync happens (login), and written into the same place the synced file would have written them
 * ({@link Config#CAN_SWITCH_MODEL}; {@code ServerSwitchState} carries the roaming switch beside it so the client's
 * own config file stays a fallback, not the authority). The transport is port-specific (1.7.10 only); the behaviour it
 * carries is upstream's exactly.
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
    /** {@code true} = the server allows roaming reports ({@code !ServerConfig.LOW_BANDWIDTH_USAGE}). */
    private boolean roamingSyncEnabled = true;

    public S2CServerConfig() {}

    /** 旧构造器（phase78 评审 RB-6 之前的唯一字段）；roaming 按上游默认允许。 */
    public S2CServerConfig(boolean canSwitchModel) {
        this(canSwitchModel, true);
    }

    public S2CServerConfig(boolean canSwitchModel, boolean roamingSyncEnabled) {
        this.canSwitchModel = canSwitchModel;
        this.roamingSyncEnabled = roamingSyncEnabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10 (NetworkHandler.java:98-107): a decoder that throws is turned into a disconnect by FML, so a truncated
        // payload is read as the upstream default (true) instead. The second byte is the phase78 addition: a peer that
        // sends only the old one-boolean layout (impossible across the protocol-13 fence, but the rule is soft-fail,
        // not "trust the peer") keeps the default "roaming enabled" rather than losing the switch to false.
        try {
            this.canSwitchModel = buf.isReadable() ? buf.readBoolean() : true;
            this.roamingSyncEnabled = buf.isReadable() ? buf.readBoolean() : true;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM server-config packet: {}", e.toString());
            this.canSwitchModel = true;
            this.roamingSyncEnabled = true;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.canSwitchModel);
        buf.writeBoolean(this.roamingSyncEnabled);
    }

    /**
     * Whether the server allows model switching. The reference declares no getter; this one exists so the
     * payload can be pinned by a round-trip test, the same reason {@code SetWelcomeConfig.isShow()}
     * carries its own.
     */
    public boolean isCanSwitchModel() {
        return this.canSwitchModel;
    }

    /** Whether the server allows roaming reports ({@code !ServerConfig.LOW_BANDWIDTH_USAGE}, review RB-6). */
    public boolean isRoamingSyncEnabled() {
        return this.roamingSyncEnabled;
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
                boolean roamingSyncEnabled = message.roamingSyncEnabled;
                // The client half lives in client/input/ServerSwitchState (it reads Minecraft.theWorld), so the
                // handler only does the client-thread hop and one static call with plain booleans. That lambda body
                // is the only reference to the client class, so instantiating this handler on a dedicated server
                // never loads it.
                DeferredWork.client(() -> ServerSwitchState.accept(canSwitchModel, roamingSyncEnabled));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle YSM server-config packet", e);
            }
            return null;
        }
    }
}
