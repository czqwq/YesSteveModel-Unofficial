package com.fox.ysmu.client.gui.button;

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

    public ConfigCheckBox(int id, int pX, int pY, String key, boolean isChecked) {
        this(id, pX, pY, key, isChecked, null);
    }

    /**
     * A checkbox for a pack-declared setting: the visible text is the pack's title (its own language file already
     * resolved it), so it must not be pushed through this mod's translation keys.
     */
    public static ConfigCheckBox forLabel(int id, int pX, int pY, String label, boolean isChecked) {
        return new ConfigCheckBox(id, pX, pY, null, isChecked, label);
    }

    private ConfigCheckBox(int id, int pX, int pY, String key, boolean isChecked, String label) {
        super(id, pX, pY, 130, 20, "");
        this.key = key;
        this.label = label == null ? I18n.format("gui.yes_steve_model.config." + key) : label;
        this.isChecked = isChecked;
        this.displayString = marker(isChecked) + this.label;
    }

    public void doPress() {
        setChecked(!this.isChecked);
    }

    /** Sets the state without a callback, for a screen that rebuilds its widgets from stored values. */
    public void setChecked(boolean checked) {
        this.isChecked = checked;
        this.displayString = marker(checked) + this.label;
    }

    public boolean isChecked() {
        return this.isChecked;
    }

    /** The mod-option translation key, or {@code null} for a pack-declared label. */
    public String getKey() {
        return this.key;
    }

    private static String marker(boolean checked) {
        return checked ? "[X] " : "[ ] ";
    }
}
