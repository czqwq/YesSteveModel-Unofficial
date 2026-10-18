package com.fox.ysmu.client.upload;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.model.upload.ModelFileValidator;
import com.fox.ysmu.model.upload.ModelUploadTarget;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SUploadModelFile;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

/**
 * 客户端的模型上传执行器（phase55）。
 *
 * <p>两个入口共用这一条路径：服务端在玩家执行 {@code /ysm upload <auth|custom> <path>} 后发来
 * {@link com.fox.ysmu.network.message.S2CRequestModelUpload}；或者玩家在 {@code /ysm manage} 管理 GUI 里
 * 选好文件点确认（{@link #startUpload}）。两者都在**后台线程**读取玩家本机的 {@code path}、用与服务端相同的
 * {@link ModelFileValidator} 预检、再按 {@link C2SUploadModelFile#MAX_CHUNK_BYTES} 大小分片经
 * {@link C2SUploadModelFile} 发回服务端。服务端会独立再校验一次并写盘——客户端这一次只为省带宽与快速报错，
 * 不是信任边界。</p>
 *
 * <p>这个类替代了 phase7 `R-02` 记下的"未实现占位"。当时要求实现时补上的两点现在都在：</p>
 * <ol>
 * <li><b>线程</b>：读盘/解析/发送都在 {@link ThreadTools#THREAD_POOL} 的后台任务里；任何聊天/游戏状态改动都
 * 经 {@link DeferredWork#client(Runnable)} 回客户端线程。</li>
 * <li><b>断线</b>：{@code ClientEventHandler.onClientDisconnect} 调 {@link #reset()}，断开后不会留下
 * "正在上传"的锁；后台任务在每片发送前检查连接是否还在。</li>
 * </ol>
 *
 * <p><b>失败时客户端后台日志打 ERROR</b>（所有者要求：文件只在客户端时由客户端后台读取并上传，失败才在客户端
 * 日志打 ERROR），同时聊天栏给玩家一行可读提示。成功不额外提示——服务端落盘后会回执最终文件名，并发送
 * {@code CompleteFeedback}（S2C id 12）让管理 GUI 离开"处理中"状态、重新拉取列表。</p>
 */
public final class UploadManager {

    /** 管理 GUI 显示的上传状态（Lgeacy 的 {@code UploadManager.Statue} 移植）。 */
    public enum Statue {
        /** 文件正在读取/校验/发送；服务端回 {@code CompleteFeedback} 后变回 {@link #FULFILL}。 */
        PROCESSING,
        /** 空闲：没有上传在跑，GUI 可以再次确认。 */
        FULFILL
    }

    /** 当前上传状态；GUI 只读它来禁用/启用确认按钮与显示状态。 */
    public static volatile Statue STATUE = Statue.FULFILL;
    /** GUI 选中/手填的本地文件路径；上传开始后保留到服务端回执。 */
    public static volatile String FILE_PATH = null;

    /** 一次只处理一个上传请求；命令与 GUI 都是串行触发的，重复请求只可能是异常情况。 */
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    /**
     * 建议文件名的字符上限。
     * <p>磁盘文件名可能很长（尤其含非 ASCII 字符时 UTF-8 字节数会超过包字段上限
     * {@code C2SUploadModelFile.MAX_FILE_NAME_BYTES}），客户端先截断到远小于上限的字符数；服务端还会再清洗
     * 一次，所以截断不会改变落盘名的合法性。
     * </p>
     */
    private static final int MAX_PROPOSED_NAME_CHARS = 120;

    private UploadManager() {}

    /**
     * 服务端请求读取本机文件（{@code S2CRequestModelUpload.Handler} 在 Netty 线程调用）。
     * 只做入口校验并转给 {@link #startUpload}。
     */
    public static void onUploadRequested(int targetWireId, String path) {
        ModelUploadTarget target = ModelUploadTarget.fromWireId(targetWireId);
        if (target == null || path == null || path.isEmpty()) {
            ysmu.LOG.warn("[YSMU-UPLOAD] Ignoring an upload request with target {} and path '{}'", targetWireId, path);
            return;
        }
        startUpload(target, path);
    }

