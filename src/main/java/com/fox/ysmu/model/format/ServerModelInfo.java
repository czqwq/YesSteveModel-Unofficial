package com.fox.ysmu.model.format;

import java.util.Optional;
import java.util.Set;

import com.fox.ysmu.util.Md5Utils;
import com.google.gson.annotations.Expose;

public class ServerModelInfo {

    /**
     * M-10:传统缓存的磁盘文件名就是"载荷 MD5"的十六进制大写串,固定 32 个字符。
     */
    public static final int CACHE_FILE_NAME_LENGTH = 32;

    /**
     * M-10:缓存容器头部里记录的载荷 MD5 长度(见 {@code EncryptTools#assembleEncryptModels})。
     */
    public static final int CACHE_HEADER_MD5_LENGTH = 16;

    @Expose
    private final Set<String> textures;
    @Expose(serialize = false, deserialize = false)
    private final Type type;
    @Expose(serialize = false, deserialize = false)
    private String md5;

    public ServerModelInfo(Set<String> textures, Type type) {
        this.textures = textures;
        this.type = type;
    }

    /**
     * M-10:元数据侧统一提供的"文件名口径"校验 —— 32 位十六进制(大小写不敏感)。
     * 网络侧({@code SendModelFile}/{@code SendModelFileChunk} 的 N-07)与缓存写入侧
     * ({@link ModelCacheWriter})都应当用它先过滤名字,再谈内容。
     */
    public static boolean hasWellFormedCacheFileName(String fileName) {
        if (fileName == null || fileName.length() != CACHE_FILE_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * M-10:"名字 == 内容 MD5" 的单个判定口径,供服务端写缓存前复用判定(N-07 的消费点也可调用)。
     *
     * @param fileName 缓存文件名(不含目录),大小写不敏感
     * @param content  缓存文件的完整字节
     */
    public static boolean matchesCacheContent(String fileName, byte[] content) {
        if (!hasWellFormedCacheFileName(fileName) || content == null) {
            return false;
        }
        return fileName.equalsIgnoreCase(Md5Utils.md5Hex(content));
    }

    public Type getType() {
        return type;
    }

    public Set<String> getTextures() {
        return textures;
    }

    public Optional<String> getTexture() {
        return textures.stream()
            .findFirst();
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }
}
