package com.fox.ysmu.model.upload;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

import com.fox.ysmu.ysmu;

/**
 * 把一个**已经过 {@link ModelFileValidator} 校验**的模型字节装进目标目录（phase55）。
 *
 * <p>职责只有两件：文件名清洗（网络送来的"建议文件名"不可信）和落盘策略（重名改名、临时文件 + 原子改名）。
 * 校验与安装合并在 {@link #install(ModelUploadTarget, String, byte[])} 里，避免"先校验一次、写盘前再解析
 * 一次"的双倍开销。</p>
 *
 * <p><b>为什么文件名要清洗两次。</b>客户端发来的名字只是"建议"：服务端先取最后一段（去掉任何目录成分，
 * 阻止 {@code ../../outside.ysm} 这类路径穿越），再把控制字符/Windows 非法字符换成 {@code _}，确保
 * {@code .ysm} 后缀、限制长度、避开 Windows 设备名。最终写入点由 {@link ModelUploadTarget} 的固定目录加
 * 清洗后的裸文件名决定，任何输入都不能把文件写到目标目录之外。</p>
 *
 * <p><b>重名不覆盖。</b>所有者要求 {@code model.ysm} 已存在时依次尝试 {@code model_1.ysm}、
 * {@code model_2.ysm}……；序列写满仍无空位时按写盘失败处理。上传是"装一个新模型"，覆盖会静默毁掉服务器上
 * 已有模型，所以这里不做覆盖。</p>
 */
public final class ModelUploadInstaller {

