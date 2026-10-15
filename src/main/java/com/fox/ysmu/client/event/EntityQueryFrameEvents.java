package com.fox.ysmu.client.event;

import com.fox.ysmu.client.animation.molang.EntityQueryScope;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * The render-tick boundary of the entity-scoped {@code query.*} / {@code ysm.*} publication
 * ({@link EntityQueryScope}).
 *
 * <p>{@code MolangParser.VARIABLES} is one process-wide table, and the 61 names
 * {@code EntityQueryBindings.apply} writes into it are suppliers closed over the entity of the draw that published
 * them. Before this pair existed nothing ever took them out again, so every reader that is not a draw - {@code /ysm
 * query}, the debug overlay, a pack's {@code @sync} script - read the last drawn entity's values: draw a vehicle or an
 * arrow after the player and the player's own queries answered with the vehicle's or the arrow's state. The readers
 * that know their entity publish it themselves for the duration of the read
 * ({@code MolangDebugSnapshot#publishForRead}, {@code S2CMolangSync}); this is the structural half, so that a reader
 * nobody has patched answers the registered default instead of another entity's value.
 *
 * <p>Why the render tick and not a render event. 1.7.10 wraps the whole frame in
 * {@code FMLCommonHandler.onRenderTickStart/End} around {@code EntityRenderer.updateCameraAndRender}
 * ({@code Minecraft.java:1065-1069}), and {@code updateCameraAndRender} is where the world pass, the HUD paperdoll and
 * the open screen's GUI previews all draw ({@code EntityRenderer.java:1111-1138}). START therefore runs before the
 * first publication of the frame and END after the last reader of it, while the in-frame order every draw depends on
 * (publish, then evaluate) is untouched - in particular the pair sits outside
 * {@code CustomPlayerModel#setLivingAnimations}, whose publish-then-{@code super.setLivingAnimations} order
 * ({@code CustomPlayerModel.java:208} then {@code :210}) is what makes a draw safe in the first place. A HUD or GUI
 * event would not do: this stream draws on both, in an order Forge does not define between two subscribers of one
 * event ({@code ClientEventHandler#onRenderOverlay}).
 */
@EventBusSubscriber(side = Side.CLIENT)
public final class EntityQueryFrameEvents {

    private EntityQueryFrameEvents() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // The frame's baseline: what the registrations left in the table, before any draw publishes over it.
            EntityQueryScope.beginFrame();
            // 箭头表达式的上下文句柄同样按帧计：句柄只在一个求值周期内有效，帧首清空让这张表不随会话增长
            // （上游的 projectile_owner 是活对象，wiki 自记它"目前存在内存泄漏问题"——那一点不复制）。
            com.fox.ysmu.client.animation.molang.MolangContextHandles.beginFrame();
        } else {
            // Every draw of this frame is done, so nothing can still be evaluating the names being put back.
            EntityQueryScope.endFrame();
        }
    }
}
