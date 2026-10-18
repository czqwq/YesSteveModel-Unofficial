package com.fox.ysmu.model.upload;

import java.nio.file.Path;

import com.fox.ysmu.model.ServerModelManager;

/**
 * 上传目标目录。phase55：{@code /ysm upload <auth|custom> <path>} 的两个目录，命令词、线号与磁盘目录的
 * 唯一映射。
 *
 * <p>目录本身是固定的：网络字节只能从这两个值里选一个，永远不能携带路径。落盘文件名另由
 * {@link ModelUploadInstaller} 清洗，两者合起来保证写入点只能在 {@code config/ysmu/auth} 或
 * {@code config/ysmu/custom} 下。</p>
 *
 * <p>线号是新的数据包 {@code S2CRequestModelUpload} / {@code C2SUploadModelFile} 的字段值，
 * 不沿用任何既有 id；未知线号在解码端被丢弃。</p>
 */
public enum ModelUploadTarget {

    /** {@code config/ysmu/custom}：公开模型，任何玩家都可以选用（与目录扫描根的 CUSTOM 一致）。 */
    CUSTOM(0, "custom", ServerModelManager.CUSTOM),
    /** {@code config/ysmu/auth}：受限模型，需要 {@code /ysm auth} 授权才能选用（同 AUTH 扫描根）。 */
    AUTH(1, "auth", ServerModelManager.AUTH);

    private final int wireId;
    private final String commandWord;
    private final Path root;

    ModelUploadTarget(int wireId, String commandWord, Path root) {
        this.wireId = wireId;
        this.commandWord = commandWord;
        this.root = root;
    }

    public int wireId() {
        return this.wireId;
    }

    public String commandWord() {
        return this.commandWord;
    }

    public Path root() {
        return this.root;
    }

    /** 命令里的目录词（{@code auth}/{@code custom}，大小写不敏感），无法识别时返回 {@code null}。 */
    public static ModelUploadTarget fromCommandWord(String word) {
        if (word == null) {
            return null;
        }
        for (ModelUploadTarget target : values()) {
            if (target.commandWord.equalsIgnoreCase(word)) {
                return target;
            }
        }
        return null;
    }

    /**
     * 线号到目录的映射；未知线号返回 {@code null}。
     * <p>
     * 接收端把 {@code null} 当作"畸形包，丢弃"：这比默认落到 CUSTOM 安全（伪造的 auth/custom 位不能靠
     * 越界值绕过），也与本仓其它包的软失败规则一致（{@code NetworkHandler} 的 N-10 注释）。
     * </p>
     */
    public static ModelUploadTarget fromWireId(int wireId) {
        for (ModelUploadTarget target : values()) {
            if (target.wireId == wireId) {
                return target;
            }
        }
        return null;
    }
}
