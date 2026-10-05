package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.Config;
import com.fox.ysmu.compat.LocalAssetProvider;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: sync the high-version game asset path and version. Sent by
 * {@code /ysm setgamepath <path> [version]} to the issuing player; the client writes both into {@link Config} and
 * rebuilds {@link LocalAssetProvider}.
 * <p>
 * Ported from the reference branch's {@code network/message/SyncGamePath.java:17-80}, with two port-specific
 * differences, both stated at their site: the payload is validated before it is decoded (the repository's N-10 rule,
 * {@code NetworkHandler.java:47-52}), and the handler body runs on the client thread.
 */
public class SyncGamePath implements IMessage {

    private String gamePath = "";
    private String assetVersion = "";

    public SyncGamePath() {}

    public SyncGamePath(String gamePath, String assetVersion) {
        this.gamePath = gamePath == null ? "" : gamePath;
        this.assetVersion = assetVersion == null ? "" : assetVersion;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10 (NetworkHandler.java:47-52): a decoder that throws is turned into a disconnect by
        // FMLProxyPacket.processPacket, so a truncated payload is dropped into empty strings instead.
        this.gamePath = readString(buf);
        this.assetVersion = readString(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeString(buf, gamePath);
        writeString(buf, assetVersion);
    }

    /**
     * The reference's framing: a 4-byte big-endian byte length followed by UTF-8 bytes ({@code SyncGamePath.java:30-51}).
     * Kept rather than replaced by {@code ByteBufUtils}, because the layout is what the two sides agree on.
     */
    private static String readString(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return "";
        }
        int length = buf.readInt();
        if (length < 0 || length > buf.readableBytes()) {
            return "";
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public static class Handler implements IMessageHandler<SyncGamePath, IMessage> {

        @Override
        public IMessage onMessage(SyncGamePath message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            try {
                // The reference applies this inline (SyncGamePath.java:56-70). The port hops to the client thread
                // first, for the same reason the other client-side handlers here do
                // (S2CPlaySound.java:59-60, S2CSetDebugOverlay.java:59-60): LocalAssetProvider.reset() ends in
                // ParticleTextureManager.clearCache() -> TextureUtil.deleteTexture, i.e. GL calls, and
                // Config.save() is a file write; neither belongs on a Netty thread.
                Minecraft.getMinecraft()
                    .func_152344_a(() -> apply(message));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM game-path update", e);
            }
            return null;
        }

        private static void apply(SyncGamePath message) {
            try {
                Config.HIGH_VERSION_GAME_PATH = message.gamePath;
                Config.HIGH_VERSION_ASSET_VERSION = message.assetVersion;
                Config.save();
                // The consumer of this packet: the provider caches the parsed asset index, the version jar and the
                // particle textures, so a new path has to drop all of it before the next access re-reads the config.
                // Reference SyncGamePath.java:63; LocalAssetProvider.reset() is compat/LocalAssetProvider.java:243.
                LocalAssetProvider.reset();
                if (Config.DEBUG_SOUND) {
                    ysmu.LOG.info(
                        "[YSMU-SOUND] Game path updated via command: '{}' v{}",
                        message.gamePath,
                        message.assetVersion);
                }
            } catch (Throwable failure) {
                ysmu.LOG.warn("Failed to apply the YSM game-path update", failure);
            }
        }
    }

    public String getGamePath() {
        return gamePath;
    }

    public String getAssetVersion() {
        return assetVersion;
    }
}
