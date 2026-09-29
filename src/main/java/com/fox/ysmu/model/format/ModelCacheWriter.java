package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.CACHE_SERVER;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SERVER_KEY;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.YSMBinarySerializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.Md5Utils;
import com.fox.ysmu.ysmu;

import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YsmCrypt;

public final class ModelCacheWriter {

    private static final int OPEN_YSM_SYNC_FORMAT = 32;

    /**
     * Bump this whenever the baked payload changes meaning - the serializer, {@code RawYsmModelAdapter}, or which
     * parts of a model the deserializer bakes.
     * <p>
     * The sync cache file name is derived from the model's own sha256 (see {@link #hashSourceFor}), which says
     * nothing about the code that baked it. Without this salt a payload written by an older build keeps passing
     * {@link #isStoredOpenYsmCacheValid} - the signature still matches - and is served to every client whose cache
     * hashes match as well, so a model can stay invisible across reloads and restarts even after the bug that baked
     * it was fixed. Bumping the value changes every hash, which re-bakes the payload and makes the client miss.
     */
    static final int OPEN_YSM_BAKE_VERSION = 2;

    /**
     * M-19:传统缓存容器的最小长度 = 幻数/版本(8) + 载荷 MD5(16) + 至少一个 AES 分组(16)。
     * 短于它的"缓存"一定是坏文件,不允许写盘。
     */
    private static final int MIN_CACHE_FILE_LENGTH = 8 + ServerModelInfo.CACHE_HEADER_MD5_LENGTH + 16;

    /**
     * M-19:OpenYSM 二进制同步载荷的最小长度(序列化后的干净字节,至少要有 format/footer 骨架)。
     */
    private static final int MIN_OPEN_YSM_PAYLOAD_LENGTH = 16;

    /** 本类原子写入使用的临时文件后缀(清理陈旧缓存时会连同它们一起回收)。 */
    private static final String TEMP_FILE_SUFFIX = ".tmp";

    private ModelCacheWriter() {}

    static ServerModelInfo write(ModelData data) throws Exception {
        byte[] dataBytes = EncryptTools.assembleEncryptModels(data);
        data.setMd5(Md5Utils.md5Hex(dataBytes).toUpperCase(Locale.US));

        // M-19:写完之前的自检 —— 容器结构合法、头部 MD5 与载荷一致。
        if (dataBytes.length < MIN_CACHE_FILE_LENGTH) {
            throw new IOException("Refusing to write a truncated model cache (" + dataBytes.length + " bytes)");
        }

        String md5 = data.getInfo()
            .getMd5();
        File target = CACHE_SERVER.resolve(md5)
            .toFile();
        // M-09:文件名就是内容 MD5,磁盘上已有同名且内容一致的文件时直接复用,不再重写。
        if (isStoredLegacyCacheValid(target, md5)) {
            return data.getInfo();
        }

        writeAtomically(target, dataBytes);
        return data.getInfo();
    }

    static OpenYsmSyncInfo writeOpenYsm(RawYsmModel raw, String modelId) throws Exception {
        if (OPEN_YSM_SERVER_KEY == null || OPEN_YSM_SERVER_KEY.length != 56) {
            throw new IllegalStateException("OpenYSM server key is not initialized");
        }

        String hashSource = hashSourceFor(raw, modelId);
        long[] hashes = YsmCrypt.calculateModelHashes(hashSource, OPEN_YSM_SERVER_KEY);
        String cacheFileName = String.format(Locale.US, "%016x%016x", hashes[0], hashes[1]);
        File target = CACHE_SERVER.resolve(cacheFileName)
            .toFile();

        // M-09:复用磁盘上已通过签名校验(rip.ysm.security.YsmCrypt#verifyServerCache)的缓存,
        // 而不是每次 reload 都重算重写。
        if (isStoredOpenYsmCacheValid(target, hashes[0], hashes[1])) {
            return new OpenYsmSyncInfo(modelId, cacheFileName, hashes[0], hashes[1], OPEN_YSM_SYNC_FORMAT, false);
        }

        byte[] clearBytes = serializeForOpenYsmSync(raw);
        if (clearBytes.length < MIN_OPEN_YSM_PAYLOAD_LENGTH) {
            throw new IOException("Refusing to write a truncated OpenYSM cache for " + modelId);
        }
        byte[] cacheBytes = YsmCrypt.encryptServerCache(clearBytes, OPEN_YSM_SERVER_KEY, hashes[0], hashes[1]);
        writeAtomically(target, cacheBytes);
        return new OpenYsmSyncInfo(modelId, cacheFileName, hashes[0], hashes[1], OPEN_YSM_SYNC_FORMAT, false);
    }

    /** M-10:名字 == 内容 MD5 的口径,见 {@link ServerModelInfo#matchesCacheContent(String, byte[])}。 */
    private static boolean isStoredLegacyCacheValid(File target, String expectedMd5) {
        if (expectedMd5 == null || !target.isFile()) {
            return false;
        }
        if (!ServerModelInfo.hasWellFormedCacheFileName(target.getName())
            || !target.getName()
                .equalsIgnoreCase(expectedMd5)) {
            return false;
        }
        try {
            return ServerModelInfo.matchesCacheContent(
                target.getName(),
                Files.readAllBytes(target.toPath()));
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to verify existing model cache {}", target, e);
            return false;
        }
    }

