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
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
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

    /**
     * The id this texture is registered under, set by {@link ModelTextureRegistry#register} just before the platform
     * registration; {@code null} for a texture that was never handed to that registry.
     * <p>
     * The reference branch's {@code OuterFileTexture} takes this id as a constructor parameter (reference
     * {@code client/texture/OuterFileTexture.java:21}, set at {@code :55-58}) because its loader creates the object
     * with the id already in hand. This port's loader is {@code ClientModelManager.registerTexture(id, image)} - a
     * file this task may not edit - so the id is attached from the registry side instead. Nothing else about the
     * texture depends on it: it exists so a GPU-touch can name the texture it touched, exactly as the reference's
     * {@code ClientModelManager.touchTextureUsed(this.id)} call does (reference {@code :85}).
     * <p>
     * The field doubles as the registry's <b>ownership</b> marker: {@code null} means "no registry owns this texture
     * any more", and the lazy re-upload path belongs to ownership (see {@link #getGlTextureId()} and
     * {@link #forgetGpuAccounting()}). The two meanings coincide because the id is only ever set at registration and
     * only ever cleared when a newer object takes over the same id.
     */
    @Nullable
    private volatile ResourceLocation registryId;

    /**
     * Whether the pixel data is currently on the GPU. Tracked explicitly instead of reading
     * {@code glTextureId != -1}: {@link AbstractTexture#getGlTextureId()} lazily allocates a new GL name on first
     * call, so after {@link #freeGlTexture()} the id would look valid again and a lazy re-upload would be skipped -
     * which renders an incomplete (black) texture. The reference branch keeps the same flag for the same reason
     * (reference {@code client/texture/OuterFileTexture.java:30-39}).
     */
    private volatile boolean uploaded;
    /** VRAM footprint (bytes) of the uploaded copy: post-downscale {@code w * h * 4} for {@code GL_RGBA} level 0. */
    private volatile long gpuBytes;
    /** Set when a lazy re-upload failed; suppresses a per-frame retry of a broken texture (reference {@code :48-53}). */
    private volatile boolean failed;
    private volatile long failedAt;
    /** Cooldown before a failed lazy re-upload is attempted again. */
    private static final long FAILED_RETRY_MS = 2000L;

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
        this.uploaded = false;

        // 2. 上传。图已经解好的话就只做这一步；只有在这一步之前没解成功（或者走的是同步的 eager 路径）才在这里解码。
        //    解码失败仍然抛 IOException（本仓原行为）：TextureManager.loadTexture 会捕获它、把该 id 指向全局
        //    missing texture，于是表现为可见的缺失贴图。不要在这里吞掉异常改用「留空纹理」，那样绑定的是一张
        //    不完整纹理（全黑），比缺失贴图更难诊断。
        BufferedImage bufferedImage = this.image != null ? this.image : decode(this.data);
        uploadImage(bufferedImage);
    }

    /**
     * 绑定/查询 GL 纹理名时的入口，同时也是 LRU 的「使用过」打点处与 VRAM 预算回收后的重上传点。
     *
     * <p>1.7.10 的 {@code TextureManager.bindTexture} 每次绑定都会调用
     * {@code ((ITextureObject) object).getGlTextureId()}（{@code build/rfg/minecraft-src/java/net/minecraft/client/
     * renderer/texture/TextureManager.java:38-49}），模型的每一条绑定路径（世界/预览 GUI/第一人称手/骨骼贴图
     * 覆盖 IGeoRenderer:193）都经由它，所以这里既是本仓唯一免费拿到的「纹理正在被使用」信号，也是
     * {@link #freeGlTexture()} 之后唯一必须补上重上传的关口——否则 {@code AbstractTexture.getGlTextureId()}
     * 只会懒分配一个新的空 GL 名，模型变纯黑。
     *
     * <p>参考分支把同一件事做成 `upload()`（deferred upload）并在其中 touch
     * （参考 {@code OuterFileTexture.java:70-85}）；本仓的上传路径是注册期 eager（见 {@link #loadTexture}），
     * 所以打点只放在真正的绑定处，不放在上传处，理由见 {@link ModelTextureRegistry#touch}。
     */
    @Override
    public int getGlTextureId() {
        ModelTextureRegistry.touch(this.registryId);
        // The re-upload belongs to ownership. A texture this registry replaced for the same id is still the platform
        // map's entry for one more call - CustomTextureManager.MinecraftHost.register asks it for its GL name in order
        // to delete the old copy - and re-uploading a texture that is about to be discarded would both waste a decode
        // and re-enter the budget counter for an object nothing can ever free again.
        if (!this.uploaded && this.registryId != null) {
            reupload();
        }
        return super.getGlTextureId();
    }

    /**
     * 把 id 交回给纹理自己，供 {@link #getGlTextureId()} 打点使用；由 {@link ModelTextureRegistry#register} 调用。
     */
    void setRegistryId(ResourceLocation registryId) {
        this.registryId = registryId;
    }

    /**
     * 真正的一次上传：降采样（{@link Config#TEXTURE_TARGET_SIZE}，0 = 关闭，关闭时原图直传）→
     * {@code TextureUtil} 上传 → 记账 → 触发 VRAM 预算检查。
     *
     * <p>这里用 {@code super.getGlTextureId()} 而不是 {@code this.getGlTextureId()}：后者会走
     * {@link #getGlTextureId()} 的重上传分支，形成递归。
     */
    private void uploadImage(BufferedImage source) {
        BufferedImage toUpload = downscale(source);
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
        TextureUtil.uploadTextureImageAllocate(super.getGlTextureId(), toUpload, blur, clamp);
        this.uploaded = true;
        this.failed = false;
        // Only textures this registry owns are accounted for. The budget's own javadoc scopes it to "YSM model
        // textures", and only such a texture can ever be freed by ModelTextureRegistry.enforceVramBudget, so counting
        // anything else would either make the budget unreachable (a footprint nothing can free) or leak: the pack
        // icons (ClientPackRegistry.registerIcon) and author avatars (ClientModelMetadataRegistry.uploadAvatar) also
        // upload OuterFileTextures, and they replace their entry by hand - deleteTexture + loadTexture - so a replaced
        // object's bytes would never be given back. The reference counts every OuterFileTexture because its counter
        // lives in a class that has no notion of "a model texture"; here the registry id supplies that notion for free.
        if (this.registryId == null) {
            return;
        }
        // 上传是按 TextureUtil.allocateTextureImpl 的 GL_RGBA、level 0 分配（无 mipmap：blur=false），
        // 所以帧存就是 w×h×4——与参考分支的算法一致（参考 ClientModelManager.java 的
        // onTextureGpuBytesChanged 由 OuterFileTexture:132-133 以 toUpload 的尺寸喂入）。
        long bytes = (long) toUpload.getWidth() * toUpload.getHeight() * 4L;
        ModelTextureRegistry.onTextureGpuBytesChanged(bytes - this.gpuBytes);
        this.gpuBytes = bytes;
        ModelTextureRegistry.enforceVramBudget();
    }

    /**
     * {@link #freeGlTexture()} 之后的重上传。字节仍在堆里（{@code image} 或 {@code data} 都是 final 字段），
     * 所以这是一次纯 CPU 解码（正常情况下不需要解码，图已解好）+ GPU 上传，不依赖加密客户端缓存的恢复路径。
     */
    private void reupload() {
        long now = System.currentTimeMillis();
        if (this.failed && now - this.failedAt < FAILED_RETRY_MS) {
            return;
        }
        try {
            BufferedImage source = this.image != null ? this.image : decode(this.data);
            if (source != null) {
                uploadImage(source);
                return;
            }
        } catch (Exception failure) {
            // 这里不能抛：调用方是渲染期的绑定路径（含 IGeoRenderer 的骨骼贴图覆盖分支），
            // 抛出去会变成一次渲染失败甚至崩客户端。按参考分支的做法节流重试（参考 :89-92, :136-142）。
            ysmu.LOG.warn("YSM texture {} could not be re-uploaded: {}", this.registryId, failure.toString());
        }
        this.failed = true;
        this.failedAt = now;
    }

    /**
     * 可选地把已解码的贴图按 {@link Config#TEXTURE_TARGET_SIZE} 降采样（0 = 关闭）以限制大模型库的显存占用。
     *
     * <p>降采样倍率永远是 2 的幂（1/2、1/4……），所以结果落在 [target, 2*target)：target=512 时
     * 1024×1024 → 512×512、768×768 原样、1280×1280 → 640×640。YSM 贴图用 GL_NEAREST 采样，非整数倍率会让
     * 双线性重采样把相邻面的像素糊过面边界（像素画游戏里非常明显）；2 的幂倍率下每个输出像素正好对应
     * 2×2/4×4 的源像素块，nearest 采样下边缘保持干净。绝不放大。
     *
     * <p>为什么对 UV 是安全的：GeckoLib 按模型自己声明的 texture_width/height 归一化 UV，每个 quad 都用
     * [0,1] 相对坐标采样，等比缩放后这些坐标仍指向同一内容，只是分辨率下降。即使模型声明的尺寸与 PNG 实际
     * 尺寸不一致也成立——我们只改分辨率，不改映射。
     *
     * <p>用精确的 box 平均（每个输出纹素 = 其 divisor×divisor 源块的均值）而不是 Graphics2D 双线性缩放：
     * {@code drawImage} 的 BILINEAR 是像素中心采样，会引入半个纹素的偏移，降采样后的纹素栅格不再与源对齐，
     * GL_NEAREST 采样就会落到邻居像素上，表现为「许多小 UV 面的模型上随机出现白色/偏色斑块」。box 平均让
     * 每个输出纹素严格对齐其源块，nearest 采样 1:1 正确。
     *
     * <p>算法逐行取自参考分支 {@code client/texture/OuterFileTexture.java:175-251}，并逐条读过错：
     * divisor 循环退出时 {@code maxDim/divisor ∈ [target, 2*target)}（上一轮 ≥ 2*target，本轮 < 2*target，
     * 且整数除法下界是 target）；{@code divisor} 每轮翻倍必然终止，不会死循环；
     * {@code totalCount ≥ 1} 且 {@code opaqueCount == 0} 有分支保护，没有除零。
     */
    private BufferedImage downscale(BufferedImage src) {
        int target = Config.TEXTURE_TARGET_SIZE;
        if (target <= 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int maxDim = Math.max(w, h);
        if (maxDim <= target) {
            return src;
        }
        int divisor = 1;
        while (maxDim / divisor >= 2 * target) {
            divisor <<= 1;
        }
        int newW = Math.max(1, w / divisor);
        int newH = Math.max(1, h / divisor);
        if (newW == w && newH == h) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        // 精确 box 平均：out[ox,oy] = src[ox*div..(ox+1)*div-1, oy*div..(oy+1)*div-1] 的均值。
        // 用 long/int 累加避免精度漂移，并处理右/下边缘不满一个 divisor 块的源块（w 或 h 不是 divisor 的整数倍）。
        //
        // 全透明像素带着垃圾 RGB（blockbench 贴图通常是 (0,0,0,0)）；把它们朴素地平均进去会让边缘纹素既半透明
        // 又发黑发浑，在小 UV 面多的模型上表现为白色/偏色斑块。所以 RGB 只对不透明源像素求平均，alpha 则取
        // 不透明覆盖率——边缘纹素颜色正确，只是按覆盖率淡出。
        int[] srcPixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] outPixels = new int[newW * newH];
        for (int oy = 0; oy < newH; oy++) {
            int y0 = oy * divisor;
            int y1 = Math.min(y0 + divisor, h);
            for (int ox = 0; ox < newW; ox++) {
                int x0 = ox * divisor;
                int x1 = Math.min(x0 + divisor, w);
                long r = 0;
                long g = 0;
                long b = 0;
                long a = 0;
                int opaqueCount = 0;
                int totalCount = 0;
                for (int y = y0; y < y1; y++) {
                    int rowBase = y * w;
                    for (int x = x0; x < x1; x++) {
                        int argb = srcPixels[rowBase + x];
                        int alpha = (argb >>> 24) & 0xFF;
                        a += alpha;
                        if (alpha > 0) {
                            r += (argb >>> 16) & 0xFF;
                            g += (argb >>> 8) & 0xFF;
                            b += argb & 0xFF;
                            opaqueCount++;
                        }
                        totalCount++;
                    }
                }
                int outA = (int) (a / totalCount);
                int outR;
                int outG;
                int outB;
                if (opaqueCount > 0) {
                    outR = (int) (r / opaqueCount);
                    outG = (int) (g / opaqueCount);
                    outB = (int) (b / opaqueCount);
                } else {
                    outR = outG = outB = 0;
                }
                outPixels[oy * newW + ox] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }
        scaled.setRGB(0, 0, newW, newH, outPixels, 0, newW);
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info(
                "[YSMU-TEX] upload({}): downscaled {}x{} -> {}x{} (target {}, divisor {})",
                this.registryId,
                w,
                h,
                newW,
                newH,
                target,
                divisor);
        }
        return scaled;
    }

    /**
     * 释放 GPU 拷贝，保留本对象与堆内字节：注册仍然存活、平台贴图表项仍在，所以下次绑定会由
     * {@link #getGlTextureId()} 直接重上传，既不会白模，也不需要重解密（参考 {@code :253-261}）。
     */
    public void freeGlTexture() {
        this.deleteGlTexture();
        this.uploaded = false;
        if (this.gpuBytes > 0) {
            ModelTextureRegistry.onTextureGpuBytesChanged(-this.gpuBytes);
            this.gpuBytes = 0;
        }
    }

    /** Whether the pixel data is currently on the GPU. */
    public boolean isUploaded() {
        return this.uploaded;
    }

    /**
     * Drops this texture's contribution to the VRAM counter and its registry ownership, for an object
     * {@link ModelTextureRegistry#register} replaced with a fresh one for the same id.
     *
     * <p>{@code uploaded} deliberately stays as it is: the replaced object is still the platform map's entry until
     * the re-registration replaces it, and {@code CustomTextureManager.MinecraftHost.register} asks that entry for
     * its GL name in order to delete it. Clearing {@link #registryId} is what stops that read from re-uploading a
     * texture that is about to be thrown away (see {@link #getGlTextureId()}). The GL name itself is deleted by that
     * same platform call, not by this method.
     */
    void forgetGpuAccounting() {
        this.registryId = null;
        long bytes = this.gpuBytes;
        this.gpuBytes = 0L;
        if (bytes > 0L) {
            ModelTextureRegistry.onTextureGpuBytesChanged(-bytes);
        }
    }

    /** VRAM footprint (bytes) of the uploaded copy; 0 while it is not uploaded. */
    public long getGpuBytes() {
        return this.gpuBytes;
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
