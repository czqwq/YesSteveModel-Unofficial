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
     * PASSWORD 的版本号（也是历史三地图模型载荷版本，保持 v2 以复用持久化密码）。
     */
    public static final int VERSION = 0x00_00_00_02;

    /**
     * Model container version, independent of the unchanged v2 PASSWORD format above. Version 3 adds a fourth,
     * separate GUI-image map; version 4 adds independent wheel/settings declarations; version 5 adds explicit PBR
     * components; version 6 appends the model's metadata links after those components. Versions 2, 3, 4 and 5 remain
     * readable. Old clients reject the new version rather than interpreting metadata or image bytes as geometry.
     * Deploy matching client/server builds for full legacy GUI support.
     */
    public static final int GUI_IMAGE_MODEL_VERSION = 0x00_00_00_03;
    /** Version 4 adds a length-prefixed independent wheel/settings declaration section after the model id. */
    public static final int WHEEL_MODEL_VERSION = 0x00_00_00_04;
    /** Version 5 appends explicit base/component associations after the four resource maps. */
    public static final int MODEL_VERSION = 0x00_00_00_05;
    /**
     * Version 6 appends the model's {@code metadata.links} - an ordered list of type/URL string pairs - as the last
     * section of the payload.
     *
     * <p>It is a new trailing section rather than a field inside {@link WheelDeclarations}, even though that JSON blob
     * would have tolerated an unknown key: the container is the thing whose layout is versioned, and the read below is
     * gated on this constant so a v5 payload simply comes back with no links (see {@code decryptModel}). This is the
     * same shape version 4 used for the wheel declarations ({@code modelVersion >= WHEEL_MODEL_VERSION}).</p>
     *
     * <p>{@code ModelCacheWriter.OPEN_YSM_BAKE_VERSION} is <b>not</b> bumped for this:
     * the OpenYSM baked payload already carries {@code metadata.links} - {@code YSMBinarySerializer:486-490} writes it
     * and {@code YSMBinaryDeserializer:605-608} reads it - so its bytes and their meaning are unchanged. Only the
     * legacy container gained a field.</p>
     */
    public static final int LINKS_MODEL_VERSION = 0x00_00_00_06;

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
     * 00 00 00 06 模型载荷版本号（独立于 PASSWORD v2）
     * 00 00 00 00 00 00 00 00
     * 00 00 00 00 00 00 00 00 文件 MD5
     * -----------------------------------------
     * 加密文件块
     */
    public static byte[] assembleEncryptModels(ModelData data) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(ByteInteger.int2Bytes(HEAD));
        stream.write(ByteInteger.int2Bytes(LINKS_MODEL_VERSION));
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
            writeString(tmp, data.getWheelDeclarations().encode());

            writeMapDataInfo(tmp, data.getModel());
            writeMapDataInfo(tmp, data.getTexture());
            writeMapDataInfo(tmp, data.getAnimation());
            // Upstream ModelInfoBuilder:57-64 carries GUI images beside settings, not model textures/geometry.
            writeMapDataInfo(tmp, data.getGuiImages());

            writeMapData(tmp, data.getModel());
            writeMapData(tmp, data.getTexture());
            writeMapData(tmp, data.getAnimation());
            writeMapData(tmp, data.getGuiImages());
            tmp.write(ByteInteger.int2Bytes(data.getPbrComponents().size()));
            for (Map.Entry<String, Map<Integer, byte[]>> base : data.getPbrComponents().entrySet()) {
                writeString(tmp, base.getKey());
                tmp.write(ByteInteger.int2Bytes(base.getValue().size()));
                for (Map.Entry<Integer, byte[]> component : base.getValue().entrySet()) {
                    tmp.write(ByteInteger.int2Bytes(component.getKey()));
                    tmp.write(ByteInteger.int2Bytes(component.getValue().length));
                    tmp.write(component.getValue());
                }
            }
            // v6: the model's metadata links, in declaration order. Written last so every earlier section keeps its
            // offset and the read of an older payload stops before this one (see LINKS_MODEL_VERSION).
            writeStringMap(tmp, data.getLinks());

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

    /** Order-preserving string map: count, then key/value pairs ({@code LINKS_MODEL_VERSION}'s section). */
    private static void writeStringMap(ByteArrayOutputStream stream, Map<String, String> map) throws IOException {
        stream.write(ByteInteger.int2Bytes(map.size()));
        for (Map.Entry<String, String> entry : map.entrySet()) {
            writeString(stream, entry.getKey() == null ? "" : entry.getKey());
            writeString(stream, entry.getValue() == null ? "" : entry.getValue());
        }
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
            int modelVersion = ByteInteger.bytes2Int(modelRawData, 4);
            if (modelVersion != VERSION && modelVersion != GUI_IMAGE_MODEL_VERSION
                && modelVersion != WHEEL_MODEL_VERSION && modelVersion != MODEL_VERSION
                && modelVersion != LINKS_MODEL_VERSION) {
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
            WheelDeclarations declarations = modelVersion >= WHEEL_MODEL_VERSION
                ? WheelDeclarations.decode(readString(tmp)) : WheelDeclarations.empty();
            Map<String, Integer> modelMapDataInfo = readMapDataInfo(tmp);
            Map<String, Integer> textureMapDataInfo = readMapDataInfo(tmp);
            Map<String, Integer> animationMapDataInfo = readMapDataInfo(tmp);
            Map<String, Integer> guiMapDataInfo = modelVersion >= GUI_IMAGE_MODEL_VERSION
                ? readMapDataInfo(tmp) : java.util.Collections.emptyMap();

            Map<String, byte[]> modelMapData = readMapData(tmp, modelMapDataInfo);
            Map<String, byte[]> textureMapData = readMapData(tmp, textureMapDataInfo);
            Map<String, byte[]> animationMapData = readMapData(tmp, animationMapDataInfo);

            Map<String, byte[]> guiMapData = readMapData(tmp, guiMapDataInfo);
            Map<String, Map<Integer, byte[]>> components = new LinkedHashMap<>();
            if (modelVersion >= MODEL_VERSION) {
                int count = readInt(tmp);
                if (count < 0 || count > tmp.available() / 8) throw new IOException("Invalid PBR base count");
                for (int i = 0; i < count; i++) {
                    String base = readString(tmp);
                    if (!textureMapData.containsKey(base) || components.containsKey(base)) {
                        throw new IOException("Invalid PBR base " + base);
                    }
                    int roles = readInt(tmp);
                    if (roles < 0 || roles > 2) throw new IOException("Invalid PBR role count");
                    Map<Integer, byte[]> values = new LinkedHashMap<>();
                    for (int j = 0; j < roles; j++) {
                        int role = readInt(tmp), size = readInt(tmp);
                        if (role != 1 && role != 2 || values.containsKey(role) || size < 0 || size > tmp.available()) {
                            throw new IOException("Invalid PBR component");
                        }
                        byte[] bytes = new byte[size];
                        if (size > 0 && tmp.read(bytes) != size) throw new IOException("Truncated PBR component");
                        values.put(role, bytes);
                    }
                    components.put(base, values);
                }
            }
            Map<String, String> links = modelVersion >= LINKS_MODEL_VERSION ? readStringMap(tmp)
                : java.util.Collections.emptyMap();
            if (tmp.available() != 0) {
                throw new IOException("Unexpected trailing model payload bytes");
            }
            return new ModelData(modelId, Type.UNKNOWN, modelMapData, textureMapData, animationMapData,
                guiMapData, declarations, components, links);
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
            if (size < 0 || size > tmp.available()) {
                throw new IOException("Invalid model resource size for " + name + ": " + size);
            }
            byte[] sizeBytes = new byte[size];
            int read = size == 0 ? 0 : tmp.read(sizeBytes);
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
        if (modelCount < 0 || modelCount > tmp.available() / 8) {
            throw new IOException("Invalid model resource count: " + modelCount);
        }
        for (int i = 0; i < modelCount; i++) {
            String name = readString(tmp);
            int size = readInt(tmp);
            if (size < 0 || mapDataInfo.put(name, size) != null) {
                throw new IOException("Invalid or duplicate model resource: " + name);
            }
        }
        return mapDataInfo;
    }

    /**
     * {@code LINKS_MODEL_VERSION}'s section: a count and then key/value string pairs, in declaration order.
     * <p>
     * Only ever called for a payload that declares that version or later, which is what makes an older payload read
     * as "no links" instead of throwing: the count and both bounds checks below mirror {@link #readMapDataInfo}'s,
     * so a truncated section fails this payload rather than silently shifting anything.
     */
    private static Map<String, String> readStringMap(ByteArrayInputStream tmp) throws IOException {
        int count = readInt(tmp);
        if (count < 0 || count > tmp.available() / 8) {
            throw new IOException("Invalid string map count: " + count);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            map.put(readString(tmp), readString(tmp));
        }
        return map;
    }

    @SuppressWarnings("all")
    private static String readString(ByteArrayInputStream stream) throws IOException {
        int size = readInt(stream);
        if (size < 0 || size > stream.available()) {
            throw new IOException("Invalid model resource name size: " + size);
        }
        byte[] stringBytes = new byte[size];
        int read = size == 0 ? 0 : stream.read(stringBytes);
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
