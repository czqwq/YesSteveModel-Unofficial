package com.fox.ysmu.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.zip.DataFormatException;

import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.bytes.ByteArrays;

public final class YesModelUtils {

    /**
     * 二进制文件的头部幻数
     * YSGP 的 ASCII 码
     * YSGP 就是 Ying Su Group，映素小组的缩写
     */
    public static final int HEAD = 0x59_53_47_50;
    /**
     * 二进制文件的版本号
     */
    public static final int VERSION = 0x00_00_00_01;
    /**
     * 新版本号，用了更新的加密解密方法
     */
    public static final int VERSION_II = 0x00_00_00_02;
    /**
     * 加密方法
     */
    private static final String ENCRYPTION_METHOD = "AES";
    /**
     * 容器头部长度：HEAD(4) + VERSION(4) + 总 MD5(16)。比这更短的文件不可能是合法的 YSGP 容器。
     */
    private static final int CONTAINER_HEADER_SIZE = 24;
    private static final int AES_IV_LENGTH = 16;

    public static Map<String, byte[]> input(File ysmFile) throws IOException {
        byte[] data = FileUtils.readFileToByteArray(ysmFile);
        // M-18：旧实现无条件 bytes2Int(data, 0)/bytes2Int(data, 4)：0~7 字节的损坏/半截文件会抛
        // ArrayIndexOutOfBoundsException，而该异常不被本方法声明、也不被 YsmFormat.cacheAllModels 的
        // catch (IOException) 捕获，会一路冒泡打断 ServerModelManager.reloadPacks()（启动崩溃 / `/ysm reload`
        // 崩服）。这里对超短文件直接按“不是本容器”处理。
        if (data.length < CONTAINER_HEADER_SIZE) {
            ysmu.LOG.warn(
                "Ignoring {} as a YSGP container: only {} byte(s), the {} byte header is incomplete",
                ysmFile,
                data.length,
                CONTAINER_HEADER_SIZE);
            return Collections.emptyMap();
        }
        int head = ByteInteger.bytes2Int(data, 0);
        int version = ByteInteger.bytes2Int(data, 4);
        if (head != HEAD) {
            return Collections.emptyMap();
        }
        if (version != VERSION && version != VERSION_II) {
            return Collections.emptyMap();
        }

        byte[] md5 = ByteArrays.copy(data, 8, 16);
        byte[] modelFilesData = ByteArrays.copy(data, CONTAINER_HEADER_SIZE, data.length - CONTAINER_HEADER_SIZE);
        if (!Arrays.equals(md5, Md5Utils.md5(modelFilesData))) {
            return Collections.emptyMap();
        }

        Map<String, byte[]> outputs = Maps.newHashMap();
        ByteArrayInputStream tmp = new ByteArrayInputStream(modelFilesData);
        while (tmp.available() > 0) {
            int remainingBefore = tmp.available();
            try {
                Pair<String, byte[]> ysmFileData;
                if (version == VERSION) {
                    ysmFileData = ysmToFile(tmp);
                } else {
                    ysmFileData = ysmToFileNew(tmp);
                }
                outputs.put(ysmFileData.left(), ysmFileData.right());
            } catch (GeneralSecurityException | DataFormatException | RuntimeException e) {
                // 单个条目损坏（截断/负长度/解密失败/解压失败）只跳过该条目；必须确认流有进展，
                // 否则“失败但没消费任何字节”会让这个 while 变成死循环。
                ysmu.LOG.warn(
                    "Skipping malformed entry in {} ({} byte(s) left to read): {}",
                    ysmFile,
                    remainingBefore,
                    e.toString());
                if (tmp.available() >= remainingBefore) {
                    break;
                }
            }
        }
        return outputs;
    }

