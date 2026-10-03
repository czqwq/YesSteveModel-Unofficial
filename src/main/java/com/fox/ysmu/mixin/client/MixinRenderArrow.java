package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.RenderArrow;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.ArrowProjectileRenderer;
import com.fox.ysmu.util.IProjectileModelArrow;

import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;

/**
 * Draws an arrow with the shooter's pack projectile model instead of vanilla's arrow.
 * <p>
 * Injected at the head of {@code RenderArrow#doRender} and cancellable: when a custom model is actually drawn the
 * vanilla body is skipped, and when it is not (no model on the arrow, no projectile entry for this pack, a missing
 * geometry or texture) the method returns without cancelling so the vanilla arrow still appears. That fallback is the
 * point of the split - a half-applied pack must never make arrows invisible.
 */
@Mixin(value = RenderArrow.class, priority = 900)
public abstract class MixinRenderArrow {

    @Inject(method = "doRender(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void ysmu$onRenderArrow(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        // RenderArrow is also the base of RenderSnowball and friends under some mods, so re-check the type.
        if (!(entity instanceof EntityArrow arrow)) {
            return;
        }
        String modelIdStr = ((IProjectileModelArrow) arrow).ysmu$getProjectileModelId();
        if (modelIdStr == null || modelIdStr.isEmpty()) {
            return;
        }
        ResourceLocation modelId;
        try {
            modelId = new ResourceLocation(modelIdStr);
        } catch (RuntimeException e) {
            // The id came off the wire, so it is untrusted text; an unparseable one means "draw vanilla".
            return;
        }

        // The projectile path borrows the player renderer for its bone-walking draw; it needs no player model.
        @SuppressWarnings("rawtypes")
        GeoReplacedEntityRenderer renderer = GeoReplacedEntityRenderer.getRenderer(CustomPlayerEntity.class);
        if (renderer == null) {
            return;
        }
        if (ArrowProjectileRenderer.render(entity, x, y, z, yaw, partialTicks, modelId, renderer)) {
            ci.cancel();
        }
    }
}
