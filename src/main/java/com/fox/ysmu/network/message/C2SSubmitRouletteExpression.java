package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import com.fox.ysmu.eep.ExtendedModelInfo;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.DeferredWork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Upstream ClientProtocolGateway.java:144-148 / ControlHandler.java:124-139's controlled roulette expression path.
 * This self-only panel never accepts a target from the client: the server sender is the sole permitted owner.
 * Maid control is not implemented by this packet. This is distinct from ysm.sync's numeric @sync arguments.
 * The model identity is port-specific: catalog forms can configure a model not worn by the sender. Human approved
 * broadcasting only for the current model and rejecting click-to-model-change races; see .agent/phase-g10-form-programs.md.
 */
public class C2SSubmitRouletteExpression implements IMessage {

    /** Upstream ControlHandler.java:53,129 bounds Java String.length(), not UTF-8 byte count. */
    public static final int MAX_EXPRESSION_LENGTH = 4096;
    private static final int MAX_UTF8_BYTES = MAX_EXPRESSION_LENGTH * 4;
    private String expression;
    private ResourceLocation modelId;

    public C2SSubmitRouletteExpression() {}

    public C2SSubmitRouletteExpression(ResourceLocation modelId, String expression) {
        this.modelId = modelId;
        this.expression = valid(expression) ? expression : null;
    }

    public ResourceLocation modelId() { return this.modelId; }

    public static boolean matchesModel(ResourceLocation requested, ResourceLocation current) {
        return requested != null && requested.equals(current);
    }

    static ResourceLocation readModel(ByteBuf buf) {
        String text = readExpression(buf);
        if (text == null) return null;
        try { return new ResourceLocation(text); }
        catch (Exception malformed) { return null; }
    }

    public String expression() {
        return this.expression;
    }

    public static boolean valid(String expression) {
        return expression != null && !expression.trim().isEmpty() && expression.length() <= MAX_EXPRESSION_LENGTH;
    }

    static String readExpression(ByteBuf buf) {
        if (buf.readableBytes() < 4) return null;
        int length = buf.readInt();
        if (length < 0 || length > MAX_UTF8_BYTES || length > buf.readableBytes()) return null;
        String text = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.skipBytes(length);
        return valid(text) ? text : null;
    }

    static void writeExpression(ByteBuf buf, String expression) {
        byte[] bytes = valid(expression) ? expression.getBytes(StandardCharsets.UTF_8) : new byte[0];
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = readModel(buf);
        this.expression = readExpression(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeExpression(buf, this.modelId == null ? null : this.modelId.toString());
        writeExpression(buf, this.expression);
    }

    public static class Handler implements IMessageHandler<C2SSubmitRouletteExpression, IMessage> {
        @Override
        public IMessage onMessage(C2SSubmitRouletteExpression message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender != null && valid(message.expression)) {
                String expression = message.expression;
                DeferredWork.server(() -> {
                    if (sender.isDead || sender.worldObj == null) return;
                    ExtendedModelInfo info = ExtendedModelInfo.get(sender);
                    if (info == null || !matchesModel(message.modelId, info.getModelId())) return;
                    S2CExecuteRouletteExpression event =
                        new S2CExecuteRouletteExpression(sender.getEntityId(), message.modelId, expression);
                    // The echo goes to every tracking client EXCEPT the sender, and that is the fix for the
                    // "the sender's program runs twice" defect.
                    //
                    // 1.7.10's sendToAllAround always includes the sender: NetworkHandler.sendToTrackingPlayersExcept
                    // carries the FML evidence (FMLOutboundHandler.java:189-209 has no exclusion branch, and
                    // NetworkRegistry.TargetPoint, NetworkRegistry.java:82-104, has no field to exclude with; the
                    // sender's squared distance to itself is 0, which passes the range test at :201). This call site
                    // used to go through sendToTrackingPlayers, so the sender's client got its own expression back and
                    // S2CExecuteRouletteExpression.Handler submitted it a second time.
                    //
                    // Reasoning that the sender still executes it exactly once:
                    //  1. ModelConfigScreen:580 already ran ModelFormExecution.submit locally, at click time, on the
                    //     sender's own client. ModelFormExecution.PENDING keys nothing by source, so nothing dedupes a
                    //     second execution - that is why the echo's recipient list is the fix.
                    //  2. ModelConfigScreen:588 then sends this C2S packet, whose only remaining job is "the OTHER
                    //     clients that render the sender see the same change"; those clients ran no local execution.
                    //  3. With the sender excluded: sender = local (1) + echo (0) = 1 execution; every other tracking
                    //     client = local (0) + echo (1) = 1.
                    // Before the change the sender's count was 1 + 1 = 2, and S2CExecuteRouletteExpression.Handler
                    // re-resolves the entity by sender.getEntityId(), so the second run landed on the same animatable -
                    // a play_sound/particle program fired twice.
                    //
                    // No wire change: the same S2CExecuteRouletteExpression bytes reach fewer recipients, so neither a
                    // packet id nor NETWORK_PROTOCOL is touched.
                    NetworkHandler.sendToTrackingPlayersExcept(event, sender, sender, 128d);
                });
            }
            return null;
        }
    }
}
