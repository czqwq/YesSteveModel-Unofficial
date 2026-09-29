package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.Config;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class C2SVersionCheck17 implements IMessage {

    private String version = "";

    public C2SVersionCheck17() {}

    public C2SVersionCheck17(String version) {
        this.version = version == null ? "" : version;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.version = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.version);
    }

    public String getVersion() {
        return version;
    }

    public static class Handler implements IMessageHandler<C2SVersionCheck17, IMessage> {

        @Override
        public IMessage onMessage(C2SVersionCheck17 message, MessageContext ctx) {
            // N-10: guard the handler body (startSync iterates the server model index, which /ysm reload rebuilds).
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null || !Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) {
                    return null;
                }
                if (!NetworkHandler.PROTOCOL_VERSION.equals(message.version)) {
                    ysmu.LOG.warn(
                        "Skipping OpenYSM model sync for {} because protocol versions differ: client={}, server={}",
                        sender.getCommandSenderName(),
                        message.version,
                        NetworkHandler.PROTOCOL_VERSION);
                    // N-02 wiring ②: the 17 channel is unusable for this client, so release the gate's pending
                    // registration now and let it send the legacy sync (instead of waiting out the grace period).
                    ServerModelManager.onOpenYsmSyncFailed(sender);
                    return null;
                }
                // N-02 wiring ①: startSync returns false when it refuses to start (17 disabled server side or the
                // server_index key is missing). Only a started round may tell the gate that the handshake arrived,
                // otherwise a healthy session would still get a second legacy sync after the grace period.
                if (OpenYsmModelSyncServer.startSync(sender)) {
                    ServerModelManager.onOpenYsmSyncStarted(sender);
                } else {
                    // N-02 wiring ②: nothing was sent, so fall back immediately.
                    ServerModelManager.onOpenYsmSyncFailed(sender);
                }
            } catch (Exception e) {
                // No hook here on purpose: if startSync threw before onOpenYsmSyncStarted ran, the gate still has
                // handshakeStarted == false, so its own grace timer will fall back.
                ysmu.LOG.warn("Failed to start OpenYSM model sync", e);
            }
            return null;
        }
    }
}
