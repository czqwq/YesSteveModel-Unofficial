package com.fox.ysmu.model.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SUploadModelFile;
import com.fox.ysmu.network.message.CompleteFeedback;
import com.fox.ysmu.network.message.S2CRequestModelUpload;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

/**
 * 服务端的上传协调器（phase55）：谁可以传、什么时候向客户端要文件、分片怎么收、校验与落盘放在哪个线程、
 * 完成后怎么回执。
 *
 * <p><b>两条入口，一个落盘器。</b></p>
 * <ul>
 *   <li>玩家执行 {@code /ysm upload}：服务端向该玩家的客户端发 {@link S2CRequestModelUpload}，客户端
 *       读本地路径、预检、分片发回；服务端收齐后独立校验并交给 {@link ModelUploadInstaller} 落盘。
 *       1.7.10 服务端进程读不到玩家客户端磁盘，所以路径只能由客户端解析。</li>
 *   <li>控制台/命令方块执行：路径是服务端本机路径，直接读、校验、落盘
 *       （{@link #installFromServerPath(ICommandSender, ModelUploadTarget, String)}）。</li>
 * </ul>
 *
 * <p><b>为什么记握手状态。</b>客户端装了 YSMU 时会在进世界时无条件发送 {@code HandshakeMessage}
 * （{@code client/ClientEventHandler.java:239}）。服务端把收到握手的玩家记进 {@link #YSM_CLIENTS}，命令分支
 * 据此判断"这次上传能不能指望客户端读文件"：没收到握手的客户端（原版客户端/未装模组）不可能响应
 * {@link S2CRequestModelUpload}，直接给聊天栏提示比静默无响应好。这不是新的信任面——上传数据包本身仍然每次
 * 重新做权限等级 4 检查（{@code EntityPlayerMP.canCommandSenderUseCommand(4, "ysm")}），握手只影响回执时机。</p>
 *
 * <p><b>线程。</b>分片包处理器在 Netty 线程上：只做字段校验、计数与内存拷贝（有界），校验/写盘退到
 * {@link ThreadTools#THREAD_POOL}，聊天回执经 {@code DeferredWork.server} 回服务端线程。累加器按玩家 UUID
 * 唯一，登录之外的清理见 {@link #forgetPlayer(UUID)} 与 {@link #sweepStale(long)}（服务端 tick 调用）。</p>
 *
 * <p><b>安全边界。</b>总长度受 {@link com.fox.ysmu.Config#MAX_MODEL_UPLOAD_MB} 限制且先比较再分配；分片必须
 * 从偏移 0 开始、严格连续、单片不超过 {@link C2SUploadModelFile#MAX_CHUNK_BYTES}；目标目录只能是
 * {@link ModelUploadTarget} 的值；内容必须通过 {@link ModelFileValidator} 的完整模型解析。任何伪造/乱序/超限
 * 的分片都会丢弃整个传输，而不是让服务端半信半疑地写盘。</p>
 */
public final class ServerModelUploadManager {

    /** 上传命令的权限等级；与 {@code YsmCommand} 的复检同一个数。 */
    public static final int UPLOAD_PERMISSION_LEVEL = 4;
    /** 一个传输空闲多久后丢弃（客户端崩溃/断线不会再发后续分片）。 */
    private static final long TRANSFER_TTL_MILLIS = 2L * 60L * 1000L;
    /** 同一玩家两次"拒绝"日志/聊天提示的最小间隔；防止伪造分片刷屏。 */
    private static final long NOTICE_THROTTLE_MILLIS = 5L * 1000L;

    /** 完成过本模组握手的玩家：说明其客户端装了 YSMU，可以响应读取请求。 */
    private static final Set<UUID> YSM_CLIENTS = ConcurrentHashMap.newKeySet();
    /** 进行中的分片传输，每个玩家最多一个。 */
    private static final Map<UUID, Transfer> TRANSFERS = new ConcurrentHashMap<>();
    /** 拒绝提示的节流表：玩家 UUID → 上次提示时间。 */
    private static final Map<UUID, Long> LAST_REJECTION_NOTICE = new ConcurrentHashMap<>();

    private ServerModelUploadManager() {}

    // ---------------------------------------------------------------------------------------------------------------
    // session lifecycle
    // ---------------------------------------------------------------------------------------------------------------

