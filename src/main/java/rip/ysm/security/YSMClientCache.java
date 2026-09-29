package rip.ysm.security;

import rip.ysm.algorithms.CityHash;
import rip.ysm.algorithms.MT19937;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fox.ysmu.ysmu;

public class YSMClientCache {

    public static String generateCacheFileName(long hash1, long hash2, byte[] rtKey) {
        if (rtKey == null || rtKey.length != 56) return null;
        // S-04（记录级）：该 seed 与参考实现逐字节相同，源码注释 "todo: 换成真随机数" 也是上游原文。
        // 缓存文件名因此可预测，但内容仍由 CityHash 签名 + verifyFileContent 校验，不构成安全缺陷；
        // 这里保持与参考一致（换 seed 会让已有客户端缓存全部失效），只在审计记录里登记。
        int seed = 114514;

        MT19937 mt = new MT19937(Integer.toUnsignedLong(seed));
        long m1 = hash1 ^ mt.extract_number();
        long m2 = hash2 ^ mt.extract_number();

        ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(seed);
        buf.putLong(m1);
        buf.putLong(m2);
        byte[] bufArray = buf.array();

        for (int i = 0; i < bufArray.length; i++) {
            bufArray[i] ^= rtKey[i % rtKey.length];
        }

        StringBuilder sb = new StringBuilder(40);
        for (byte b : bufArray) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static boolean verifyFileContent(File cacheFile, long hash1, long hash2) {
        if (cacheFile == null || !cacheFile.exists() || cacheFile.length() <= 8) {
            return false;
        }

        try {
            byte[] fileData = Files.readAllBytes(cacheFile.toPath());
            int payloadLen = fileData.length - 8;

            long realHash = ByteBuffer.wrap(fileData, payloadLen, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

            byte[] payload = Arrays.copyOfRange(fileData, 0, payloadLen);
            CityHash ch = new CityHash();
            long calculatedHash = ch.hash64WithSeed(payload, YsmCrypt.SEED_CACHE_VERIFICATION);

            long verif = calculatedHash ^ hash1 ^ hash2;
            return verif == realHash;
        } catch (Exception e) {
            // M-16：旧实现静默 return false（“文件不可读”与“签名不符”无法区分，latest.log 里查不到线索）。
            // 返回语义不变，只补一条日志。
            ysmu.LOG.warn("Failed to verify YSM client cache file {}", cacheFile, e);
            return false;
        }
    }

    public static UUID getModelUUIDFromFileName(String fileName, byte[] rtKey) {
        if (fileName == null || fileName.length() != 40 || rtKey == null || rtKey.length != 56) {
            return null;
        }

        try {
            byte[] buf = new byte[20];
            for (int i = 0; i < 20; i++) {
                int high = Character.digit(fileName.charAt(i * 2), 16);
                int low = Character.digit(fileName.charAt(i * 2 + 1), 16);
                if (high == -1 || low == -1) return null;
                buf[i] = (byte) ((high << 4) | low);
            }

            for (int i = 0; i < buf.length; i++) {
                buf[i] ^= rtKey[i % rtKey.length];
            }

            ByteBuffer byteBuf = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int seed = byteBuf.getInt();
            long m1 = byteBuf.getLong();
            long m2 = byteBuf.getLong();

            MT19937 mt = new MT19937(Integer.toUnsignedLong(seed));
            long hash1 = m1 ^ mt.extract_number();
            long hash2 = m2 ^ mt.extract_number();

            return new UUID(hash1, hash2);
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<UUID, File> buildCacheIndex(File cacheDir, byte[] rtKey) {
        Map<UUID, File> cacheIndex = new HashMap<>();

        if (!cacheDir.exists() || !cacheDir.isDirectory()) {
            return cacheIndex;
        }

        File[] files = cacheDir.listFiles();
        if (files == null) return cacheIndex;

        for (File file : files) {
            if (file.isFile()) {
                UUID realModelUuid = getModelUUIDFromFileName(file.getName(), rtKey);
                if (realModelUuid != null) {
                    cacheIndex.put(realModelUuid, file);
                }
            }
        }
        return cacheIndex;
    }
}
