package com.fox.ysmu.model.manage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.network.message.RequestServerModelInfo;
import com.fox.ysmu.ysmu;

/**
 * 服务端侧的模型管理文件操作（phase55，LgeacyYSM 的 {@code ManageCommand.getFilesInfo} + {@code HandleFile}
 * 的移植）：列出 {@code config/ysmu/{custom,auth}} 顶层条目，并执行删除/移动/重命名。
 *
 * <p><b>列表范围。</b>只列顶层 FOLDER 与 {@code .ysm} 文件——本仓扫描器只加载这两种形态（Lgeacy 的列表还有
 * {@code .zip}，但本仓不解析 zip，列出无法加载的文件只会误导管理员，所有者也确认了这一裁剪）。符号链接不列也
 * 不允许操作：它可能指向目录之外，而删除目录会跟随链接。</p>
 *
 * <p><b>名字不可信。</b>条目名来自客户端。每个操作都先经 {@link #sanitizeEntryName} 清洗，再把
 * {@code root.resolve(name).normalize()} 的父目录与根的绝对规范化路径比较——名字里带 {@code /}、
 * {@code \\}、{@code ..} 或控制字符一律拒绝。解析出的条目还必须"像管理列表里的东西"（目录或 {@code .ysm}
 * 常规文件），否则拒绝。</p>
 *
 * <p><b>不覆盖。</b>移动/重命名的目标已存在时返回 {@link Outcome#DEST_EXISTS}，绝不覆盖已有模型；这是与
 * Lgeacy 的行为差异（它直接 {@code FileUtils.moveFile}，IOException 被 {@code printStackTrace} 吞掉，
 * 客户端看不到反馈）。</p>
 *
 * <p>这些操作只改磁盘文件，不触模型重载——与 Lgeacy 一致：要让变化生效需要 {@code /ysm reload}。</p>
 */
public final class ServerModelManage {

    /** 条目名长度上限（字符）。 */
    private static final int MAX_NAME_LENGTH = 100;
    /** 一次列表的条目上限，与 {@link RequestServerModelInfo#MAX_ENTRIES} 对齐。 */
    private static final int MAX_LIST_ENTRIES = RequestServerModelInfo.MAX_ENTRIES;
    /** 与 {@code ModelUploadInstaller} 一致的 Windows 保留设备名；重命名/管理对这些名字一律拒绝。 */
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
    /** 本仓扫描器识别为模型的文件后缀。 */
    private static final String MODEL_FILE_SUFFIX = ".ysm";

    /** 管理操作的结果；非 {@link #OK} 时带一个聊天提示键。 */
    public enum Outcome {
        OK(""),
        NOT_FOUND("commands.yes_steve_model.manage.error.not_found"),
        DEST_EXISTS("commands.yes_steve_model.manage.error.dest_exists"),
        INVALID_NAME("commands.yes_steve_model.manage.error.invalid_name"),
        UNSUPPORTED("commands.yes_steve_model.manage.error.unsupported"),
        IO_ERROR("commands.yes_steve_model.manage.error.io_error");

        private final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }

