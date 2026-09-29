package com.fox.ysmu.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.AESUtil;
import com.fox.ysmu.util.ByteInteger;
import com.fox.ysmu.util.DeflateUtil;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.ysmu;
import it.unimi.dsi.fastutil.bytes.ByteArrays;

public final class EncryptTools {

    /**
     * 二进制文件的头部幻数
     * YSGP 的 ASCII 码
     * YSGP 就是 Ying Su Group，映素小组的缩写
     */
    public static final int HEAD = 0x59_53_47_50;

    /**
     * 二进制文件的版本号
     */
    public static final int VERSION = 0x00_00_00_02;

    /**
     * 加密方法
     */
    private static final String ENCRYPTION_METHOD = "AES";

    /**
     * 二进制密码文件长度
     */
    private static final int PASSWORD_SIZE = 40;

    /**
     * 加密后模型载荷的最小长度：AES 至少一个分组，用来挡住"空载荷"被当成合法缓存写盘。
     */
    private static final int MIN_ENCRYPTED_MODEL_BYTES = 16;

    /**
     * 模型包密码
     */
    private static SecretKey SECRET_KEY;

    /**
     * 模型包特征矩阵（还是密码）
     */
    private static IvParameterSpec IV;

    public static void createRandomPassword() {
        SECRET_KEY = AESUtil.generateKey();
        IV = AESUtil.generateIv();
    }

    public static boolean readPassword(byte[] fileBytes) {
        if (fileBytes.length != PASSWORD_SIZE) {
            return false;
        }
        int head = ByteInteger.bytes2Int(fileBytes, 0);
        int version = ByteInteger.bytes2Int(fileBytes, 4);
        if (head != HEAD) {
            return false;
        }
        if (version != VERSION) {
            return false;
        }
        byte[] password = ByteArrays.copy(fileBytes, 8, 16);
        byte[] iv = ByteArrays.copy(fileBytes, 24, 16);
        SECRET_KEY = new SecretKeySpec(password, ENCRYPTION_METHOD);
        IV = new IvParameterSpec(iv);
        return true;
    }

