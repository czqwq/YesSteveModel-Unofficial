package com.fox.ysmu.util;

/**
 * Interface implemented by EntityArrow via Mixin to expose the
 * projectile model ID stored in the datawatcher.
 */
public interface IProjectileModelArrow {
    String ysmu$getProjectileModelId();

    /**
     * 射出这支箭的物品在**服务端**的注册名（如 {@code TConstruct:Crossbow}），没有则为空串。
     *
     * <p>1.7.10 的 {@code EntityArrow.shootingEntity} 只在服务端赋值、不同步给客户端，
     * 所以这个值由 {@code MixinEntityArrow} 在箭矢构造时写进 datawatcher 带过来，
     * 供 {@code ysm.shoot_item_id} 使用（折算规则见 {@link ProjectileShootItemIds}）。</p>
     */
    String ysmu$getShootItemId();

    /**
     * 射击者的**实体 id**；没有射击者（如发射器射出的箭）或该槽还没被写时为 0。
     *
     * <p>供 {@code ysm.projectile_owner} 使用。上游那个变量返回射击者的**上下文**
     * （{@code tmp/YesSteveModel-dev-1.20 .../client/animation/molang/YSMBinding.java:193}
     * {@code projectileVar("projectile_owner", ctx -> ctx.createChild(ctx.entity().getOwner()))}），包用箭头表达式
     * {@code ysm.projectile_owner->…} 查询射击者的属性（wiki：{@code tmp/wiki/molang_misc.md:148}）。1.7.10 从不同步
     * {@code EntityArrow.shootingEntity}，所以 {@code MixinEntityArrow} 在箭矢构造时把实体 id 写进第三个
     * datawatcher 槽带过来，客户端再用 {@code World#getEntityByID} 换回实体——只对**已加载**的实体有效，
     * 换不回来时调用方应发布"没有上下文"，而不是伪造一个。</p>
     */
    int ysmu$getProjectileOwnerId();
}