    /** 记下一个完成握手的客户端。由 {@code HandshakeMessage.Handler} 调用（协议匹配时）。 */
    public static void markClientHandshake(EntityPlayerMP player) {
        if (player != null) {
            YSM_CLIENTS.add(player.getUniqueID());
        }
    }

    /** 该玩家当前连接是否登记过 YSMU 客户端握手。 */
    public static boolean clientSupportsUpload(EntityPlayerMP player) {
        return player != null && YSM_CLIENTS.contains(player.getUniqueID());
    }

    /** 登出清理：握手标记与未完成的分片传输都属于刚结束的会话。 */
    public static void forgetPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        YSM_CLIENTS.remove(playerId);
        LAST_REJECTION_NOTICE.remove(playerId);
        Transfer dropped = TRANSFERS.remove(playerId);
        if (dropped != null) {
            ysmu.LOG.info(
                "[YSMU-UPLOAD] Dropped incomplete upload from {} ({} of {} byte(s) received)",
                playerId,
                dropped.receivedBytes,
                dropped.totalLength);
        }
    }

    /**
     * 清理空闲超时的传输。由服务端 tick（{@code CommonEventHandler.onServerTick}）周期调用。
     *
     * @return 本轮丢弃的传输数
     */
    public static int sweepStale(long nowMillis) {
        int dropped = 0;
        for (Map.Entry<UUID, Transfer> entry : TRANSFERS.entrySet()) {
            Transfer transfer = entry.getValue();
            if (nowMillis - transfer.lastActivityMillis < TRANSFER_TTL_MILLIS) {
                continue;
            }
            if (TRANSFERS.remove(entry.getKey(), transfer)) {
                dropped++;
                ysmu.LOG.warn(
                    "[YSMU-UPLOAD] Dropped stalled upload from {} ({} of {} byte(s) received)",
                    entry.getKey(),
                    transfer.receivedBytes,
                    transfer.totalLength);
            }
        }
        for (Map.Entry<UUID, Long> entry : LAST_REJECTION_NOTICE.entrySet()) {
            if (nowMillis - entry.getValue() >= NOTICE_THROTTLE_MILLIS) {
                LAST_REJECTION_NOTICE.remove(entry.getKey(), entry.getValue());
            }
        }
        return dropped;
    }

    /**
     * 丢弃一个分片/传输时只提示一次（5 秒节流）：伪造分片可以每 tick 来一个，聊天栏与日志都不能被它刷屏。
     * 提示本身仍记录完整原因到日志，聊天栏用固定的拒绝文案。
     */
    private static void notifyRejectedOnce(UUID playerId, String logMessage, String chatKey) {
        long now = System.currentTimeMillis();
        Long previous = LAST_REJECTION_NOTICE.get(playerId);
        if (previous != null && now - previous < NOTICE_THROTTLE_MILLIS) {
            return;
        }
        LAST_REJECTION_NOTICE.put(playerId, now);
        ysmu.LOG.warn("[YSMU-UPLOAD] Rejected a chunk from {}: {}", playerId, logMessage);
        replyToPlayer(playerId, chatKey);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // command entry: player -> ask the owning client to read its own path
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 让玩家客户端读取 {@code path} 并上传。
     *
     * @return 客户端是否收到了请求；{@code false} 表示该玩家的客户端没登记过 YSMU 握手（没装模组），
     *         调用方应给聊天栏提示而不是静默等待
     */
    public static boolean requestClientUpload(EntityPlayerMP player, ModelUploadTarget target, String path) {
        if (player == null || target == null || path == null || path.isEmpty()) {
            return false;
        }
        if (!clientSupportsUpload(player)) {
            return false;
        }
        try {
            NetworkHandler.CHANNEL.sendTo(new S2CRequestModelUpload(target.wireId(), path), player);
            if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_SYNC) {
                ysmu.LOG.info(
                    "[YSMU-UPLOAD] Asked {} to read '{}' for the {} root",
                    player.getCommandSenderName(),
                    path,
                    target.commandWord());
            }
            return true;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("[YSMU-UPLOAD] Failed to ask {} for '{}'", player.getCommandSenderName(), path, e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // command entry: console -> read the server's own path
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 控制台/命令方块执行时：{@code path} 是服务端本机路径，读文件、校验、复制进目标目录，然后回执给
     * {@code sender}。工作（读盘/完整模型解析）在后台线程，聊天回执经 {@code DeferredWork.server}。
     */
    public static void installFromServerPath(ICommandSender sender, ModelUploadTarget target, String path) {
        if (sender == null || target == null || path == null || path.isEmpty()) {
            return;
        }
        ThreadTools.THREAD_POOL.submit(() -> {
            try {
                installFromServerPathNow(sender, target, path);
            } catch (Throwable t) {
                ysmu.LOG.warn("[YSMU-UPLOAD] Failed to install a server-side model file from {}", path, t);
                replyTo(sender, "commands.yes_steve_model.upload.write_failed", path);
            }
        });
    }

    private static void installFromServerPathNow(ICommandSender sender, ModelUploadTarget target, String path) {
        Path source;
        try {
            source = Path.of(path);
        } catch (InvalidPathException invalid) {
            replyTo(sender, "commands.yes_steve_model.upload.read_failed", path);
            return;
        }
        if (!Files.isRegularFile(source)) {
            replyTo(sender, "commands.yes_steve_model.upload.read_failed", path);
            return;
        }
        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            replyTo(sender, "commands.yes_steve_model.upload.read_failed", path);
            return;
        }
        long maxUploadBytes = ModelFileValidator.maxUploadBytes();
        if (size > maxUploadBytes) {
            replyTo(
                sender,
                "commands.yes_steve_model.upload.too_large",
                path,
                String.valueOf(ModelFileValidator.maxUploadMb()));
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(source);
        } catch (IOException e) {
            ysmu.LOG.warn("[YSMU-UPLOAD] Failed to read the server-side model file {}", source, e);
            replyTo(sender, "commands.yes_steve_model.upload.read_failed", path);
            return;
        }
        if (data.length > maxUploadBytes) {
            // 读之前看过大小；这里再查一次，覆盖"检查后文件被改大"的竞争。
            replyTo(
                sender,
                "commands.yes_steve_model.upload.too_large",
                path,
                String.valueOf(ModelFileValidator.maxUploadMb()));
            return;
        }
        String sourceName = source.getFileName() == null ? "model.ysm" : source.getFileName()
            .toString();
        ModelUploadInstaller.InstallResult result = ModelUploadInstaller.install(target, sourceName, data);
        replyInstallResult(result, reply -> replyTo(sender, reply[0], (Object[]) Arrays.copyOfRange(reply, 1, reply.length)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // packet entry: the client's chunks
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * 收一个分片。由 {@code C2SUploadModelFile.Handler} 在 Netty 线程调用；畸形分片丢弃整段传输并回一句拒绝，
     * 绝不半信半疑地继续累加。
     *
     * @param data 分片字节；调用方已保证非 {@code null}（解码失败的分片不会走到这里）
     */
    public static void acceptChunk(EntityPlayerMP player, ModelUploadTarget target, String fileName, int totalLength,
        int offset, byte[] data) {
        if (player == null || target == null || data == null || fileName == null) {
            return;
        }
        UUID playerId;
        try {
            playerId = player.getUniqueID();
            if (playerId == null) {
                return;
            }
            rejectUnlessValidChunk(totalLength, offset, data.length);
            if (!player.canCommandSenderUseCommand(UPLOAD_PERMISSION_LEVEL, "ysm")) {
                TRANSFERS.remove(playerId);
                notifyRejectedOnce(playerId, "permission level " + UPLOAD_PERMISSION_LEVEL + " is required", "commands.generic.permission");
                // phase55: the manage GUI shows "processing" until this packet arrives, so a refusal has to
                // release it too - otherwise a non-op who guessed the GUI would be stuck forever.
                sendCompleteFeedback(playerId);
                return;
            }
            Transfer transfer = resolveTransfer(playerId, target, fileName, totalLength, offset);
            if (transfer == null) {
                sendCompleteFeedback(playerId);
                return;
            }
            synchronized (transfer) {
                if (TRANSFERS.get(playerId) != transfer || transfer.finished) {
                    return;
                }
                if (offset != transfer.receivedBytes) {
                    discard(playerId, transfer);
                    notifyRejectedOnce(
                        playerId,
                        "chunk at offset " + offset + " is out of order (expected " + transfer.receivedBytes + ")",
                        "commands.yes_steve_model.upload.rejected");
                    sendCompleteFeedback(playerId);
                    return;
                }
                System.arraycopy(data, 0, transfer.buffer, offset, data.length);
                transfer.receivedBytes += data.length;
                transfer.lastActivityMillis = System.currentTimeMillis();
                if (transfer.receivedBytes < transfer.totalLength) {
                    return;
                }
                transfer.finished = true;
                TRANSFERS.remove(playerId, transfer);
                finishInBackground(playerId, transfer);
            }
        } catch (RuntimeException e) {
            // N-10：处理器体不得把异常漏给 FML（否则会被当成协议错误断开玩家）。
            UUID rejected = player.getUniqueID();
            if (rejected != null) {
                TRANSFERS.remove(rejected);
                notifyRejectedOnce(rejected, "malformed chunk: " + e, "commands.yes_steve_model.upload.rejected");
                sendCompleteFeedback(rejected);
            } else {
                ysmu.LOG.warn("[YSMU-UPLOAD] Ignoring a chunk from a sender without a UUID", e);
            }
        }
    }

    private static void rejectUnlessValidChunk(int totalLength, int offset, int length) {
        long maxUploadBytes = ModelFileValidator.maxUploadBytes();
        if (totalLength <= 0 || totalLength > maxUploadBytes) {
            throw new MalformedChunkException(
                "declared total length " + totalLength + " is outside (0, " + maxUploadBytes + "]");
        }
        if (offset < 0 || length <= 0 || length > C2SUploadModelFile.MAX_CHUNK_BYTES
            || offset > totalLength - length) {
            throw new MalformedChunkException(
                "offset " + offset + " / length " + length + " is not a valid chunk of " + totalLength);
        }
    }

    private static Transfer resolveTransfer(UUID playerId, ModelUploadTarget target, String fileName,
        int totalLength, int offset) {
        Transfer existing = TRANSFERS.get(playerId);
        if (existing != null && existing.matches(target, fileName, totalLength)) {
            return existing;
        }
        if (offset != 0) {
            // 没有匹配的传输却从中间开始：要求从 0 重新来，并丢掉可能残留的旧传输。
            if (existing != null) {
                discard(playerId, existing);
            }
            notifyRejectedOnce(
                playerId,
                "a chunk arrived at offset " + offset + " without a matching transfer",
                "commands.yes_steve_model.upload.rejected");
            return null;
        }
        if (existing != null) {
            discard(playerId, existing);
        }
        Transfer transfer = new Transfer(target, fileName, totalLength);
        TRANSFERS.put(playerId, transfer);
        return transfer;
    }

    private static void discard(UUID playerId, Transfer transfer) {
        TRANSFERS.remove(playerId, transfer);
        ysmu.LOG.warn(
            "[YSMU-UPLOAD] Discarding upload from {} ({} of {} byte(s) received)",
            playerId,
            transfer.receivedBytes,
            transfer.totalLength);
    }

    private static void finishInBackground(UUID playerId, Transfer transfer) {
        ThreadTools.THREAD_POOL.submit(() -> {
            try {
                completeTransfer(playerId, transfer);
            } catch (Throwable t) {
                ysmu.LOG.warn("[YSMU-UPLOAD] Failed to finish an upload from {}", playerId, t);
                replyToPlayer(playerId, "commands.yes_steve_model.upload.write_failed", transfer.fileName);
                sendCompleteFeedback(playerId);
            }
        });
    }

    private static void completeTransfer(UUID playerId, Transfer transfer) {
        ModelUploadInstaller.InstallResult result = ModelUploadInstaller.install(
            transfer.target,
            transfer.fileName,
            transfer.buffer);
        replyInstallResult(result, reply -> replyToPlayer(playerId, reply[0],
            (Object[]) Arrays.copyOfRange(reply, 1, reply.length)));
        // phase55: the client's manage GUI leaves its "processing" state on this packet; it is sent for every
        // terminal outcome (success, rejected content, write failure), so the GUI can never stay locked.
        sendCompleteFeedback(playerId);
    }

    /**
     * 通知上传方客户端"本次上传已结束"（Lgeacy 的 {@code CompleteFeedback}，S2C id 12）。GUI 用它离开
     * PROCESSING 状态并刷新列表；控制台路径没有玩家，调用方只在玩家上传路径上调用。
     */
    private static void sendCompleteFeedback(UUID playerId) {
        DeferredWork.server(() -> {
            EntityPlayerMP player = findOnlinePlayer(playerId);
            if (player == null) {
                return;
            }
            try {
                NetworkHandler.CHANNEL.sendTo(new CompleteFeedback(), player);
            } catch (RuntimeException e) {
                ysmu.LOG.warn("[YSMU-UPLOAD] Failed to send the upload completion feedback to {}", playerId, e);
            }
        });
    }

    /**
     * 成功/失败共用的回执映射：安装结果是三种结局，回执键是同一张表，玩家与控制台两条路径共用。
     * {@code reply} 的第一项是翻译键，其余是 {@code %s} 参数。
     */
    private static void replyInstallResult(ModelUploadInstaller.InstallResult result,
        java.util.function.Consumer<String[]> rejectOrAccept) {
        if (result == null) {
            rejectOrAccept.accept(new String[] { "commands.yes_steve_model.upload.write_failed", "?" });
            return;
        }
        switch (result.outcome()) {
            case OK:
                rejectOrAccept
                    .accept(new String[] { "commands.yes_steve_model.upload.done", result.relativePath() });
                break;
            case INVALID: {
                ModelFileValidator.Result validation = result.validation();
                String reason = validation == null ? "?" : validation.reason()
                    .name();
                ysmu.LOG.warn("[YSMU-UPLOAD] Rejected an uploaded model file: {}", result.detail());
                rejectOrAccept.accept(new String[] { "commands.yes_steve_model.upload.invalid", reason });
                break;
            }
            case WRITE_FAILED:
            default:
                ysmu.LOG.warn("[YSMU-UPLOAD] Could not write an uploaded model file: {}", result.detail());
                rejectOrAccept.accept(new String[] { "commands.yes_steve_model.upload.write_failed",
                    result.relativePath() == null ? "?" : result.relativePath() });
                break;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reply helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** 回执给一个在线玩家（按 UUID 重新解析；传输完成时原对象可能已断开）。 */
    private static void replyToPlayer(UUID playerId, String key, Object... args) {
        DeferredWork.server(() -> {
            EntityPlayerMP player = findOnlinePlayer(playerId);
            if (player != null) {
                player.addChatMessage(new ChatComponentTranslation(key, args));
            }
        });
    }

    /** 回执给任意命令发送者（控制台/命令方块/玩家）；在服务端线程执行。 */
    private static void replyTo(ICommandSender sender, String key, Object... args) {
        DeferredWork.server(() -> {
            if (sender != null) {
                sender.addChatMessage(new ChatComponentTranslation(key, args));
            }
        });
    }

    private static EntityPlayerMP findOnlinePlayer(UUID playerId) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return null;
        }
        for (Object candidate : server.getConfigurationManager().playerEntityList) {
            if (candidate instanceof EntityPlayerMP player && playerId.equals(player.getUniqueID())) {
                return player;
            }
        }
        return null;
    }

    /** 一个玩家的一条进行中传输。 */
    private static final class Transfer {

        private final ModelUploadTarget target;
        private final String fileName;
        private final int totalLength;
        private final byte[] buffer;
        private int receivedBytes;
        private boolean finished;
        private volatile long lastActivityMillis = System.currentTimeMillis();

        private Transfer(ModelUploadTarget target, String fileName, int totalLength) {
            this.target = target;
            this.fileName = fileName;
            this.totalLength = totalLength;
            this.buffer = new byte[totalLength];
        }

        private boolean matches(ModelUploadTarget candidateTarget, String candidateName, int candidateLength) {
            return this.target == candidateTarget && this.totalLength == candidateLength
                && this.fileName.equals(candidateName);
        }
    }

    /** 畸形分片的内部信号：只在 {@link #acceptChunk} 里被转成聊天回执与传输丢弃。 */
    private static final class MalformedChunkException extends RuntimeException {

        private MalformedChunkException(String message) {
            super(message);
        }
    }
}
