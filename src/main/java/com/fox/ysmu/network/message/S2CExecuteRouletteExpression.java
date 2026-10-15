package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.roaming.ModelFormExecution;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.eep.ExtendedModelInfo;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server-authorized self expression broadcast, mirroring upstream ControlHandler.java:253-267. */
public class S2CExecuteRouletteExpression implements IMessage {
    private int entityId;
    private String expression;
    private ResourceLocation modelId;

    public S2CExecuteRouletteExpression() {}

    public S2CExecuteRouletteExpression(int entityId, ResourceLocation modelId, String expression) {
        this.entityId = entityId;
        this.modelId = modelId;
        this.expression = expression;
    }

    public ResourceLocation modelId() { return this.modelId; }

    public int entityId() { return this.entityId; }
    public String expression() { return this.expression; }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = 0;
        this.modelId = null;
        this.expression = null;
        if (buf.readableBytes() < 4) return;
        this.entityId = buf.readInt();
        this.modelId = C2SSubmitRouletteExpression.readModel(buf);
        this.expression = C2SSubmitRouletteExpression.readExpression(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        C2SSubmitRouletteExpression.writeExpression(buf, this.modelId == null ? null : this.modelId.toString());
        C2SSubmitRouletteExpression.writeExpression(buf, this.expression);
    }

    public static class Handler implements IMessageHandler<S2CExecuteRouletteExpression, IMessage> {
        @Override
        public IMessage onMessage(S2CExecuteRouletteExpression message, MessageContext ctx) {
            if (!C2SSubmitRouletteExpression.valid(message.expression)) return null;
            Minecraft mc = Minecraft.getMinecraft();
            net.minecraft.client.multiplayer.WorldClient world = mc.theWorld;
            long generation = ClientModelManager.currentGeneration();
            mc.func_152344_a(() -> {
                if (world == null || mc.theWorld != world || generation != ClientModelManager.currentGeneration()) return;
                Entity entity = world.getEntityByID(message.entityId);
                if (!(entity instanceof EntityPlayer player)) return;
                ExtendedModelInfo info = ExtendedModelInfo.get(player);
                if (info != null && C2SSubmitRouletteExpression.matchesModel(message.modelId, info.getModelId())) {
                    ModelFormExecution.submit(player, message.modelId, message.expression, null,
                        () -> mc.theWorld == world && generation == ClientModelManager.currentGeneration()
                            && C2SSubmitRouletteExpression.matchesModel(message.modelId, info.getModelId()));
                }
            });
            return null;
        }
    }
}
