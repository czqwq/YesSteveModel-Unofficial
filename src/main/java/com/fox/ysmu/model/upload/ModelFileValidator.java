package com.fox.ysmu.model.upload;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fox.ysmu.Config;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmFormat;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.ByteInteger;
import com.fox.ysmu.util.YesModelUtils;

import rip.ysm.security.YsmCrypt;
import software.bernie.geckolib3.geo.raw.pojo.Converter;

/**
 * 一个 {@code .ysm} 文件"是不是这个模组能装的模型"的判定（phase55，{@code /ysm upload} 的安全地基）。
 *
 * <p><b>为什么需要它。</b>上游 LgeacyYSM 的 GUI 上传只检查权限就把客户端字节写盘
 * （{@code tmp/LgeacyYSM-1.20.1-forge/.../network/message/UploadFile.java}）；本仓的上传入口要求
 * "上传到服务端前一定要校验是否是正常/有效的 ysm 文件，防止注入攻击"，所以客户端在发送前、服务端在写盘前
 * 用同一个判定各校验一次。服务端永远独立校验，客户端那一次只为省带宽与给出更快的错误提示。</p>
 *
 * <p><b>判定复用扫描器自己的路径，不另写一套"看起来像"的规则。</b>两个合法容器：</p>
 * <ul>
 *   <li>legacy YSGP 容器（{@link YesModelUtils#input(byte[], String, long, int)}）：要求 {@code main.json}、
 *       {@code arm.json}、至少一个 {@code .png}，与 {@code model/format/YsmFormat.cacheAllModels:57-79} 的
 *       收录条件一致；两个几何 JSON 还要能过 {@link Converter#fromJsonString(String)}，与同一扫描器
 *       {@code getModelData:140-145} 的解析一致。条目名不得带路径分隔符或控制字符——即使当前扫描器只在内存里
 *       用这些名字，也不给未来任何"按名字落盘"的路径留穿越面。</li>
 *   <li>OpenYSM 二进制容器（{@link OpenYsmFormat#isOpenYsmBinary(byte[])} 判前缀）：解密、反序列化、
 *       footer、{@link RawYsmModelAdapter#isBridgeable(RawYsmModel)} 全部通过才算有效，
 *       与 {@code OpenYsmFormat.cacheBinaryModel:122-197} 的收录链一致。</li>
 * </ul>
 *
 * <p><b>资源上限。</b>文件本身上限来自 {@link Config#MAX_MODEL_UPLOAD_MB}；解压输出总预算见
 * {@link #maxDecompressedBytes()}（deflate/zstd 的压缩比没有上界，网络字节必须先证明撑不出 OOM）；条目数
 * 上限见 {@link #MAX_ENTRIES}。校验在后台线程执行，调用方负责 catch 异常并给出回执。</p>
 *
 * <p>只做**只读解析**：不解包到磁盘、不加载类、不执行任何模型内容，因此不会把上传变成代码注入面。解析器
 * 自身对长度前缀的防御见 {@code YSMByteBuf.readString/readByteArray} 与
 * {@code YSMBinaryDeserializer} 的 visibleBoundsOffset 校验（同为 phase55 的加固）。</p>
 */
public final class ModelFileValidator {

    /** 解压后总字节预算的下限：小模型（数 MB）在任何配置下都能通过。 */
    private static final long MIN_DECOMPRESSED_BUDGET = 64L * 1024L * 1024L;
    /** 解压预算相对上传上限的倍数：合法模型（PNG + JSON）的压缩比通常远低于此。 */
    private static final long DECOMPRESSED_BUDGET_FACTOR = 4L;
    /** 条目数上限：legacy 容器每个文件一个条目，4096 对任何真实模型都绰绰有余。 */
    public static final int MAX_ENTRIES = 4096;
    /** 条目名长度上限；真实条目是 main.json / arm.json / *.animation.json / *.png。 */
    private static final int MAX_ENTRY_NAME_LENGTH = 200;

    private ModelFileValidator() {}

    /** 判定结果。{@link Reason#OK} 以外的原因名用于服务端日志与聊天栏回执。 */
    public static final class Result {

        public enum Reason {
            OK,
            EMPTY,
            TOO_LARGE,
            NOT_YSM,
            CORRUPT,
            MISSING_MODEL_FILES,
            INVALID_ENTRY_NAME,
            INVALID_GEOMETRY,
            UNBRIDGEABLE
        }

        private final Reason reason;
        private final String detail;

        private Result(Reason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }

        public boolean isValid() {
            return this.reason == Reason.OK;
        }

        public Reason reason() {
            return this.reason;
        }

        /** 面向服务端/客户端日志的细节；聊天栏只用 {@link #reason()} 的名字。 */
        public String detail() {
            return this.detail;
        }

        @Override
        public String toString() {
            return this.reason + (this.detail == null || this.detail.isEmpty() ? "" : ": " + this.detail);
        }
    }

    private static Result ok() {
        return new Result(Result.Reason.OK, "");
    }

    private static Result reject(Result.Reason reason, String detail) {
        return new Result(reason, detail == null ? "" : detail);
    }

    /** {@link Config#MAX_MODEL_UPLOAD_MB} 换算成的字节数；配置被写坏时退回默认 64 MiB。 */
    public static long maxUploadBytes() {
        return maxUploadMb() * 1024L * 1024L;
    }

    /** {@link Config#MAX_MODEL_UPLOAD_MB} 的生效值（剔除坏配置后的 MiB 数），用于聊天回执。 */
    public static int maxUploadMb() {
        int megabytes = Config.MAX_MODEL_UPLOAD_MB;
        if (megabytes <= 0 || megabytes > 512) {
            megabytes = 64;
        }
        return megabytes;
    }

