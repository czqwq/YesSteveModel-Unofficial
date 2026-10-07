package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

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
 * The {@code 模型设置} panel of one model: a paged list of every button the pack declared, and one widget per config
 * form of the selected button.
 * <p>
 * A form's type decides the widget ({@code range} a slider, {@code checkbox} a toggle, {@code radio} a row of labelled
 * buttons), and its {@code value} is the program that names the {@code v.roaming.*} <em>roaming variable</em> the
 * setting lives in. Upstream hosts the same panel inside {@code AnimationRouletteScreen} and evaluates the program as
 * MoLang; here the named variable is written through {@link ClientRoamingStore}, which is what makes it visible to the
 * animation immediately, persistent across a relog, and visible to other players - see
 * {@code .agent/phase15-roaming-variables.md}, Milestone 5.
 * <p>
 * <b>Why a list.</b> A pack may declare many buttons - LaPluma 2.5.3 declares sixteen with 122 forms between them - and
 * upstream opens whichever one the wheel's {@code "#<buttonId>"} entry names, as a scrolling column of the roulette
 * ({@code client/gui/AnimationRouletteScreen.java:638-643} opens it, {@code :205-235} lays it out). This port's panel
 * is its own screen, so it shows the declaration-ordered list on the left (paged), the selected button's forms on the
 * right (paged by height), and takes an initial button id from its caller so a wheel entry can still route to exactly
 * the button it named. Before that, only the first button that carried forms was reachable.
 */
@SideOnly(Side.CLIENT)
public class ModelConfigScreen extends GuiScreen {

    private static final int BACK_BUTTON_ID = 0;
    /** The button-list rows are {@link #LIST_ROW_FIRST_ID} .. {@code + LIST_ROWS - 1}. */
    private static final int LIST_ROW_FIRST_ID = 1;
    private static final int LIST_PREV_ID = 20;
    private static final int LIST_NEXT_ID = 21;
    private static final int FORM_PREV_ID = 22;
    private static final int FORM_NEXT_ID = 23;
    /** Form widgets (sliders, checkboxes, radio buttons) start here, clear of the fixed ids above. */
    private static final int FORM_WIDGET_FIRST_ID = 100;

    private static final int ROW_HEIGHT = 34;
    private static final int LIST_X_OFFSET = 5;
    private static final int LIST_WIDTH = 130;
    private static final int FORMS_X_OFFSET = 142;
    private static final int FORMS_WIDTH = 273;
    private static final int LIST_ROW_HEIGHT = 20;
    private static final int TOP_OFFSET = 26;
    private static final int BOTTOM_OFFSET = 228;
    /** Height reserved at the bottom of each column for its pager. */
    private static final int PAGER_HEIGHT = 22;
    /** Radio labels are laid out as 52-wide buttons with a 2px gap, exactly as this screen always did. */
    private static final int RADIO_STEP = 54;
    /** How many button rows and how much form height fit above the pagers. */
    private static final int LIST_ROWS = (BOTTOM_OFFSET - TOP_OFFSET - PAGER_HEIGHT) / LIST_ROW_HEIGHT;
    private static final int FORMS_HEIGHT = BOTTOM_OFFSET - TOP_OFFSET - PAGER_HEIGHT;

    private final GuiScreen parent;
    private final ResourceLocation modelId;
    private final int modelKey;
    /** The button id the caller asked for, honoured on the first {@link #initGui}; see the constructor. */
    private final String initialButtonId;
    /** Every declared button that carries forms, in declaration order; refreshed by {@link #initGui}. */
    private List<RawYsmModel.ExtraAnimationButton> panels = Collections.emptyList();
    /** Index into {@link #panels} of the button whose forms are shown; survives page flips. */
    private int selectedIndex;
    /** Which page of {@link #panels} the left column shows. */
    private int listPage;
    /** Which page of the selected button's forms is shown. */
    private int formPage;
    /** Whether the initial selection has been resolved, so a later {@link #initGui} keeps the user's choice. */
    private boolean selectionInitialised;

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

