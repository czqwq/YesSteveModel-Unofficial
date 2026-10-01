package com.fox.ysmu.client.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.annotation.Nullable;
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

    /** The payload to decode here, for a texture that could not be decoded earlier; see {@link #decode}. */
    @Nullable
    private final byte[] data;
    /** A payload already decoded, which only has to be uploaded here. */
    @Nullable
    private final BufferedImage image;

    public OuterFileTexture(byte[] data) {
        this.data = data;
        this.image = null;
    }

    /**
     * A texture whose image was decoded earlier, on the loader thread.
     * <p>
     * This is the port's half of upstream's split: upstream decodes a texture on a worker
     * ({@code PreparedTextureSet.prepare}) and only the GPU upload is asserted onto the render thread
     * ({@code HostTexturePublisher.publish}, which asserts the render thread). Everything below {@code loadTexture} is
     * CPU work - reading the header, checking the limits, decoding - and only the last call touches GL, so decoding
     * where the payload is already being parsed keeps a tick from paying for every texture of the models it installs.
     */
    public OuterFileTexture(BufferedImage image) {
        this.data = null;
        this.image = image;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        // 1. 在上传新纹理前，先删除旧的OpenGL纹理ID（如果存在）
        this.deleteGlTexture();

        // 2. 上传。图已经解好的话就只做这一步；只有在这一步之前没解成功（或者走的是同步的 eager 路径）才在这里解码。
        BufferedImage bufferedImage = this.image != null ? this.image : decode(this.data);

        // 3. 使用 1.7.10 的 TextureUtil 将 BufferedImage 上传到GPU
        // a. getGlTextureId() 会在第一次调用时返回-1，TextureUtil会为其分配一个新的纹理ID
        // b. 这个方法处理了创建纹理、绑定、设置参数（如过滤）和上传像素数据的所有步骤
        boolean blur = false; // 是否使用模糊/线性过滤
        // clamp = true，即 GL_CLAMP / 边缘拉伸，这也是上游所依赖的平台默认：它的纹理经由 1.20 的 TextureManager
        // 上传，采样器默认 CLAMP_TO_EDGE。
        //
        // 为什么必须 clamp：包用"把不想要的面的 uv_size 写 0"来表示"这面没有图案"，上游照样建面
        // （GeoBuilder:186-193 只跳过 uv 数据缺失的面），而这类面的四个 uv 全部相同。法阵
        // ysmGlowdamofazhen3 的 south 面就是 uv=(256,0)/uv_size=(0,0) → 归一化后四个顶点都是 u=1.0。
        // 在 clamp 下 u=1.0 取的是该行最后一个纹素，实测 magic.png (255,0) 是全透明的 A0 → 这个面不可见，
        // 正是上游的表现；在 REPEAT 下 u=1.0 绕回第 0 列，实测 (0,0) 是 A255 的不透明灰 (57,51,67) →
        // 整片 60×60 被铺成一块不透明的灰色方片，盖住法阵并与它 z-fighting 出噪点。
        boolean clamp = true; // 是否使用边缘拉伸
        TextureUtil.uploadTextureImageAllocate(this.getGlTextureId(), bufferedImage, blur, clamp);
    }

    /**
     * 预检并解码贴图。纯 CPU，没有 GL 调用，所以可以在 loader 线程上做（见 {@link #OuterFileTexture(BufferedImage)}）。
     * <p>
     * 违规时抛 {@link IOException}：
     * {@code TextureManager.loadTexture} 会捕获它、记录 "Failed to load texture" 并改用全局 missing
     * texture，因此表现为可见的缺失贴图，而不是 OOM / 客户端崩溃——调用方
     * {@code ClientModelManager.registerTexture} 只捕获 {@code Exception}，而 {@code OutOfMemoryError}
     * 是 {@code Error}，捕获不到。
     */
    public static BufferedImage decode(@Nullable byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("empty YSM texture data");
        }
        if (data.length > MAX_DATA_BYTES) {
            ysmu.LOG.warn(
                "YSM texture rejected: {} bytes exceeds the {} byte limit",
                data.length,
                MAX_DATA_BYTES);
            throw new IOException("YSM texture data too large (" + data.length + " bytes)");
        }

        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (imageInput == null) {
                throw new IOException("cannot create an image input stream for YSM texture data");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                ysmu.LOG.warn(
                    "YSM texture rejected: no image reader accepts the {} byte payload",
                    data.length);
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

                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new IOException("image reader returned no image for YSM texture data");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        }
    }
}
