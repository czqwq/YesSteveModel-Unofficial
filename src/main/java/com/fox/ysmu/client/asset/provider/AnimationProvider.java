package com.fox.ysmu.client.asset.provider;

import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.AnimationDomain;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.asset.AssetProvider;
import com.fox.ysmu.client.asset.ReleaseMode;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 动画资源策略：卸载后从加密客户端缓存重新解密+解析（后台），主线程重跑本仓的安装规则。
 *
 * <p>释放深度为 {@link ReleaseMode#DROP_HEAP}：动画的 GeckoKeyFrame 图是大型模型库堆内存的最大头，
 * 正是「释放 → 后台重载」闭环要管理的核心目标（参考 {@code provider/AnimationProvider.java:15-17}）。
 *
 * <p><b>与参考分支的差异（逐条见 {@code .agent/phase40-eviction-members.md}）</b>：
 * <ol>
 *   <li><b>value 是动画文件字节，不是一个合并的 {@code AnimationFile}</b>。参考的 key 同为 mainId，value 是
 *       {@code parseAnimationFromCache} 把该模型所有非弹射物动画 JSON 合成的那**一个**文件
 *       （参考 {@code provider/AnimationProvider.java:22-23}、{@code ClientModelManager.java:1555-1594}）。
 *       本仓的安装是按 domain 一个文件（{@code AnimationDomain}），合成就等于静默丢掉 {@code fp_arm}
 *       domain；而能产出那两个文件的只有本仓自己的安装规则（控制器注册、模组依赖扫描、逐 domain 的
 *       内置默认兜底，{@code ClientModelManager.registerAnimations}）。所以这里缓存该规则的**输入**，
 *       {@link #apply} 交给 {@code ClientModelManager.reinstallAnimations} 重跑同一条规则 ——
 *       不复制第二份（参考分支自己在 {@code ClientModelManager.java:154} 警告两条路径的过滤规则会漂移）。
 *       value 只保留动画字节、不保留整份 {@code ModelData}，理由见 {@code AssetManager.ANIM} 的说明。</li>
 *   <li><b>没有 {@code CustomPlayerModel.clearInjectedCache(mainId)}</b>（参考
 *       {@code provider/AnimationProvider.java:33}）：本仓没有虚拟骨骼注入，那个「让下一次
 *       getModel 重新注入」的标记没有东西可清。这是一个 BLOCKED 成员（phase32 表格第 5/9 行、
 *       phase40 的裁定），不写空实现去假装它存在。参考 {@code onGeoModelReleased} 那一半在本仓
 *       已移植并有调用。</li>
 *   <li><b>release 摘掉两个 domain 的文件</b>。参考只有一个文件（mainId），本仓同一模型的动画在
 *       引擎缓存里是两个条目（{@code main} / {@code arm}，见 {@code AnimationDomain.animationFileId}），
 *       而引擎的 {@code AnimationData} 是按 animatable 自己答的动画 id 绑定的
 *       （{@code AnimatedGeoModel.setLivingAnimations} → {@code bindAnimationFile(getAnimationFileLocation)}），
 *       第一人称手臂绑的是 {@code arm} id —— 只摘 main 会把手臂那份留在缓存里，这一侧的释放就白做。</li>
 * </ol>
 */
public final class AnimationProvider implements AssetProvider<ResourceLocation, Map<String, byte[]>> {

    /**
     * 后台线程：从一个模型在加密客户端缓存里的 payload 里取出它的动画文件字节。
     * <p>
     * 空 map 与「取不到 payload」一样回 {@code null}：没有东西可安装时条目应当进 FAILED 而不是把一份空
     * payload 安装成该模型的动画（参考 {@code parseAnimationFromCache} 在没有动画幸存时同样返回 null，
     * 参考 {@code ClientModelManager.java:1589-1592}）。
     */
    @Override
    @Nullable
    public Map<String, byte[]> load(ResourceLocation mainId) {
        // 本仓只认 OpenYSM 加密缓存（legacy 通道的「模型 → 缓存文件」关联记在 network/**，
        // 见 ClientModelManager.hasReloadableClientCache 的说明），与 ClientModelManager 的采纳门
        // 是同一个事实，所以这里不需要再判一次。
        ModelData data = ClientModelManager.loadLegacyModelData(mainId);
        if (data == null) {
            return null;
        }
        Map<String, byte[]> animations = data.getAnimation();
        return animations == null || animations.isEmpty() ? null : animations;
    }

    @Override
    public void apply(ResourceLocation mainId, Map<String, byte[]> animations) {
        ClientModelManager.reinstallAnimations(mainId, animations);
    }

    @Override
    public void release(ResourceLocation mainId, Map<String, byte[]> animations, ReleaseMode mode) {
        if (mode == ReleaseMode.GPU_ONLY) {
            // 动画没有「只丢 GPU 那一半」的说法：堆对象就是全部。
            return;
        }
        ResourceLocation baseId = ModelIdUtil.getParentModelId(mainId);
        for (String domain : AnimationDomain.ALL) {
            GeckoLibCache.getInstance()
                .getAnimations()
                .remove(AnimationDomain.animationFileId(baseId, domain));
        }
        // 引擎侧：绑定了这两份文件的动画数据必须松开对 KeyFrame 图的引用，否则 DROP_HEAP 只搬走了
        // GeckoLibCache 里的登记项，堆并没有真的释放（参考 provider/AnimationProvider.java:40 只做
        // mainId，本仓两个 domain 各绑各的，见类 javadoc 第 3 条）。
        AnimationData.releaseAnimationFile(mainId);
        AnimationData.releaseAnimationFile(ModelIdUtil.getArmId(baseId));
        // 参考 provider/AnimationProvider.java:41：让控制器丢掉按旧文件解析出来的片段，
        // 下一帧按重载后的文件重新 setAnimation（本仓的运行时状态见
        // OpenYsmPlayerControllerRuntime.releaseModelAnimations，phase32 表格第 6 行）。
        OpenYsmPlayerControllerRuntime.releaseModelAnimations(mainId);
    }

    @Override
    public ReleaseMode defaultReleaseMode() {
        return ReleaseMode.DROP_HEAP;
    }

    @Override
    public long weight(ResourceLocation mainId, Map<String, byte[]> animations) {
        if (animations == null) {
            return 0;
        }
        // 参考按合并文件的动画条数粗估（参考 provider/AnimationProvider.java:51-57）；本仓缓存的就是
        // 原始 JSON 字节，直接按字节数算更准。只在容量上限启用时才被调用（当前 MAX_WEIGHT = 0，
        // 见 AssetCache.evict），所以这点遍历不影响空闲扫描。
        long total = 0L;
        for (byte[] bytes : animations.values()) {
            if (bytes != null) {
                total += bytes.length;
            }
        }
        return total;
    }
}