    private int nextWidgetId = FORM_WIDGET_FIRST_ID;

    public ModelConfigScreen(GuiScreen parent, ResourceLocation modelId) {
        this(parent, modelId, null);
    }

    /**
     * Opens the panel of one specific button.
     * <p>
     * That is what a wheel entry whose value is {@code "#<buttonId>"} needs: upstream opens that button's forms as a
     * column of the roulette ({@code clickConfig}, {@code client/gui/AnimationRouletteScreen.java:638-643}), and this
     * port's settings panel is its own screen, so the route has to land on the right button. {@code null} means "the
     * first button that carries forms", which is what the model-selection screen's settings icon opens and what this
     * screen did before the other buttons were reachable at all.
     */
    public ModelConfigScreen(GuiScreen parent, ResourceLocation modelId, @Nullable String initialButtonId) {
        this.parent = parent;
        this.modelId = modelId;
        this.modelKey = ClientRoamingKeys.keyFor(modelId);
        this.initialButtonId = initialButtonId;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.sliderVariables.clear();
        this.checkboxVariables.clear();
        this.radioTargets.clear();
        this.captions.clear();
        this.nextWidgetId = FORM_WIDGET_FIRST_ID;

        this.panelX = (this.width - 420) / 2;
        this.panelY = (this.height - 235) / 2;

        // Re-read the declarations: a model that arrives again (a re-sync, or a pack added while the screen is open)
        // replaces its buttons, and the panel must follow rather than keep showing the old list.
        this.panels = ModelConfigRegistry.settingsButtonsFor(this.modelId);
        if (!this.selectionInitialised) {
            this.selectionInitialised = true;
            this.selectedIndex = indexOfButton(this.initialButtonId);
        }
        if (this.selectedIndex < 0 || this.selectedIndex >= this.panels.size()) {
            this.selectedIndex = 0;
        }

        this.buttonList.add(
            new FlatColorButton(
                BACK_BUTTON_ID,
                panelX + 5,
                panelY,
                80,
                18,
                I18n.format("gui.yes_steve_model.model.return")));

        if (this.panels.isEmpty()) {
            return;
        }
        this.addButtonListPage();
        this.addFormPage();
    }

    /**
     * The left column: one row per declared button, paged, with the selected row outlined by {@link #drawScreen}.
     * <p>
     * A list rather than upstream's {@code Map<id, button>} because the port's panel is its own screen and has to show
     * one button at a time; before this, only the first button that carried forms was reachable and LaPluma's other
     * fifteen (113 of its 122 forms) could not be opened at all.
     */
    private void addButtonListPage() {
        int pages = listPageCount();
        if (this.listPage >= pages) {
            this.listPage = pages - 1;
        }
        if (this.listPage < 0) {
            this.listPage = 0;
        }
        int listTop = panelY + TOP_OFFSET;
        int first = this.listPage * LIST_ROWS;
        for (int row = 0; row < LIST_ROWS && first + row < this.panels.size(); row++) {
            RawYsmModel.ExtraAnimationButton panel = this.panels.get(first + row);
            String label = this.fontRendererObj.trimStringToWidth(buttonLabel(panel), LIST_WIDTH - 6);
            this.buttonList.add(
                new FlatColorButton(
                    LIST_ROW_FIRST_ID + row,
                    panelX + LIST_X_OFFSET,
                    listTop + row * LIST_ROW_HEIGHT,
                    LIST_WIDTH,
                    LIST_ROW_HEIGHT - 2,
                    label));
        }
        if (pages > 1) {
            int pagerY = panelY + BOTTOM_OFFSET - PAGER_HEIGHT + 4;
            this.buttonList.add(new FlatColorButton(LIST_PREV_ID, panelX + LIST_X_OFFSET, pagerY, 20, 16, "<"));
            this.buttonList.add(
                new FlatColorButton(LIST_NEXT_ID, panelX + LIST_X_OFFSET + LIST_WIDTH - 20, pagerY, 20, 16, ">"));
        }
    }

