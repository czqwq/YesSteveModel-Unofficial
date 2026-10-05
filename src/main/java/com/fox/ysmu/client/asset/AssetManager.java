package com.fox.ysmu.client.asset;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.provider.AnimationProvider;
import com.fox.ysmu.client.asset.provider.GeoModelProvider;

import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 模型资源生命周期门面：统一管理「加载 → 释放 → 重加载」。
 *
 * <p>业务代码只通过 {@link #geo}/{@link #anim} 拿到 {@link AssetHandle} 访问资源，不感知内部状态机与线程调度。
 * 生命周期编排：
 * <ul>
 *   <li>首次同步注册：几何/动画安装到 GeckoLibCache 时调 {@link #registerGeo}/{@link #registerAnim}
 *       登记为 READY（跳过加载阶段）；</li>
 *   <li>闲置回收：主线程 {@link #tick}（空闲超时）释放 READY 资源回 ABSENT；</li>
 *   <li>重加载：之后任何 {@link AssetHandle#get()} 命中 ABSENT 时，自动在后台
 *       重新解密+解析（{@code ThreadTools.THREAD_POOL}），主线程写回 GeckoLibCache。</li>
 * </ul>
 *
 * <p><b>与参考分支的差异（逐条见 {@code .agent/phase40-eviction-members.md}）</b>：
 * <ol>
 *   <li><b>总开关默认关闭</b>。参考分支没有开关，{@code tick()} 无条件回收
 *       （参考 {@code AssetManager.java:73-94}）。本仓默认 {@code false}：这条路径从未运行过，
 *       且一次错误的空闲判定会把正在显示的几何/动画移出 GeckoLibCache。关闭时本类的每个入口
 *       都直接返回 —— 不登记、不回收、不清空，引擎释放钩子也不会被调用。</li>
 *   <li><b>ANIM 缓存的 key 相同、value 不同</b>。参考分支的 {@code ANIM} 是
 *       {@code AssetCache<ResourceLocation, AnimationFile>}，key 为 mainId，value 是「把该模型所有
 *       非弹射物动画 JSON 合成的那一个 {@code AnimationFile}」（参考 {@code AssetManager.java:43-44}
 *       + {@code provider/AnimationProvider.java:22-23}）。本仓的动画安装是**按 domain 一个文件**
 *       （{@code AnimationDomain.animationFileId}）+ 控制器注册 + 模组依赖扫描 + 逐 domain 的内置默认兜底
 *       （{@code ClientModelManager.registerAnimations}），一个合并文件无法表达它（会静默丢掉
 *       {@code fp_arm} domain）。所以这里缓存的是**该安装规则的输入**（动画文件字节），
 *       {@link AnimationProvider#apply} 调 {@code ClientModelManager.reinstallAnimations} 重跑同一条
 *       安装规则 —— 不复制第二份（参考分支自己在 {@code ClientModelManager.java:154} 警告两条路径的
 *       过滤规则会漂移）。value 为什么不是整份 {@code ModelData}，见 {@link #ANIM} 的说明。</li>
 *   <li><b>纹理空闲卸载没有移植</b>。参考分支 {@code tick()} 末尾还调
 *       {@code ClientModelManager.unloadIdleTextures(now)}（参考 {@code AssetManager.java:93}）；
 *       本仓缺它所需的全部结构（见 phase32 表格第 3 行），且纹理那半边已按**上游**的机制实现
 *       （{@code client/texture/CustomTextureManager} + {@code ModelTextureRegistry}，见
 *       {@code .agent/phase34-texture-release.md}），再加一套空闲时间戳就是同一件事的第二套实现。
 *       理由与取舍写在 phase40 报告里，这一行因此没有对应调用。</li>
 * </ol>
 */
public final class AssetManager {

    /** 失败重试冷却：损坏/缺失资源不每帧重试。 */
    private static final long FAILED_RETRY_MS = 2000L;
    /** 几何/动画空闲多久（ms）后释放。与旧纹理/动画卸载窗口一致（参考 {@code AssetManager.java:35}）。 */
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

    /**
     * 动画缓存：key 为模型自身的 mainId（如 "ysmu:model_id/main"），value 是**该模型的动画文件字节**
     * （即 {@code ClientModelManager.registerAnimations} 的第二个参数）。见类 javadoc 第 2 条：
     * 本仓按 domain 装两个文件，能重跑的只有「安装规则 + 它的输入」，而参考分支合成为一个
     * {@code AnimationFile} 的写法会静默丢掉 {@code fp_arm} domain。
     * <p>
     * 为什么 value 是动画字节而不是整份 payload：{@code ModelData} 还带着几何与纹理字节，
     * 那些在本仓安装完成后就已经释放（{@code PENDING_ANIMATIONS} / {@code PENDING_GEOMETRY} 会被取走），
     * 缓存整份 payload 会让它们随会话常驻 —— 那是本子系统最不该做的事。动画字节本身是安装规则唯一的输入，
     * 也是本仓在「已同步未安装」期间本来就会持有的那一份（{@code PENDING_ANIMATIONS} 的 javadoc：
     * 它比解析出来的对象图小得多）。
     */
    private static final AssetCache<ResourceLocation, Map<String, byte[]>> ANIM = new AssetCache<>(
        new AnimationProvider(),
        FAILED_RETRY_MS);

    /**
     * 被 {@link #registerAnim} 采纳过动画的资源 key。
     * <p>
     * <b>为什么门面要自己记这一份</b>：几何侧的「要不要重新加载」可以由引擎缓存回答 —— 一个已发布的模型
     * 的几何一定在 {@code GeckoLibCache} 里，所以「引擎缓存里没有」就等于「被回收了」
     * （{@code ClientModelManager.ensureGeometry} 的 seam）。动画侧没有这条不变式：一个**自身不声明任何
     * 动画**的模型本来就没有文件，它的正常状态就是回落到内置兜底
     * （{@code CustomPlayerEntity.getAnimation()} 在文件缺失时答 {@code DEFAULT_MAIN_ANIMATION}），
     * 那不是「被回收」。而 {@link AssetCache} 在回收时会把条目整个移出登记表
     * （{@code AssetCache.release} → {@code entries.remove}，由 {@code AssetCacheTest} 钉住），
     * 于是回收之后没有任何地方还能回答「这个 key 曾被采纳过」。没有这一份记录时，seam 会对每个
     * 「自身无动画」的模型发起一次后台重载（重解密整份模型文件），既浪费又改变那条回落路径。
     */
    private static final Set<ResourceLocation> ADOPTED_ANIMATIONS = ConcurrentHashMap.newKeySet();

    /** {@link #tick()} 上次真正执行扫描的时刻。 */
    private static volatile long lastScanAt;

    private AssetManager() {}

    /**
     * 子系统是否启用。
     * <p>
     * 所有调用点都必须先问这个（本类的登记/回收入口自己也判一次，双保险）：关闭时
     * {@link AssetHandle#get()} 会因为「未登记」而触发后台加载，所以关闭时连 {@link #geo}/{@link #anim}
     * 都不能被业务代码调用。
     */
    public static boolean isEnabled() {
        return Config.ENABLE_IDLE_ASSET_EVICTION;
    }

    /** 获取几何资源句柄（key 为 sub-model id）。调用点必须判 {@link #isEnabled()}。 */
    public static AssetHandle<ResourceLocation, GeoModel> geo(ResourceLocation geoId) {
        return new AssetHandle<>(GEO, geoId);
    }

    /** 获取动画资源句柄（key 为该模型的 mainId）。调用点必须判 {@link #isEnabled()}。 */
    public static AssetHandle<ResourceLocation, Map<String, byte[]>> anim(ResourceLocation mainId) {
        return new AssetHandle<>(ANIM, mainId);
    }

    /** 主线程登记已就绪的几何（几何安装到 GeckoLibCache 时调用）。关闭时为 no-op。 */
    public static void registerGeo(ResourceLocation geoId, GeoModel geo) {
        if (!isEnabled() || geoId == null || geo == null) {
            return;
        }
        GEO.register(geoId, geo);
    }

    /**
     * 主线程登记一个模型已经安装的动画文件字节（{@code ClientModelManager.ensureAnimations} 装完两个 domain 的
     * 文件后调用）。关闭时为 no-op。
     * <p>
     * 登记即「采纳」：{@link #tick} 之后可以回收它，而 {@link ClientModelManager} 的 seam 会按
     * {@link #managesAnimations} 决定是否重新加载。
     */
    public static void registerAnim(ResourceLocation mainId, Map<String, byte[]> animations) {
        if (!isEnabled() || mainId == null || animations == null) {
            return;
        }
        ADOPTED_ANIMATIONS.add(mainId);
        ANIM.register(mainId, animations);
    }

    /**
     * 这个模型的动画是否被 {@link #registerAnim} 采纳过（即本子系统负责它的空闲回收与重加载）。
     * <p>
     * 回收之后仍然为 {@code true}：这正是它存在的理由（见 {@link #ADOPTED_ANIMATIONS}）。
     */
    public static boolean managesAnimations(@Nullable ResourceLocation mainId) {
        return mainId != null && ADOPTED_ANIMATIONS.contains(mainId);
    }

    /**
     * 主线程周期回收空闲几何/动画。调用点是客户端 tick，内部按 {@link #SCAN_INTERVAL_MS} 限流。
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
        // 而且它的几何/动画**没有**加密客户端缓存可恢复（loadDefaultModel 直接从本地 builtin/ 或
        // legacy custom/ 读盘，从不写 CACHED_MODEL_MD5），释放后 reload 必然失败。几何按子 id
        // 分开缓存，所以这里按"属于 default 模型"整批刷新活跃时间，不能只 touch main。
        // 动画侧的 key 是该模型的 mainId，同一个判定同样成立（顺便：它一个模型一个条目，
        // 不存在几何那种「只 touch main 会漏掉 arm」的问题）。
        // （参考 AssetManager.java:81-82；本仓用 ModelIdUtil.getParentModelId 表达同一个
        //   「去掉最后一个路径段」的判定，见 ClientModelManager#isDefaultModelId。）
        GEO.touchIf(ClientModelManager::isDefaultModelId, now);
        ANIM.touchIf(ClientModelManager::isDefaultModelId, now);

        // Suppress all resource eviction while the preview GUI is open or within
        // the post-close grace period.
        if (ClientModelManager.isIdleEvictionSuppressed()) {
            return;
        }

        GEO.evict(now, IDLE_UNLOAD_MS, MAX_WEIGHT);
        ANIM.evict(now, IDLE_UNLOAD_MS, MAX_WEIGHT);
        // 参考分支此处还有一行 ClientModelManager.unloadIdleTextures(now)
        // （参考 AssetManager.java:93）：本仓缺它所需的全部结构，且纹理那半边已按上游机制实现，
        // 故不移植、不加第二套空闲策略，见类 javadoc 第 3 条。
    }

    /**
     * 释放全部资源并清空状态（断线 / /ysm reload 时调用）。关闭时为 no-op。
     * <p>
     * {@link #ADOPTED_ANIMATIONS} 一并清空：它记录的是「这一轮会话里本子系统接管了哪些模型」，
     * 会话结束就跟着 {@code ClientModelManager.clearRuntimeModelCaches} 的其余状态一起作废。
     */
    public static void clearAll() {
        if (!isEnabled()) {
            return;
        }
        GEO.clear();
        ANIM.clear();
        ADOPTED_ANIMATIONS.clear();
    }

    /**
     * 立即释放一份资源的懒资源（主线程调用）。关闭时为 no-op。
     * <p>
     * 几何缓存按 sub-model id 登记，动画缓存按模型的 mainId 登记，所以 mainId 会同时命中两者
     * （参考 {@code AssetManager.release} 同样按 mainId 释放 geo + anim，
     * 参考 {@code AssetManager.java:104-107}），而 arm 之类的其它 sub-model id 在动画侧只是一次
     * no-op（{@link AssetCache#release} 对未登记的 key 什么都不做）。
     * <p>
     * 注意：本仓目前没有调用点（参考分支由 {@code sweepInUseModels} 的 {@code IN_USE_MODELS} 驱动，
     * 本仓没有那张表，见 phase32）；保留形状而不发明调用点。
     */
    public static void release(ResourceLocation key) {
        if (!isEnabled() || key == null) {
            return;
        }
        GEO.release(key);
        ANIM.release(key);
    }

    /** 当前登记的几何条目数（诊断用 / 测试用）。 */
    public static int geoSize() {
        return GEO.size();
    }

    /** 当前登记的动画条目数（诊断用 / 测试用）。 */
    public static int animSize() {
        return ANIM.size();
    }
}
