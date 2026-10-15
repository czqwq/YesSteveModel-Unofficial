package com.fox.ysmu.network.message;

import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.api.ModelAuthorization;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.util.UuidUtils;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SyncModelFiles implements IMessage {

    private static final int LEGACY_DIRECT_SEND_LIMIT = 1900 * 1024;
    /** N-08: upper bound for waiting on a writable channel before giving the send up. */
    private static final long BACKPRESSURE_TIMEOUT_MILLIS = 15_000L;
    private static final long BACKPRESSURE_POLL_MILLIS = 20L;

    private String[] md5Info;

    public SyncModelFiles() {}

    public SyncModelFiles(String[] md5Info) {
        this.md5Info = md5Info;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-06: the count comes from the client. Compare it with the readable bytes before allocating, and
        // soft-fail (drop the packet) instead of throwing: an exception here would be turned into a kick by
        // FMLProxyPacket.processPacket -> rejectHandshake.
        int count = buf.readInt();
        if (count < 0 || count > buf.readableBytes()) {
            ysmu.LOG.warn(
                "Ignoring malformed YSM model file list: count={}, readableBytes={}",
                count,
                buf.readableBytes());
            this.md5Info = null;
            return;
        }
        String[] parsed = new String[count];
        for (int i = 0; i < count; i++) {
            try {
                parsed[i] = ByteBufUtils.readUTF8String(buf);
            } catch (RuntimeException e) {
                ysmu.LOG.warn("Ignoring truncated YSM model file list at index {}: {}", i, e.toString());
                this.md5Info = null;
                return;
            }
        }
        this.md5Info = parsed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        String[] safeMd5Info = this.md5Info == null ? new String[0] : this.md5Info;
        buf.writeInt(safeMd5Info.length);
        for (String md5 : safeMd5Info) {
            ByteBufUtils.writeUTF8String(buf, md5);
        }
    }

    /**
     * Whether {@code player} may be handed {@code internalModelId}'s bytes: the one asset gate both sync channels
     * apply before a model leaves the server, i.e. the port of upstream's asset admission.
     *
     * <p><b>Upstream.</b> {@code network/dispatch/ServerAssetTransfers.accept(ModelChunkRequest):234-243} reads
     * {@code ServerConfig.RESTRICTED_AUTH} ({@code config/ServerConfig.java:13,56-57}) and refuses the chunk when
     * {@code model/session/server/ServerModelSession.canRequest(modelId, AssetRef.Kind.CHUNK, restrictedAuth):205-221}
     * says no, which is exactly: the switch is on, the model is neither {@code PUBLIC} nor granted, and it is not the
     * model an operator force-set on this player ({@code ignoresGrantsFor}, {@code :219-220}). With the switch off -
     * upstream's and this port's default - every request passes, and that is what lets every client render other
     * players' models. The presentation half of upstream's gate ({@code :299,447} still serves {@code PREVIEW} and
     * {@code ICON}) has no counterpart here: this port's presentation travels inside the model payload, so there is no
     * smaller unit to allow - recorded in {@code .agent/phase53-model-authorization.md}.</p>
     *
     * <p><b>Callers.</b> {@code SyncModelFiles.Handler.sendModelFiles} (the legacy cache file and its
     * {@code RequestLoadModel} reply) and {@code network/sync/OpenYsmModelSyncServer} (the 17 index and its packet-05
     * payload). {@code internalModelId} is the identity both of them hold: a
     * {@link com.fox.ysmu.model.ServerModelManager#CACHE_NAME_INFO} key in the legacy channel and
     * {@code OpenYsmSyncInfo.getModelId()} in the 17 channel - the internal id
     * {@code ModelAuthorization.allRegisteredModelIds} wraps in {@code ysmu:}, so the id is rebuilt the same way here
     * and {@code ServerModelManager.accessOf} resolves the model's winning source root.</p>
     */
    public static boolean mayTransferAssets(EntityPlayer player, String internalModelId) {
        ResourceLocation modelId = new ResourceLocation(ysmu.MODID, internalModelId);
        ExtendedModelInfo selection = ExtendedModelInfo.get(player);
        return mayTransferAssets(Config.RESTRICTED_AUTH, ModelAuthorization.grantsOf(player), internalModelId,
            selection != null && selection.ignoresGrantsFor(modelId));
    }

    /**
     * The same decision with the player-owned inputs already resolved, so a unit JVM can pin it without a server
     * ({@code network/ModelAssetGateTest}): {@code grants} is {@link ModelAuthorization#grantsOf(EntityPlayer)}, and
     * {@code null} means the grant table was never attached rather than "no grants" - see
     * {@code api/ModelAuthorization.canSelect} for why that must not refuse.
     */
    public static boolean mayTransferAssets(boolean restrictedAuth, @Nullable Set<ResourceLocation> grants,
        String internalModelId, boolean ignoresGrants) {
        return ModelAuthorization.canRequest(restrictedAuth, grants,
            internalModelId == null ? null : new ResourceLocation(ysmu.MODID, internalModelId), ignoresGrants);
    }

    public static class Handler implements IMessageHandler<SyncModelFiles, IMessage> {

        @Override
        public IMessage onMessage(SyncModelFiles message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender == null || message.md5Info == null) {
                return null;
            }
            String[] claimed = Arrays.copyOf(message.md5Info, message.md5Info.length);
            // N-01: the password packet and the RequestLoadModel packets that follow it must be sent from the
            // SAME thread, otherwise two threads race to enqueue their writes and the client can receive
            // RequestLoadModel first (its 20 s retry then papers over the reordering). One background task does
            // the whole round: password first (inline, no further submit), then the load requests and file sends.
            // The handler thread itself performs no file IO or encryption.
            // Deliberately NOT DeferredWork: deferring to the next tick would add a tick of latency and weaken
            // exactly the ordering this change establishes.
            try {
                ThreadTools.THREAD_POOL.submit(() -> syncToPlayer(claimed, sender));
            } catch (RuntimeException e) {
                ysmu.LOG.warn("Failed to schedule YSM model sync for " + sender.getCommandSenderName(), e);
            }
            return null;
        }

        private void syncToPlayer(String[] claimed, EntityPlayerMP sender) {
            try {
                if (!sendPassword(sender)) {
                    // Without the password every RequestLoadModel would burn its full retry budget and then drop
                    // the model, so skip the whole round instead.
                    return;
                }
                sendModelFiles(claimed, sender);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to start YSM legacy model sync for " + sender.getCommandSenderName(), e);
            }
        }

        private void sendModelFiles(String[] md5Info, EntityPlayerMP sender) {
            // One channel per model, which is the shape upstream has. A model the OpenYSM channel carries must not
            // also be delivered here: for a folder pack the two channels adapt the same source differently - the
            // baked side re-emits the JSON pretty printed with the pack's floats widened to doubles, 3,021,214 bytes
            // of geometry against 9,010,817 for wine_fox/05_magical - so the content-signature dedupe in
            // ClientModelManager cannot see that they are the same model. It registered twice per session, and each
            // re-registration parked the geometry it had just published, leaving the built-in default up for about a
            // second: the flicker.
            //
            // Only models the OpenYSM index really carries are skipped, and only while that channel is enabled.
            // Built-in models and every model OpenYsmFormat cannot produce a payload for stay legacy-only, which is
            // what ServerModelManager's sync notes require; with the protocol off nothing changes at all.
            boolean openYsmOwnsModels = Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL;
            // Authorization (RestrictedAuth): a restricted model this player may not receive is dropped here, before
            // the md5 list exists, so it is excluded from BOTH halves below - the RequestLoadModel reply ("you already
            // have it, load it": the client decrypts its own cached file and registers that model,
            // RequestLoadModel.java:202-221) and the file/chunk send. Upstream refuses the same moment
            // (ServerAssetTransfers.java:234-243 -> ServerModelSession.canRequest:205-221); with the switch off, the
            // default, the predicate is true for every model and this round is byte-identical to before.
            // D-01 (network侧): filter through the shared "well-formed cache file name" predicate and drop null
            // md5 entries. A null md5 would otherwise reach CACHE_SERVER.resolve(null) and NPE inside the send task.
            Collection<String> cache = CACHE_NAME_INFO.entrySet()
                .stream()
                .filter(entry -> mayTransferAssets(sender, entry.getKey()))
                .filter(entry -> !(openYsmOwnsModels && OPEN_YSM_SYNC_INFO.containsKey(entry.getKey())))
                .map(
                    entry -> entry.getValue()
                        .getMd5())
                .filter(ServerModelInfo::hasWellFormedCacheFileName)
                .collect(Collectors.toList());
            List<String> output = Lists.newArrayList(cache);
            for (String md5 : md5Info) {
                if (md5 != null && cache.contains(md5)) {
                    output.remove(md5);
                    NetworkHandler.sendToClientPlayer(new RequestLoadModel(md5), sender);
                }
            }
            for (String md5 : output) {
                File modelFile = CACHE_SERVER.resolve(md5)
                    .toFile();
                ThreadTools.THREAD_POOL.submit(() -> sendModelFile(md5, modelFile, sender));
            }
        }

        private void sendModelFile(String md5, File modelFile, EntityPlayerMP sender) {
            try {
                long fileLength = modelFile.length();
                if (fileLength > Integer.MAX_VALUE) {
                    ysmu.LOG.warn(
                        "Skipping YSM legacy model cache file {} because it is too large: {} bytes",
                        md5,
                        fileLength);
                    return;
                }
                if (fileLength <= LEGACY_DIRECT_SEND_LIMIT) {
                    byte[] data = FileUtils.readFileToByteArray(modelFile);
                    if (!waitForWritable(sender)) {
                        return;
                    }
                    NetworkHandler.sendToClientPlayer(new SendModelFile(data), sender);
                    throttle(data.length);
                    return;
                }

                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                    ysmu.LOG.info(
                        "Sending large YSM legacy model cache {} in chunks: size={} bytes, chunk={} bytes",
                        md5,
                        fileLength,
                        SendModelFileChunk.MAX_CHUNK_BYTES);
                }
                int offset = 0;
                byte[] buffer = new byte[SendModelFileChunk.MAX_CHUNK_BYTES];
                try (InputStream input = FileUtils.openInputStream(modelFile)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read == 0) {
                            continue;
                        }
                        byte[] chunk = Arrays.copyOf(buffer, read);
                        if (!waitForWritable(sender)) {
                            return;
                        }
                        NetworkHandler
                            .sendToClientPlayer(new SendModelFileChunk(md5, (int) fileLength, offset, chunk), sender);
                        throttle(read);
                        offset += read;
                    }
                }
            } catch (IOException e) {
                ysmu.LOG.warn("Failed to read YSM server model cache file " + md5, e);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                ysmu.LOG.warn("Interrupted while sending YSM server model cache file " + md5);
            }
        }

        /**
         * N-08: wait until the outbound buffer accepts writes again. Gives up when the connection is gone or the
         * wait exceeds {@link #BACKPRESSURE_TIMEOUT_MILLIS}, so a stalled client cannot pin a pool thread forever.
         */
        private boolean waitForWritable(EntityPlayerMP sender) throws InterruptedException {
            long deadline = System.currentTimeMillis() + BACKPRESSURE_TIMEOUT_MILLIS;
            while (!NetworkHandler.isChannelWritable(sender)) {
                if (!NetworkHandler.isChannelOpen(sender) || System.currentTimeMillis() > deadline) {
                    ysmu.LOG.warn(
                        "Aborting YSM legacy model send to {} because the connection is no longer writable",
                        sender.getCommandSenderName());
                    return false;
                }
                Thread.sleep(BACKPRESSURE_POLL_MILLIS);
            }
            return true;
        }

        /**
         * N-08: the legacy path honours {@link Config#BANDWIDTH_LIMIT} like the 17-protocol path does. The default
         * (0 with LOW_BANDWIDTH_USAGE off) means no throttling at all, i.e. the previous behaviour.
         */
        private void throttle(int bytes) throws InterruptedException {
            int bandwidthLimit = Config.BANDWIDTH_LIMIT;
            if (Config.LOW_BANDWIDTH_USAGE && bandwidthLimit <= 0) {
                bandwidthLimit = 64 * 1024;
            }
            if (bandwidthLimit <= 0) {
                return;
            }
            long sleepMillis = Math.max(1L, bytes * 1000L / bandwidthLimit);
            Thread.sleep(sleepMillis);
        }

        /**
         * N-01: sends the password on the calling thread (no {@code THREAD_POOL.submit} in here) and returns
         * whether it was written. The caller runs inside one pool task and sends RequestLoadModel only after this
         * returns true, so both writes are issued by the same thread and reach the channel in order.
         */
        private boolean sendPassword(EntityPlayerMP sender) {
            try {
                byte[] password = FileUtils.readFileToByteArray(PASSWORD_FILE.toFile());
                UUID playerId = sender.getUniqueID();
                byte[] uuid = UuidUtils.asBytes(playerId);
                byte[] output = EncryptTools.encryptPassword(uuid, password);
                // The client must receive the password blob before RequestLoadModel can decrypt cached files.
                NetworkHandler.sendToClientPlayer(new SendModelPassword(playerId, output), sender);
                return true;
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to send YSM model sync password to " + sender.getCommandSenderName(), e);
                return false;
            }
        }
    }
}
