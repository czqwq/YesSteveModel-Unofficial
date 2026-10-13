package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;

/**
 * {@link ModelTextureRegistry} 的预算回收与所有权：{@link Config#TEXTURE_VRAM_BUDGET_MB} 的 LRU 回收
 * 与 {@code register}/{@code clear} 的 id 回归面。
 *
 * <p><b>测试替身</b>：真正把像素放进显存要经过 {@code TextureUtil}（{@code GL11}），单测里做不到，所以
 * {@link UploadedTexture} 只覆盖预算决策读到的三个公开入口（{@code isUploaded}/{@code getGpuBytes}/
 * {@code freeGlTexture}），并照真实实现的契约在释放时扣回记账。注册路径本身是生产代码：{@code register}
 * 会走 {@code CustomTextureManager.register}，而它的 {@code MinecraftHost} 在没有客户端时
 * （{@code Minecraft.getMinecraft() == null}）只做空操作，所以这里能真实地测"注册 → 平台拿到调用方的 id"。
 * 反向的那一半（{@code clear()} 时平台收到了哪个 id）在本类里观察不到：{@code CustomTextureManager.REGISTRY}
 * 是私有的静态实例，宿主接缝由 {@link CustomTextureManagerOwnershipTest#theCallersIdIsTheIdThePlatformPublishes()}
 * 用一个假 {@code Host} 钉住。
 *
 * <p>时间：生产路径里保护窗口用 {@code System.currentTimeMillis()}，测试用本类的
 * {@code enforceVramBudget(long now)} 重载把它变成参数（生产多出的只是这次时钟读取），所以"5 秒内不动 /
 * 5 秒后可以回收"不需要 sleep。
 */
class ModelTextureRegistryTest {

    private static final long MB = 1024L * 1024L;
    /** 1.5 MB：用来分辨"降到预算的 75%"与"降到预算本身"。 */
    private static final long ONE_AND_A_HALF_MB = MB * 3L / 2L;

    private int savedBudget;

    @BeforeEach
    void resetRegistry() {
        this.savedBudget = Config.TEXTURE_VRAM_BUDGET_MB;
        // `false`: an empty registry, i.e. the pinned built-in fallback dropped as well. The pin is a production rule
        // (see ModelTextureRegistry#clear(boolean)) and the tests that exercise it assert it themselves; every other
        // test in this class needs the registry it found empty, whatever ran before it in this JVM.
        ModelTextureRegistry.clear(false);
    }

    @AfterEach
    void restoreConfig() {
        ModelTextureRegistry.clear(false);
        Config.TEXTURE_VRAM_BUDGET_MB = this.savedBudget;
    }

    /** An uploaded model texture without GL: reports the three public values the budget decision reads. */
    private static final class UploadedTexture extends OuterFileTexture {

        private final long bytes;
        private boolean uploaded;
        private int frees;

        private UploadedTexture(long bytes, boolean uploaded) {
            super((BufferedImage) null);
            this.bytes = bytes;
            this.uploaded = uploaded;
        }

        @Override
        public boolean isUploaded() {
            return this.uploaded;
        }

        @Override
        public long getGpuBytes() {
            return this.uploaded ? this.bytes : 0L;
        }

        @Override
        public void freeGlTexture() {
            if (!this.uploaded) {
                return;
            }
            this.uploaded = false;
            this.frees++;
            // The accounting half of the real freeGlTexture(): the budget counter has to come down with it,
            // or the eviction loop could never reach its target.
            ModelTextureRegistry.onTextureGpuBytesChanged(-this.bytes);
        }
    }

    /** Registers an already-uploaded texture of {@code bytes} bytes and adds it to the footprint counter. */
    private static UploadedTexture upload(ResourceLocation id, long bytes) {
        UploadedTexture texture = new UploadedTexture(bytes, true);
        ModelTextureRegistry.onTextureGpuBytesChanged(bytes);
        ModelTextureRegistry.register(id, texture);
        return texture;
    }

    private static ResourceLocation textureId(String model, String file) {
        return new ResourceLocation("ysmu", model + "/" + file);
    }

