package com.fox.ysmu.client.asset;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.provider.GeoModelProvider;

import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 模型几何资源生命周期门面：统一管理「加载 → 释放 → 重加载」。
 *
 * <p>业务代码只通过 {@link #geo} 拿到 {@link AssetHandle} 访问资源，不感知内部状态机与线程调度。
 * 生命周期编排：
 * <ul>
 *   <li>首次同步注册：几何安装到 GeckoLibCache 时调 {@link #registerGeo} 登记为 READY
 *       （跳过加载阶段）；</li>
 *   <li>闲置回收：主线程 {@link #tick}（空闲超时）释放 READY 资源回 ABSENT；</li>
 *   <li>重加载：之后任何 {@link AssetHandle#get()} 命中 ABSENT 时，自动在后台
 *       重新解密+解析（{@code ThreadTools.THREAD_POOL}），主线程写回 GeckoLibCache。</li>
 * </ul>
 *
 * <p><b>与参考分支的差异（逐条见 {@code .agent/phase32-asset-eviction-subsystem.md}）</b>：
 * <ol>
 *   <li><b>总开关默认关闭</b>。参考分支没有开关，{@code tick()} 无条件回收
 *       （参考 {@code AssetManager.java:73-94}）。本仓默认 {@code false}：这条路径从未运行过，
 *       且一次错误的空闲判定会把正在显示的几何移出 GeckoLibCache。关闭时本类的每个入口
 *       都直接返回 —— 不登记、不回收、不清空，引擎释放钩子也不会被调用。</li>
 *   <li><b>没有 ANIM 缓存</b>。参考分支还有 {@code AssetCache<ResourceLocation, AnimationFile> ANIM}
 *       （参考 {@code AssetManager.java:43-44}，provider 为参考 {@code provider/AnimationProvider.java}）。
 *       本仓没有移植它：{@code AnimationProvider.load} 要调
 *       {@code ClientModelManager.parseAnimationFromCache}，而本仓的动画安装不是「把 JSON 合成一个
 *       AnimationFile」而是按 domain 分别安装并同时注册控制器、扫描模组依赖、从内置默认补全
 *       （{@code ClientModelManager.registerAnimations:1428-1650}），参考分支那段 40 行的
 *       {@code load} 无法表达它 —— 照抄会静默丢掉第一人称 domain 与默认动画兜底。所以
 *       {@link #tick()} 只回收几何，ANIM 侧整体留给报告，不做 stub。</li>
 *   <li><b>纹理空闲卸载没有移植</b>。参考分支 {@code tick()} 末尾还调
 *       {@code ClientModelManager.unloadIdleTextures(now)}（参考 {@code AssetManager.java:93}）；
 *       本仓缺 {@code unloadIdleTextures} 所需的全部结构（见 phase32 表格第 3 行），所以这一行没有对应
 *       调用，纹理回收保持本仓既有状态（本仓本来就不做空闲纹理卸载）。</li>
 * </ol>
 */
public final class AssetManager {

    /** 失败重试冷却：损坏/缺失资源不每帧重试。 */
    private static final long FAILED_RETRY_MS = 2000L;
    /** 几何空闲多久（ms）后释放。与旧纹理/动画卸载窗口一致（参考 {@code AssetManager.java:35}）。 */
    private static final long IDLE_UNLOAD_MS = 30_000L;
    /** 可选容量上限（字节），0 = 不启用（只按空闲超时回收）。 */
    private static final long MAX_WEIGHT = 0L;
    /**
     * 两次回收扫描之间的最小间隔。
     * <p>
     * 参考分支的 {@code tick()} 由 60 秒一次的缓存清理驱动（参考 {@code ClientModelManager.java:1750}）；
     * 本仓的调用点是客户端 tick（{@code ClientEventHandler.onClientTick} → {@code ClientModelManager.tick}），
     * 每 tick 一次，所以在这里限流到同一量级，避免每个 tick 都遍历一次登记表。
     */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    /** 几何缓存：key 为 sub-model id（如 "ysmu:model_id/main"、"ysmu:model_id/arm"）。 */
    private static final AssetCache<ResourceLocation, GeoModel> GEO = new AssetCache<>(
        new GeoModelProvider(),
        FAILED_RETRY_MS);

    /** {@link #tick()} 上次真正执行扫描的时刻。 */
    private static volatile long lastScanAt;

    private AssetManager() {}

    /**
     * 子系统是否启用。
     * <p>
     * 所有调用点都必须先问这个（本类的登记/回收入口自己也判一次，双保险）：关闭时
     * {@link AssetHandle#get()} 会因为「未登记」而触发后台加载，所以关闭时连 {@link #geo}
     * 都不能被业务代码调用。
     */
    public static boolean isEnabled() {
        return Config.ENABLE_IDLE_ASSET_EVICTION;
    }

    /** 获取几何资源句柄（key 为 sub-model id）。调用点必须判 {@link #isEnabled()}。 */
    public static AssetHandle<ResourceLocation, GeoModel> geo(ResourceLocation geoId) {
        return new AssetHandle<>(GEO, geoId);
    }

    /** 主线程登记已就绪的几何（几何安装到 GeckoLibCache 时调用）。关闭时为 no-op。 */
    public static void registerGeo(ResourceLocation geoId, GeoModel geo) {
        if (!isEnabled() || geoId == null || geo == null) {
            return;
        }
        GEO.register(geoId, geo);
    }

    /**
     * 主线程周期回收空闲几何。调用点是客户端 tick，内部按 {@link #SCAN_INTERVAL_MS} 限流。
     * <p>
     * 当模型预览 GUI 打开或刚关闭（宽限期内），跳过回收。关闭总开关时整体 no-op。
     */
    public static void tick() {
        if (!isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastScanAt;
        if (now - previous < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        tick(now);
    }

    /**
     * 一次回收扫描（不限度流，供测试用合成时钟驱动 —— 与 {@link AssetCache#evict} 的
     * {@code now} 参数同一约定）。
     */
    static void tick(long now) {
        if (!isEnabled()) {
            return;
        }
        // 内置兜底模型（default）必须常驻：若被闲置释放，GUI 预览 fallback 到它时
        // GeckoLib 的 getModel() 会抛 GeoModelException "Could not find model" 崩溃。
        // 而且它的几何**没有**加密客户端缓存可恢复（loadDefaultModel 直接从本地 builtin/ 或
        // legacy custom/ 读盘，从不写 CACHED_MODEL_MD5），释放后 reload 必然失败。几何按子 id
        // 分开缓存，所以这里按"属于 default 模型"整批刷新活跃时间，不能只 touch main。
        // （参考 AssetManager.java:81-82；本仓用 ModelIdUtil.getParentModelId 表达同一个
        //   「去掉最后一个路径段」的判定，见 ClientModelManager#isDefaultModelId。）
        GEO.touchIf(ClientModelManager::isDefaultModelId, now);

        // Suppress all resource eviction while the preview GUI is open or within
        // the post-close grace period.
        if (ClientModelManager.isIdleEvictionSuppressed()) {
            return;
        }

        GEO.evict(now, IDLE_UNLOAD_MS, MAX_WEIGHT);
        // 参考分支此处还有一行 ClientModelManager.unloadIdleTextures(now)
        // （参考 AssetManager.java:93）：本仓缺它所需的全部结构，故不移植，见类 javadoc 第 3 条。
    }

    /** 释放全部资源并清空状态（断线 / /ysm reload 时调用）。关闭时为 no-op。 */
    public static void clearAll() {
        if (!isEnabled()) {
            return;
        }
        GEO.clear();
    }

    /** 立即释放一份几何的懒资源（主线程调用）。关闭时为 no-op。 */
    public static void release(ResourceLocation geoId) {
        if (!isEnabled() || geoId == null) {
            return;
        }
        GEO.release(geoId);
    }

    /** 当前登记的几何条目数（诊断用 / 测试用）。 */
    public static int geoSize() {
        return GEO.size();
    }
}
