package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.gui.ModelSelectionTarget;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetStarModel;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

public class StarButton extends FlatColorButton {
    private final static ResourceLocation ICON = new ResourceLocation(ysmu.MODID, "texture/icon.png");
    /**
     * CU-05: 使用界面持有的目标（与 ModelButton、PlayerModelScreen 的 STAR 过滤同源），
     * 而不是 {@code Minecraft.getMinecraft().thePlayer}；否则以他人/NPC 为目标打开界面时，
     * 星标图标读的是目标、点击改的却是本地玩家。
     */
    private final ModelSelectionTarget target;

    public StarButton(int id, int x, int y, ModelSelectionTarget target) {
        super(id, x, y, 20, 20, "");
        this.target = target;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        super.drawButton(mc, mouseX, mouseY);
        int startX = (this.width - 16) / 2;
        int startY = (this.height - 16) / 2;
        ResourceLocation modelId = this.target.getModelId();
        if (modelId != null) {
            mc.getTextureManager().bindTexture(ICON);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            if (this.target.isStarred(modelId)) {
                this.drawTexturedModalRect(this.xPosition + startX, this.yPosition + startY, 16, 0, 16, 16);
            } else {
                this.drawTexturedModalRect(this.xPosition + startX, this.yPosition + startY, 0, 0, 16, 16);
            }
        }
    }

    public void doPress() {
        // CU-05: SetStarModel 是"发送者本人"作用域的服务端包，只有目标就是本地玩家时收藏才有意义；
        // 对他人/NPC 目标直接禁用（不改客户端 EEP，避免与 STAR 过滤状态分叉）。
        EntityPlayer self = Minecraft.getMinecraft().thePlayer;
        if (self == null || this.target.getPreviewEntity() != self) {
            return;
        }
        ResourceLocation modelId = this.target.getModelId();
        ExtendedStarModels starModelsEEP = ExtendedStarModels.get(self);
        if (modelId == null || starModelsEEP == null) {
            return;
        }
        if (starModelsEEP.containModel(modelId)) {
            starModelsEEP.removeModel(modelId);
            NetworkHandler.CHANNEL.sendToServer(SetStarModel.remove(modelId));
        } else {
            starModelsEEP.addModel(modelId);
            NetworkHandler.CHANNEL.sendToServer(SetStarModel.add(modelId));
        }
    }
}
