package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.entity.EntityLiving;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code RenderLiving#func_110827_b}, the leash draw, which is {@code protected} in 1.7.10
 * ({@code RenderLiving.java:45}) and therefore unreachable from the vehicle renderer.
 *
 * <p>1.7.10 draws a leash in {@code RenderLiving#doRender} <b>after</b> the model pass:
 * {@code super.doRender(...)} at {@code RenderLiving.java:36}, then {@code func_110827_b(...)} at {@code :37}. That
 * ordering is why the old per-entity-event shape never lost the leash - cancelling {@code RenderLivingEvent.Pre} only
 * skipped the inner call, and {@code RenderLiving#doRender} itself still reached {@code :37}. Replacing the whole
 * {@code render.doRender(...)} call in the dispatcher does skip it, so the vehicle renderer calls the vanilla method
 * back through this invoker and keeps the leash on a leashed pig, horse or modded living vehicle.
 *
 * <p>This is the same gap the port already closes for replaced players, by its own leash pass
 * ({@code GeoReplacedEntityRenderer.java:145-150} calls its {@code renderLeash} at {@code :148}); that class extends
 * {@code RendererLivingEntity} rather than {@code RenderLiving}, so it cannot inherit the vanilla method and carries a
 * copy. Here the entity's own renderer <em>is</em> a {@code RenderLiving}, so the vanilla method is called instead of
 * a second implementation.
 */
@Mixin(RenderLiving.class)
public interface RenderLivingInvoker {

    @Invoker("func_110827_b")
    void ysmu$renderLeash(EntityLiving entity, double x, double y, double z, float entityYaw, float partialTicks);
}