    private static boolean isStoredOpenYsmCacheValid(File target, long hash1, long hash2) {
        if (!target.isFile()) {
            return false;
        }
        try {
            return YsmCrypt.verifyServerCache(Files.readAllBytes(target.toPath()), hash1, hash2);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to verify existing OpenYSM cache {}", target, e);
            return false;
        }
    }

    /** 先写临时文件再原子替换,避免客户端/其他线程读到写了一半的缓存。 */
    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        Path directory = target.toPath()
            .getParent();
        Path temp = Files.createTempFile(directory, target.getName(), TEMP_FILE_SUFFIX);
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * M-23:不再就地改共享的 {@link RawYsmModel}(dev 基线会在序列化期间把 {@code soundFiles}
     * 换成空表再换回来,任何并发的读者都可能看到一个被临时清空的模型)。
     * 这里改为写一个只替换 {@code soundFiles} 的浅拷贝。
     */
    private static byte[] serializeForOpenYsmSync(RawYsmModel raw) {
        RawYsmModel model = raw;
        if (!Config.ACCEPT_SOUND_FX && raw.soundFiles != null && !raw.soundFiles.isEmpty()) {
            model = copyWithoutSoundFiles(raw);
        }
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(model, OPEN_YSM_SYNC_FORMAT, true)) {
            return serialized.toArray();
        }
    }

    private static RawYsmModel copyWithoutSoundFiles(RawYsmModel raw) {
        RawYsmModel copy = new RawYsmModel();
        copy.modelId = raw.modelId;
        copy.formatVersion = raw.formatVersion;
        copy.metadata = raw.metadata;
        copy.properties = raw.properties;
        copy.mainEntity = raw.mainEntity;
        copy.vehicles = raw.vehicles;
        copy.projectiles = raw.projectiles;
        copy.soundFiles = new LinkedHashMap<>();
        copy.functionFiles = raw.functionFiles;
        copy.languageFiles = raw.languageFiles;
        copy.footer = raw.footer;
        return copy;
    }

    /**
     * The content the sync cache hash is derived from.
     * <p>
     * The baked payload is identified by the model's own sha256, plus the {@link #OPEN_YSM_BAKE_VERSION}/
     * {@link #OPEN_YSM_SYNC_FORMAT} pair, so a build that bakes differently produces different hashes for the same
     * model instead of reusing the older payload.
     */
    static String hashSourceFor(RawYsmModel raw, String modelId) {
        String content;
        if (raw != null && raw.properties != null && raw.properties.sha256 != null
            && !raw.properties.sha256.isEmpty()) {
            content = raw.properties.sha256;
        } else if (raw != null && raw.modelId != null && !raw.modelId.isEmpty()) {
            content = raw.modelId;
        } else {
            content = modelId;
        }
        return OPEN_YSM_BAKE_VERSION + "|" + OPEN_YSM_SYNC_FORMAT + "|" + content;
    }

    /** M-09:供 {@code ServerModelManager.reloadPacks()} 判断某个缓存文件本次是否仍被登记。 */
    public static boolean isRegisteredCacheFile(String fileName, Map<String, ServerModelInfo> legacy,
        Map<String, OpenYsmSyncInfo> openYsm) {
        for (ServerModelInfo info : legacy.values()) {
            if (info != null && info.getMd5() != null && info.getMd5()
                .equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        for (OpenYsmSyncInfo info : openYsm.values()) {
            if (info != null && fileName.equals(info.getCacheFileName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * M-09:按本次 reload 的登记结果清理 {@code cache/server} 里的陈旧缓存,保留密码文件。
     * 只删除"32 位十六进制"这一种缓存命名,以及本类自己产生的 {@code *.tmp} 写入产物;
     * 其余文件可能是用户的其它数据,一律不动。
     *
     * @return 被删除的文件数
     */
    public static int pruneUnregisteredCaches(Map<String, ServerModelInfo> legacy,
        Map<String, OpenYsmSyncInfo> openYsm) {
        File[] files = CACHE_SERVER.toFile()
            .listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile()) {
                continue;
            }
            boolean staleTempFile = name.endsWith(TEMP_FILE_SUFFIX);
            if (!staleTempFile) {
                if ("PASSWORD".equals(name) || !ServerModelInfo.hasWellFormedCacheFileName(name)) {
                    continue;
                }
                if (isRegisteredCacheFile(name, legacy, openYsm)) {
                    continue;
                }
            }
            try {
                if (Files.deleteIfExists(file.toPath())) {
                    removed++;
                }
            } catch (IOException e) {
                ysmu.LOG.warn("Failed to delete stale model cache {}", file, e);
            }
        }
        if (removed > 0) {
            ysmu.LOG.info("Removed {} stale model cache file(s) from {}", removed, CACHE_SERVER);
        }
        return removed;
    }
}