        /** 失败时给命令发送者的翻译键（{@code %s} 是条目名）；{@link #OK} 返回空串。 */
        public String messageKey() {
            return this.messageKey;
        }
    }

    private ServerModelManage() {}

    /**
     * 列出一个扫描根的顶层条目（文件夹 + {@code .ysm} 文件），按名字排序，最多
     * {@link RequestServerModelInfo#MAX_ENTRIES} 条。
     *
     * <p>目录大小是递归字节数（与 Lgeacy 的 {@code FileUtils.sizeOf} 一致）；目录不存在时返回空列表而不是
     * 报错——管理界面在首次使用前可能还没有这些目录，但 {@code ServerModelManager.reloadPacks()} 会创建它们。</p>
     */
    public static List<RequestServerModelInfo.Info> listEntries(Path root) {
        List<RequestServerModelInfo.Info> entries = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return entries;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path candidate : stream) {
                if (entries.size() >= MAX_LIST_ENTRIES) {
                    ysmu.LOG.warn("Stopping the YSM model-manage listing at {} entries", MAX_LIST_ENTRIES);
                    break;
                }
                String name = candidate.getFileName() == null ? null : candidate.getFileName()
                    .toString();
                if (name == null || Files.isSymbolicLink(candidate)) {
                    continue;
                }
                if (Files.isDirectory(candidate)) {
                    entries.add(new RequestServerModelInfo.Info(name, Type.FOLDER, FileUtils.sizeOf(candidate.toFile())));
                } else if (isModelFile(candidate)) {
                    entries.add(new RequestServerModelInfo.Info(name, Type.YSM, safeSize(candidate)));
                }
            }
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to list the YSM model directory {}", root, e);
        }
        entries.sort(Comparator.comparing(RequestServerModelInfo.Info::getFileName));
        return entries;
    }

    /** 删除一个顶层条目（目录递归删除）。 */
    public static Outcome delete(Path root, String name) {
        Path entry = resolveManagedEntry(root, name);
        if (entry == null) {
            return Outcome.INVALID_NAME;
        }
        if (Files.isDirectory(entry)) {
            try {
                FileUtils.deleteDirectory(entry.toFile());
                return Outcome.OK;
            } catch (IOException e) {
                ysmu.LOG.warn("[YSMU-MANAGE] Failed to delete the model directory {}", entry, e);
                return Outcome.IO_ERROR;
            }
        }
        try {
            Files.delete(entry);
            return Outcome.OK;
        } catch (IOException e) {
            ysmu.LOG.warn("[YSMU-MANAGE] Failed to delete the model file {}", entry, e);
            return Outcome.IO_ERROR;
        }
    }

    /** 把一个顶层条目从 {@code fromRoot} 移到 {@code toRoot}（custom↔auth）；目标已存在时拒绝。 */
    public static Outcome move(Path fromRoot, Path toRoot, String name) {
        Path source = resolveManagedEntry(fromRoot, name);
        if (source == null) {
            return Outcome.INVALID_NAME;
        }
        String safeName = source.getFileName()
            .toString();
        Path destination = resolveChild(toRoot, safeName);
        if (destination == null) {
            return Outcome.INVALID_NAME;
        }
        return moveEntry(source, destination);
    }

    /** 在同一根内改名。文件自动保留 {@code .ysm} 扩展名；目录不强制扩展名（与 Lgeacy 的 GUI 规则一致）。 */
    public static Outcome rename(Path root, String name, String newName) {
        Path source = resolveManagedEntry(root, name);
        if (source == null) {
            return Outcome.INVALID_NAME;
        }
        String safeNewName = sanitizeEntryName(newName);
        if (safeNewName == null) {
            return Outcome.INVALID_NAME;
        }
        if (!Files.isDirectory(source) && !safeNewName.toLowerCase(Locale.ROOT)
            .endsWith(MODEL_FILE_SUFFIX)) {
            safeNewName = safeNewName + MODEL_FILE_SUFFIX;
        }
        if (safeNewName.equals(
            source.getFileName()
                .toString())) {
            return Outcome.OK;
        }
        Path destination = resolveChild(root, safeNewName);
        if (destination == null) {
            return Outcome.INVALID_NAME;
        }
        return moveEntry(source, destination);
    }

    private static Outcome moveEntry(Path source, Path destination) {
        if (Files.exists(destination)) {
            return Outcome.DEST_EXISTS;
        }
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.move(source, destination);
            return Outcome.OK;
        } catch (IOException e) {
            ysmu.LOG.warn("[YSMU-MANAGE] Failed to move the model entry {} to {}", source, destination, e);
            return Outcome.IO_ERROR;
        }
    }

    /**
     * 名称清洗：去首尾空白、拒绝空名/过长/路径分隔符/控制字符/Windows 非法字符/保留设备名/首尾点或空格。
     * <p>与 {@code ModelUploadInstaller.sanitizeFileName} 的区别：这里不做"替换非法字符"或"补后缀"的修复，
     * 因为管理动作是对已有条目的精确操作，修复过的名字会指向不存在的条目；非法就返回 {@code null} 让调用方
     * 报错。</p>
     */
    public static String sanitizeEntryName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        if (".".equals(name) || "..".equals(name)) {
            return null;
        }
        if (name.charAt(0) == '.' || name.charAt(0) == ' '
            || name.charAt(name.length() - 1) == '.' || name.charAt(name.length() - 1) == ' ') {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '/' || c == '\\' || c == '<' || c == '>' || c == ':' || c == '"'
                || c == '|' || c == '?' || c == '*') {
                return null;
            }
        }
        if (WINDOWS_RESERVED_NAMES.contains(
            name.toUpperCase(Locale.ROOT))) {
            return null;
        }
        return name;
    }

    /**
     * 解析一个可管理的条目：名字必须能清洗、解析出的路径父目录必须是 {@code root} 本身、条目必须存在且是
     * 目录或 {@code .ysm} 常规文件、且不是符号链接。
     *
     * @return 条目路径；任何一项不满足返回 {@code null}
     */
    private static Path resolveManagedEntry(Path root, String name) {
        String safeName = sanitizeEntryName(name);
        if (safeName == null) {
            return null;
        }
        Path entry = resolveChild(root, safeName);
        if (entry == null || !Files.exists(entry)) {
            return null;
        }
        if (Files.isSymbolicLink(entry)) {
            return null;
        }
        if (Files.isDirectory(entry)) {
            return entry;
        }
        return isModelFile(entry) ? entry : null;
    }

    private static Path resolveChild(Path root, String safeName) {
        if (root == null) {
            return null;
        }
        Path normalizedRoot = root.toAbsolutePath()
            .normalize();
        Path candidate = normalizedRoot.resolve(safeName)
            .normalize();
        if (!normalizedRoot.equals(candidate.getParent())) {
            return null;
        }
        return candidate;
    }

    private static boolean isModelFile(Path path) {
        String name = path.getFileName() == null ? null : path.getFileName()
            .toString();
        return name != null && Files.isRegularFile(path) && name.toLowerCase(Locale.ROOT)
            .endsWith(MODEL_FILE_SUFFIX);
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
