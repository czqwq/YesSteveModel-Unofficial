package com.fox.ysmu.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Md5Utils {

    private static final String ALGORITHM = "MD5";

    private Md5Utils() {}

    /**
     * 每次调用都新建一个 {@link MessageDigest}。
     *
     * {@code MessageDigest} 不是线程安全的（JDK 文档明确写出），而本类的调用点横跨 Netty 收包线程、模型线程池
     * 与服务端 reload/命令线程：{@code network/message/SendModelFile}、{@code network/message/RequestLoadModel}
     * → {@code data/EncryptTools}、{@code data/EncryptTools}（组包）、{@code model/format/ModelCacheWriter}、
     * {@code util/YesModelUtils}。共享同一个实例会产生**错误摘要**，进而破坏缓存文件名与完整性校验。
     * 新建实例的开销相对被摘要的数据量可以忽略。
     */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " digest is not available", e);
        }
    }

    public static String md5Hex(byte[] data) {
        return toHexString(newDigest().digest(data));
    }

    public static byte[] md5(byte[] data) {
        return newDigest().digest(data);
    }

    public static String toHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
