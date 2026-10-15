package com.fox.ysmu.network.message;

import com.fox.ysmu.client.gui.ModelSelectionTarget;
import com.fox.ysmu.client.gui.PlayerModelScreen;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Server to client: open the model selection GUI for an entity.
 * <p>
 * It used to open only for a player. Any {@link EntityLivingBase} is accepted now, so a companion mod can hand a
 * maid to the same screen; the entity id is what the client resolves to preview and the npc id is what the server
 * resolves when the selection is sent back.
 * <p>
 * The GUI is opened from the client tick rather than the packet thread; the npc id travels with the screen's
 * target instead of being parked in a static field.
 */
public class OpenModelGuiMessage implements IMessage {

    private int entityId;
    private int npcId;

    public OpenModelGuiMessage() {}

    public OpenModelGuiMessage(int entityId, int npcId) {
        this.entityId = entityId;
        this.npcId = npcId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.npcId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        buf.writeInt(this.npcId);
    }

    public static class Handler implements IMessageHandler<OpenModelGuiMessage, IMessage> {
        @Override
        public IMessage onMessage(OpenModelGuiMessage message, MessageContext ctx) {
            // N-10: the GUI itself is opened from DeferredWork (whose drain catches Throwable); guarding here keeps
            // the onMessage part from escaping into FML's catch(Throwable) -> rejectHandshake.
            try {
                if (ctx.side == Side.CLIENT) {
                    int entityId = message.entityId;
                    int npcId = message.npcId;
                    DeferredWork.client(() -> handleMessage(entityId, npcId));
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to open the YSM model selection screen", e);
            }
            return null;
        }
    }

    private static void handleMessage(int entityId, int npcId) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer localPlayer = minecraft.thePlayer;
        if (localPlayer == null) {
            return;
        }
        Entity entity = localPlayer.worldObj.getEntityByID(entityId);
        if (entity == null) {
            return;
        }
        if (entity instanceof EntityPlayer player) {
            minecraft.displayGuiScreen(new PlayerModelScreen(ModelSelectionTarget.of(player, npcId)));
        } else if (entity instanceof EntityLivingBase living) {
            minecraft.displayGuiScreen(new PlayerModelScreen(ModelSelectionTarget.of(living, npcId)));
        }
    }
}
