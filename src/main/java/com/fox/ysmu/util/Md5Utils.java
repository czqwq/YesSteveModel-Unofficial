package com.fox.ysmu.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

    /**
     * 摘要/字节序列的十六进制文本：小写，每字节固定两位。
     *
     * <p>用 {@link HexFormat}（Java 17）而不是手写循环，与 upstream 的同一操作一致
     * （{@code model/domain/Hash256.java:10,99}、{@code model/catalog/content/DefaultAnimationKey.java:43-44}）。
     * {@code HexFormat.of()} 的默认就是小写、定长，因此输出与替换前的循环逐字相同 —— 这个字符串同时是缓存
     * 文件名与内容校验口径（{@code network/message/SendModelFile}、{@code model/format/ModelCacheWriter}、
     * {@code data/EncryptTools}、{@code ServerModelInfo}），不能变。JDK 8 启动器上这些调用由 JVM Downgrader
     * 改写到它自己的 stub（{@code gradle.properties} 的 {@code enableModernJavaSyntax = jvmDowngrader}）。
     */
    public static String toHexString(byte[] bytes) {
        return HexFormat.of()
            .formatHex(bytes);
    }
}
