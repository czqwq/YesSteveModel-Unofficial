package rip.ysm.security;

import io.netty.buffer.ByteBuf;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class YSMByteBuf implements AutoCloseable {
    private final ByteBuf buf;

    public YSMByteBuf(ByteBuf buf) {
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN); // YSM的基於C++，使用小端序
    }

    public ByteBuf getRawBuf() { return this.buf; }

    // 消費垃圾數據頭部，防止讀寫出問題
    public int skipGarbageHeader() {
        int garbageLen = buf.readByte() & 0x7F;
        buf.skipBytes(1);
        buf.skipBytes(garbageLen);
        return garbageLen;
    }

    public void writeGarbageHeader(int garbageLen, byte[] garbageData) {
        buf.writeByte(garbageLen | 0x80);
        buf.writeByte(0x00);
        buf.writeBytes(garbageData);
    }

    public int getOffset() {
        return buf.readerIndex();
    }

    public void setOffset(int offset) {
        buf.readerIndex(offset);
    }

    public byte readByte() {
        return buf.readByte();
    }

    public float readFloat() {
        return buf.readFloat();
    }

    public long readDword() {
        return buf.readUnsignedInt();
    }

    public void writeDword(int format) {
        buf.writeInt(format);
    }

    public int readVarInt() {
        int value = 0;
        int position = 0;
        while (true) {
            byte currentByte = buf.readByte();
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 64) throw new RuntimeException("VarInt too big");
        }
        return value;
    }

    public void writeVarInt(int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public long readVarLong() {
        long value = 0L;
        int position = 0;
        while (true) {
            byte currentByte = buf.readByte();
            value |= (long) (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) break;
            position += 7;
            if (position >= 64) throw new RuntimeException("VarLong too big");
        }
        return value;
    }

    public void writeVarLong(long value) {
        while ((value & -128L) != 0L) {
            buf.writeByte((int) (value & 127L) | 128);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    public byte[] readByteArray() {
        int len = readVarInt();
        if (len == 0) return new byte[0];
        requireAvailable(len, "byte array");
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return bytes;
    }

    public String readString() {
        int len = readVarInt();
        if (len == 0) return "";
        requireAvailable(len, "string");
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * phase55：长度前缀先与剩余字节比较，再分配数组。
     * <p>
     * 这个类读的是解压后的模型字节流；一段解压后仍可声明 2^30 长度的字符串。旧实现直接
     * {@code new byte[len]} 再 {@code readBytes}，于是分配先发生、越界异常后发生——上传校验的
     * "防注入" 要求不允许网络字节先让服务端分配 1 GB 再失败（负长度还会抛
     * {@code NegativeArraySizeException}）。声明长度大于剩余字节在任何合法流里都不可能成立，所以这是
     * 一条严格的不变式，不是新增语义。
     * </p>
     */
    private void requireAvailable(int len, String what) {
        if (len < 0 || len > buf.readableBytes()) {
            throw new RuntimeException(
                "Invalid " + what + " length " + len + " (" + buf.readableBytes() + " byte(s) left)");
        }
    }

    public byte[] toArray() {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    public void skipBytes(int n) {
        buf.skipBytes(n);
    }

    public void writeString(String s) {
        if (s == null || s.isEmpty()) {
            writeVarInt(0);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public void writeByte(byte value) {
        buf.writeByte(value);
    }

    public void writeFloat(float value) {
        buf.writeFloat(value);
    }

    public void writeByteArray(byte[] data) {
        if (data == null || data.length == 0) {
            writeVarInt(0);
            return;
        }
        writeVarInt(data.length);
        buf.writeBytes(data);
    }

    public void writeByteBuf(ByteBuf other) {
        buf.writeBytes(other);
    }

    public void release() {
        if (this.buf != null && this.buf.refCnt() > 0) {
            this.buf.release();
        }
    }

    @Override
    public void close() {
        release();
    }
}
