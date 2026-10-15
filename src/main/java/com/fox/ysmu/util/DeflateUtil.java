package com.fox.ysmu.util;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class DeflateUtil {

    public static byte[] compressBytes(final byte[] input) {
        if (input.length == 0) {
            return new byte[] {};
        }
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final byte[] buf = new byte[1024];
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(input);
            deflater.finish();
            while (!deflater.finished()) {
                final int compressedDataLength = deflater.deflate(buf);
                stream.write(buf, 0, compressedDataLength);
            }
        } finally {
            deflater.end();
        }
        return stream.toByteArray();
    }

    /**
     * 解压一段 raw deflate 数据。
     *
     * {@code Inflater.inflate} 在输入中途耗尽时返回 0 而 {@code finished()} 仍为 false，直接
     * {@code while (!finished())} 会变成 CPU 忙等（master 分支实测：把 {@code compressBytes(200_000)} 的输出
     * 截断 1/2/5/17 字节，旧实现四次都没有在 4 秒内返回）。这里对“没有进展”的情况显式抛
     * {@link DataFormatException}，让调用方按“数据损坏”处理；{@code inflater.end()} 放进 finally，
     * 保证抛异常时也释放 native 资源。
     */
    public static byte[] decompressBytes(final byte[] input) throws DataFormatException {
        if (input.length == 0) {
            return new byte[] {};
        }
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final byte[] buf = new byte[1024];
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(input, 0, input.length);
            while (!inflater.finished()) {
                final int resultLength = inflater.inflate(buf);
                if (resultLength > 0) {
                    stream.write(buf, 0, resultLength);
                    continue;
                }
                if (inflater.finished()) {
                    break;
                }
                if (inflater.needsInput()) {
                    throw new DataFormatException("Truncated deflate stream: input exhausted before the end of the stream");
                }
                if (inflater.needsDictionary()) {
                    throw new DataFormatException("Deflate stream needs a preset dictionary");
                }
                throw new DataFormatException("Deflate stream made no progress");
            }
        } finally {
            inflater.end();
        }
        return stream.toByteArray();
    }
}
