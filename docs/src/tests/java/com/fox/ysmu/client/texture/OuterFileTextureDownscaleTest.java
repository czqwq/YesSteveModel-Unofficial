package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;

/**
 * {@link Config#TEXTURE_TARGET_SIZE} 的降采样：divisor 数学与 box 平均。
 *
 * <p>这段代码决定"一张贴图往显存里放多少字节"（{@code GPU bytes = w×h×4}），也是 {@code IGeoRenderer}
 * 的 GL_NEAREST 采样能干净工作的前提：倍率必须是 2 的幂，否则 box 边界与纹素栅格错位，小 UV 面的模型上会出现
 * 白色/偏色斑块。整条路径是纯 {@link BufferedImage} 进出，没有 GL，所以可以在单测里逐像素验证。
 *
 * <p>每个用例都直接钉"上的是哪张图"（尺寸 + 若干纹素值），不是钉实现：手工构造像素矩阵，手工算期望均值。
 */
class OuterFileTextureDownscaleTest {

    private int savedTargetSize;
    private boolean savedDebugModelLoad;
    private boolean savedDebugModelParse;

    @BeforeEach
    void saveConfig() {
        this.savedTargetSize = Config.TEXTURE_TARGET_SIZE;
        this.savedDebugModelLoad = Config.DEBUG_MODEL_LOAD;
        this.savedDebugModelParse = Config.DEBUG_MODEL_PARSE;
        // The debug branch below only logs; keep it off so the measured image is never influenced by logging.
        Config.DEBUG_MODEL_LOAD = false;
        Config.DEBUG_MODEL_PARSE = false;
    }

    @AfterEach
    void restoreConfig() {
        Config.TEXTURE_TARGET_SIZE = this.savedTargetSize;
        Config.DEBUG_MODEL_LOAD = this.savedDebugModelLoad;
        Config.DEBUG_MODEL_PARSE = this.savedDebugModelParse;
    }

    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static BufferedImage downscale(BufferedImage source) {
        return new OuterFileTexture(source).downscale(source);
    }

    /** 0（关闭）与负数都必须原图直传：同一个对象、同一批像素，一次拷贝都不做。 */
    @Test
    void aDisabledTargetPassesTheImageThroughUntouched() {
        Config.TEXTURE_TARGET_SIZE = 0;
        BufferedImage source = image(64, 32);
        source.setRGB(3, 4, argb(255, 1, 2, 3));
        assertSame(source, downscale(source), "target=0 必须直传同一个对象");
        assertEquals(argb(255, 1, 2, 3), downscale(source).getRGB(3, 4));

        Config.TEXTURE_TARGET_SIZE = -4;
        assertSame(source, downscale(source), "负数 target 与 0 同义（关闭）");
    }

    /**
     * divisor 循环的两条边界：结果永远落在 {@code [target, 2*target)}，且整数除法保证下界就是 target。
     * 1024→512、768 原样、1280→640、2048→512、4096×2048→512×256。
     */
    @Test
    void powerOfTwoDivisorsLandInsideTheTargetBand() {
        Config.TEXTURE_TARGET_SIZE = 512;

        BufferedImage exact = image(1024, 1024);
        BufferedImage downscaledExact = downscale(exact);
        assertNotSame(exact, downscaledExact);
        assertEquals(512, downscaledExact.getWidth());
        assertEquals(512, downscaledExact.getHeight());

        // 768 ∈ [512, 1024)：maxDim > target，但 divisor 1 的 768 已落在带内，原样返回（不放大也不缩）。
        BufferedImage inBand = image(768, 768);
        assertSame(inBand, downscale(inBand), "768 落在 [target, 2*target) 里，必须原样");

        BufferedImage wide = image(1280, 1280);
        assertEquals(640, downscale(wide).getWidth(), "1280 / 2 = 640");

        BufferedImage huge = image(2048, 2048);
        assertEquals(512, downscale(huge).getWidth(), "2048 / 4 = 512");

        BufferedImage rectangular = image(4096, 2048);
        BufferedImage downscaledRect = downscale(rectangular);
        assertEquals(512, downscaledRect.getWidth(), "4096 / 8 = 512");
        assertEquals(256, downscaledRect.getHeight(), "2048 / 8 = 256");

        BufferedImage atTarget = image(512, 512);
        assertSame(atTarget, downscale(atTarget), "maxDim <= target 时不缩放");
    }

    /** 均匀色块的 box 平均就是该颜色本身：每个输出纹素对应一个完整的 2×2 源块。 */
    @Test
    void aUniformBlockAveragesToItsOwnColour() {
        Config.TEXTURE_TARGET_SIZE = 3; // 8 / 1 = 8 >= 6 -> divisor 2；8 / 2 = 4 < 6 -> 停
        BufferedImage source = image(8, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                source.setRGB(x, y, argb(255, 40, 80, 120));
            }
        }

