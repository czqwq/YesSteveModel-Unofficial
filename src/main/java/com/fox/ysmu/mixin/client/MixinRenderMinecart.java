package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.RenderMinecart;
import net.minecraft.entity.item.EntityMinecart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.client.renderer.VehicleRenderer;

/**
 * Draws a minecart with its owner's pack vehicle model instead of vanilla's minecart.
 * <p>
 * One injection covers the whole vanilla family: {@code RenderMinecart#doRender} is declared here, and
 * {@code RenderTntMinecart} and {@code RenderMinecartMobSpawner} extend it without overriding {@code doRender}
 * (their only override is the cargo-block helper), so the transformed method is what they inherit. The entity classes
 * are the same story - the seven concrete minecart types have their own renderer-map entries only for TNT and the
 * spawner, and the rest resolve up to {@code EntityMinecart.class} through
 * {@code RenderManager.getEntityClassRenderObject:192-203}.
 * <p>
 * Cancellable at the head for the same reason as {@code MixinRenderBoat}: a custom model replaces the vanilla body,
 * and anything missing means vanilla draws instead of nothing.
 */
@Mixin(value = RenderMinecart.class, priority = 900)
public abstract class MixinRenderMinecart {

    @Inject(method = "doRender(Lnet/minecraft/entity/item/EntityMinecart;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void ysmu$onRenderMinecart(EntityMinecart minecart, double x, double y, double z, float yaw,
        float partialTicks, CallbackInfo ci) {
        if (VehicleRenderer.render(minecart, x, y, z, yaw, partialTicks)) {
            ci.cancel();
        }
    }
}
