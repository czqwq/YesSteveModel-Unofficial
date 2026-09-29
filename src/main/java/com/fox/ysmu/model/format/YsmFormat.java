package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.YesModelUtils;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;

public final class YsmFormat {

    private YsmFormat() {}

    /**
     * M-12:与 {@link OpenYsmFormat} / {@link FolderFormat} 统一用 {@code walkFileTree} +
     * {@link OpenYsmFormat#toModelName} 取模型名(嵌套目录里的 .ysm 也会被登记成 {@code group/model})。
     * M-18:单个 .ysm 失败(含解压/解析异常与 LinkageError)只 warn,不中断整次扫描。
     */
    public static void cacheAllModels(Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return;
        }
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    if (!Files.isRegularFile(file) || !file.getFileName()
                        .toString()
                        .endsWith(".ysm")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String modelId = ModelIdUtil.getInternalModelId(
                        removeExtension(OpenYsmFormat.toModelName(rootPath, file)));
                    try {
                        Map<String, byte[]> data = YesModelUtils.input(file.toFile());
                        if (data.isEmpty()) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!data.containsKey(MAIN_MODEL_FILE_NAME)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!data.containsKey(ARM_MODEL_FILE_NAME)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (data.keySet()
                            .stream()
                            .noneMatch(fileName -> fileName.endsWith(".png"))) {
                            return FileVisitResult.CONTINUE;
                        }

                        ServerModelInfo info = cacheModel(data, modelId);
                        if (info != null) {
                            CACHE_NAME_INFO.put(modelId, info);
                        }
                    } catch (Exception | LinkageError e) {
                        ysmu.LOG.warn("Failed to cache legacy .ysm model {}", file, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to scan .ysm models under {}", rootPath, e);
        }
    }

    private static ServerModelInfo cacheModel(Map<String, byte[]> input, String modelId) {
        try {
            ModelData data = getModelData(input, modelId);
            return ModelCacheWriter.write(data);
        } catch (Exception | LinkageError e) {
            // M-16/M-18:统一日志,单个模型失败不影响其余模型。
            ysmu.LOG.warn("Failed to cache .ysm model {}", modelId, e);
        }
        return null;
    }

    @NotNull
    private static ModelData getModelData(Map<String, byte[]> data, String modelId) throws IOException {
        Map<String, byte[]> model = Maps.newHashMap();
        model.put("main", getBytes(data, MAIN_MODEL_FILE_NAME));
        model.put("arm", getBytes(data, ARM_MODEL_FILE_NAME));

        Map<String, byte[]> texture = Maps.newHashMap();
        data.forEach((name, textureData) -> {
            if (name.endsWith(".png")) {
                texture.put(name, textureData);
            }
        });

        Map<String, byte[]> animation = Maps.newHashMap();
        animation.put("main", getBytes(data, MAIN_ANIMATION_FILE_NAME));
        animation.put("arm", getBytes(data, ARM_ANIMATION_FILE_NAME));
        animation.put("extra", getBytes(data, EXTRA_ANIMATION_FILE_NAME));

        return new ModelData(modelId, Type.YSM, model, texture, animation);
    }

    private static byte[] getBytes(Map<String, byte[]> data, String fileName) throws IOException {
        if (MAIN_ANIMATION_FILE_NAME.equals(fileName) && !data.containsKey(MAIN_ANIMATION_FILE_NAME)) {
            Path filePath = CUSTOM.resolve("default/main.animation.json");
            return FileUtils.readFileToByteArray(filePath.toFile());
        }
        if (ARM_ANIMATION_FILE_NAME.equals(fileName) && !data.containsKey(ARM_ANIMATION_FILE_NAME)) {
            Path filePath = CUSTOM.resolve("default/arm.animation.json");
            return FileUtils.readFileToByteArray(filePath.toFile());
        }
        if (EXTRA_ANIMATION_FILE_NAME.equals(fileName) && !data.containsKey(EXTRA_ANIMATION_FILE_NAME)) {
            Path filePath = CUSTOM.resolve("default/extra.animation.json");
            return FileUtils.readFileToByteArray(filePath.toFile());
        }

        if (MAIN_MODEL_FILE_NAME.equals(fileName) || ARM_MODEL_FILE_NAME.equals(fileName)) {
            String modelJson = new String(data.get(fileName), StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);
            // 直接返回JSON字符串的字节数组，而不是尝试序列化RawGeoModel对象
            return modelJson.getBytes(StandardCharsets.UTF_8);
        }

        return data.get(fileName);
    }
}
