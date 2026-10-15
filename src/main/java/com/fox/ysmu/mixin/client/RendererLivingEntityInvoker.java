package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code RendererLivingEntity#passSpecialRender}, which is {@code protected} in 1.7.10
 * ({@code RendererLivingEntity.java:466}) and therefore unreachable from the vehicle renderer.
 *
 * <p>The method is the vanilla "specials" pass: it posts {@code RenderLivingEvent.Specials.Pre}
 * ({@code RendererLivingEntity.java:468}) and then draws the entity's name tag ({@code :471-...}), and
 * {@code RendererLivingEntity#doRender} calls it at {@code :296}, immediately before posting
 * {@code RenderLivingEvent.Post} at {@code :297}. Because it lives <b>inside</b> {@code doRender}, replacing the whole
 * {@code render.doRender(...)} call in the dispatcher skips it - which is why the vehicle renderer calls it back
 * through this invoker instead of reimplementing a name tag.
 */
@Mixin(RendererLivingEntity.class)
public interface RendererLivingEntityInvoker {

    @Invoker("passSpecialRender")
    void ysmu$passSpecialRender(EntityLivingBase entity, double x, double y, double z);
}