    /** 目标目录里的文件后缀；扫描器只收 {@code .ysm}。 */
    public static final String MODEL_SUFFIX = ".ysm";
    /** 清洗后的文件名长度上限（含后缀）。 */
    private static final int MAX_FILE_NAME_LENGTH = 100;
    /** 重名时尝试的序号上限。 */
    private static final int MAX_NAME_SUFFIX = 999;
    /** Windows 保留设备名：即使目标不是 Windows，带上它们也不会有坏处。 */
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "COM1",
        "COM2",
        "COM3",
        "COM4",
        "COM5",
        "COM6",
        "COM7",
        "COM8",
        "COM9",
        "LPT1",
        "LPT2",
        "LPT3",
        "LPT4",
        "LPT5",
        "LPT6",
        "LPT7",
        "LPT8",
        "LPT9");

    /** 命名与写盘的串行化锁：重名判定的"检查再写"必须在同一把锁里，否则两个同名上传会撞车。 */
    private static final Object INSTALL_LOCK = new Object();

    private ModelUploadInstaller() {}

    /** 安装结果。 */
    public static final class InstallResult {

        public enum Outcome {
            /** 文件已落盘。 */
            OK,
            /** 内容不是有效模型，未写盘。 */
            INVALID,
            /** 内容有效，但写盘失败（目录不可写、重名序列耗尽等）。 */
            WRITE_FAILED
        }

        private final Outcome outcome;
        private final ModelFileValidator.Result validation;
        private final Path installedPath;
        private final String relativePath;
        private final String detail;

        private InstallResult(Outcome outcome, ModelFileValidator.Result validation, Path installedPath,
            String relativePath, String detail) {
            this.outcome = outcome;
            this.validation = validation;
            this.installedPath = installedPath;
            this.relativePath = relativePath;
            this.detail = detail;
        }

        public Outcome outcome() {
            return this.outcome;
        }

        /** 只有 {@link Outcome#INVALID} 时非 {@code null}，供调用方打印拒绝原因。 */
        public ModelFileValidator.Result validation() {
            return this.validation;
        }

        public Path installedPath() {
            return this.installedPath;
        }

        /** 面向玩家的落点，形如 {@code custom/model_1.ysm}。 */
        public String relativePath() {
            return this.relativePath;
        }

        public String detail() {
            return this.detail;
        }
    }

    /**
     * 校验并安装。校验不通过时**不触碰磁盘**；写盘先写同目录临时文件，再原子改名，失败时清理临时文件。
     *
     * @param target 目标目录（命令词固定，网络字节不能指定路径）
     * @param proposedFileName 客户端/命令里带来的建议文件名，可为任意字符串，会被清洗
     * @param data 完整文件字节
     */
    public static InstallResult install(ModelUploadTarget target, String proposedFileName, byte[] data) {
        return installInto(
            target.root()
                .toAbsolutePath()
                .normalize(),
            target.commandWord(),
            proposedFileName,
            data);
    }

    /**
     * 安装进任意目录的同一条实现；{@link #install} 用它，测试也用它（测试必须写进临时目录，不能往工程根的
     * {@code config/ysmu} 里写）。生产调用点只有 {@link #install}。
     *
     * @param root 目标目录（会被创建）
     * @param commandWord 只用于回执里的落点前缀（{@code custom}/{@code auth}）
     */
    static InstallResult installInto(Path root, String commandWord, String proposedFileName, byte[] data) {
        ModelFileValidator.Result validation = ModelFileValidator.validate(data);
        if (!validation.isValid()) {
            return new InstallResult(
                InstallResult.Outcome.INVALID,
                validation,
                null,
                null,
                ModelFileValidator.describe(validation));
        }
        String sanitized = sanitizeFileName(proposedFileName);
        synchronized (INSTALL_LOCK) {
            try {
                Files.createDirectories(root);
                String finalName = uniqueFileName(root, sanitized);
                if (finalName == null) {
                    return new InstallResult(
                        InstallResult.Outcome.WRITE_FAILED,
                        null,
                        null,
                        null,
                        "more than " + MAX_NAME_SUFFIX + " files are already named like " + sanitized);
                }
                Path destination = root.resolve(finalName)
                    .normalize();
                if (!root.equals(destination.getParent())) {
                    return new InstallResult(
                        InstallResult.Outcome.WRITE_FAILED,
                        null,
                        null,
                        null,
                        "the sanitized file name escaped " + root);
                }
                writeAtomically(root, destination, data);
                String relative = commandWord + "/" + finalName;
                ysmu.LOG.info("[YSMU-UPLOAD] Installed uploaded model as {}", relative);
                return new InstallResult(InstallResult.Outcome.OK, null, destination, relative, "");
            } catch (IOException | RuntimeException e) {
                ysmu.LOG.warn("[YSMU-UPLOAD] Failed to write an uploaded model into {}", root, e);
                return new InstallResult(
                    InstallResult.Outcome.WRITE_FAILED,
                    null,
                    null,
                    null,
                    String.valueOf(e));
            }
        }
    }

    private static void writeAtomically(Path dir, Path destination, byte[] data) throws IOException {
        Path temporary = Files.createTempFile(dir, ".upload-", ".part");
        boolean moved = false;
        try {
            Files.write(temporary, data, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // 某些文件系统（网络盘/部分 Windows 卷）不支持原子改名；先尝试不带 REPLACE 的普通改名，
                // 目标已存在就失败而不是覆盖。
                Files.move(temporary, destination);
            }
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    ysmu.LOG.warn("[YSMU-UPLOAD] Failed to delete temporary upload file {}", temporary, cleanupFailure);
                }
            }
        }
    }

    /**
     * 把任意字符串洗成一个可安全落进目标目录的 {@code .ysm} 文件名。
     *
     * <p>步骤：去掉目录成分（最后一个 {@code /} 或 {@code \\} 之后的部分）→ 去掉首尾空白 → 控制字符与
     * Windows 非法字符 {@code <>:"|?*} 换成 {@code _} → 去掉首尾的点与空格（Windows 不允许以点/空格结尾）
     * → 避开 Windows 设备名 → 确保 {@code .ysm} 后缀 → 截断到 {@link #MAX_FILE_NAME_LENGTH} 并保留后缀。
     * 空结果退回 {@code model.ysm}。</p>
     */
    static String sanitizeFileName(String proposedFileName) {
        String name = proposedFileName == null ? "" : proposedFileName.trim();
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        StringBuilder cleaned = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '<' || c == '>' || c == ':' || c == '"' || c == '|' || c == '?'
                || c == '*') {
                cleaned.append('_');
            } else {
                cleaned.append(c);
            }
        }
        String result = trimDotsAndSpaces(cleaned.toString());
        if (result.isEmpty()) {
            result = "model";
        }
        if (!result.toLowerCase(Locale.ROOT)
            .endsWith(MODEL_SUFFIX)) {
            result = result + MODEL_SUFFIX;
        }
        if (result.length() > MAX_FILE_NAME_LENGTH) {
            String stem = result.substring(0, result.length() - MODEL_SUFFIX.length());
            stem = trimDotsAndSpaces(stem.substring(0, Math.min(stem.length(), MAX_FILE_NAME_LENGTH - MODEL_SUFFIX
                .length())));
            if (stem.isEmpty()) {
                stem = "model";
            }
            result = stem + MODEL_SUFFIX;
        }
        String stem = result.substring(0, result.length() - MODEL_SUFFIX.length())
            .toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(stem)) {
            result = "_" + result;
        }
        return result;
    }

    private static String trimDotsAndSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '.' || value.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * 在目标目录里找一个空位：{@code wanted} 本身、否则 {@code stem_1.ysm}、{@code stem_2.ysm}……
     *
     * @return 可用的裸文件名；序列耗尽时 {@code null}。调用方必须持有 {@link #INSTALL_LOCK}。
     */
    static String uniqueFileName(Path dir, String wanted) {
        if (!Files.exists(dir.resolve(wanted))) {
            return wanted;
        }
        String stem = wanted.substring(0, wanted.length() - MODEL_SUFFIX.length());
        for (int i = 1; i <= MAX_NAME_SUFFIX; i++) {
            String candidate = stem + "_" + i + MODEL_SUFFIX;
            if (!Files.exists(dir.resolve(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