    /** 预算 0 是文档里的"关闭"：超预算也不回收，纹理的 GPU 拷贝原样留着。 */
    @Test
    void aDisabledBudgetNeverEvicts() {
        Config.TEXTURE_VRAM_BUDGET_MB = 0;
        UploadedTexture texture = upload(textureId("model_a", "tex"), 8L * MB);

        ModelTextureRegistry.enforceVramBudget(System.currentTimeMillis());

        assertEquals(0, texture.frees, "0 = 关闭，一次释放都不能发生");
        assertEquals(8L * MB, texture.getGpuBytes(), "GPU 拷贝必须还在");
    }

    /**
     * 超预算时按 LRU 回收**整个模型**（同一模型的 main/arm 一起走），并且降到预算的 75% 就停 ——
     * 滞回是为了让下一次上传不会立刻再触发一轮。用 1.5 MB 的块把"降到 75%"与"降到预算本身"分开：
     * 预算 4 MB（目标 3 MB）、总量 5 MB 时，放掉一个 1.5 MB 的模型后还有 3.5 MB，必须再放一个。
     */
    @Test
    void wholeModelsAreEvictedLeastRecentlyUsedFirstDownToTheHysteresisFloor() {
        Config.TEXTURE_VRAM_BUDGET_MB = 4;

        // 两个从未绑定过的模型各 1.5 MB（各两个 sub-model 贴图）：它们排在最前。
        UploadedTexture neverBoundMain = upload(textureId("model_c", "main"), ONE_AND_A_HALF_MB / 2);
        UploadedTexture neverBoundArm = upload(textureId("model_c", "arm"), ONE_AND_A_HALF_MB / 2);
        UploadedTexture otherNeverBound = upload(textureId("model_d", "main"), ONE_AND_A_HALF_MB);
        // 两个绑定过的模型各 1 MB，远在保护窗口之外。
        UploadedTexture boundOld = upload(textureId("model_a", "tex"), MB);
        ModelTextureRegistry.touch(textureId("model_a", "tex"));
        UploadedTexture boundNew = upload(textureId("model_b", "tex"), MB);
        ModelTextureRegistry.touch(textureId("model_b", "tex"));

        long now = System.currentTimeMillis() + 60_000L; // 保护窗口早已过期
        ModelTextureRegistry.enforceVramBudget(now);

        assertEquals(1, neverBoundMain.frees, "整个模型一起回收：main");
        assertEquals(1, neverBoundArm.frees, "整个模型一起回收：arm");
        assertEquals(1, otherNeverBound.frees, "5 MB -> 3.5 MB 还在 75% 目标(3 MB)以上，必须继续回收");
        assertEquals(0, boundOld.frees, "已经降到目标就停，不许连带回收别的模型");
        assertEquals(0, boundNew.frees);

        // 再一次调用：总量已回到预算以内，什么都不做（不得出现第二轮回收）。
        ModelTextureRegistry.enforceVramBudget(now);
        assertEquals(1, otherNeverBound.frees, "第二次调用不得重复释放");
        assertEquals(0, boundOld.frees);
        assertEquals(0, boundNew.frees);
    }

    /** 5 秒保护窗口内的模型不是候选：它马上会被再绑一次，回收只会造成 上传→回收→重传 的抖动。 */
    @Test
    void aRecentlyBoundModelIsProtectedFromEviction() {
        Config.TEXTURE_VRAM_BUDGET_MB = 1;
        long beforeTouch = System.currentTimeMillis();
        UploadedTexture texture = upload(textureId("model_a", "tex"), 2L * MB);
        ModelTextureRegistry.touch(textureId("model_a", "tex"));

        // 时钟只走 1 秒：lastUsed >= beforeTouch，所以 now - lastUsed <= 1 秒，落在 5 秒窗口内。
        ModelTextureRegistry.enforceVramBudget(beforeTouch + 1000L);

        assertEquals(0, texture.frees, "5 秒保护窗口内不得回收");
    }