    @NotNull
    private static Pair<String, byte[]> ysmToFile(ByteArrayInputStream tmp)
        throws IOException, GeneralSecurityException, DataFormatException {
        String name = readString(tmp);
        int size = readInt(tmp);

        byte[] passwordBytes = readExact(tmp, AES_IV_LENGTH, "AES key");
        byte[] ivBytes = readExact(tmp, AES_IV_LENGTH, "AES iv");
        SecretKeySpec key = new SecretKeySpec(passwordBytes, ENCRYPTION_METHOD);
        IvParameterSpec iv = new IvParameterSpec(ivBytes);

        byte[] fileData = readExact(tmp, size, "encrypted file body");

        ByteArrayOutputStream decryptData = AESUtil.decrypt(key, iv, fileData);
        byte[] rawData = DeflateUtil.decompressBytes(decryptData.toByteArray());

        return Pair.of(name, rawData);
    }

    @NotNull
    private static Pair<String, byte[]> ysmToFileNew(ByteArrayInputStream tmp)
        throws IOException, GeneralSecurityException, DataFormatException {
        String fileName = readBase64String(tmp);
        int fileSize = readInt(tmp);
        int cipherSecretKeySize = readInt(tmp);

        byte[] cipherSecretKey = readExact(tmp, cipherSecretKeySize, "cipher secret key");
        byte[] ivBytes = readExact(tmp, AES_IV_LENGTH, "AES iv");
        byte[] fileData = readExact(tmp, fileSize, "encrypted file body");

        byte[] keyFromMd5 = getKeyFromMd5(fileData);
        SecretKey secretSecretKey = AESUtil.getKey(keyFromMd5);
        IvParameterSpec iv = new IvParameterSpec(ivBytes);
        byte[] decryptSecretKey = AESUtil.decrypt(secretSecretKey, iv, cipherSecretKey)
            .toByteArray();
        SecretKey key = AESUtil.getKey(decryptSecretKey);
        ByteArrayOutputStream decryptData = AESUtil.decrypt(key, iv, fileData);
        byte[] rawData = DeflateUtil.decompressBytes(decryptData.toByteArray());

        return Pair.of(fileName, rawData);
    }

    public static void export(File dir) throws IOException {
        String dirName = dir.getName();
        boolean noMainModelFile = true;
        boolean noArmModelFile = true;
        boolean noTextureFile = true;
        Collection<File> files = FileUtils.listFiles(dir, FileFileFilter.FILE, null);
        for (File file : files) {
            String fileName = file.getName();
            if ("main.json".equals(fileName)) {
                noMainModelFile = false;
            }
            if ("arm.json".equals(fileName)) {
                noArmModelFile = false;
            }
            if (fileName.endsWith(".png")) {
                noTextureFile = false;
            }
        }
        if (noMainModelFile) {
            return;
        }
        if (noArmModelFile) {
            return;
        }
        if (noTextureFile) {
            return;
        }

        byte[] ysmData = filesToYsm(files);
        File outputFile = ServerModelManager.EXPORT.resolve(dirName + ".ysm")
            .toFile();
        FileUtils.writeByteArrayToFile(outputFile, ysmData, false);
    }

    /**
     * 头部幻数
     * 版本号
     * 总 MD5
     * 各个文件
     */
    private static byte[] filesToYsm(Collection<File> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ByteInteger.int2Bytes(HEAD));
        output.write(ByteInteger.int2Bytes(VERSION_II));