    /**
     * 从管理 GUI 或服务端请求启动一次上传。
     *
     * @return 是否已开始；已有上传在跑或路径为空时返回 {@code false}（GUI 保持原状态并提示）
     */
    public static boolean startUpload(ModelUploadTarget target, String path) {
        if (target == null || path == null || path.trim()
            .isEmpty()) {
            return false;
        }
        if (!BUSY.compareAndSet(false, true)) {
            ysmu.LOG.warn("[YSMU-UPLOAD] Ignoring an upload request for '{}' while another upload is running", path);
            chat("commands.yes_steve_model.upload.client.busy");
            return false;
        }
        FILE_PATH = path;
        STATUE = Statue.PROCESSING;
        try {
            ThreadTools.THREAD_POOL.submit(() -> {
                boolean sent = false;
                try {
                    sent = performUpload(target, path);
                } catch (Throwable t) {
                    ysmu.LOG.error("[YSMU-UPLOAD] Unexpected failure while uploading '{}'", path, t);
                    chat("commands.yes_steve_model.upload.client.read_failed", path);
                } finally {
                    if (!sent) {
                        // 没发出去（读/校验/连接失败）：没有服务端回执会来，GUI 必须自己解锁。
                        STATUE = Statue.FULFILL;
                        FILE_PATH = null;
                    }
                    BUSY.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            BUSY.set(false);
            STATUE = Statue.FULFILL;
            FILE_PATH = null;
            ysmu.LOG.error("[YSMU-UPLOAD] Could not schedule the upload task for '{}'", path, rejected);
            chat("commands.yes_steve_model.upload.client.read_failed", path);
        }
        return true;
    }

    /**
     * 读取 + 完整校验 + 分片发送。
     *
     * @return 分片是否全部发出；{@code false} 表示本地失败（调用方负责复位 GUI 状态）
     */
    private static boolean performUpload(ModelUploadTarget target, String path) {
        File file = new File(path);
        if (!file.isFile()) {
            fail(path, "the path is not a regular file");
            chat("commands.yes_steve_model.upload.client.read_failed", path);
            return false;
        }
        long size;
        try {
            size = Files.size(file.toPath());
        } catch (Exception e) {
            fail(path, "cannot read the file size: " + e);
            chat("commands.yes_steve_model.upload.client.read_failed", path);
            return false;
        }
        long maxUploadBytes = ModelFileValidator.maxUploadBytes();
        if (size > maxUploadBytes) {
            fail(path, "the file is " + size + " bytes, above the " + maxUploadBytes + " byte limit");
            chat(
                "commands.yes_steve_model.upload.client.too_large",
                path,
                String.valueOf(ModelFileValidator.maxUploadMb()));
            return false;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            fail(path, "cannot read the file: " + e);
            chat("commands.yes_steve_model.upload.client.read_failed", path);
            return false;
        }
        if (data.length > maxUploadBytes) {
            // 检查大小与读文件之间的竞态：文件在两步之间被改大。
            fail(path, "the file grew to " + data.length + " bytes while being read");
            chat(
                "commands.yes_steve_model.upload.client.too_large",
                path,
                String.valueOf(ModelFileValidator.maxUploadMb()));
            return false;
        }
        ModelFileValidator.Result validation = ModelFileValidator.validate(data);
        if (!validation.isValid()) {
            fail(path, "not a valid .ysm model: " + ModelFileValidator.describe(validation));
            chat(
                "commands.yes_steve_model.upload.client.invalid",
                path,
                validation.reason()
                    .name());
            return false;
        }
        if (!sendChunks(target, proposedFileName(file), data)) {
            // sendChunks 已经把具体原因写进日志；连接消失时不再额外往聊天栏打字。
            chat("commands.yes_steve_model.upload.client.read_failed", path);
            return false;
        }
        // 分片已全部发出；状态保持 PROCESSING，等服务端的 CompleteFeedback（id 12）解锁 GUI。
        return true;
    }

    /**
     * 顺序发送所有分片。
     *
     * @return 是否全部发出；连接在发送过程中消失时返回 {@code false}
     */
    private static boolean sendChunks(ModelUploadTarget target, String fileName, byte[] data) {
        int totalLength = data.length;
        int chunkIndex = 0;
        for (int offset = 0; offset < totalLength; offset += C2SUploadModelFile.MAX_CHUNK_BYTES) {
            int length = Math.min(C2SUploadModelFile.MAX_CHUNK_BYTES, totalLength - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(data, offset, chunk, 0, length);
            try {
                NetworkHandler.CHANNEL.sendToServer(new C2SUploadModelFile(target, fileName, totalLength, offset, chunk));
            } catch (RuntimeException e) {
                ysmu.LOG.error("[YSMU-UPLOAD] Failed to send chunk at offset {} of '{}'", offset, fileName, e);
                return false;
            }
            chunkIndex++;
            if (!isStillConnected()) {
                ysmu.LOG.warn(
                    "[YSMU-UPLOAD] Stopped uploading '{}' at offset {}: the connection is gone",
                    fileName,
                    offset);
                return false;
            }
            // 轻度节流：64 MiB 上传是约 2100 个包，全速灌进 Netty 的写队列没有意义，也更容易触发服务端
            // 分片处理与 GC 峰值。每 32 片（约 1 MiB）让出 2 ms，总代价约 130 ms。
            if ((chunkIndex & 0x1F) == 0) {
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    ysmu.LOG.warn("[YSMU-UPLOAD] Interrupted while uploading '{}'", fileName);
                    return false;
                }
            }
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_SYNC) {
            ysmu.LOG.info(
                "[YSMU-UPLOAD] Sent '{}' ({} bytes) to the server for the {} root",
                fileName,
                totalLength,
                target.commandWord());
        }
        return true;
    }

    private static boolean isStillConnected() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft != null && minecraft.getNetHandler() != null;
    }

    /**
     * 客户端建议给服务端的文件名：取磁盘名、截断到 {@link #MAX_PROPOSED_NAME_CHARS} 字符。真正的清洗在
     * 服务端 {@code ModelUploadInstaller.sanitizeFileName} 里，客户端不做安全决策。
     */
    private static String proposedFileName(File file) {
        String name = file.getName();
        if (name == null || name.isEmpty()) {
            return "model.ysm";
        }
        if (name.length() > MAX_PROPOSED_NAME_CHARS) {
            return name.substring(0, MAX_PROPOSED_NAME_CHARS);
        }
        return name;
    }

    /** 客户端后台日志 ERROR + 聊天栏提示的统一失败出口。 */
    private static void fail(String path, String reason) {
        ysmu.LOG.error("[YSMU-UPLOAD] Could not upload '{}': {}", path, reason);
    }

    /** 聊天提示必须回客户端线程；DeferredWork.client 由客户端 tick 排空。 */
    private static void chat(String key, Object... args) {
        DeferredWork.client(() -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null && minecraft.thePlayer != null) {
                minecraft.thePlayer.addChatMessage(new ChatComponentTranslation(key, args));
            }
        });
    }

