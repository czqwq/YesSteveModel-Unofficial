package com.fox.ysmu.client.gui;

import com.fox.ysmu.Config;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;

import java.util.List;

/**
 * 免责声明界面。
 * <p>
 * CU-14（有意为之的设计，勿当 bug 反复"修"）：未勾选"已阅读并同意"时点"关闭"只会关闭本界面
 * （{@code displayGuiScreen(null)}），{@code Config.DISCLAIMER_SHOW} 仍为 true，下次按 Alt+Y 会再次弹出
 * —— 即允许跳过但不会永久跳过，也不会永久卡死。本界面也不会在首次进入世界时自动弹出，只有
 * {@code PlayerModelScreenKey} 会打开它。
 */
public class DisclaimerScreen extends GuiScreen {
    private GuiButton readCheckbox;
    private boolean hasAgreed;
    private int x;
    private int y;
    private List<String> textLines;

    @Override
    public void initGui() {
        hasAgreed = !Config.DISCLAIMER_SHOW;
        this.buttonList.clear();

        String mainText = I18n.format("gui.yes_steve_model.disclaimer.text").replace("\\n", "\n");
        this.textLines = this.fontRendererObj.listFormattedStringToWidth(mainText, 400);
        int totalHeight = this.textLines.size() * this.fontRendererObj.FONT_HEIGHT + 20 + 20 + 10 + 20;
        x = (this.width - 400) / 2;
        y = (this.height - totalHeight) / 2;

        String readCheckboxText = (hasAgreed ? "[X] " : "[ ] ") + I18n.format("gui.yes_steve_model.disclaimer.read");
        int readTextWidth = this.fontRendererObj.getStringWidth(readCheckboxText) + 25; // 为 "[X] " 留出空间
        readCheckbox = new GuiButton(0, (this.width - readTextWidth) / 2, y + totalHeight - 50, readTextWidth, 20, readCheckboxText);
        this.buttonList.add(readCheckbox);
        this.buttonList.add(new GuiButton(1, (width - 300) / 2, y + totalHeight - 20, 300, 20, I18n.format("gui.yes_steve_model.disclaimer.close")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                hasAgreed = !hasAgreed;
                button.displayString = (hasAgreed ? "[X] " : "[ ] ") + I18n.format("gui.yes_steve_model.disclaimer.read");
                break;
            case 1:
                if (hasAgreed) {
                    Config.DISCLAIMER_SHOW = false;
                    Config.save();
                    // CU-03: 主菜单里 mc.thePlayer == null，此时打开模型界面会在渲染时 NPE，
                    // 因此这里判空并把玩家显式传给界面（不把 null 交给 ModelSelectionTarget.of）。
                    EntityPlayer player = this.mc.thePlayer;
                    this.mc.displayGuiScreen(player == null ? null : new PlayerModelScreen(player));
                } else {
                    this.mc.displayGuiScreen(null);
                }
                break;
        }
    }
    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        this.drawDefaultBackground();
        int currentY = this.y;
        if (this.textLines != null) {
            for (String line : this.textLines) {
                this.fontRendererObj.drawString(line, this.x, currentY, 0xFFFFFF);
                currentY += this.fontRendererObj.FONT_HEIGHT;
            }
        }
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }
}
