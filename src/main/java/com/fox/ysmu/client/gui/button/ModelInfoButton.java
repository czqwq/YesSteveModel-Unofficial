package com.fox.ysmu.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.network.message.RequestServerModelInfo;
import com.fox.ysmu.util.FileSizeUtils;

/**
 * 模型管理 GUI 的一行（phase55，LgeacyYSM {@code client/gui/button/ModelInfoButton.java} 的 1.7.10 移植；
 * 参考分支已有一份 1.7.10 版本，本类以它为底稿）。
 *
 * <p>行内容：文件名、类型（Folder / ysm）、大小。选中时底色变蓝、悬停画白边。与参考/Lgeacy 的差异只有一处：
 * 本仓的 {@link Type} 没有 {@code ZIP}（管理列表不列 zip），所以类型只有两个分支。</p>
 */
public class ModelInfoButton extends GuiButton {

    private final RequestServerModelInfo.Info info;
    private boolean isSelect = false;

    public ModelInfoButton(int id, int x, int y, int height, RequestServerModelInfo.Info info) {
        super(id, x, y, 250, height, "");
        this.info = info;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;
        int backgroundColor = isSelect ? 0xff_1E90FF : 0xff_434242;
        this.drawGradientRect(
            this.xPosition,
            this.yPosition,
            this.xPosition + this.width,
            this.yPosition + this.height,
            backgroundColor,
            backgroundColor);
        if (this.field_146123_n) {
            this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
        }
        int textY = this.yPosition + (this.height - 8) / 2;
        this.drawString(font, info.getFileName(), this.xPosition + 5, textY, 0xF3EFE0);
        if (info.getType() == Type.FOLDER) {
            this.drawString(
                font,
                I18n.format("gui.yes_steve_model.model_manage.type.folder"),
                this.xPosition + 155,
                textY,
                0x55FFFF);
        } else {
            this.drawString(
                font,
                I18n.format("gui.yes_steve_model.model_manage.type.ysm"),
                this.xPosition + 155,
                textY,
                0xFFFF55);
        }
        this.drawString(font, FileSizeUtils.size(info.getSize()), this.xPosition + 205, textY, 0xC0C0C0);
    }

    public void setSelect(boolean select) {
        this.isSelect = select;
    }
}
