package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.gui.button.ConfigCheckBox;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.client.gui.button.FlatSlider;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.client.roaming.RoamingProgram;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The {@code 模型设置} panel of one model: one widget per config form the pack declared.
 * <p>
 * A form's type decides the widget ({@code range} a slider, {@code checkbox} a toggle, {@code radio} a row of labelled
 * buttons), and its {@code value} is the program that names the {@code v.roaming.*} <em>roaming variable</em> the
 * setting lives in. Upstream hosts the same panel inside {@code AnimationRouletteScreen} and evaluates the program as
 * MoLang; here the named variable is written through {@link ClientRoamingStore}, which is what makes it visible to the
 * animation immediately, persistent across a relog, and visible to other players - see
 * {@code .agent/phase15-roaming-variables.md}, Milestone 5.
 */
@SideOnly(Side.CLIENT)
public class ModelConfigScreen extends GuiScreen {

    private static final int BACK_BUTTON_ID = 0;
    private static final int PANEL_WIDTH = 220;
    private static final int ROW_HEIGHT = 34;

    private final GuiScreen parent;
    private final ResourceLocation modelId;
    private final RawYsmModel.ExtraAnimationButton button;
    private final int modelKey;

    private int panelX;
    private int panelY;

    /** Id -> the variable a slider writes. */
    private final Map<Integer, String> sliderVariables = new HashMap<>();
    /** Id -> the variable a checkbox writes. */
    private final Map<Integer, String> checkboxVariables = new HashMap<>();
    /** Id -> the variable and value a radio button writes. */
    private final Map<Integer, RadioTarget> radioTargets = new HashMap<>();
    /** Text drawn by {@link #drawScreen} instead of by a widget: form titles, descriptions and the unusable note. */
    private final List<Caption> captions = new ArrayList<>();

    private int nextWidgetId = 1;