    /**
     * 断线/会话结束复位（{@code ClientEventHandler.onClientDisconnect} 调用）。
     * <p>
     * 清"正在上传"的锁与 GUI 状态：后台任务可能在断线后几百毫秒内还在跑，它会自行发现连接消失并退出；
     * 没有需要跨会话保留的上传状态。
     * </p>
     */
    public static void reset() {
        BUSY.set(false);
        STATUE = Statue.FULFILL;
        FILE_PATH = null;
    }

    /**
     * 服务端在本次上传结束时发来 {@code CompleteFeedback}（S2C id 12）调用（Netty 线程）。
     * <p>
     * 复位状态，并在客户端线程让打开的管理 GUI 重新拉取列表（它会收到新的
     * {@code RequestServerModelInfo} 并重建界面）。Lgeacy 只在"确认"按钮里刷新列表，上传完成时没有；
     * 这里补上是因为上传是异步的，确认后立刻刷新会看不到刚上传的条目。
     * </p>
     */
    public static void finishUpload() {
        reset();
        DeferredWork.client(() -> {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null
                && minecraft.currentScreen instanceof com.fox.ysmu.client.gui.ModelManageScreen) {
                ((com.fox.ysmu.client.gui.ModelManageScreen) minecraft.currentScreen).refreshingAfterUpload();
            }
        });
    }
}
