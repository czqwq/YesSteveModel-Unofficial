package com.fox.ysmu.network.message;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.util.UuidUtils;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

public class RequestLoadModel implements IMessage {

    /**
     * N-12: waiting for the password is event driven now. It used to be 40 x 500 ms of {@code Thread.sleep}
     * inside the shared pool, which pinned a worker for up to 20 s per model (the pool is effectively single
     * threaded, see ThreadTools) and stalled the 17-protocol traffic that shares it. The retry semantics are kept
     * (wait for the password, bounded, then drop the model with a warning) but the waiting itself happens on the
     * client tick: 100 ticks ~ 5 s and it occupies no pool thread.
     */
    private static final int PASSWORD_WAIT_TICKS = 100;

    /** Cache file names queued while the password is still missing (LinkedHashSet: ordered, no duplicates). */
    private static final Set<String> PENDING_LOADS = new LinkedHashSet<>();
    private static boolean waitingForPassword;

    private String fileName;

    public RequestLoadModel() {}

    public RequestLoadModel(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.fileName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.fileName);
    }

    public static class Handler implements IMessageHandler<RequestLoadModel, IMessage> {

        @Override
        public IMessage onMessage(RequestLoadModel message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                try {
                    ClientModelManager.rememberCachedModel(message.fileName);
                    loadModel(message.fileName);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to handle YSM cache load request", e);
                }
            }
            return null;
        }
    }

    /**
     * Client-side entry point: queue a cached file for decryption and registration. The actual read/decrypt/parse
     * runs on the background pool, never on the packet handler thread (N-04).
     */
    @SideOnly(Side.CLIENT)
    public static void loadModel(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        synchronized (PENDING_LOADS) {
            PENDING_LOADS.add(fileName);
        }
        if (passwordAvailable()) {
            drainPendingLoads();
        } else {
            schedulePasswordWait();
        }
    }

    /** N-12: called by SendModelPassword when the blob arrives, so queued loads continue instead of idling. */
    @SideOnly(Side.CLIENT)
    public static void onPasswordAvailable() {
        waitingForPassword = false;
        drainPendingLoads();
    }

    /** Drops queued loads, for example when the client leaves the world or starts a new sync round. */
    @SideOnly(Side.CLIENT)
    public static void clearPendingLoads() {
        waitingForPassword = false;
        synchronized (PENDING_LOADS) {
            PENDING_LOADS.clear();
        }
    }

    private static boolean passwordAvailable() {
        return ClientModelManager.PASSWORD != null && ClientModelManager.PASSWORD_UUID != null;
    }

    private static void drainPendingLoads() {
        List<String> batch;
        synchronized (PENDING_LOADS) {
            if (PENDING_LOADS.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(PENDING_LOADS);
            PENDING_LOADS.clear();
        }
        for (String fileName : batch) {
            ThreadTools.THREAD_POOL.submit(() -> loadModelNow(fileName));
        }
    }

    private static void schedulePasswordWait() {
        if (waitingForPassword) {
            return;
        }
        waitingForPassword = true;
        schedulePasswordWaitTick(0);
    }

    private static void schedulePasswordWaitTick(int elapsedTicks) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                if (!waitingForPassword) {
                    return;
                }
                if (passwordAvailable()) {
                    waitingForPassword = false;
                    drainPendingLoads();
                    return;
                }
                if (Minecraft.getMinecraft().thePlayer == null) {
                    waitingForPassword = false;
                    dropPendingLoads("the client left the world");
                    return;
                }
                if (elapsedTicks >= PASSWORD_WAIT_TICKS) {
                    waitingForPassword = false;
                    dropPendingLoads("timed out waiting for the YSM model password");
                    return;
                }
                schedulePasswordWaitTick(elapsedTicks + 1);
            });
    }

    private static void dropPendingLoads(String reason) {
        List<String> dropped;
        synchronized (PENDING_LOADS) {
            dropped = new ArrayList<>(PENDING_LOADS);
            PENDING_LOADS.clear();
        }
        if (!dropped.isEmpty()) {
            ysmu.LOG.warn("Dropping {} queued YSM cache load(s) because {}", dropped.size(), reason);
        }
    }

    /** Pool worker: read the cache file, decrypt it and hand the ModelData back to the client thread. */
    @SideOnly(Side.CLIENT)
    private static void loadModelNow(String fileName) {
        try {
            byte[] password = ClientModelManager.PASSWORD;
            UUID passwordUuid = ClientModelManager.PASSWORD_UUID;
            if (password == null || passwordUuid == null) {
                ysmu.LOG.warn("Cannot load YSM model cache file {} without the model password", fileName);
                return;
            }
            if (Minecraft.getMinecraft().thePlayer == null) {
                return;
            }
            File modelFile = ServerModelManager.CACHE_CLIENT.resolve(fileName)
                .toFile();
            byte[] fileBytes = FileUtils.readFileToByteArray(modelFile);
            ModelData data = EncryptTools.decryptModel(UuidUtils.asBytes(passwordUuid), password, fileBytes);
            if (data != null) {
                // Resource registration always returns to the client thread. The generation this work started on
                // travels with it: reading and decrypting a cache file takes long enough to outlive a disconnect and
                // the join that follows, and registering after that would put the previous server's model into the
                // new server's catalogue, where a build requested from it carries the new generation and therefore
                // passes every guard.
                long generation = ClientModelManager.currentGeneration();
                Minecraft.getMinecraft()
                    .func_152344_a(() -> {
                        if (generation != ClientModelManager.currentGeneration()) {
                            return;
                        }
                        ClientModelManager.registerAll(data);
                    });
            } else {
                ysmu.LOG.warn("Failed to decrypt YSM model cache file {}", fileName);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load YSM model cache file " + fileName, e);
        }
    }
}
