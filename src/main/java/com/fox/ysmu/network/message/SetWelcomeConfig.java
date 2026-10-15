package com.fox.ysmu.network.message;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: toggle {@link Config#SHOW_WELCOME_MESSAGE}. Sent by {@code /ysm welcome on|off} to the issuing
 * player.
 * <p>
 * Ported from the reference branch's {@code network/message/SetWelcomeConfig.java:16-45}. The consumer here is the
 * same as the reference's - the client writes the flag and saves the config - but note that <b>nothing in this tree
 * reads the flag yet</b>: the reference's reader is its join-world welcome block
 * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/ClientEventHandler.java:193}, inside
 * {@code onClientPlayerJoinWorld}), and this tree has no such block (no {@code SHOW_WELCOME_MESSAGE} reader outside
 * {@code Config.java:125} / {@code :336}). {@code /ysm welcome off} therefore persists the setting and takes effect
 * only once that reader exists; see {@code .agent/phase39-ysm-subcommands.md}.
 */
public class SetWelcomeConfig implements IMessage {

    private boolean show = true;

    public SetWelcomeConfig() {}

    public SetWelcomeConfig(boolean show) {
        this.show = show;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10 (NetworkHandler.java:47-52): a decoder that throws disconnects the player, so a truncated payload
        // (which cannot select anything meaningful) is read as the enabled default instead.
        this.show = buf.isReadable() ? buf.readBoolean() : true;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(show);
    }

    public static class Handler implements IMessageHandler<SetWelcomeConfig, IMessage> {

        @Override
        public IMessage onMessage(SetWelcomeConfig message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            // No client-thread hop: this touches only the common-side Config and its file, the way the reference
            // does (SetWelcomeConfig.java:38-42).
            try {
                Config.SHOW_WELCOME_MESSAGE = message.show;
                Config.save();
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM welcome-config update", e);
            }
            return null;
        }
    }

    /** Whether the welcome message should be shown. The reference declares no getter; this one exists so the
     *  single-boolean payload can be pinned by a round-trip test (the same reason {@code S2CSetDebugOverlay} carries
     *  its port-added {@code getMode()}). */
    public boolean isShow() {
        return show;
    }
}