        BufferedImage scaled = downscale(source);
        assertEquals(4, scaled.getWidth());
        assertEquals(4, scaled.getHeight());
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(argb(255, 40, 80, 120), scaled.getRGB(x, y), "block (" + x + "," + y + ")");
            }
        }
    }

    /**
     * 全透明像素带着垃圾 RGB（blockbench 贴图常见 (0,0,0,0)）不能参与颜色平均，否则边缘纹素发黑发浑；
     * alpha 则取**覆盖率**（四个源像素的 alpha 均值）。一个全透明块整个输出 0。
     */
    @Test
    void transparentRgbDoesNotDragTheColourDown() {
        Config.TEXTURE_TARGET_SIZE = 3; // 8x8 -> 4x4, divisor 2
        BufferedImage source = image(8, 8);
        // 块 (0,0)：一个不透明红 + 三个"垃圾 RGB"全透明像素
        source.setRGB(0, 0, argb(255, 255, 0, 0));
        source.setRGB(1, 0, argb(0, 9, 9, 9));
        source.setRGB(0, 1, argb(0, 9, 9, 9));
        source.setRGB(1, 1, argb(0, 9, 9, 9));

        BufferedImage scaled = downscale(source);
        assertEquals(argb(255 / 4, 255, 0, 0), scaled.getRGB(0, 0),
            "alpha 用覆盖率 255/4=63，RGB 只对可见像素求均值 = (255,0,0)");
        assertEquals(0, scaled.getRGB(3, 3), "没有任何可见像素的块输出全 0");
    }

    /**
     * alpha 是**覆盖率的均值**（含半透明像素），RGB 则只累加 alpha > 0 的像素（半透明像素的颜色算数，
     * 全透明的垃圾 RGB 不算）。一个块里四种 alpha 各一个时三个通道都能看出用的是哪一组。
     */
    @Test
    void alphaAveragesCoverageWhileColourIgnoresFullyTransparentPixels() {
        Config.TEXTURE_TARGET_SIZE = 3; // 8x8 -> 4x4, divisor 2
        BufferedImage source = image(8, 8);
        source.setRGB(0, 0, argb(255, 255, 0, 0)); // 不透明红
        source.setRGB(1, 0, argb(128, 10, 20, 30)); // 半透明
        source.setRGB(0, 1, argb(0, 200, 200, 200)); // 全透明，RGB 是垃圾
        source.setRGB(1, 1, argb(0, 200, 200, 200));

        BufferedImage scaled = downscale(source);
        // alpha = (255 + 128 + 0 + 0) / 4 = 95（整数除法）；RGB = 两个可见像素的均值。
        assertEquals(argb(95, (255 + 10) / 2, (0 + 20) / 2, (0 + 30) / 2), scaled.getRGB(0, 0));
    }

    /**
     * 输出尺寸是**整除**（{@code newW = w / divisor}），所以不够一个块的余数被裁掉而不是并进最后一个块：
     * 11×11 在 target=3 下 divisor=2 → 5×5，第 11 列与第 11 行整个丢弃。用 R=20x、G=20y 的像素矩阵
     * （每通道都 ≤ 255，不会串位），每个输出纹素的期望值就是它覆盖的那 2×2 个源的均值。
     */
    @Test
    void theRemainderOutsideTheLastWholeBlockIsCropped() {
        Config.TEXTURE_TARGET_SIZE = 3; // 11 / 1 = 11 >= 6 -> divisor 2；11 / 2 = 5 < 6 -> 停
        BufferedImage source = image(11, 11);
        for (int y = 0; y < 11; y++) {
            for (int x = 0; x < 11; x++) {
                source.setRGB(x, y, argb(255, x * 20, y * 20, 0));
            }
        }

        BufferedImage scaled = downscale(source);
        assertEquals(5, scaled.getWidth(), "11 / 2 = 5（余下的第 11 列被裁掉）");
        assertEquals(5, scaled.getHeight(), "11 / 2 = 5（余下的第 11 行被裁掉）");
        // x={0,1}, y={0,1}
        assertEquals(argb(255, 10, 10, 0), scaled.getRGB(0, 0));
        // x={8,9}（不是 {8,9,10}），y={0,1}
        assertEquals(argb(255, 170, 10, 0), scaled.getRGB(4, 0));
        // x={0,1}, y={8,9}
        assertEquals(argb(255, 10, 170, 0), scaled.getRGB(0, 4));
        // 右下角：x={8,9}, y={8,9}
        assertEquals(argb(255, 170, 170, 0), scaled.getRGB(4, 4));
    }

    /**
     * 短边比 divisor 还窄时 {@code Math.max(1, w / divisor)} 把输出钉在 1 像素，块宽由
     * {@code Math.min(x0 + divisor, w)} 收到源宽：3×64 在 target=3 下 divisor=16 → 1×4，
     * 每个输出纹素是 3×16=48 个源像素的均值（不裁边就会读出行外数据）。
     */
    @Test
    void aBlockWiderThanTheSourceIsClampedToTheSourceWidth() {
        Config.TEXTURE_TARGET_SIZE = 3; // 64 / 1..8 都 >= 6，64 / 16 = 4 < 6 -> divisor 16
        BufferedImage source = image(3, 64);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 3; x++) {
                source.setRGB(x, y, argb(255, x * 20, y * 3, 0));
            }
        }

        BufferedImage scaled = downscale(source);
        assertEquals(1, scaled.getWidth(), "3 / 16 -> max(1, 0) = 1");
        assertEquals(4, scaled.getHeight(), "64 / 16 = 4");
        // x={0,1,2}, y={0..15}: R=(0+20+40)/3=20, G=3*(0+..+15)/16=22
        assertEquals(argb(255, 20, 22, 0), scaled.getRGB(0, 0));
        // x={0,1,2}, y={48..63}: R=20, G=3*888/16=166
        assertEquals(argb(255, 20, 166, 0), scaled.getRGB(0, 3));
    }

    /** 小图不放大：target 大于图长边时原样（同一个对象）。 */
    @Test
    void aSmallImageIsNeverUpscaled() {
        Config.TEXTURE_TARGET_SIZE = 512;
        BufferedImage source = image(64, 64);
        assertSame(source, downscale(source));

        Config.TEXTURE_TARGET_SIZE = 100;
        BufferedImage eighty = image(128, 128);
        assertSame(eighty, downscale(eighty), "128 落在 [100, 200) 里，原样");
    }
}
