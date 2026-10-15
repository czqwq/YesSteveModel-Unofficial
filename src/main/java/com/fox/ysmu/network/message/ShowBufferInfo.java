package com.fox.ysmu.network.message;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Locale;

import javax.management.ObjectName;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: print the direct-buffer, mapped-buffer and heap figures in the issuing player's chat. Sent by
 * {@code /ysm buffer}.
 * <p>
 * Ported from the reference branch's {@code network/message/ShowBufferInfo.java:23-113}. The payload is empty on both
 * sides, exactly as the reference has it. Two port-specific differences, both stated at their site: the body runs on
 * the client thread (it writes to the chat), and the three lines are translation keys rather than hard-coded English
 * (the repository's user-facing-text rule).
 */
public class ShowBufferInfo implements IMessage {

    public ShowBufferInfo() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<ShowBufferInfo, IMessage> {

        @Override
        public IMessage onMessage(ShowBufferInfo message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            try {
                // The reference prints inline (ShowBufferInfo.java:35-110). The port hops to the client thread for the
                // same reason its neighbours do (S2CPlaySound.java:59-60, S2CSetDebugOverlay.java:59-60): the body
                // reads Minecraft.getMinecraft().thePlayer and answers in chat.
                Minecraft.getMinecraft()
                    .func_152344_a(Handler::report);
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring YSM buffer-info request", e);
            }
            return null;
        }

        private static void report() {
            // getMinecraft() is null before the client exists (the same guard CommandYsmClient uses, :94), in which
            // case the figures are only logged.
            Minecraft mc = Minecraft.getMinecraft();
            try {
                // 1. Direct Buffer Pool (java.nio:type=BufferPool,name=direct)
                ObjectName directPool = new ObjectName("java.nio:type=BufferPool,name=direct");
                long directMemoryUsed = ((Number) ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(directPool, "MemoryUsed")).longValue();
                long directTotalCap = ((Number) ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(directPool, "TotalCapacity")).longValue();
                int directCount = ((Number) ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(directPool, "Count")).intValue();

                // 2. Mapped Buffer Pool (memory-mapped files)
                long mappedMemoryUsed = 0;
                try {
                    ObjectName mappedPool = new ObjectName("java.nio:type=BufferPool,name=mapped");
                    mappedMemoryUsed = ((Number) ManagementFactory.getPlatformMBeanServer()
                        .getAttribute(mappedPool, "MemoryUsed")).longValue();
                } catch (Exception ignored) {}

                // 3. Heap memory usage
                MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
                MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
                long heapUsed = heapUsage.getUsed();
                long heapMax = heapUsage.getMax();

                // Locale.ROOT, like YsmCommand's reload timing (YsmCommand.java:334): a comma decimal separator in a
                // chat line reads as garbage, and the value is a measurement, not prose.
                String directMB = String.format(Locale.ROOT, "%.1f", directMemoryUsed / (1024.0 * 1024.0));
                String directCapMB = String.format(Locale.ROOT, "%.1f", directTotalCap / (1024.0 * 1024.0));
                String mappedMB = String.format(Locale.ROOT, "%.1f", mappedMemoryUsed / (1024.0 * 1024.0));
                String heapMB = String.format(Locale.ROOT, "%.1f", heapUsed / (1024.0 * 1024.0));
                String heapMaxMB = String.format(Locale.ROOT, "%.1f", heapMax / (1024.0 * 1024.0));

                if (mc != null && mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "message.yes_steve_model.buffer.direct",
                        directMB,
                        directCount,
                        directCapMB));
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation("message.yes_steve_model.buffer.mapped", mappedMB));
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation("message.yes_steve_model.buffer.heap", heapMB, heapMaxMB));
                }

                // Also log for detailed analysis
                ysmu.LOG.info(
                    "[YSM-BUFFER] Direct: {} MB ({} buffers, cap {} MB), Mapped: {} MB, Heap: {} MB / {} MB",
                    directMB,
                    directCount,
                    directCapMB,
                    mappedMB,
                    heapMB,
                    heapMaxMB);
            } catch (Throwable e) {
                ysmu.LOG.error("[YSM-BUFFER] Failed to read buffer info", e);
                if (mc != null && mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "message.yes_steve_model.buffer.error",
                        String.valueOf(e.getMessage())));
                }
            }
        }
    }
}