    /**
     * The right column: the selected button's forms, paginated by the height each one needs.
     * <p>
     * Upstream scrolls this column ({@code AnimationRouletteScreen.java:229-235}, its {@code configScrollY} plus
     * up/down buttons); 1.7.10's fixed pipeline has no scissor-free scroll container here and the widgets are real
     * {@code GuiButton}s positioned absolutely, so the port pages instead. Every form of every button stays reachable
     * either way, and the pager shows where the reader is.
     */
    private void addFormPage() {
        RawYsmModel.ExtraAnimationButton panel = selected();
        if (panel == null) {
            return;
        }
        int[] pages = formPages(panel);
        int pageCount = formPageCount(pages);
        if (this.formPage >= pageCount) {
            this.formPage = pageCount - 1;
        }
        if (this.formPage < 0) {
            this.formPage = 0;
        }
        int cursor = panelY + TOP_OFFSET;
        for (int i = 0; i < panel.forms.size(); i++) {
            if (pages[i] == this.formPage) {
                cursor = addForm(panel.forms.get(i), cursor);
            }
        }
        if (pageCount > 1) {
            int pagerY = panelY + BOTTOM_OFFSET - PAGER_HEIGHT + 4;
            this.buttonList.add(new FlatColorButton(FORM_PREV_ID, panelX + FORMS_X_OFFSET, pagerY, 20, 16, "<"));
            this.buttonList.add(
                new FlatColorButton(FORM_NEXT_ID, panelX + FORMS_X_OFFSET + FORMS_WIDTH - 20, pagerY, 20, 16, ">"));
        }
    }

    /** The button whose forms are shown, or {@code null} when the model declares none. */
    @Nullable
    private RawYsmModel.ExtraAnimationButton selected() {
        return this.selectedIndex >= 0 && this.selectedIndex < this.panels.size() ? this.panels.get(this.selectedIndex)
            : null;
    }

    /** The index of one button in the current list, or 0 (the first panel) when the id names none. */
    private int indexOfButton(@Nullable String buttonId) {
        if (buttonId == null || buttonId.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < this.panels.size(); i++) {
            if (buttonId.equals(this.panels.get(i).id)) {
                return i;
            }
        }
        return 0;
    }

    /** The pack's own name for a button, falling back to its id; upstream shows the same string (:730). */
    private static String buttonLabel(RawYsmModel.ExtraAnimationButton panel) {
        return panel.name == null || panel.name.isEmpty() ? panel.id : panel.name;
    }

    private int listPageCount() {
        return Math.max(1, (this.panels.size() + LIST_ROWS - 1) / LIST_ROWS);
    }

    /**
     * Which page each form of {@code panel} lands on, filling pages greedily by the height each form needs. A form
     * taller than the column still gets a page of its own rather than being dropped.
     */
    private int[] formPages(RawYsmModel.ExtraAnimationButton panel) {
        int[] pages = new int[panel.forms.size()];
        int page = 0;
        int used = 0;
        for (int i = 0; i < pages.length; i++) {
            int height = formHeight(panel.forms.get(i));
            if (used > 0 && used + height > FORMS_HEIGHT) {
                page++;
                used = 0;
            }
            pages[i] = page;
            used += height;
        }
        return pages;
    }

    private static int formPageCount(int[] pages) {
        int max = 0;
        for (int page : pages) {
            max = Math.max(max, page);
        }
        return max + 1;
    }

    /** The vertical space one form needs; the same rules {@link #addForm} lays it out with. */
    private int formHeight(RawYsmModel.ConfigForm form) {
        int height = blockHeight(form);
        if (form.description != null && !form.description.isEmpty()) {
            height += 10;
        }
        return height;
    }

    /** The height of a form's widget/caption block, without its description line. */
    private int blockHeight(RawYsmModel.ConfigForm form) {
        String type = form.type == null ? "" : form.type.toLowerCase(Locale.ROOT);
        if (RoamingProgram.variableName(form.defaultValue) == null) {
            return 20;
        }
        if ("range".equals(type) || "checkbox".equals(type)) {
            return ROW_HEIGHT;
        }
        if ("radio".equals(type)) {
            int rows = Math.max(1, (form.labels.size() + radioPerLine() - 1) / radioPerLine());
            return 14 + rows * 20 + 6;
        }
        return 20;
    }

