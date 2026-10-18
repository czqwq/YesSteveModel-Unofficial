package rip.ysm.zstd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

@SuppressWarnings("removal")
public final class ZstdUtil {

    private ZstdUtil() {}

    public static byte[] compress(byte[] input, int level) {
        if (level < 3 || level > 4) {
            level = 3;
        }
        CompressionParameters parameters = CompressionParameters.compute(level, -1);
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] buffer = new byte[compressor.maxCompressedLength(input.length)];
        long inputAddress = ARRAY_BYTE_BASE_OFFSET;
        long inputLimit = inputAddress + input.length;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET;
        long outputLimit = outputAddress + buffer.length;

        long output = outputAddress;
        output += ZstdFrameCompressor.writeMagic(buffer, output, outputLimit);
        output += ZstdFrameCompressor.writeFrameHeader(buffer, output, outputLimit, input.length, parameters.getWindowSize());
        output += ZstdFrameCompressor.compressFrame(input, inputAddress, inputLimit, buffer, output, outputLimit, parameters);
//        output += ZstdFrameCompressor.writeChecksum(buffer, output, outputLimit, input, inputAddress, inputLimit);

        int compressedSize = (int) (output - outputAddress);
        byte[] result = new byte[compressedSize];
        System.arraycopy(buffer, 0, result, 0, compressedSize);
        return result;
    }

    public static byte[] decompress(byte[] input) {
        return decompress(input, Long.MAX_VALUE);
    }

    /**
     * 解压一个魔改 zstd 帧，并给输出字节数设上限（phase55：上传校验的防解压炸弹手段）。
     * <p>
     * 帧头里声明了内容大小时先比较上限再分配；声明为未知大小时走流式解压并按累计写入量检查。
     * 旧的 {@link #decompress(byte[])} 委托到这里并以 {@code Long.MAX_VALUE} 为上限，现有解缓存/加载路径
     * 行为不变。
     * </p>
     */
    public static byte[] decompress(byte[] input, long maxOutputBytes) {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        long size = decompressor.getDecompressedSize(input, 0, input.length);
        if (size >= 0) {
            // getDecompressedSize 来自帧头，是攻击者可控的声明值；先比较上限与 int 范围再分配。
            if (size > maxOutputBytes) {
                throw new IllegalArgumentException(
                    "Zstd frame declares " + size + " bytes, above the " + maxOutputBytes + " byte limit");
            }
            if (size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Zstd frame declares more than Integer.MAX_VALUE bytes");
            }
            byte[] output = new byte[(int) size];
            decompressor.decompress(input, 0, input.length, output, 0, output.length);
            return output;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            long written = 0L;
            try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(input))) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    written += read;
                    if (written > maxOutputBytes) {
                        throw new IllegalArgumentException(
                            "Zstd stream exceeds the " + maxOutputBytes + " byte decompression limit");
                    }
                    baos.write(buf, 0, read);
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new MalformedInputException(0, e.getMessage());
        }
    }
}
