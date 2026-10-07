package com.fox.ysmu.event;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraftforge.client.event.RenderLivingEvent;

import com.fox.ysmu.client.renderer.VehicleRenderer;
import com.fox.ysmu.data.NPCData;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * The living half of the vehicle replacement: a pig or a horse that carries a vehicle model is drawn through
 * {@link VehicleRenderer} instead of its own renderer.
 *
 * <p>Why this seam and not a Mixin: 1.7.10's Forge has no generic entity-render event, but it does fire
 * {@code RenderLivingEvent.Pre} from {@code RendererLivingEntity#doRender} ({@code RendererLivingEntity.java:85-87}),
 * and the event is cancellable. Neither {@code RenderPig} nor {@code RenderHorse} overrides {@code doRender}, so both
 * necessarily pass through it - which is the "zero Mixin" path for the living vehicles. Boats and minecarts are not
 * living entities and never reach this event; they have their own head-injections
 * ({@code MixinRenderBoat}, {@code MixinRenderMinecart}).
 *
 * <p>Why it lives in its own client-only subscriber rather than in {@code client/ClientEventHandler}: that class
 * already claims this event for NPC overrides, and two subscribers that each cancel and each draw would draw twice.
 * This handler therefore claims exactly the complement of that one - an entity this handler draws has no NPC override,
 * and an entity with one is left to the NPC path. The only case where that ordering is visible is an entity that is
 * both a companion-mod NPC and a vehicle, and there the companion mod's explicit registration wins; upstream instead
 * lets its vehicle capability win over everything, which this port cannot express through one shared event.
 *
 * <p>{@code player} is null here (a pig or horse is not one), and the event carries no partial tick, so the frame
 * interpolation comes from {@code Minecraft.timer.renderPartialTicks} - the same reading the NPC branch of
 * {@code ClientEventHandler} documents at its lines 263-265.
 */
@EventBusSubscriber(side = Side.CLIENT)
public class ClientVehicleEventHandler {

    @SubscribeEvent
    public static void onRenderLivingVehicle(RenderLivingEvent.Pre event) {
        if (!(event.entity instanceof EntityPig) && !(event.entity instanceof EntityHorse)) {
            return;
        }
        // The NPC override owns this entity: see the class javadoc for why exactly one of the two handlers may claim
        // a frame.
        if (NPCData.contains(event.entity)) {
            return;
        }
        if (VehicleRenderer.ownerModelId(event.entity) == null) {
            return;
        }
        float partialTicks = Minecraft.getMinecraft().timer.renderPartialTicks;
        // The body yaw, not rotationYaw: this is the value the engine's own GeckoLib entity renderer rotates by
        // (GeoReplacedEntityRenderer.resolveBodyYaw:185 lerps prevRenderYawOffset/renderYawOffset), and it is the one
        // vanilla's RendererLivingEntity#applyRotations would have used for this entity.
        float bodyYaw = event.entity.prevRenderYawOffset
            + (event.entity.renderYawOffset - event.entity.prevRenderYawOffset) * partialTicks;
        if (VehicleRenderer.render(
            event.entity,
            event.x,
            event.y - event.entity.yOffset,
            event.z,
            bodyYaw,
            partialTicks)) {
            event.setCanceled(true);
        }
    }
}
