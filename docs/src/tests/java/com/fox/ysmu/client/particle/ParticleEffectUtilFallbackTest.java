package com.fox.ysmu.client.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link ParticleEffectUtil} 的 1.7.10 降级表（高版本粒子名 → 内置近似粒子名）。
 *
 * <p>表里没有的键意味着"原样交给 vanilla 的 {@code spawnParticle}"；表里有的键则意味着那个高版本名字
 * 在 1.7.10 根本不存在，必须换名 —— 1.7.10 的 {@code spawnParticle} 对未知名字是**静默跳过**，
 * 所以漏一条映射的表现不是报错，而是整个模型的粒子效果凭空消失。
 *
 * <p>这张表本身是 private 的，唯一消费者 {@code resolve} 也是 private，所以单测通过新增的包内访问器
 * {@link ParticleEffectUtil#fallbackNameFor(String)} 核对。{@code null} 同时就是
 * {@code resolve} 里"保持原样、走原路径"的那一支，所以两个方向都被钉住。
 */
class ParticleEffectUtilFallbackTest {

    @Test
    void theNewlyMappedHighVersionParticlesLandOnSomethingVanillaActuallyHas() {
        // 1.20 的彩色粉末：delta(r,g,b) 恰好是 1.7.10 reddust 的颜色参数，只差名字。
        assertEquals("reddust", ParticleEffectUtil.fallbackNameFor("dust"));
        // 樱花花瓣在 1.7.10 没有同形粒子，取行为最近的下落白色碎屑。
        assertEquals("snowshovel", ParticleEffectUtil.fallbackNameFor("cherry_leaves"));
        // 末地烛的紫色光点。
        assertEquals("townaura", ParticleEffectUtil.fallbackNameFor("end_rod"));
    }

    @Test
    void theOlderMappingsStillHold() {
        assertEquals("dripWater", ParticleEffectUtil.fallbackNameFor("falling_dripstone_water"));
        assertEquals("dripWater", ParticleEffectUtil.fallbackNameFor("dripping_dripstone_water"));
        assertEquals("dripLava", ParticleEffectUtil.fallbackNameFor("dripping_dripstone_lava"));
        assertEquals("droplet", ParticleEffectUtil.fallbackNameFor("rain"));
        assertEquals("snowshovel", ParticleEffectUtil.fallbackNameFor("snowflake"));
        assertEquals("snowshovel", ParticleEffectUtil.fallbackNameFor("snow"));
        assertEquals("bubble", ParticleEffectUtil.fallbackNameFor("bubble_pop"));
        assertEquals("bubble", ParticleEffectUtil.fallbackNameFor("bubble_column_up"));
        assertEquals("smoke", ParticleEffectUtil.fallbackNameFor("poof"));
        assertEquals("flame", ParticleEffectUtil.fallbackNameFor("small_flame"));
        assertEquals("flame", ParticleEffectUtil.fallbackNameFor("soul_fire_flame"));
        assertEquals("smoke", ParticleEffectUtil.fallbackNameFor("campfire_cosy_smoke"));
        assertEquals("largesmoke", ParticleEffectUtil.fallbackNameFor("campfire_signal_smoke"));
        assertEquals("largesmoke", ParticleEffectUtil.fallbackNameFor("large_smoke"));
        assertEquals("largeexplode", ParticleEffectUtil.fallbackNameFor("explosion"));
        assertEquals("hugeexplosion", ParticleEffectUtil.fallbackNameFor("explosion_emitter"));
        assertEquals("witchMagic", ParticleEffectUtil.fallbackNameFor("dragon_breath"));
        assertEquals("happyVillager", ParticleEffectUtil.fallbackNameFor("totem_of_undying"));
    }

    /**
     * 表外的名字没有降级项：{@code resolve} 于是保持原名，vanilla 的 {@code spawnParticle} 走原路径
     * （1.7.10 自己就认识这些名字）。{@code null}/空串同理，而且不能抛。
     */
    @Test
    void namesOutsideTheTableKeepTheirOriginalName() {
        assertNull(ParticleEffectUtil.fallbackNameFor("flame"), "1.7.10 自带 flame，不该被换名");
        assertNull(ParticleEffectUtil.fallbackNameFor("heart"));
        assertNull(ParticleEffectUtil.fallbackNameFor("note"));
        assertNull(ParticleEffectUtil.fallbackNameFor("reddust"), "1.7.10 的名字本身不该再被降级");
        assertNull(ParticleEffectUtil.fallbackNameFor("largesmoke"));
        assertNull(ParticleEffectUtil.fallbackNameFor("not_a_particle"));
        assertNull(ParticleEffectUtil.fallbackNameFor(""));
        assertNull(ParticleEffectUtil.fallbackNameFor(null));
    }

    /**
     * 表键是**裸名**：`minecraft:` 前缀由调用方在查表前去掉（{@code normalizeParticleId}），
     * 所以带前缀的写法在这里查不到 —— 这正是 normalize 必须发生在那之前的原因。
     */
    @Test
    void theTableIsKeyedByTheBareNameWithoutTheMinecraftPrefix() {
        assertNull(ParticleEffectUtil.fallbackNameFor("minecraft:dust"));
        assertNull(ParticleEffectUtil.fallbackNameFor("minecraft:end_rod"));
        assertEquals("reddust", ParticleEffectUtil.fallbackNameFor("dust"));
    }
}
