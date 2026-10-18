package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.removeExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import rip.ysm.security.YsmCrypt;

public final class OpenYsmFormat {

    private static final byte[] OPEN_YSM_PREFIX = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P' };

    private OpenYsmFormat() {}

    public static void cacheAllModels(Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return;
        }

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (ServerModelManager.isPackRoot(dir)) {
                        // 包根本身不是模型（即使里面还残留 ysm.json）；继续下钻登记包内模型。
                        return FileVisitResult.CONTINUE;
                    }
                    if (!Files.isRegularFile(dir.resolve("ysm.json")) || !YSMFolderDeserializer.isModelFolder(dir)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String diskModelName = toModelName(rootPath, dir);
                    cacheFolderModel(rootPath, dir, ModelIdUtil.getInternalModelId(diskModelName),
                        ModelIndexCache.Source.of(ModelIndexCache.Scanner.OPEN_YSM, rootPath, dir));
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    File diskFile = file.toFile();
                    if (diskFile.isFile() && diskFile.getName().endsWith(".ysm")) {
                        cacheBinaryModel(rootPath, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to scan OpenYSM models under {}", rootPath, e);
        }
    }

    /**
     * {@code source} 是这个目录在 {@link ModelIndexCache} 里的身份（扫描器 + 扫描根 + 相对路径）。
     * 命中索引时整段跳过：不解密、不解析 {@code ysm.json} 树、不烘 {@code ModelData}、不加密。
     * <p>
     * {@code rootPath} 只用于第 6 项的来源判定（{@code ServerModelManager.registerModel} 需要扫描根来区分内置与
     * 用户域）；它不参与模型 id 的计算，id 仍由 {@code source} 的相对路径决定。
     */
    private static void cacheFolderModel(Path rootPath, Path dir, String modelId, ModelIndexCache.Source source) {
        if (ModelIndexCache.restoreIfHit(source, modelId)) {
            return;
        }
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            raw.modelId = modelId;
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                int texCount = raw.mainEntity != null ? raw.mainEntity.textures.size() : 0;
                ysmu.LOG.info("[YSMU-MODEL] Folder model {} ({}): textures={}", dir.getFileName(), modelId, texCount);
            }
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                ysmu.LOG.warn("OpenYSM folder model {} parsed but cannot be bridged to legacy ModelData", dir);
                return;
            }
            ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
            ServerModelInfo info = ModelCacheWriter.write(data);
            if (info != null) {
                // 第 6 项：唯一登记入口的第一次调用（legacy 半边）。拿到 17 载荷后同一来源会再登记一次＝补齐，
                // 不产生冲突（见 ServerModelManager.registerModel 的 javadoc 第 4 条）。
                ServerModelManager.registerModel(modelId, info, null, dir, rootPath);
            }
            // M-01:二进制同步缓存只序列化已烘焙的 bones/cubes/faces,不能用 isBridgeable 做门控
            // ——它对散件模型因 sourceJson != null 恒 true,会写出一个几何为空的缓存。
            if (!RawYsmModelAdapter.isBinaryPayloadBridgeable(raw)) {
                ysmu.LOG.warn(
                    "Skipping OpenYSM binary sync cache for folder model {}: baked geometry is empty (main faces={}, arm faces={})",
                    dir,
                    RawYsmModelAdapter.countBakedFaces(raw.mainEntity.mainModel),
                    RawYsmModelAdapter.countBakedFaces(raw.mainEntity.armModel));
                ModelIndexCache.remember(source, modelId, info, null);
                return;
            }
            OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
            ServerModelManager.registerModel(modelId, info, syncInfo, dir, rootPath);
            ModelIndexCache.remember(source, modelId, info, syncInfo);
        } catch (Exception | LinkageError e) {
            // S-02:类加载/链接期错误(缺类、版本不匹配)也应按"跳过并 warn"处理,而不是中断整次扫描。
            ysmu.LOG.warn("Failed to load OpenYSM folder model {}", dir, e);
        }
    }

    private static void cacheBinaryModel(Path rootPath, Path file) {
        try {
            byte[] encrypted = Files.readAllBytes(file);
            if (!isOpenYsmBinary(encrypted)) {
                return;
            }
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info("[YSMU-MODEL] Found OpenYSM .ysm file: {} (size={})", file.getFileName(), encrypted.length);
            }
            // 模型名只由路径决定，先算出来才能问索引（原来这行在反序列化之后）。命中则整段跳过
            // 解密→解压→反序列化→烘 ModelData→加密。
            String modelId = ModelIdUtil.getInternalModelId(removeExtension(toModelName(rootPath, file)));
            ModelIndexCache.Source source = ModelIndexCache.Source.of(ModelIndexCache.Scanner.OPEN_YSM, rootPath, file);
            if (ModelIndexCache.restoreIfHit(source, modelId)) {
                return;
            }
            byte[] rawBytes = YsmCrypt.decryptYsmFile(encrypted);
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info("[YSMU-MODEL] Decrypted .ysm file {}: decompressed size={}", file.getFileName(), rawBytes.length);
            }
            try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(rawBytes)) {
                RawYsmModel raw = deserializer.deserializeKeepOpen();
                deserializer.parseYSMFooter(raw);
                raw.modelId = modelId;
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    int texCount = raw.mainEntity != null ? raw.mainEntity.textures.size() : 0;
                    int animCount = raw.mainEntity != null ? raw.mainEntity.animationFiles.size() : 0;
                    boolean hasMainModel = raw.mainEntity != null && raw.mainEntity.mainModel != null;
                    boolean hasArmModel = raw.mainEntity != null && raw.mainEntity.armModel != null;
                    ysmu.LOG.info("[YSMU-MODEL] Parsed .ysm {} -> modelId={}, formatVersion={}, "
                        + "mainModel={}, armModel={}, textures={}, animations={}, footer.version={}",
                        file.getFileName(), modelId, raw.formatVersion,
                        hasMainModel, hasArmModel, texCount, animCount, raw.footer.version);
                    if (raw.mainEntity != null) {
                        for (RawYsmModel.RawTexture tex : raw.mainEntity.textures.values()) {
                            ysmu.LOG.info("[YSMU-MODEL]   texture: name={}, format={}, w={}, h={}, dataLen={}",
                                tex.name, tex.imageFormat, tex.width, tex.height,
                                tex.data == null ? 0 : tex.data.length);
                        }
                    }
                }
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    ysmu.LOG.info("[YSMU-MODEL] isBridgeable({}) = {}", modelId, RawYsmModelAdapter.isBridgeable(raw));
                }
                if (!RawYsmModelAdapter.isBridgeable(raw)) {
                    ysmu.LOG.info("OpenYSM binary model {} parsed but cannot be bridged to legacy ModelData", file);
                    return;
                }
                ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
                ServerModelInfo info = ModelCacheWriter.write(data);
                if (info != null) {
                    // 第 6 项：唯一登记入口（先 legacy 半边，拿到 17 载荷后同一来源补齐）。
                    ServerModelManager.registerModel(modelId, info, null, file, rootPath);
                }
                if (!RawYsmModelAdapter.isBinaryPayloadBridgeable(raw)) {
                    ysmu.LOG.warn(
                        "Skipping OpenYSM sync cache for binary model {}: baked geometry is empty (main faces={}, arm faces={})",
                        file,
                        RawYsmModelAdapter.countBakedFaces(raw.mainEntity.mainModel),
                        RawYsmModelAdapter.countBakedFaces(raw.mainEntity.armModel));
                    ModelIndexCache.remember(source, modelId, info, null);
                    return;
                }
                OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
                ServerModelManager.registerModel(modelId, info, syncInfo, file, rootPath);
                ModelIndexCache.remember(source, modelId, info, syncInfo);
            }
        } catch (UnsupportedOperationException e) {
            ysmu.LOG.warn("Unsupported OpenYSM binary model {}: {}", file, e.getMessage(), e);
        } catch (Exception | LinkageError e) {
            ysmu.LOG.warn("Failed to load OpenYSM binary model {}", file, e);
            if (Config.DEBUG_MODEL_LOAD && (Config.DEBUG_MODEL_SCAN || ysmu.LOG.isDebugEnabled())) {
                ysmu.LOG.warn("[YSMU-MODEL] OpenYSM binary model {} load failure detail", file, e);
            }
        }
    }

    /**
     * 是否是 OpenYSM 二进制 {@code .ysm} 容器的开头（BOM + "YSGP"）。
     * <p>
     * phase55：上传校验器（{@code model/upload/ModelFileValidator}）也要在解密之前按同一个前缀分流，
     * 所以这里从 private 提为 public，而不是让校验器复制一份前缀常量。判定本身未变。
     * </p>
     */
    public static boolean isOpenYsmBinary(byte[] data) {
        if (data == null || data.length < OPEN_YSM_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < OPEN_YSM_PREFIX.length; i++) {
            if (data[i] != OPEN_YSM_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * M-12:三个扫描器(OpenYsmFormat/FolderFormat/YsmFormat)统一的"磁盘模型名"口径
     * —— 相对 root 的路径 + 正斜杠。分散实现会让嵌套目录的模型在三个扫描器里得到不同的 id。
     */
    public static String toModelName(Path rootPath, Path path) {
        return rootPath.relativize(path)
            .toString()
            .replace('\\', '/');
    }
}