    public static byte[] writePassword() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(ByteInteger.int2Bytes(HEAD));
        stream.write(ByteInteger.int2Bytes(VERSION));
        stream.write(SECRET_KEY.getEncoded());
        stream.write(IV.getIV());
        return stream.toByteArray();
    }

    /**
     * 将附加信息和加密文件块组合成二进制文件
     * -----------------------------------------
     * 59 53 47 50 幻数
     * 00 00 00 01 版本号
     * 00 00 00 00 00 00 00 00
     * 00 00 00 00 00 00 00 00 文件 MD5
     * -----------------------------------------
     * 加密文件块
     */
    public static byte[] assembleEncryptModels(ModelData data) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(ByteInteger.int2Bytes(HEAD));
        stream.write(ByteInteger.int2Bytes(VERSION));
        byte[] encryptModelBytes = encryptModel(data);
        // M-19:空/过短的载荷不再被写进缓存文件,否则客户端只会拿到一个解不开的 24 字节壳。
        if (encryptModelBytes.length < MIN_ENCRYPTED_MODEL_BYTES) {
            throw new IOException(
                "Encrypted model payload for " + data.getModelId() + " is too short: " + encryptModelBytes.length);
        }
        byte[] md5 = Md5Utils.md5(encryptModelBytes);
        stream.write(md5);
        stream.write(encryptModelBytes);
        return stream.toByteArray();
    }

    /**
     * 将模型、材质、动画组合成加密、压缩、二进制数据
     * -----------------------------------------
     * 7F FF FF FF 模型名称字符串
     * <p>
     * 7F FF FF FF 模型文件数量
     * 7F FF FF FF 模型文件名称
     * 7F FF FF FF 模型文件大小
     * 7F FF FF FF 模型文件名称
     * 7F FF FF FF 模型文件大小
     * <p>
     * 7F FF FF FF 材质文件大小
     * 7F FF FF FF 动画文件大小
     * 模型二进制文件块
     * 材质二进制文件块
     * 动画二进制文件块
     * -----------------------------------------
     * 进行一次 tar.gz 压缩
     * 进行一次 AES 加密
     */
    private static byte[] encryptModel(ModelData data) throws IOException {
        try {
            ByteArrayOutputStream tmp = new ByteArrayOutputStream();

            writeString(tmp, data.getModelId());

            writeMapDataInfo(tmp, data.getModel());
            writeMapDataInfo(tmp, data.getTexture());
            writeMapDataInfo(tmp, data.getAnimation());

            writeMapData(tmp, data.getModel());
            writeMapData(tmp, data.getTexture());
            writeMapData(tmp, data.getAnimation());

            byte[] output = DeflateUtil.compressBytes(tmp.toByteArray());
            return AESUtil.encrypt(SECRET_KEY, IV, output)
                .toByteArray();
        } catch (Exception e) {
            // M-19:失败必须冒泡。dev 基线在这里返回 EMPTY_ARRAY,调用方照样写出一个
            // "头部合法、载荷为空"的缓存文件,客户端拿到后无法解密。
            throw new IOException("Failed to encrypt model " + data.getModelId(), e);
        }
    }

    private static void writeString(ByteArrayOutputStream stream, String string) throws IOException {
        byte[] stringBytes = string.getBytes(StandardCharsets.UTF_8);
        stream.write(ByteInteger.int2Bytes(stringBytes.length));
        stream.write(stringBytes);
    }

    private static void writeFileInfo(ByteArrayOutputStream stream, String name, byte[] input) throws IOException {
        writeString(stream, name);
        stream.write(ByteInteger.int2Bytes(input.length));
    }

    private static void writeMapDataInfo(ByteArrayOutputStream stream, Map<String, byte[]> input) throws IOException {
        stream.write(ByteInteger.int2Bytes(input.size()));
        for (String name : input.keySet()) {
            writeFileInfo(stream, name, input.get(name));
        }
    }

    private static void writeMapData(ByteArrayOutputStream stream, Map<String, byte[]> input) throws IOException {
        for (byte[] data : input.values()) {
            stream.write(data);
        }
    }

    public static byte[] encryptPassword(byte[] uuid, byte[] input) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(uuid, ENCRYPTION_METHOD);
        IvParameterSpec iv = new IvParameterSpec(uuid);
        return AESUtil.encrypt(secretKey, iv, input)
            .toByteArray();
    }

    private static byte[] decryptPassword(byte[] uuid, byte[] input) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(uuid, ENCRYPTION_METHOD);
        IvParameterSpec iv = new IvParameterSpec(uuid);
        byte[] rawPassword = AESUtil.decrypt(secretKey, iv, input)
            .toByteArray();
        if (ByteInteger.bytes2Int(rawPassword, 0) != HEAD) {
            return ByteArrays.EMPTY_ARRAY;
        }
        if (ByteInteger.bytes2Int(rawPassword, 4) != VERSION) {
            return ByteArrays.EMPTY_ARRAY;
        }
        return rawPassword;
    }

    @Nullable
    public static ModelData decryptModel(byte[] uuid, byte[] password, byte[] modelRawData) {
        try {
            byte[] rawPassword = decryptPassword(uuid, password);
            if (rawPassword.length == 0) {
                return null;
            }
            if (ByteInteger.bytes2Int(modelRawData, 0) != HEAD) {
                return null;
            }
            if (ByteInteger.bytes2Int(modelRawData, 4) != VERSION) {
                return null;
            }

            String md5 = Md5Utils.toHexString(ByteArrays.copy(modelRawData, 8, 16));
            byte[] encryptModelData = ByteArrays.copy(modelRawData, 24, modelRawData.length - 24);
            String dataMd5 = Md5Utils.md5Hex(encryptModelData);
            if (!md5.equals(dataMd5)) {
                // M-08:头部记录的 MD5 与实际载荷不一致 => 文件已损坏或被篡改,直接失败。
                // (dev 基线这里只 warn 然后继续解密,配合 M-07 的共享 MessageDigest 会掩盖真正的问题;
                //  M-07 修好后两边都是 per-call MD5,不一致只可能来自坏文件。)
                ysmu.LOG.warn(
                    "Refusing to decrypt model cache: header MD5 {} does not match payload MD5 {}",
                    md5,
                    dataMd5);
                return null;
            }

            byte[] passwordBytes = ByteArrays.copy(rawPassword, 8, 16);
            byte[] ivBytes = ByteArrays.copy(rawPassword, 24, 16);
            SecretKeySpec key = new SecretKeySpec(passwordBytes, ENCRYPTION_METHOD);
            IvParameterSpec iv = new IvParameterSpec(ivBytes);
            ByteArrayOutputStream decryptModelData = AESUtil.decrypt(key, iv, encryptModelData);
            byte[] modelData = DeflateUtil.decompressBytes(decryptModelData.toByteArray());

            if (modelData.length == 0) {
                return null;
            }

            ByteArrayInputStream tmp = new ByteArrayInputStream(modelData);

            String modelId = readString(tmp);
            Map<String, Integer> modelMapDataInfo = readMapDataInfo(tmp);
            Map<String, Integer> textureMapDataInfo = readMapDataInfo(tmp);
            Map<String, Integer> animationMapDataInfo = readMapDataInfo(tmp);

            Map<String, byte[]> modelMapData = readMapData(tmp, modelMapDataInfo);
            Map<String, byte[]> textureMapData = readMapData(tmp, textureMapDataInfo);
            Map<String, byte[]> animationMapData = readMapData(tmp, animationMapDataInfo);

            return new ModelData(modelId, Type.UNKNOWN, modelMapData, textureMapData, animationMapData);
        } catch (Exception e) {
            // M-16:统一走日志,不再 printStackTrace。
            ysmu.LOG.warn("Failed to decrypt model cache payload", e);
        }
        return null;
    }

    @SuppressWarnings("all")
    private static Map<String, byte[]> readMapData(ByteArrayInputStream tmp, Map<String, Integer> modelMapDataInfo)
        throws IOException {
        Map<String, byte[]> output = new LinkedHashMap<>();
        for (String name : modelMapDataInfo.keySet()) {
            int size = modelMapDataInfo.get(name);
            byte[] sizeBytes = new byte[size];
            int read = tmp.read(sizeBytes);
            if (read != size) {
                throw new IOException("Unexpected EOF while reading model resource " + name);
            }
            output.put(name, sizeBytes);
        }
        return output;
    }

    private static Map<String, Integer> readMapDataInfo(ByteArrayInputStream tmp) throws IOException {
        Map<String, Integer> mapDataInfo = new LinkedHashMap<>();
        int modelCount = readInt(tmp);
        for (int i = 0; i < modelCount; i++) {
            String name = readString(tmp);
            int size = readInt(tmp);
            mapDataInfo.put(name, size);
        }
        return mapDataInfo;
    }

    @SuppressWarnings("all")
    private static String readString(ByteArrayInputStream stream) throws IOException {
        int size = readInt(stream);
        byte[] stringBytes = new byte[size];
        int read = stream.read(stringBytes);
        if (read != size) {
            throw new IOException("Unexpected EOF while reading model resource name");
        }
        return new String(stringBytes, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("all")
    private static int readInt(ByteArrayInputStream stream) throws IOException {
        byte[] sizeBytes = new byte[4];
        int read = stream.read(sizeBytes);
        if (read != sizeBytes.length) {
            throw new IOException("Unexpected EOF while reading model resource size");
        }
        return ByteInteger.bytes2Int(sizeBytes, 0);
    }
}
