package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.fox.ysmu.client.renderer.VehicleRenderer;

/**
 * The vehicle replacement point, in the render dispatcher - the port's counterpart of upstream's
 * {@code mixin/client/EntityRenderDispatcherMixin.java:20-52}, which wraps the one {@code EntityRenderer.render(...)}
 * call inside {@code EntityRenderDispatcher#render}.
 *
 * <p>Why here rather than at each entity's renderer (the port's first shape: a {@code RenderBoat} mixin, a
 * {@code RenderMinecart} mixin and a {@code RenderLivingEvent.Pre} branch for pigs and horses): upstream's vehicle
 * capability hangs on <b>every</b> entity that is neither a player nor a projectile
 * ({@code event/CapabilityEvent.java:78-81}), so a modded vehicle is replaced the moment its entity type appears in a
 * pack's {@code files.vehicles[].match}. A per-class hook can never enumerate those, and the dispatcher is the one
 * place every entity passes through. This port's {@code RenderManager} has exactly one such call site.
 *
 * <p><b>Why the redirect and not a head-cancel of the whole method.</b> {@code RenderManager.func_147939_a} does more
 * than call the renderer: {@code render.doRender(...)} is at {@code RenderManager.java:300} and
 * {@code render.doRenderShadowAndFire(...)} is at {@code :309} - and {@code Render#doRenderShadowAndFire}
 * ({@code Render.java:311-320}) is what draws the entity's <b>shadow and fire</b>, followed at {@code :316-326} by the
 * F3+B bounding box. Cancelling the method would swallow all three; redirecting the single {@code doRender} call
 * replaces only the model draw and leaves the rest of the dispatcher running, which is also exactly what upstream's
 * condition wrapper does (it wraps the renderer call, not the dispatcher).
 *
 * <p>The redirect must not swallow anything else either: it calls the vanilla renderer itself whenever
 * {@link VehicleRenderer} reports that it drew nothing, so a missing model, a missing geometry or a missing texture
 * leaves the entity with its own renderer - upstream's "restore the host's draw" fallback.
 *
 * <p><b>Precedence: the vehicle wins.</b> Because this hook sits <em>before</em> the entity's renderer is entered,
 * nothing inside that renderer runs for a claimed entity - including the {@code RenderLivingEvent.Pre} that the port's
 * NPC override subscribes to ({@code client/ClientEventHandler#onRenderLiving}). An entity a companion mod registered
 * as an NPC <em>and</em> that a pack declares a vehicle for is therefore drawn as the vehicle, which is upstream's
 * order: its dispatcher wrapper checks the vehicle capability for every non-projectile entity before any renderer is
 * called ({@code EntityRenderDispatcherMixin.java:46-49}). The old shape had the opposite order - an explicit
 * {@code NPCData.contains(entity)} bail-out in the vehicle handler - and that gate is gone with the handler.
 */
@Mixin(RenderManager.class)
public abstract class MixinRenderManager {

    @Redirect(
        method = "func_147939_a(Lnet/minecraft/entity/Entity;DDDFFZ)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void ysmu$replaceVehicle(Render render, Entity entity, double x, double y, double z, float yaw,
        float partialTicks) {
        if (VehicleRenderer.render(entity, x, y, z, yaw, partialTicks, render)) {
            // The pack's vehicle model is on screen; the vanilla renderer is deliberately not called.
            return;
        }
        render.doRender(entity, x, y, z, yaw, partialTicks);
    }
}
