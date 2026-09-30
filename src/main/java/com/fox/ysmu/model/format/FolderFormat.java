package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;

public final class FolderFormat {

    private FolderFormat() {}

    /**
     * M-12:改用 {@code walkFileTree} + {@link OpenYsmFormat#toModelName}(与 OpenYsmFormat/YsmFormat
     * 同一套命名),这样放在分组目录里的模型({@code custom/group/model})也会被登记成 {@code group/model},
     * 而不是被降级成 {@code model} 与其它目录撞车。
     * <p>
     * M-21:带 {@code ysm.json} 的目录属于 OpenYSM 布局,由 {@link OpenYsmFormat} 负责,这里按 modelId
     * 去重 —— 只有当该 id 已经在 {@code CACHE_NAME_INFO} 里(即 OpenYSM 扫描器确实登记成功)时才跳过整棵
     * 子树,否则一个 ysm.json 解析失败的目录会被两套扫描器同时放弃、模型整个消失。去重依赖
     * {@code ServerModelManager.rebuildModelCaches} 的执行顺序:两个 OpenYsmFormat.cacheAllModels 都在
     * {@code cacheAllModels(CUSTOM)} 之前。两个扫描器算 id 用的是同一个
     * {@link OpenYsmFormat#toModelName} + {@link ModelIdUtil#getInternalModelId}。
     */
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
                    if (isPackRoot(dir)) {
                        // 包根只是容器：继续下钻去登记包里的模型，但绝不把包根自身登记成模型，否则
                        // config/ysmu/custom/wine_fox（包根 + 内置写入的 main.json/arm.json/skin.png）
                        // 会变成一个和包同名的模型，并把包封面 ysm-pack.png 当贴图收进去。
                        return FileVisitResult.CONTINUE;
                    }
                    if (Files.isRegularFile(dir.resolve("ysm.json")) && hasOpenYsmRegistration(rootPath, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!isModelDirectory(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String modelId = ModelIdUtil.getInternalModelId(OpenYsmFormat.toModelName(rootPath, dir));
                    ServerModelInfo info = cacheModel(dir, modelId);
                    if (info != null) {
                        CACHE_NAME_INFO.put(modelId, info);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to scan legacy folder models under {}", rootPath, e);
        }
    }

    /**
     * M-21:该目录的 modelId 是否已经被 OpenYSM 扫描器登记。id 的算法与
     * {@link OpenYsmFormat#cacheAllModels} 完全一致({@link OpenYsmFormat#toModelName} +
     * {@link ModelIdUtil#getInternalModelId}),所以命中即代表"同一目录已经登记过"。
     */
    private static boolean hasOpenYsmRegistration(Path rootPath, Path dir) {
        return CACHE_NAME_INFO.containsKey(ModelIdUtil.getInternalModelId(OpenYsmFormat.toModelName(rootPath, dir)));
    }

    /** dev 基线的判定条件原样保留:非空 main.json + 非空 arm.json + 至少一个 png。 */
    private static boolean isModelDirectory(Path dir) {
        boolean hasMainModelFile = false;
        boolean hasArmModelFile = false;
        boolean hasTextureFile = false;
        Collection<File> files = FileUtils.listFiles(dir.toFile(), FileFileFilter.FILE, null);
        for (File file : files) {
            String fileName = file.getName();
            if (MAIN_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                hasMainModelFile = true;
            }
            if (ARM_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                hasArmModelFile = true;
            }
            if (fileName.endsWith(".png")) {
                hasTextureFile = true;
            }
        }
        return hasMainModelFile && hasArmModelFile && hasTextureFile;
    }

    private static ServerModelInfo cacheModel(Path modelPath, String modelId) {
        try {
            ModelData data = getModelDataFromPath(modelPath, modelId);
            return ModelCacheWriter.write(data);
        } catch (Exception | LinkageError e) {
            // M-16/M-18:统一日志,且单个模型失败不影响其余模型。
            ysmu.LOG.warn("Failed to cache legacy folder model {}", modelPath, e);
        }
        return null;
    }

    @NotNull
    public static ModelData getModelData(Path rootPath, String modelName) throws IOException {
        return getModelDataFromPath(rootPath.resolve(modelName), ModelIdUtil.getInternalModelId(modelName));
    }

    @NotNull
    private static ModelData getModelDataFromPath(Path modelPath, String modelId) throws IOException {
        Map<String, byte[]> model = Maps.newHashMap();
        model.put("main", getBytes(modelPath, MAIN_MODEL_FILE_NAME));
        model.put("arm", getBytes(modelPath, ARM_MODEL_FILE_NAME));

        Map<String, byte[]> texture = Maps.newHashMap();
        Collection<File> textures = FileUtils.listFiles(modelPath.toFile(), new String[] { "png" }, false);
        for (File png : textures) {
            String fileName = png.getName();
            if (PACK_ICON_FILE_NAME.equals(fileName)) {
                // 包封面是包自己的资源，不是模型贴图；把它当贴图会让模型默认皮肤变成那张封面。
                continue;
            }
            texture.put(fileName, getBytes(modelPath, fileName));
        }

        Map<String, byte[]> animation = Maps.newHashMap();
        animation.put("main", getBytes(modelPath, MAIN_ANIMATION_FILE_NAME));
        animation.put("arm", getBytes(modelPath, ARM_ANIMATION_FILE_NAME));
        animation.put("extra", getBytes(modelPath, EXTRA_ANIMATION_FILE_NAME));

        return new ModelData(modelId, Type.FOLDER, model, texture, animation);
    }

    private static byte[] getBytes(Path root, String fileName) throws IOException {
        Path filePath = root.resolve(fileName);
        if (MAIN_ANIMATION_FILE_NAME.equals(fileName) && !filePath.toFile()
            .isFile()) {
            filePath = CUSTOM.resolve("default/main.animation.json");
        }
        if (ARM_ANIMATION_FILE_NAME.equals(fileName) && !filePath.toFile()
            .isFile()) {
            filePath = CUSTOM.resolve("default/arm.animation.json");
        }
        if (EXTRA_ANIMATION_FILE_NAME.equals(fileName) && !filePath.toFile()
            .isFile()) {
            filePath = CUSTOM.resolve("default/extra.animation.json");
        }

        if (MAIN_MODEL_FILE_NAME.equals(fileName) || ARM_MODEL_FILE_NAME.equals(fileName)) {
            String modelJson = FileUtils.readFileToString(filePath.toFile(), StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);
            // 直接返回JSON字符串的字节数组，而不是尝试序列化RawGeoModel对象
            return modelJson.getBytes(StandardCharsets.UTF_8);
        }

        return FileUtils.readFileToByteArray(filePath.toFile());
    }

    private static boolean isNotBlankFile(File file) {
        try {
            String fileText = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            return StringUtils.isNoneBlank(fileText);
        } catch (IOException e) {
            // M-16:统一走日志。
            ysmu.LOG.warn("Failed to read model file {}", file, e);
        }
        return false;
    }
}