        byte[] filesData = filesToBytes(files);
        byte[] md5 = Md5Utils.md5(filesData);
        output.write(md5);
        output.write(filesData);
        return output.toByteArray();
    }

    private static byte[] filesToBytes(Collection<File> files) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        files.forEach(file -> {
            try {
                output.write(fileToBytes(file));
            } catch (IOException | GeneralSecurityException e) {
                ysmu.LOG.warn("Failed to pack {} into the YSGP container", file, e);
            }
        });
        return output.toByteArray();
    }

    /**
     * 名称
     * 长度
     * 密码
     * 主体
     */
    private static byte[] fileToBytes(File file) throws IOException, GeneralSecurityException {
        byte[] rawData = FileUtils.readFileToByteArray(file);
        byte[] compressData = DeflateUtil.compressBytes(rawData);
        // 生成秘钥 1 和特征矩阵
        SecretKey secretKey = AESUtil.generateKey();
        IvParameterSpec iv = AESUtil.generateIv();
        // 将原文件 AES 加密
        ByteArrayOutputStream encryptData = AESUtil.encrypt(secretKey, iv, compressData);
        // 通过文件的 MD5 来生成特殊的秘钥 2
        byte[] keyFromMd5 = getKeyFromMd5(encryptData.toByteArray());
        // 用这个秘钥 2 来加密秘钥 1，获得密文秘钥
        ByteArrayOutputStream cipherSecretKey = AESUtil.encrypt(AESUtil.getKey(keyFromMd5), iv, secretKey.getEncoded());
        // 开始写文件
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // 书写 Base64 加密过的字符串
        writeBase64String(output, file.getName());
        // 书写密文长度
        output.write(ByteInteger.int2Bytes(encryptData.size()));
        // 书写密文秘钥长度
        output.write(ByteInteger.int2Bytes(cipherSecretKey.size()));
        // 书写密文秘钥
        output.write(cipherSecretKey.toByteArray());
        // 书写特征矩阵
        output.write(iv.getIV());
        // 书写密文
        output.write(encryptData.toByteArray());
        return output.toByteArray();
    }

    private static byte[] getKeyFromMd5(byte[] fileData) {
        // 获取得到 MD5
        byte[] md5 = Md5Utils.md5(fileData);
        // 以 MD5 为种子，获取固定的随机数
        Random random = new Random(toLong(md5));
        // 得到固定的随机数秘钥
        byte[] keys = new byte[16];
        random.nextBytes(keys);
        return keys;
    }

    private static long toLong(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) + (b & 255);
        }
        return value;
    }

    private static void writeString(ByteArrayOutputStream stream, String string) throws IOException {
        byte[] stringBytes = string.getBytes(StandardCharsets.UTF_8);
        stream.write(ByteInteger.int2Bytes(stringBytes.length));
        stream.write(stringBytes);
    }

    private static void writeBase64String(ByteArrayOutputStream stream, String string) throws IOException {
        byte[] stringBytes = Base64.getEncoder()
            .encode(string.getBytes(StandardCharsets.UTF_8));
        stream.write(ByteInteger.int2Bytes(stringBytes.length));
        stream.write(stringBytes);
    }

    private static String readString(ByteArrayInputStream stream) throws IOException {
        int size = readInt(stream);
        byte[] stringBytes = readExact(stream, size, "string body");
        return new String(stringBytes, StandardCharsets.UTF_8);
    }

    private static String readBase64String(ByteArrayInputStream stream) throws IOException {
        int size = readInt(stream);
        byte[] stringBytes = readExact(stream, size, "base64 string body");
        return new String(
            Base64.getDecoder()
                .decode(stringBytes),
            StandardCharsets.UTF_8);
    }

    private static boolean readBoolean(ByteArrayInputStream stream) throws IOException {
        return readInt(stream) != 0;
    }

    private static int readInt(ByteArrayInputStream stream) throws IOException {
        byte[] sizeBytes = readExact(stream, 4, "int");
        return ByteInteger.bytes2Int(sizeBytes, 0);
    }

    /**
     * 按容器里声明的长度读满 {@code size} 字节（M-18 的长度上限校验）。
     *
     * 长度先与 {@code stream.available()} 比较**再**分配数组：这样既不会为损坏文件里的
     * {@code 0x7FFFFFFF} 之类的长度申请内存（{@code OutOfMemoryError}），也不会出现
     * {@code NegativeArraySizeException}；读到一半的短读同样按 IOException 处理。
     */
    private static byte[] readExact(ByteArrayInputStream stream, int size, String what) throws IOException {
        if (size < 0 || size > stream.available()) {
            throw new IOException(
                "Declared " + what + " length " + size + " exceeds the " + stream.available() + " remaining byte(s)");
        }
        byte[] bytes = new byte[size];
        int read = stream.read(bytes);
        if (read != size) {
            throw new IOException("Unexpected EOF while reading " + what + " (" + read + "/" + size + " byte(s))");
        }
        return bytes;
    }

}
