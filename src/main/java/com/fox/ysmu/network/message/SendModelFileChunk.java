package com.fox.ysmu.network.message;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SendModelFileChunk implements IMessage {

    public static final int MAX_CHUNK_BYTES = 512 * 1024;

    /** N-07: how long an interrupted transfer keeps its accumulator (and its `.part` file) before it is dropped. */
    private static final long ACCUMULATOR_TTL_MILLIS = 10 * 60 * 1000L;
    /** N-07: minimum interval between cleanup sweeps (the sweep runs on a pool thread, never on the handler). */
    private static final long SWEEP_INTERVAL_MILLIS = 60 * 1000L;

    private static final Map<String, ChunkAccumulator> ACCUMULATORS = new ConcurrentHashMap<>();
    private static volatile long lastSweepMillis;

    private String fileName;
    private int totalLength;
    private int offset;
    /** null marks a malformed/truncated packet; the handler ignores it instead of throwing (N-10). */
    private byte[] data = new byte[0];

    public SendModelFileChunk() {}

    public SendModelFileChunk(String fileName, int totalLength, int offset, byte[] data) {
        this.fileName = fileName;
        this.totalLength = totalLength;
        this.offset = offset;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-06/N-10: soft-fail on a malformed chunk. Throwing here would make FMLProxyPacket.processPacket turn
        // the decode error into a disconnect (rejectHandshake) instead of dropping the packet.
        try {
            this.fileName = ByteBufUtils.readUTF8String(buf);
            this.totalLength = buf.readInt();
            this.offset = buf.readInt();
            int length = buf.readInt();
            if (!isValidChunkRange(this.totalLength, this.offset, length)) {
                ysmu.LOG.warn(
                    "Ignoring malformed YSM model chunk: total={}, offset={}, length={}",
                    this.totalLength,
                    this.offset,
                    length);
                this.data = null;
                return;
            }
            this.data = new byte[length];
            buf.readBytes(this.data);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM model chunk: {}", e.toString());
            this.data = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        byte[] safeData = this.data == null ? new byte[0] : this.data;
        // The sending side stays strict: an out-of-range chunk is a bug in the caller and must surface locally.
        validateLengths(this.totalLength, this.offset, safeData.length);
        ByteBufUtils.writeUTF8String(buf, this.fileName);
        buf.writeInt(this.totalLength);
        buf.writeInt(this.offset);
        buf.writeInt(safeData.length);
        buf.writeBytes(safeData);
    }

    private static boolean isValidChunkRange(int totalLength, int offset, int length) {
        return totalLength >= 0 && offset >= 0 && length >= 0
            && length <= MAX_CHUNK_BYTES
            && offset <= totalLength - length;
    }

    private static void validateLengths(int totalLength, int offset, int length) {
        if (!isValidChunkRange(totalLength, offset, length)) {
            throw new IllegalArgumentException(
                "Invalid YSM model chunk: total=" + totalLength + ", offset=" + offset + ", length=" + length);
        }
    }

    public static class Handler implements IMessageHandler<SendModelFileChunk, IMessage> {

        @Override
        public IMessage onMessage(SendModelFileChunk message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && message.data != null) {
                try {
                    receiveChunk(message);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to handle YSM model chunk", e);
                }
            }
            return null;
        }

        private void receiveChunk(SendModelFileChunk message) {
            if (!isValidCacheFileName(message.fileName)) {
                ysmu.LOG.warn("Ignoring YSM model chunk with invalid cache file name {}", message.fileName);
                return;
            }

            ChunkAccumulator accumulator = ACCUMULATORS.compute(
                message.fileName,
                (name, existing) -> existing != null && existing.totalLength == message.totalLength
                    ? existing
                    : new ChunkAccumulator(message.totalLength));

            // Accumulator bookkeeping stays on the receiving thread (packet order is FML's), only the file IO is
            // moved off it (N-04).
            synchronized (accumulator) {
                if (accumulator.finished) {
                    return;
                }
                accumulator.touch();
                accumulator.acceptChunk(message.offset, message.data.length);
            }

            try {
                ThreadTools.THREAD_POOL.submit(() -> writeChunkAndMaybeFinish(message, accumulator));
            } catch (RuntimeException e) {
                ACCUMULATORS.remove(message.fileName, accumulator);
                ysmu.LOG.warn("Failed to schedule YSM model chunk write for " + message.fileName, e);
            }
        }

        private void writeChunkAndMaybeFinish(SendModelFileChunk message, ChunkAccumulator accumulator) {
            File partFile = ServerModelManager.CACHE_CLIENT.resolve(message.fileName + ".part")
                .toFile();
            try {
                writeChunk(partFile, message);
            } catch (IOException e) {
                ACCUMULATORS.remove(message.fileName, accumulator);
                ysmu.LOG.warn("Failed to write YSM model chunk for " + message.fileName, e);
                return;
            } finally {
                sweepStaleAccumulatorsIfDue();
            }

            boolean finish = false;
            synchronized (accumulator) {
                accumulator.completedWrites++;
                // Coverage complete and every accepted chunk written: the last writer finishes the file. The
                // counter keeps this correct even when the pool runs more than one worker.
                if (accumulator.coverageComplete && !accumulator.finished
                    && accumulator.completedWrites >= accumulator.acceptedWrites) {
                    accumulator.finished = true;
                    finish = true;
                }
            }
            if (finish) {
                finishChunkedFile(message.fileName, partFile, accumulator);
            }
        }

        private void writeChunk(File partFile, SendModelFileChunk message) throws IOException {
            File parent = partFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create YSM client model cache directory " + parent);
            }
            try (RandomAccessFile file = new RandomAccessFile(partFile, "rw")) {
                if (message.offset == 0 && file.length() != message.totalLength) {
                    file.setLength(message.totalLength);
                }
                file.seek(message.offset);
                file.write(message.data);
            }
        }

        /**
         * N-07: the content is verified BEFORE the rename. The file name is the md5 of the server cache file, so a
         * mismatch means a truncated or corrupted transfer: drop the `.part` file, do not register anything, and
         * let the next sync round fetch it again. Renaming first would make the half-written file look like a
         * permanent cache hit (rememberCachedModel) that the server never re-sends.
         */
        private void finishChunkedFile(String fileName, File partFile, ChunkAccumulator accumulator) {
            File outputFile = ServerModelManager.CACHE_CLIENT.resolve(fileName)
                .toFile();
            try {
                byte[] bytes = FileUtils.readFileToByteArray(partFile);
                if (!contentMatchesFileName(bytes, fileName)) {
                    ysmu.LOG.warn(
                        "Discarding corrupted YSM chunked model cache {} because its content does not match its name",
                        fileName);
                    ACCUMULATORS.remove(fileName, accumulator);
                    FileUtils.deleteQuietly(partFile);
                    return;
                }
                FileUtils.deleteQuietly(outputFile);
                FileUtils.moveFile(partFile, outputFile);
                ACCUMULATORS.remove(fileName, accumulator);
                ClientModelManager.rememberCachedModel(fileName);
                RequestLoadModel.loadModel(fileName);
                ysmu.LOG.info(
                    "Received chunked YSM model cache file {} ({} bytes)",
                    fileName,
                    accumulator.totalLength);
            } catch (Exception e) {
                ACCUMULATORS.remove(fileName, accumulator);
                ysmu.LOG.warn("Failed to finish YSM chunked model cache file " + fileName, e);
            }
        }

        /**
         * Whether the payload hashes to the file name. Uses its own {@link MessageDigest} instance instead of the
         * shared static one {@code util/Md5Utils} had before this round (M-07), so the check cannot be defeated by
         * a concurrent digest.
         */
        private boolean contentMatchesFileName(byte[] bytes, String fileName) {
            // D-01: the "well-formed cache file name" predicate is owned by the model side; reuse it instead of
            // keeping a second hex loop here (AGENTS.md: prefer one implementation to two).
            if (!ServerModelInfo.hasWellFormedCacheFileName(fileName)) {
                return false;
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] hash = digest.digest(bytes);
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    int value = b & 0xFF;
                    if (value < 0x10) {
                        hex.append('0');
                    }
                    hex.append(Integer.toHexString(value));
                }
                return hex.toString()
                    .equalsIgnoreCase(fileName);
            } catch (NoSuchAlgorithmException e) {
                ysmu.LOG.warn("Cannot verify YSM chunked model cache {}: MD5 is unavailable", fileName, e);
                return true;
            }
        }

        /**
         * D-01: delegates to {@code ServerModelInfo.hasWellFormedCacheFileName} (32 hex digits, case insensitive) so
         * the network side and the cache writer share one definition instead of two copies drifting apart.
         */
        private boolean isValidCacheFileName(String fileName) {
            return ServerModelInfo.hasWellFormedCacheFileName(fileName);
        }

        /**
         * N-07: drop accumulators that stopped receiving chunks and delete their `.part` files. Runs from a pool
         * task (never on the packet handler thread) and at most once per {@link #SWEEP_INTERVAL_MILLIS}.
         */
        private void sweepStaleAccumulatorsIfDue() {
            long now = System.currentTimeMillis();
            if (now - lastSweepMillis < SWEEP_INTERVAL_MILLIS) {
                return;
            }
            lastSweepMillis = now;
            File cacheDir = ServerModelManager.CACHE_CLIENT.toFile();
            for (Map.Entry<String, ChunkAccumulator> entry : ACCUMULATORS.entrySet()) {
                ChunkAccumulator accumulator = entry.getValue();
                long idleMillis = now - accumulator.lastTouched;
                if (idleMillis < ACCUMULATOR_TTL_MILLIS) {
                    continue;
                }
                if (ACCUMULATORS.remove(entry.getKey(), accumulator)) {
                    FileUtils.deleteQuietly(new File(cacheDir, entry.getKey() + ".part"));
                    ysmu.LOG.warn(
                        "Dropped stalled YSM model chunk transfer for {} (no chunk for {} ms)",
                        entry.getKey(),
                        idleMillis);
                }
            }
        }
    }

    private static final class ChunkAccumulator {

        private final int totalLength;
        private final Set<Integer> offsets = new HashSet<>();
        private int receivedLength;
        private int acceptedWrites;
        private int completedWrites;
        private boolean coverageComplete;
        private boolean finished;
        private volatile long lastTouched = System.currentTimeMillis();

        private ChunkAccumulator(int totalLength) {
            this.totalLength = totalLength;
        }

        private void touch() {
            this.lastTouched = System.currentTimeMillis();
        }

        private void acceptChunk(int offset, int length) {
            if (this.offsets.add(offset)) {
                this.receivedLength += length;
            }
            this.acceptedWrites++;
            if (this.receivedLength >= this.totalLength) {
                this.coverageComplete = true;
            }
        }
    }
}