    public ModelConfigScreen(GuiScreen parent, ResourceLocation modelId) {
        this.parent = parent;
        this.modelId = modelId;
        this.modelKey = ClientRoamingKeys.keyFor(modelId);
        this.button = ModelConfigRegistry.settingsButtonFor(modelId);
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.sliderVariables.clear();
        this.checkboxVariables.clear();
        this.radioTargets.clear();
        this.captions.clear();
        this.nextWidgetId = 1;

        this.panelX = (this.width - 420) / 2;
        this.panelY = (this.height - 235) / 2;

        this.buttonList.add(
            new FlatColorButton(
                BACK_BUTTON_ID,
                panelX + 5,
                panelY,
                80,
                18,
                I18n.format("gui.yes_steve_model.model.return")));

        int cursor = panelY + 28;
        for (RawYsmModel.ConfigForm form : button.forms) {
            String variable = RoamingProgram.variableName(form.defaultValue);
            String title = form.title == null || form.title.isEmpty() ? form.defaultValue : form.title;
            if (variable == null) {
                // A program this screen cannot turn into a stored value. Say so once instead of drawing a widget that
                // would silently do nothing.
                this.captions.add(new Caption(title + " - " + I18n.format("gui.yes_steve_model.model.config.unsupported"),
                    panelX + 5, cursor, 0xFFB06060));
                cursor += 20;
                continue;
            }
            double current = currentValue(variable);
            String type = form.type == null ? "" : form.type.toLowerCase(Locale.ROOT);
            if ("range".equals(type)) {
                if (form.max <= form.min) {
                    this.captions.add(new Caption(title + " - " + I18n.format("gui.yes_steve_model.model.config.bad_range"),
                        panelX + 5, cursor, 0xFFB06060));
                    cursor += 20;
                    continue;
                }
                int id = this.nextWidgetId++;
                this.sliderVariables.put(id, variable);
                final String target = variable;
                this.buttonList.add(
                    new FlatSlider(id, panelX + 5, cursor + 10, PANEL_WIDTH, title, form.min, form.max, form.step,
                        (float) current, (slider, value) -> write(target, value)));
                cursor += ROW_HEIGHT;
            } else if ("checkbox".equals(type)) {
                int id = this.nextWidgetId++;
                this.checkboxVariables.put(id, variable);
                this.buttonList.add(ConfigCheckBox.forLabel(id, panelX + 5, cursor + 10, title, current >= 0.5d));
                cursor += ROW_HEIGHT;
            } else if ("radio".equals(type)) {
                this.captions.add(new Caption(title, panelX + 5, cursor, 0xFFF3EFE0));
                int offset = 0;
                for (Map.Entry<String, String> label : form.labels.entrySet()) {
                    double assigned = RoamingProgram.assignedValue(label.getValue());
                    if (Double.isNaN(assigned)) {
                        continue;
                    }
                    String labelVariable = RoamingProgram.variableName(label.getValue());
                    // A label may assign a different variable than the form reads; fall back to the form's own.
                    String target = labelVariable == null ? variable : labelVariable;
                    int id = this.nextWidgetId++;
                    this.radioTargets.put(id, new RadioTarget(target, assigned));
                    this.buttonList.add(
                        new FlatColorButton(id, panelX + 5 + offset, cursor + 12, 52, 18, label.getKey()));
                    offset += 54;
                }
                cursor += ROW_HEIGHT + 6;
            } else {
                this.captions.add(new Caption(
                    title + " - " + I18n.format("gui.yes_steve_model.model.config.unsupported"),
                    panelX + 5,
                    cursor,
                    0xFFB06060));
                cursor += 20;
                continue;
            }
            if (form.description != null && !form.description.isEmpty()) {
                this.captions.add(new Caption(form.description, panelX + 5, cursor - 8, 0xFF9E9E9E));
                cursor += 10;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BACK_BUTTON_ID) {
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        String checkboxVariable = this.checkboxVariables.get(button.id);
        if (checkboxVariable != null && button instanceof ConfigCheckBox) {
            ConfigCheckBox box = (ConfigCheckBox) button;
            box.doPress();
            write(checkboxVariable, box.isChecked() ? 1d : 0d);
            return;
        }
        RadioTarget radio = this.radioTargets.get(button.id);
        if (radio != null) {
            write(radio.variable, radio.value);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        for (Object widget : this.buttonList) {
            if (widget instanceof FlatSlider) {
                ((FlatSlider) widget).mouseDragged(this.mc, mouseX, mouseY);
            }
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (which >= 0) {
            for (Object widget : this.buttonList) {
                if (widget instanceof FlatSlider) {
                    ((FlatSlider) widget).mouseReleased(mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(
            this.fontRendererObj,
            I18n.format("gui.yes_steve_model.model.config.title") + " - " + this.button.name,
            this.width / 2,
            panelY - 14,
            0xF3EFE0);
        for (Caption caption : captions) {
            this.drawString(this.fontRendererObj, caption.text, caption.x, caption.y, caption.color);
        }
        if (button.forms.isEmpty()) {
            this.drawString(
                this.fontRendererObj,
                I18n.format("gui.yes_steve_model.model.config.none"),
                panelX + 5,
                panelY + 28,
                0xFF9E9E9E);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** The stored value of a variable, or zero when nothing has been stored for it yet. */
    private double currentValue(String variable) {
        EntityPlayer local = this.mc.thePlayer;
        if (local == null) {
            return 0d;
        }
        Double stored = ClientRoamingStore.valuesFor(local.getUniqueID(), modelKey)
            .get(variable);
        return stored == null ? 0d : stored;
    }

    /**
     * Stores a value for the local player. The client tick reports it to the server, which echoes the authoritative
     * map back, so what the panel writes is a request that the server then confirms.
     */
    private void write(String variable, double value) {
        EntityPlayer local = this.mc.thePlayer;
        if (local == null) {
            ysmu.LOG.warn("Ignoring YSM model setting '{}' outside a world", variable);
            return;
        }
        ClientRoamingStore.setLocal(local.getUniqueID(), modelKey, variable, value);
    }

    /** One line of text the widgets do not draw themselves. */
    private static final class Caption {

        private final String text;
        private final int x;
        private final int y;
        private final int color;

        private Caption(String text, int x, int y, int color) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    /** What a radio button writes when pressed. */
    private static final class RadioTarget {

        private final String variable;
        private final double value;

        private RadioTarget(String variable, double value) {
            this.variable = variable;
            this.value = value;
        }
    }
}
