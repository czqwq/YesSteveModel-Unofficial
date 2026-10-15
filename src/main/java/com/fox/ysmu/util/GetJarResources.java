package com.fox.ysmu.util;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.ysmu;

public final class GetJarResources {

    private GetJarResources() {}

    /**
     * 复制本模组的文件到指定文件夹
     *
     * @param filePath jar 里面的文件地址
     * @param destPath 想要复制到的目录
     * @param fileName 复制后的文件名
     */
    public static void copyYesSteveModelFile(String filePath, Path destPath, String fileName) {
        URL url = ysmu.class.getResource(filePath);
        if (url == null) {
            // 旧实现静默 return：打包布局变化或资源缺失时内置模型会缺件，而 latest.log 里查不到任何线索。
            ysmu.LOG.warn("Missing built-in model resource {} (cannot copy it to {})", filePath, destPath);
            return;
        }
        Path target = destPath.resolve(fileName);
        try {
            FileUtils.copyURLToFile(url, target.toFile());
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to copy built-in model resource {} to {}", filePath, target, e);
        }
    }
}
