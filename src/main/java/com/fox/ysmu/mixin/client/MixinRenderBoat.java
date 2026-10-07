package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.RenderBoat;
import net.minecraft.entity.item.EntityBoat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.client.renderer.VehicleRenderer;

/**
 * Draws a boat with its owner's pack vehicle model instead of vanilla's boat.
 * <p>
 * Injected at the head of {@code RenderBoat#doRender} and cancellable: when a custom model is actually drawn the
 * vanilla body is skipped, and when it is not (no state for this entity, no vehicle entry for the model, a missing
 * geometry or texture) the method returns without cancelling so the vanilla boat still appears. That fallback is the
 * point of the split - a half-applied pack must never make boats invisible, and it is upstream's own rule (a render
 * failure restores the host's draw).
 * <p>
 * The descriptor names {@code EntityBoat}, so no re-check of the argument is needed here (unlike
 * {@code MixinRenderArrow}, whose target takes a bare {@code Entity}). 1.7.10 routes the entity pass through
 * {@code Render#doRender(Entity, ...)}, whose bridge in {@code RenderBoat} casts and calls this typed method.
 */
@Mixin(value = RenderBoat.class, priority = 900)
public abstract class MixinRenderBoat {

    @Inject(method = "doRender(Lnet/minecraft/entity/item/EntityBoat;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void ysmu$onRenderBoat(EntityBoat boat, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        if (VehicleRenderer.render(boat, x, y, z, yaw, partialTicks)) {
            ci.cancel();
        }
    }
}