    /** How many radio labels fit on one line; upstream derives the same count from the label widths (:307). */
    private static int radioPerLine() {
        return Math.max(1, FORMS_WIDTH / RADIO_STEP);
    }

    /**
     * Places one form's widgets and captions at {@code cursor} and returns the cursor for the next form.
     * <p>
     * The form-to-widget mapping is the one this screen always had: a {@code range} becomes a {@link FlatSlider}, a
     * {@code checkbox} a {@link ConfigCheckBox}, a {@code radio} a row of labelled buttons, and a form whose program
     * names no variable is reported instead of drawn as a widget that would silently do nothing.
     */
    private int addForm(RawYsmModel.ConfigForm form, int cursor) {
        String variable = RoamingProgram.variableName(form.defaultValue);
        String title = form.title == null || form.title.isEmpty() ? form.defaultValue : form.title;
        String type = form.type == null ? "" : form.type.toLowerCase(Locale.ROOT);
        int formsX = panelX + FORMS_X_OFFSET;
        if (variable == null) {
            this.captions.add(
                new Caption(
                    title + " - " + I18n.format("gui.yes_steve_model.model.config.unsupported"),
                    formsX,
                    cursor,
                    0xFFB06060));
        } else if ("range".equals(type)) {
            // A reverse range (`min: 0, max: -180`, which LaPluma 2.5.3 declares for 左小臂_Rx/右小臂_Rx) is
            // legitimate and upstream hands it to a slider unchanged
            // (com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java:379); this screen used to reject it with a
            // `bad_range` caption and made those forms unreachable. FlatSlider clamps against the sorted pair, so
            // both orders work; see its clamp().
            int id = this.nextWidgetId++;
            this.sliderVariables.put(id, variable);
            final String target = variable;
            this.buttonList.add(
                new FlatSlider(id, formsX, cursor + 10, FORMS_WIDTH, title, form.min, form.max, form.step,
                    (float) currentValue(variable), (slider, value) -> write(target, value)));
        } else if ("checkbox".equals(type)) {
            int id = this.nextWidgetId++;
            this.checkboxVariables.put(id, variable);
            this.buttonList.add(
                ConfigCheckBox.forLabel(id, formsX, cursor + 10, FORMS_WIDTH, title, currentValue(variable) >= 0.5d));
        } else if ("radio".equals(type)) {
            this.captions.add(new Caption(title, formsX, cursor, 0xFFF3EFE0));
            int perLine = radioPerLine();
            int index = 0;
            for (Map.Entry<String, String> label : form.labels.entrySet()) {
                double assigned = RoamingProgram.assignedValue(label.getValue());
                if (!Double.isNaN(assigned)) {
                    String labelVariable = RoamingProgram.variableName(label.getValue());
                    // A label may assign a different variable than the form reads; fall back to the form's own.
                    String target = labelVariable == null ? variable : labelVariable;
                    int id = this.nextWidgetId++;
                    this.radioTargets.put(id, new RadioTarget(target, assigned));
                    // Upstream keeps each label in its declared cell (:321-349), so a label whose expression cannot
                    // be read leaves a gap instead of shifting the rest of the row.
                    int labelX = formsX + RADIO_STEP * (index % perLine);
                    int labelY = cursor + 12 + 20 * (index / perLine);
                    this.buttonList.add(
                        new FlatColorButton(
                            id,
                            labelX,
                            labelY,
                            52,
                            18,
                            this.fontRendererObj.trimStringToWidth(label.getKey(), 50)));
                }
                index++;
            }
        } else {
            this.captions.add(
                new Caption(
                    title + " - " + I18n.format("gui.yes_steve_model.model.config.unsupported"),
                    formsX,
                    cursor,
                    0xFFB06060));
        }
        cursor += blockHeight(form);
        if (form.description != null && !form.description.isEmpty()) {
            this.captions.add(new Caption(form.description, formsX, cursor, 0xFF9E9E9E));
            cursor += 10;
        }
        return cursor;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BACK_BUTTON_ID) {
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        if (button.id == LIST_PREV_ID) {
            if (this.listPage > 0) {
                this.listPage--;
                this.initGui();
            }
            return;
        }
        if (button.id == LIST_NEXT_ID) {
            if (this.listPage < listPageCount() - 1) {
                this.listPage++;
                this.initGui();
            }
            return;
        }
        if (button.id == FORM_PREV_ID) {
            if (this.formPage > 0) {
                this.formPage--;
                this.initGui();
            }
            return;
        }
        if (button.id == FORM_NEXT_ID) {
            // Clamped by addFormPage, which is the one place that knows how many pages there are.
            this.formPage++;
            this.initGui();
            return;
        }
        if (button.id >= LIST_ROW_FIRST_ID && button.id < LIST_ROW_FIRST_ID + LIST_ROWS) {
            int index = this.listPage * LIST_ROWS + (button.id - LIST_ROW_FIRST_ID);
            if (index < this.panels.size() && index != this.selectedIndex) {
                this.selectedIndex = index;
                // A different button is a different form list, so its pages start over.
                this.formPage = 0;
                this.initGui();
            }
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
        RawYsmModel.ExtraAnimationButton panel = selected();
        String title = I18n.format("gui.yes_steve_model.model.config.title");
        if (panel != null) {
            title = title + " - " + buttonLabel(panel);
        }
        this.drawCenteredString(this.fontRendererObj, title, this.width / 2, panelY - 14, 0xF3EFE0);
        for (Caption caption : captions) {
            this.drawString(this.fontRendererObj, caption.text, caption.x, caption.y, caption.color);
        }
        if (this.panels.isEmpty()) {
            this.drawString(
                this.fontRendererObj,
                I18n.format("gui.yes_steve_model.model.config.none"),
                panelX + 5,
                panelY + 28,
                0xFF9E9E9E);
        } else {
            this.drawPagerText();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        // After the widgets: a button fills its own row, so a highlight drawn underneath would be invisible.
        this.drawSelectedRowBorder();
    }

    /** "1/2" under each column that has more than one page. */
    private void drawPagerText() {
        int pagerY = panelY + BOTTOM_OFFSET - PAGER_HEIGHT + 8;
        if (listPageCount() > 1) {
            this.drawCenteredString(
                this.fontRendererObj,
                (this.listPage + 1) + "/" + listPageCount(),
                panelX + LIST_X_OFFSET + LIST_WIDTH / 2,
                pagerY,
                0xFF9E9E9E);
        }
        RawYsmModel.ExtraAnimationButton panel = selected();
        if (panel == null) {
            return;
        }
        int formPageTotal = formPageCount(formPages(panel));
        if (formPageTotal > 1) {
            this.drawCenteredString(
                this.fontRendererObj,
                (this.formPage + 1) + "/" + formPageTotal,
                panelX + FORMS_X_OFFSET + FORMS_WIDTH / 2,
                pagerY,
                0xFF9E9E9E);
        }
    }

    /** Outlines the selected button's row, the way the texture screen outlines the selected texture. */
    private void drawSelectedRowBorder() {
        if (this.selectedIndex < 0 || this.selectedIndex / LIST_ROWS != this.listPage) {
            return;
        }
        int row = this.selectedIndex % LIST_ROWS;
        int y = panelY + TOP_OFFSET + row * LIST_ROW_HEIGHT;
        drawBorder(panelX + LIST_X_OFFSET, y, LIST_WIDTH, LIST_ROW_HEIGHT - 2, 0xff_82C56A);
    }

    private void drawBorder(int x, int y, int width, int height, int color) {
        this.drawGradientRect(x, y + 1, x + 1, y + height - 1, color, color);
        this.drawGradientRect(x, y, x + width, y + 1, color, color);
        this.drawGradientRect(x + width - 1, y + 1, x + width, y + height - 1, color, color);
        this.drawGradientRect(x, y + height - 1, x + width, y + height, color, color);
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
