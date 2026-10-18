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
     * 解压一段 raw deflate 数据，不限制输出大小；现有模型扫描路径用它，行为与加入上限重载之前逐字节一致。
     */
    public static byte[] decompressBytes(final byte[] input) throws DataFormatException {
        return decompressBytes(input, Long.MAX_VALUE);
    }

    /**
     * 解压一段 raw deflate 数据，并给输出字节数设上限。
     *
     * {@code Inflater.inflate} 在输入中途耗尽时返回 0 而 {@code finished()} 仍为 false，直接
     * {@code while (!finished())} 会变成 CPU 忙等（master 分支实测：把 {@code compressBytes(200_000)} 的输出
     * 截断 1/2/5/17 字节，旧实现四次都没有在 4 秒内返回）。这里对“没有进展”的情况显式抛
     * {@link DataFormatException}，让调用方按“数据损坏”处理；{@code inflater.end()} 放进 finally，
     * 保证抛异常时也释放 native 资源。
     *
     * <p><b>上限是上传校验的防解压炸弹手段（phase55）。</b>deflate 的压缩比没有上界，几十 KB 的输入可以声明
     * 出数 GB 的输出；{@link #decompressBytes(byte[])} 的调用方读的是磁盘上已经存在的模型文件，这一段输入
     * 的规模受服务器磁盘控制，而上传路径的字节来自网络，必须先证明它撑不出一个 OOM。超限在写满
     * {@code maxOutputBytes} 后立刻抛异常，已经解出的字节随调用失败被丢弃。</p>
     */
    public static byte[] decompressBytes(final byte[] input, final long maxOutputBytes) throws DataFormatException {
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
                    if (stream.size() > maxOutputBytes) {
                        throw new DataFormatException(
                            "Deflate stream exceeds the " + maxOutputBytes + " byte decompression limit");
                    }
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