    /** 保护窗口过期后同一个模型就是候选了（阈值是"小于 5 秒"，所以 5 秒整已经可以回收）。 */
    @Test
    void anIdleModelBecomesCollectableAfterTheProtectWindow() {
        Config.TEXTURE_VRAM_BUDGET_MB = 1;
        long beforeTouch = System.currentTimeMillis();
        UploadedTexture texture = upload(textureId("model_a", "tex"), 2L * MB);
        ModelTextureRegistry.touch(textureId("model_a", "tex"));

        ModelTextureRegistry.enforceVramBudget(beforeTouch + 60_000L);

        assertEquals(1, texture.frees, "保护窗口过期后必须回收");
        assertEquals(0L, texture.getGpuBytes(), "回收的是 GPU 拷贝");
    }

    /** 没有 GPU 拷贝的注册不是候选（它本来就没占显存），只有上传过的才回收。 */
    @Test
    void onlyUploadedTexturesAreCandidates() {
        Config.TEXTURE_VRAM_BUDGET_MB = 1;
        UploadedTexture neverUploaded = new UploadedTexture(MB, false);
        ModelTextureRegistry.register(textureId("model_idle", "tex"), neverUploaded);
        UploadedTexture uploaded = upload(textureId("model_a", "tex"), 2L * MB);

        ModelTextureRegistry.enforceVramBudget(System.currentTimeMillis() + 60_000L);

        assertEquals(0, neverUploaded.frees, "没有 GPU 拷贝的注册不能回收");
        assertEquals(1, uploaded.frees);
    }

    /** 预算为 0 时 {@link ModelTextureRegistry#touch} 连一次 map 写都不做（"关闭"必须零成本）。 */
    @Test
    void touchIsANoOpWhileTheBudgetIsOff() {
        Config.TEXTURE_VRAM_BUDGET_MB = 0;
        UploadedTexture texture = upload(textureId("model_a", "tex"), 2L * MB);
        ModelTextureRegistry.touch(textureId("model_a", "tex"));

        // 预算关着：即使再过一小时也不该有任何回收。
        ModelTextureRegistry.enforceVramBudget(System.currentTimeMillis() + 3_600_000L);
        assertEquals(0, texture.frees);
    }

    /**
     * 贴图 id 的回归面（{@code register} 的模型路径此前没有任何单测）：注册后
     * {@code holder.id()} 必须是**调用方绑定的那个 id**，同一 id 重注册只留一个条目，{@code clear()} 全部忘掉；
     * 空参数按生产约定返回 null 而不是抛异常。
     */
    @Test
    void registerKeepsTheCallersIdAndClearForgetsEveryHolder() {
        Config.TEXTURE_VRAM_BUDGET_MB = 0;
        ResourceLocation id = new ResourceLocation("ysmu", "wine_fox/05_magical/magic");
        UploadedTexture first = new UploadedTexture(MB, true);

        TextureHolder holder = ModelTextureRegistry.register(id, first);

        assertNotNull(holder);
        assertEquals(id, holder.id(), "holder 必须是调用方绑定的那个 id，而不是合成的 ysmu:textures/<n>");
        assertEquals(1, ModelTextureRegistry.size());

        // 同一个 id 重新注册（重新同步/重新上传）仍旧只占一个条目，新的 holder 还是那个 id。
        UploadedTexture replacement = new UploadedTexture(MB, true);
        TextureHolder replacementHolder = ModelTextureRegistry.register(id, replacement);
        assertNotNull(replacementHolder);
        assertEquals(id, replacementHolder.id());
        assertEquals(1, ModelTextureRegistry.size());

        assertNull(ModelTextureRegistry.register(null, first), "没有 id 就没有注册");
        assertNull(ModelTextureRegistry.register(id, null), "没有纹理就没有注册");
        assertEquals(1, ModelTextureRegistry.size(), "被拒绝的注册不能进表");

        ModelTextureRegistry.clear();
        assertEquals(0, ModelTextureRegistry.size(), "clear 之后一个 holder 都不留");
    }

