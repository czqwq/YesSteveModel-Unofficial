package com.fox.ysmu.client.asset.provider;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.AssetProvider;
import com.fox.ysmu.client.asset.ReleaseMode;

import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 几何资源策略：卸载后从加密客户端缓存重新解密+解析（后台），主线程写回 GeckoLibCache。
 *
 * <p>释放深度为 {@link ReleaseMode#DROP_HEAP}：几何 JSON 体积小、重解析快，
 * 卸载后通过本缓存走「释放 → 后台重载」闭环。
 *
 * <p><b>与参考分支的差异</b>：参考 {@code GeoModelProvider.java:37} 的
 * {@code CustomPlayerModel.clearInjectedCache(geoId)} 调用在本仓**没有对应调用** ——
 * 本仓没有虚拟骨骼注入（{@code injectVirtualBones} / {@code INJECTED_LOCATIONS} /
 * {@code VirtualBone} 三者在本仓都不存在），所以那个「让下一次 getModel 重新注入」的标记
 * 没有东西可清。这是一个 BLOCKED 成员，见 {@code .agent/phase32-asset-eviction-subsystem.md}；
 * 这里不写空实现去假装它存在。参考 {@code onGeoModelReleased} 那一半在本仓**已移植并有调用**。
 */
public final class GeoModelProvider implements AssetProvider<ResourceLocation, GeoModel> {

    @Override
    public GeoModel load(ResourceLocation geoId) {
        return ClientModelManager.parseSingleGeoFromCache(geoId);
    }

    @Override
    public void apply(ResourceLocation geoId, GeoModel geo) {
        GeckoLibCache.getInstance().getGeoModels().put(geoId, geo);
    }

    @Override
    public void release(ResourceLocation geoId, GeoModel geo, ReleaseMode mode) {
        if (mode != ReleaseMode.GPU_ONLY) {
            GeckoLibCache.getInstance().getGeoModels().remove(geoId);
            // 参考 GeoModelProvider.java:37 调 CustomPlayerModel.clearInjectedCache(geoId)：
            // 本仓无虚拟骨骼注入，没有标记可清（见类 javadoc）。
            // 还要让模型实例松开对这份几何的强引用（currentModel / 骨骼登记）——
            // 只从 GeckoLibCache 里移除的话，堆内存释放不掉，见 onGeoModelReleased 的说明。
            com.fox.ysmu.client.model.CustomPlayerModel.onGeoModelReleased(geo);
        }
    }

    @Override
    public ReleaseMode defaultReleaseMode() {
        return ReleaseMode.DROP_HEAP;
    }

    @Override
    public long weight(ResourceLocation geoId, GeoModel geo) {
        if (geo == null || geo.topLevelBones == null) {
            return 0;
        }
        // 粗略按顶层骨骼数估算体积（容量上限统计用）。
        return geo.topLevelBones.size() * 1024L;
    }
}