    /**
     * 单次校验允许的解压总字节数：{@code max(64 MiB, 4 × 上传上限)}。
     * <p>
     * 上传上限默认 64 MiB 时该预算是 256 MiB；配置文件把它调到 512 MiB 时预算是 2 GiB。预算随上传上限
     * 增长是有意的（否则大模型的合法压缩比会被误杀），代价是"一次上传最多让服务端在一个后台线程里持有
     * 这么多字节"——只有权限等级 4 的玩家能触发，且必须在文件上限之内。
     * </p>
     */
    public static long maxDecompressedBytes() {
        long scaled = maxUploadBytes() * DECOMPRESSED_BUDGET_FACTOR;
        return Math.max(MIN_DECOMPRESSED_BUDGET, Math.min(scaled, Integer.MAX_VALUE));
    }

    /**
     * 校验一份完整的 {@code .ysm} 文件字节。
     *
     * @return 永不返回 {@code null}；失败时 {@link Result#detail()} 给日志用的原因。
     */
    public static Result validate(byte[] data) {
        if (data == null || data.length == 0) {
            return reject(Result.Reason.EMPTY, "the file is empty");
        }
        long maxUploadBytes = maxUploadBytes();
        if (data.length > maxUploadBytes) {
            return reject(
                Result.Reason.TOO_LARGE,
                data.length + " bytes is above the " + maxUploadBytes + " byte upload limit");
        }
        if (isLegacyContainer(data)) {
            return validateLegacy(data);
        }
        if (OpenYsmFormat.isOpenYsmBinary(data)) {
            return validateOpenYsmBinary(data);
        }
        return reject(Result.Reason.NOT_YSM, "neither a YSGP legacy container nor an OpenYSM binary file");
    }

    private static boolean isLegacyContainer(byte[] data) {
        if (data.length < 24) {
            return false;
        }
        if (ByteInteger.bytes2Int(data, 0) != YesModelUtils.HEAD) {
            return false;
        }
        int version = ByteInteger.bytes2Int(data, 4);
        return version == YesModelUtils.VERSION || version == YesModelUtils.VERSION_II;
    }

    private static Result validateLegacy(byte[] data) {
        Map<String, byte[]> entries;
        try {
            // strict：任何条目坏掉都整体拒绝，既符合"有效模型文件"的语义，也避免"上千个解压炸弹条目各烧一遍
            // 预算"的 CPU 放大（见 YesModelUtils.inputStrict 的 javadoc）。
            entries = YesModelUtils.inputStrict(data, "network model upload", maxDecompressedBytes(), MAX_ENTRIES);
        } catch (Exception | LinkageError e) {
            return reject(Result.Reason.CORRUPT, "the YSGP container could not be decoded: " + e);
        }
        if (entries.isEmpty()) {
            // 头部对但整体 MD5 不匹配，或没有任何可解条目：按损坏处理。
            return reject(Result.Reason.CORRUPT, "the YSGP container decoded to no entries (md5 mismatch?)");
        }
        boolean hasMain = false;
        boolean hasArm = false;
        boolean hasTexture = false;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!isSafeEntryName(name)) {
                return reject(Result.Reason.INVALID_ENTRY_NAME, "'" + name + "' is not a flat file name");
            }
            if (ServerModelManager.MAIN_MODEL_FILE_NAME.equals(name)) {
                hasMain = true;
            } else if (ServerModelManager.ARM_MODEL_FILE_NAME.equals(name)) {
                hasArm = true;
            }
            if (name.endsWith(".png")) {
                hasTexture = true;
            }
        }
        if (!hasMain || !hasArm || !hasTexture) {
            return reject(
                Result.Reason.MISSING_MODEL_FILES,
                "main=" + hasMain + ", arm=" + hasArm + ", texture=" + hasTexture);
        }
        for (String geometryName : new String[] { ServerModelManager.MAIN_MODEL_FILE_NAME,
            ServerModelManager.ARM_MODEL_FILE_NAME }) {
            try {
                Converter.fromJsonString(new String(entries.get(geometryName), StandardCharsets.UTF_8));
            } catch (Exception | LinkageError e) {
                return reject(Result.Reason.INVALID_GEOMETRY, geometryName + " does not parse: " + e);
            }
        }
        return ok();
    }

    private static Result validateOpenYsmBinary(byte[] data) {
        byte[] raw;
        try {
            raw = YsmCrypt.decryptYsmFile(data, maxDecompressedBytes());
        } catch (Exception | LinkageError e) {
            return reject(Result.Reason.CORRUPT, "the OpenYSM container could not be decrypted: " + e);
        }
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(raw)) {
            RawYsmModel model = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(model);
            model.modelId = "<upload>";
            if (!RawYsmModelAdapter.isBridgeable(model)) {
                return reject(
                    Result.Reason.UNBRIDGEABLE,
                    "the model parsed but cannot be bridged to this port's legacy ModelData");
            }
            return ok();
        } catch (Exception | LinkageError e) {
            return reject(Result.Reason.CORRUPT, "the OpenYSM payload could not be parsed: " + e);
        }
    }

    /**
     * 条目名是否是一个扁平、可安全展示/使用的文件名。
     * <p>
     * 扫描器目前只在内存里用这些名字当 map key，不做落盘，所以这不是在修一个现存漏洞；它的目的正是让
     * "以后某条路径按条目名写盘" 不会把上传变成路径穿越。因此拒绝的范围比扫描器现在容忍的更窄：
     * 路径分隔符、{@code .}/{@code ..}、控制字符、空名、超长名。
     * </p>
     */
    static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_ENTRY_NAME_LENGTH) {
            return false;
        }
        if (".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** 测试/诊断用：把判定结果写进日志的一行（调用方决定级别）。 */
    public static String describe(Result result) {
        return result == null ? "null" : result.toString();
    }
}
