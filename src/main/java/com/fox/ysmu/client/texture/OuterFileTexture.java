package com.fox.ysmu.client.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

import com.fox.ysmu.ysmu;

public class OuterFileTexture extends AbstractTexture {

    /** 单个贴图负载的字节上限（第一道防线：解码前先拦掉超大/伪装成 PNG 的数据）。 */
    private static final int MAX_DATA_BYTES = 8 * 1024 * 1024;
    /**
     * 单边像素上限（R-07）。1.7.10 的 {@link TextureUtil} 对尺寸没有任何检查：
     * {@code allocateTextureImpl} 直接按 w×h×4 申请 GL_RGBA 显存，
     * {@code uploadTextureImageSubImpl} 还会额外分配 {@code int[4194304 / w * w]} 缓冲。
     */
    private static final int MAX_DIMENSION = 4096;

    private final byte[] data;

    public OuterFileTexture(byte[] data) {
        this.data = data;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        // 1. 在上传新纹理前，先删除旧的OpenGL纹理ID（如果存在）
        this.deleteGlTexture();

        // 2. 只读图片头部就先做体积/尺寸预检，避免直接解码超大图（PNG 解压炸弹）
        BufferedImage bufferedImage = readImageWithinLimits();

        // 3. 使用 1.7.10 的 TextureUtil 将 BufferedImage 上传到GPU
        // a. getGlTextureId() 会在第一次调用时返回-1，TextureUtil会为其分配一个新的纹理ID
        // b. 这个方法处理了创建纹理、绑定、设置参数（如过滤）和上传像素数据的所有步骤
        boolean blur = false; // 是否使用模糊/线性过滤
        boolean clamp = false; // 是否使用边缘拉伸
        TextureUtil.uploadTextureImageAllocate(this.getGlTextureId(), bufferedImage, blur, clamp);
    }

    /**
     * 预检并解码贴图。违规时抛 {@link IOException}：
     * {@code TextureManager.loadTexture} 会捕获它、记录 "Failed to load texture" 并改用全局 missing
     * texture，因此表现为可见的缺失贴图，而不是 OOM / 客户端崩溃——调用方
     * {@code ClientModelManager.registerTexture} 只捕获 {@code Exception}，而 {@code OutOfMemoryError}
     * 是 {@code Error}，捕获不到。
     */
    private BufferedImage readImageWithinLimits() throws IOException {
        if (this.data == null || this.data.length == 0) {
            throw new IOException("empty YSM texture data");
        }
        if (this.data.length > MAX_DATA_BYTES) {
            ysmu.LOG.warn(
                "YSM texture rejected: {} bytes exceeds the {} byte limit",
                this.data.length,
                MAX_DATA_BYTES);
            throw new IOException("YSM texture data too large (" + this.data.length + " bytes)");
        }

        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(this.data))) {
            if (imageInput == null) {
                throw new IOException("cannot create an image input stream for YSM texture data");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                ysmu.LOG.warn(
                    "YSM texture rejected: no image reader accepts the {} byte payload",
                    this.data.length);
                throw new IOException("no image reader for YSM texture data");
            }

            ImageReader reader = readers.next();
            try {
                // 只读 header，不做完整解码
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    ysmu.LOG.warn(
                        "YSM texture rejected: {}x{} exceeds the {} pixel per-axis limit",
                        width,
                        height,
                        MAX_DIMENSION);
                    throw new IOException("YSM texture is too large (" + width + "x" + height + ")");
                }

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("image reader returned no image for YSM texture data");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }
}