    /**
     * 内置兜底模型（default）自己的贴图不随模型列表一起释放。
     *
     * <p>它不是连接级模型列表的一部分：{@code CustomPlayerModel.textureFor} 在模型尚未发布时按常量把这张贴图
     * 交出去（渲染器此时画的正是内置 default 的几何），而 1.7.10 的 {@code TextureManager.bindTexture} 对没有表项
     * 的 id 会新建 {@code SimpleTexture} 去资源包里找——模型贴图永远找不到，于是打一条
     * {@code Failed to load texture} + {@code FileNotFoundException}，并把该 id 永久指向全局缺失贴图。
     * 上一次实测：{@code ysmu:default/default.png} 就是这么被绑定的。
     *
     * <p>同一模型的几何/动画早已被 {@code AssetManager} 用同一条理由钉住（「内置兜底模型必须常驻，释放后没有
     * 加密缓存可恢复」），这里按同一个判定（{@code ClientModelManager.isDefaultModelId}）把贴图那半边补齐。
     */
    @Test
    void clearKeepsTheBuiltInDefaultsTexturesAndReleasesTheModelsOfTheConnection() {
        Config.TEXTURE_VRAM_BUDGET_MB = 0;
        ResourceLocation fallback = textureId(Config.DEFAULT_MODEL_ID, "default.png");
        ResourceLocation fallbackSecond = textureId(Config.DEFAULT_MODEL_ID, "blue.png");
        ResourceLocation ordinary = textureId("model_a", "tex");
        upload(fallback, MB);
        upload(fallbackSecond, MB);
        upload(ordinary, MB);
        assertTrue(ModelTextureRegistry.isRegistered(ordinary), "普通模型贴图先注册上了");

        ModelTextureRegistry.clear();

        assertTrue(ModelTextureRegistry.isRegistered(fallback), "default 的默认贴图必须留在注册表里");
        assertTrue(ModelTextureRegistry.isRegistered(fallbackSecond), "default 的每一张贴图都算，不只是默认那张");
        assertFalse(ModelTextureRegistry.isRegistered(ordinary), "连接内的普通模型贴图照旧释放");
    }

    /**
     * 常驻的兜底贴图仍然计入显存账：清理时把计数清零会让预算「看不见」它，
     * 于是在真实超预算时一次回收都不触发（预算形同虚设）。
     *
     * <p>预算 2 MB、目标 1.5 MB：常驻的 1.5 MB + 新来的 1 MB = 2.5 MB 超预算，必须回收一张。常驻那张刚刚绑定过
     * （{@code touch}），所以 LRU 先回收从没绑定过的 1 MB；回收后正好落到目标，循环停下。旧实现把计数清零时这里
     * 只有 1 MB，预算不触发，{@code fresh.frees} 会是 0。
     */
    @Test
    void thePinnedFallbackStillCountsAgainstTheBudget() {
        Config.TEXTURE_VRAM_BUDGET_MB = 2;
        ResourceLocation fallback = textureId(Config.DEFAULT_MODEL_ID, "default.png");
        UploadedTexture pinned = upload(fallback, ONE_AND_A_HALF_MB);

        ModelTextureRegistry.clear();
        ModelTextureRegistry.touch(fallback);
        UploadedTexture fresh = upload(textureId("model_fresh", "tex"), MB);

        ModelTextureRegistry.enforceVramBudget(System.currentTimeMillis() + 60_000L);

        assertEquals(1, fresh.frees, "常驻的 1.5 MB 仍在预算里，新贴图必须被回收");
        assertEquals(0, pinned.frees, "按 LRU 只回收最新的那张，常驻贴图不动（它的 GPU 拷贝也留着）");
    }

    /**
     * {@code clear()} 同时把总记账清零：否则上一批模型的余额会把新注册的小贴图立刻挤出预算。
     * 2 MB 的旧纹理 clear 之后，新注册的 0.5 MB 纹理在 1 MB 预算下必须留着。
     */
    @Test
    void clearResetsTheFootprintCounter() {
        Config.TEXTURE_VRAM_BUDGET_MB = 1;
        upload(textureId("model_old", "tex"), 2L * MB);

        ModelTextureRegistry.clear();

        UploadedTexture fresh = upload(textureId("model_fresh", "tex"), MB / 2);
        ModelTextureRegistry.enforceVramBudget(System.currentTimeMillis());

        assertEquals(0, fresh.frees, "clear 必须把记账清零，新注册的纹理不该被上一批的余额挤掉");
    }
}
