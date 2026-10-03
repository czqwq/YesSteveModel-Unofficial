package com.fox.ysmu.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

/**
 * A flat checkbox. It serves two callers with different label sources: the mod's own options in
 * {@code ConfigScreen}, whose labels come from translation keys, and a pack-declared model setting in
 * {@code ModelConfigScreen}, whose label is the pack's own text (see {@code .agent/phase15-roaming-variables.md},
 * Milestone 5).
 */
public class ConfigCheckBox extends GuiButton {
    //移除 ForgeConfigSpec，这个按钮只负责UI状态，配置的读写应由使用它的Screen负责
    private boolean isChecked;
    /** Translation key of a mod option, or {@code null} for a pack-declared label. */
    private final String key;
    /** The visible label without the {@code [X]}/{@code [ ]} marker. */
    private final String label;

    /** {@code ConfigScreen}'s full-row option: it spans that screen's 420-wide content column. */
    private static final int CONFIG_ROW_WIDTH = 400;

    public ConfigCheckBox(int id, int pX, int pY, String key, boolean isChecked) {
        this(id, pX, pY, CONFIG_ROW_WIDTH, key, isChecked, null);
    }

    /**
     * A checkbox for a pack-declared setting: the visible text is the pack's title (its own language file already
     * resolved it), so it must not be pushed through this mod's translation keys. The caller supplies {@code width}
     * so that a compact panel does not get a full-width row that overhangs its background.
     */
    public static ConfigCheckBox forLabel(int id, int pX, int pY, int width, String label, boolean isChecked) {
        return new ConfigCheckBox(id, pX, pY, width, null, isChecked, label);
    }

    private ConfigCheckBox(int id, int pX, int pY, int width, String key, boolean isChecked, String label) {
        super(id, pX, pY, width, 20, "");
        this.key = key;
        this.label = label == null ? I18n.format("gui.yes_steve_model.config." + key) : label;
        this.isChecked = isChecked;
        this.displayString = this.label;
    }

    public void doPress() {
        setChecked(!this.isChecked);
    }

    /** Sets the state without a callback, for a screen that rebuilds its widgets from stored values. */
    public void setChecked(boolean checked) {
        this.isChecked = checked;
    }

    public boolean isChecked() {
        return this.isChecked;
    }

    /** The mod-option translation key, or {@code null} for a pack-declared label. */
    public String getKey() {
        return this.key;
    }

    /**
     * 行背景 + 左侧勾选标记 + 标签，与 {@code ConfigSlider} 的行样式一致：整行可点，悬停高亮，
     * 标记与文字分开绘制（因此 {@link #displayString} 只保存标签本身，不再前缀 {@code [X]}/{@code [ ]}）。
     */
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int bgColor = hovered ? 0x55FFFFFF : 0x33000000;
        drawRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, bgColor);
        String checkMark = isChecked ? "[X]" : "[ ]";
        int checkColor = hovered ? 0xFFB100 : 0xF3EFE0;
        mc.fontRenderer.drawString(checkMark, this.xPosition + 2, this.yPosition + (this.height - 8) / 2, checkColor);
        int textColor = this.enabled ? (hovered ? 0xFFB100 : 0xF3EFE0) : 0x666666;
        mc.fontRenderer.drawString(this.displayString, this.xPosition + 28, this.yPosition + (this.height - 8) / 2, textColor);
    }
}
