package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.gui.button.ConfigCheckBox;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.client.gui.button.FlatSlider;
import com.fox.ysmu.client.input.ServerSwitchState;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.roaming.ModelFormExecution;
import com.fox.ysmu.client.roaming.RoamingProgram;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SSubmitRouletteExpression;
import com.fox.ysmu.util.RenderUtil;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The {@code 模型设置} panel of one model: a paged list of every button the pack declared, and one widget per config
 * form of the selected button.
 * <p>
 * A form's type decides the widget ({@code range} a slider, {@code checkbox} a toggle, {@code radio} a row of labelled
 * buttons), and its {@code value} is a complete MoLang read program. Upstream's AnimationRouletteScreen.java:237-338
 * evaluates those reads and complete radio actions in an animatable context. This screen owns an independent real
 * GUI player/model context so it can configure a model the player is not wearing. Roaming writes still use the
 * existing server-authoritative roaming channel; non-roaming expressions use the sender-authorized expression path.
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
    /**
     * The text offset {@code ConfigCheckBox} draws its label at: the {@code [X]} mark occupies +2..+12 and the label
     * starts at +28. A radio button therefore has to reserve this much room or its label is truncated
     * ({@code button/ConfigCheckBox.java:76-80}).
     */
    private static final int RADIO_TEXT_INSET = 30;
    /** Gap between two radio buttons on the same line. */
    private static final int RADIO_GAP = 2;
    /** How many button rows and how much form height fit above the pagers. */
    private static final int LIST_ROWS = (BOTTOM_OFFSET - TOP_OFFSET - PAGER_HEIGHT) / LIST_ROW_HEIGHT;
    private static final int FORMS_HEIGHT = BOTTOM_OFFSET - TOP_OFFSET - PAGER_HEIGHT;

    /**
     * Upstream's text-key templates, verbatim
     * ({@code client/gui/AnimationRouletteScreen.java:67-69}); the pack's own language files declare these keys and
     * the raw {@code ysm.json} string is the fallback, which is what upstream passes as the default to
     * {@code LanguageManager.getI18n} (:299-310, :323-326, :370-371, :388-389).
     */
    private static final String CONFIG_TITLE = "properties.extra_animation_buttons.%s.config_forms.%d.title";
    private static final String CONFIG_DESC = "properties.extra_animation_buttons.%s.config_forms.%d.description";
    private static final String CONFIG_LABELS = "properties.extra_animation_buttons.%s.config_forms.%d.labels.%d";

    private final GuiScreen parent;
    private final ResourceLocation modelId;
    private CustomGuiPlayerEntity formPreview;
    private int bindingGeneration;
    private final List<ReadBinding> readBindings = new ArrayList<>();
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

    /** Id -> the variable a checkbox writes. */
    private final Map<Integer, String> checkboxVariables = new HashMap<>();
    /** Id -> the complete action program a radio button executes. */
    private final Map<Integer, String> radioTargets = new HashMap<>();
    /** Text drawn by {@link #drawScreen} instead of by a widget: form titles and the unusable note. */
    private final List<Caption> captions = new ArrayList<>();
    /** One form prepared for display, in declaration order; rebuilt by {@link #initGui}. */
    private List<ResolvedForm> resolvedForms = Collections.emptyList();
    /** Which page each entry of {@link #resolvedForms} landed on; rebuilt whenever the form list is. */
    private int[] formPageOfForm = new int[0];
    /** Total number of form pages; 1 when there is one page or none. */
    private int formPageTotal = 1;
    /** Hover rectangles carrying a form's description, which upstream shows as a tooltip (:312-316, :373-374). */
    private final List<HoverTip> hoverTips = new ArrayList<>();

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
        this.formPreview = new CustomGuiPlayerEntity();
        this.initialButtonId = initialButtonId;
    }

    @Override
    public void initGui() {
        this.bindingGeneration++;
        this.readBindings.clear();
        ModelFormExecution.cancelInvalid();
        if (this.formPreview == null) this.formPreview = new CustomGuiPlayerEntity();
        this.buttonList.clear();
        this.checkboxVariables.clear();
        this.radioTargets.clear();
        this.captions.clear();
        this.hoverTips.clear();
        this.resolvedForms = Collections.emptyList();
        this.formPageOfForm = new int[0];
        this.formPageTotal = 1;
        this.nextWidgetId = FORM_WIDGET_FIRST_ID;

        this.panelX = (this.width - 520) / 2;
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
        this.resolvedForms = resolveForms(selected());
        this.paginateForms();
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
        if (this.formPage >= this.formPageTotal) {
            this.formPage = this.formPageTotal - 1;
        }
        if (this.formPage < 0) {
            this.formPage = 0;
        }
        int cursor = panelY + TOP_OFFSET;
        for (int i = 0; i < this.resolvedForms.size(); i++) {
            if (this.formPageOfForm[i] == this.formPage) {
                cursor = addForm(this.resolvedForms.get(i), cursor);
            }
        }
        if (this.formPageTotal > 1) {
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
     * Prepares every form of one button for display: the MoLang programs are kept verbatim, the pack-declared text is
     * resolved for the running locale, and a radio form's line layout is decided from the width of its longest label.
     * <p>
     * Upstream does the same three things where it builds each widget ({@code AnimationRouletteScreen.java:237-286}
     * and its {@code getFlatSlider}/{@code getFlatCheckbox}/{@code addRatioButtons}).
     */
    private List<ResolvedForm> resolveForms(@Nullable RawYsmModel.ExtraAnimationButton panel) {
        if (panel == null || panel.forms.isEmpty()) {
            return Collections.emptyList();
        }
        String locale = ClientModelMetadataRegistry.currentLocale();
        ClientModelMetadataRegistry.Metadata metadata = ClientModelMetadataRegistry.get(this.modelId);
        List<ResolvedForm> forms = new ArrayList<>(panel.forms.size());
        for (int i = 0; i < panel.forms.size(); i++) {
            forms.add(resolveForm(panel, panel.forms.get(i), i, locale, metadata));
        }
        return forms;
    }

    /**
     * Resolves one config form.
     * <p>
     * <b>Action-only radio forms are supported.</b> Upstream explicitly allows a {@code radio} form whose {@code value}
     * is blank: it reads the constant program {@code "0"} and each label carries a complete action program
     * ({@code format/parser/ModelInfoBuilder.java:154-166}, the {@code actionOnlyRadio} branch; the screen's radio
     * branch then executes those actions, {@code AnimationRouletteScreen.java:287-366}). The old code tested the value
     * first and showed such a form as unsupported, which made every reset/apply-preset button of the built-in
     * {@code wine_fox/08_sta} and {@code tmp/Endfield_Rossi_v1.1} packs unreachable.
     * <p>
     * A blank value on a {@code checkbox} or {@code range} form is different: upstream rejects the whole model there
     * ({@code ModelInfoBuilder.java:158-160} throws "Config expression is empty"), so there is no behaviour to copy.
     * This port keeps the model usable and marks the entry as unsupported instead.
     */
    private ResolvedForm resolveForm(RawYsmModel.ExtraAnimationButton panel, RawYsmModel.ConfigForm form, int formIndex,
        String locale, @Nullable ClientModelMetadataRegistry.Metadata metadata) {
        String type = form.type == null ? "" : form.type.toLowerCase(Locale.ROOT);
        String title = resolveText(metadata, locale, CONFIG_TITLE, panel.id, formIndex, form.title);
        if ((title == null || title.isEmpty()) && form.defaultValue != null) {
            // The port's old fallback for a pack that declares no title at all: show the target program. Upstream's
            // default is the form's own {@code title}, so this only changes an otherwise blank label.
            title = form.defaultValue;
        }
        String description = resolveText(metadata, locale, CONFIG_DESC, panel.id, formIndex, form.description);
        String readSource = form.defaultValue == null ? "" : form.defaultValue;
        boolean blank = readSource.trim()
            .isEmpty();
        if ("radio".equals(type) && !(blank && form.labels.isEmpty())) {
            List<Label> labels = resolveLabels(panel.id, form, formIndex, locale, metadata);
            int[] layout = radioLayout(labels);
            return new ResolvedForm(
                form,
                type,
                title,
                description,
                // Upstream's action-only read program is the constant "0" (ModelInfoBuilder.java:165), which selects
                // the first label exactly as its normal radioIndex path would.
                blank ? "0" : readSource,
                false,
                labels,
                layout[0],
                layout[1],
                layout[2]);
        }
        boolean unsupported = blank || !("range".equals(type) || "checkbox".equals(type));
        return new ResolvedForm(
            form,
            type,
            title,
            description,
            unsupported ? null : readSource,
            unsupported,
            Collections.emptyList(),
            1,
            FORMS_WIDTH,
            FORMS_WIDTH - RADIO_GAP);
    }

    /**
     * Resolves one form label's display text for the running locale, returning the pack's raw text when neither the
     * requested locale nor {@code en_us} declares the key.
     * <p>
     * The lookup chain is upstream's: the running locale, then {@code en_us}, then the caller's default
     * ({@code format/schema/model/views/ModelInfoView.java:90-111} {@code translateOr}, reached from
     * {@code client/lang/LanguageManager.java:34-41}).
     */
    @Nullable
    private static String resolveText(@Nullable ClientModelMetadataRegistry.Metadata metadata, String locale,
        String template, String buttonId, int formIndex, @Nullable String fallback) {
        if (metadata != null && buttonId != null) {
            String key = String.format(template, buttonId, formIndex);
            String resolved = metadata.localized(key, locale);
            if (resolved != null) {
                return resolved;
            }
        }
        return fallback;
    }

    /**
     * The labels of a radio form in declaration order, each resolved through its own key
     * ({@code properties.extra_animation_buttons.<buttonId>.config_forms.<i>.labels.<j>}, upstream
     * {@code AnimationRouletteScreen.java:67-69, 296-303, 322-326}), the raw label text being the fallback exactly as
     * {@code label.name()} is upstream.
     * <p>
     * <b>Declaration order is kept on purpose.</b> Upstream's payload builder sorts a folder model's labels by their
     * text before serializing them ({@code format/parser/ModelInfoBuilder.java:170-178}), but every built-in pack's own
     * language files index those labels by declaration order - measured on {@code wine_fox/08_sta}: form
     * {@code 变更服装.config_forms.2} declares {@code fumo笑, 无语, 0.0, ???, 爱心} and {@code en_us.json} translates
     * labels 0..4 as {@code Fumo Smile, Speechless, 0.0, ???, Heart}. Copying the sort would pair each translation with
     * the wrong label and shift the value-to-index selection with it, so the port keeps the order the pack authored and
     * records the difference instead (see {@code .agent/phase64-model-config-screen-review.md}).
     */
    private static List<Label> resolveLabels(String buttonId, RawYsmModel.ConfigForm form, int formIndex, String locale,
        @Nullable ClientModelMetadataRegistry.Metadata metadata) {
        List<Label> labels = new ArrayList<>(form.labels.size());
        int labelIndex = 0;
        for (Map.Entry<String, String> entry : form.labels.entrySet()) {
            String text = entry.getKey();
            if (metadata != null && buttonId != null) {
                String resolved = metadata
                    .localized(String.format(CONFIG_LABELS, buttonId, formIndex, labelIndex), locale);
                if (resolved != null) {
                    text = resolved;
                }
            }
            labels.add(new Label(text == null ? "" : text, entry.getValue() == null ? "" : entry.getValue()));
            labelIndex++;
        }
        return labels;
    }

    /**
     * How many radio buttons fit on one line and how wide each is.
     * <p>
     * Upstream derives the count from the longest label it has to draw ({@code AnimationRouletteScreen.java:294-311}:
     * {@code lineMaxWidth = max(font.width(labelStr) + 16)}, {@code countPerLine = max(1, 115 / lineMaxWidth)}), which
     * is what keeps a wide translated label from being cut off. The port's form column is wider than upstream's
     * 115 px column and its checkbox reserves a bigger text inset, so the same rule is applied with this column's
     * width and {@link #RADIO_TEXT_INSET}.
     *
     * @return {@code {perLine, buttonStep, buttonWidth}}
     */
    private int[] radioLayout(List<Label> labels) {
        int widest = 0;
        for (Label label : labels) {
            widest = Math.max(widest, this.fontRendererObj.getStringWidth(label.text) + RADIO_TEXT_INSET);
        }
        int perLine = Math.max(1, FORMS_WIDTH / Math.max(1, widest));
        int step = FORMS_WIDTH / perLine;
        int width = Math.max(30, step - RADIO_GAP);
        return new int[] { perLine, step, width };
    }

    /** The vertical space one form needs. */
    private static int blockHeight(ResolvedForm resolved) {
        if (resolved.unsupported) {
            return 20;
        }
        if ("radio".equals(resolved.type)) {
            int rows = resolved.labels.isEmpty() ? 0
                : (resolved.labels.size() + resolved.perLine - 1) / resolved.perLine;
            // Caption line, then one 20 px row per line of radio buttons; the trailing pad is upstream's +3
            // (AnimationRouletteScreen.java:314, :352) rounded into this screen's row grid.
            return 14 + rows * 20 + 6;
        }
        return ROW_HEIGHT;
    }

    /** Pages the prepared forms greedily by the height each one needs; a form taller than the column gets its own page. */
    private void paginateForms() {
        this.formPageOfForm = new int[this.resolvedForms.size()];
        int page = 0;
        int used = 0;
        for (int i = 0; i < this.formPageOfForm.length; i++) {
            int height = blockHeight(this.resolvedForms.get(i));
            if (used > 0 && used + height > FORMS_HEIGHT) {
                page++;
                used = 0;
            }
            this.formPageOfForm[i] = page;
            used += height;
        }
        this.formPageTotal = this.formPageOfForm.length == 0 ? 1 : page + 1;
    }

    /**
     * Places one form's widgets, captions and description hover region at {@code cursor} and returns the cursor for the
     * next form.
     * <p>
     * The form-to-widget mapping is the one this screen always had: a {@code range} becomes a {@link FlatSlider}, a
     * {@code checkbox} a {@link ConfigCheckBox}, a {@code radio} a row of labelled buttons. Complete read source is
     * bound to each widget; malformed programs are reported by the parser rather than restricted to variable names.
     */
    private int addForm(ResolvedForm resolved, int cursor) {
        int formsX = panelX + FORMS_X_OFFSET;
        if (resolved.unsupported) {
            this.captions.add(
                new Caption(
                    resolved.title + " - " + I18n.format("gui.yes_steve_model.model.config.unsupported"),
                    formsX,
                    cursor,
                    0xFFB06060));
            addDescriptionHover(resolved, formsX, cursor);
            return cursor + blockHeight(resolved);
        }
        if ("range".equals(resolved.type)) {
            int id = this.nextWidgetId++;
            final String target = resolved.readSource;
            FlatSlider slider = new FlatSlider(
                id,
                formsX,
                cursor + 10,
                FORMS_WIDTH,
                // The widget draws the title and the value on one centred line; leave room for the value.
                this.fontRendererObj.trimStringToWidth(resolved.title, FORMS_WIDTH - 60),
                resolved.form.min,
                resolved.form.max,
                resolved.form.step,
                0f,
                (widget, value) -> executeAction(target + "=" + value));
            this.buttonList.add(slider);
            addReadBinding(resolved.readSource, value -> {
                if (Double.isFinite(value) && !slider.isDragging()) slider.setValue(value.floatValue());
            });
        } else if ("checkbox".equals(resolved.type)) {
            int id = this.nextWidgetId++;
            this.checkboxVariables.put(id, resolved.readSource);
            ConfigCheckBox checkbox = ConfigCheckBox.forLabel(
                id,
                formsX,
                cursor + 10,
                FORMS_WIDTH,
                this.fontRendererObj.trimStringToWidth(resolved.title, FORMS_WIDTH - RADIO_TEXT_INSET),
                false);
            this.buttonList.add(checkbox);
            addReadBinding(resolved.readSource, value -> {
                if (Double.isFinite(value)) checkbox.setChecked(value > 0d);
            });
        } else if ("radio".equals(resolved.type)) {
            this.captions.add(new Caption(resolved.title, formsX, cursor, 0xFFF3EFE0));
            List<ConfigCheckBox> radios = new ArrayList<>(resolved.labels.size());
            int index = 0;
            for (Label label : resolved.labels) {
                int id = this.nextWidgetId++;
                this.radioTargets.put(id, label.action);
                int labelX = formsX + resolved.radioStep * (index % resolved.perLine);
                int labelY = cursor + 12 + 20 * (index / resolved.perLine);
                ConfigCheckBox radio = ConfigCheckBox.forLabel(
                    id,
                    labelX,
                    labelY,
                    resolved.radioWidth,
                    this.fontRendererObj.trimStringToWidth(label.text, resolved.radioWidth - RADIO_TEXT_INSET),
                    false);
                radios.add(radio);
                this.buttonList.add(radio);
                index++;
            }
            addReadBinding(resolved.readSource, value -> {
                int selected = radioIndex(value, radios.size());
                for (int i = 0; i < radios.size(); i++) {
                    radios.get(i)
                        .setChecked(i == selected);
                }
            });
        }
        addDescriptionHover(resolved, formsX, cursor);
        return cursor + blockHeight(resolved);
    }

    /**
     * Registers a form's description as a hover tooltip, which is where upstream shows it
     * ({@code AnimationRouletteScreen.java:312-316, 373-374, 390-392} call {@code Tooltip.create} on the description
     * Component and hand it to the widget; the roulette itself never reserves a line for it). The old screen drew the
     * description as an always-visible line and grew every form by 10 px for it, which made a long description run
     * past the column.
     */
    private void addDescriptionHover(ResolvedForm resolved, int x, int y) {
        if (resolved.description.isEmpty()) {
            return;
        }
        List<String> lines = this.fontRendererObj.listFormattedStringToWidth(resolved.description, 200);
        if (!lines.isEmpty()) {
            this.hoverTips.add(new HoverTip(x, y, FORMS_WIDTH, blockHeight(resolved), lines));
        }
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
        if (checkboxVariable != null && button instanceof ConfigCheckBox box) {
            box.doPress();
            executeAction(checkboxVariable + "=" + (box.isChecked() ? "1" : "0"));
            return;
        }
        String radio = this.radioTargets.get(button.id);
        if (radio != null) {
            executeAction(radio);
        }
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
        this.refreshConfigValues();
        renderFormPreview();
        RawYsmModel.ExtraAnimationButton panel = selected();
        String title = I18n.format("gui.yes_steve_model.model.config.title");
        if (panel != null) {
            title = title + " - " + buttonLabel(panel);
        }
        this.drawCenteredString(this.fontRendererObj, title, this.width / 2, panelY - 14, 0xF3EFE0);
        for (Caption caption : captions) {
            this.drawString(
                this.fontRendererObj,
                this.fontRendererObj.trimStringToWidth(caption.text, FORMS_WIDTH),
                caption.x,
                caption.y,
                caption.color);
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
        this.drawHoverTips(mouseX, mouseY);
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
        if (this.formPageTotal > 1) {
            this.drawCenteredString(
                this.fontRendererObj,
                (this.formPage + 1) + "/" + this.formPageTotal,
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

    /** Draws the description of the form under the mouse, the way upstream's widget tooltips do. */
    private void drawHoverTips(int mouseX, int mouseY) {
        for (HoverTip tip : this.hoverTips) {
            if (mouseX >= tip.x && mouseX < tip.x + tip.width && mouseY >= tip.y && mouseY < tip.y + tip.height) {
                this.func_146283_a(tip.lines, mouseX, mouseY);
                return;
            }
        }
    }

    private void renderFormPreview() {
        if (this.mc.thePlayer == null) return;
        ResourceLocation texture = CustomPlayerModel.resolveTexture(
            this.modelId,
            ClientModelManager.MODELS.get(this.modelId),
            null,
            ClientModelManager.DECLARED_DEFAULT_TEXTURES.get(this.modelId));
        RenderUtil.renderFormPreview(
            panelX + 470,
            panelY + 190,
            45,
            this.mc.thePlayer,
            this.modelId,
            texture,
            this.formPreview);
    }

    private void executeAction(String source) {
        EntityPlayer local = this.mc.thePlayer;
        if (local == null) return;
        try {
            // Compile once and hand the compiled program to the queue, so a click parses its action exactly once.
            RoamingProgram.Compiled compiled = RoamingProgram.compile(GeckoLibCache.getInstance().parser, source);
            ModelFormExecution.submit(local, this.modelId, compiled, null, () -> true);
            // Drive the independent context now as well: closing the screen before another draw must not strand an
            // action for a model the player is not wearing. Read callbacks remain generation/screen guarded.
            renderFormPreview();
            refreshConfigValues();
            // This is the expression path, not ysm.sync. Upstream sends the source only when the mod's remote channel
            // is present and the server did not ask for low bandwidth
            // (client/gui/AnimationRouletteScreen.java:335, 401: `NetworkHandler.isRemoteChannelPresent()`); the port's
            // per-connection equivalent of that query is the handshake reply tracked by ServerSwitchState.
            if (!compiled.hasOnlyRoamingAssignments() && !Config.LOW_BANDWIDTH_USAGE
                && ServerSwitchState.isRemoteChannelPresent()) {
                NetworkHandler.CHANNEL.sendToServer(new C2SSubmitRouletteExpression(this.modelId, source));
            }
        } catch (Exception failure) {
            ysmu.LOG.warn("Failed to parse model form action '{}': {}", source, failure.getMessage());
        }
    }

    private void refreshConfigValues() {
        if (this.mc.currentScreen != this || this.mc.thePlayer == null) return;
        final int generation = this.bindingGeneration;
        for (ReadBinding binding : this.readBindings) {
            if (binding.pending) continue;
            binding.pending = true;
            boolean queued = ModelFormExecution.submit(this.mc.thePlayer, this.modelId, binding.program, value -> {
                binding.pending = false;
                if (this.mc.currentScreen == this && this.bindingGeneration == generation) binding.apply.accept(value);
            }, () -> this.mc.currentScreen == this && this.bindingGeneration == generation);
            if (!queued) binding.pending = false;
        }
    }

    /**
     * Binds a form's complete read program to its widget.
     * <p>
     * The program is compiled here once instead of on every draw; the screen used to hand the same source text to
     * {@link ModelFormExecution#submit} every frame, which re-parsed every visible form sixty times a second. A
     * program the parser refuses is reported once, and the widget keeps the value it was built with - the same outcome
     * as upstream's per-form parse error ({@code AnimationRouletteScreen.java:524-530} reports and runs nothing).
     */
    private void addReadBinding(String source, Consumer<Double> apply) {
        try {
            this.readBindings.add(
                new ReadBinding(
                    RoamingProgram.compile(GeckoLibCache.getInstance().parser, source),
                    apply));
        } catch (Exception failure) {
            ysmu.LOG.warn("Failed to parse model form read program '{}': {}", source, failure.getMessage());
        }
    }

    static int radioIndex(double value, int labelCount) {
        if (!Double.isFinite(value)) return -1;
        long index = Math.round(value);
        return index >= 0 && index < labelCount ? (int) index : -1;
    }

    /**
     * Upstream's roulette does not pause the game while its settings column is open
     * ({@code client/gui/AnimationRouletteScreen.java:702-705}, {@code isPauseScreen()} returns {@code false}), and
     * every other screen of this port's model GUI chain answers {@code false} too
     * ({@code PlayerModelScreen.java:560}, {@code client/gui/AnimationRouletteScreen.java:481}). Pausing would freeze
     * the world the model is being configured in and, in single player, hold the integrated server so the
     * server-authoritative roaming echo cannot arrive until the panel is closed.
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        this.bindingGeneration++;
        this.readBindings.clear();
        this.hoverTips.clear();
        this.resolvedForms = Collections.emptyList();
        this.formPageOfForm = new int[0];
        ModelFormExecution.cancelInvalid();
        if (this.formPreview != null) this.formPreview.close();
        this.formPreview = null;
        super.onGuiClosed();
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

    /** A hover rectangle carrying one form's description lines. */
    private static final class HoverTip {

        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final List<String> lines;

        private HoverTip(int x, int y, int width, int height, List<String> lines) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.lines = lines;
        }
    }

    /** One config form prepared for display; see {@link #resolveForm}. */
    private static final class ResolvedForm {

        private final RawYsmModel.ConfigForm form;
        private final String type;
        private final String title;
        private final String description;
        /** The program the widget's current value is read with; {@code null} for an unsupported form. */
        @Nullable
        private final String readSource;
        private final boolean unsupported;
        private final List<Label> labels;
        private final int perLine;
        private final int radioStep;
        private final int radioWidth;

        private ResolvedForm(RawYsmModel.ConfigForm form, String type, String title, String description,
            @Nullable String readSource, boolean unsupported, List<Label> labels, int perLine, int radioStep,
            int radioWidth) {
            this.form = form;
            this.type = type;
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.readSource = readSource;
            this.unsupported = unsupported;
            this.labels = labels;
            this.perLine = perLine;
            this.radioStep = radioStep;
            this.radioWidth = radioWidth;
        }
    }

    /** One radio label: the text to draw and the complete action program its click executes. */
    private static final class Label {

        private final String text;
        private final String action;

        private Label(String text, String action) {
            this.text = text;
            this.action = action;
        }
    }

    private static final class ReadBinding {

        final RoamingProgram.Compiled program;
        final Consumer<Double> apply;
        boolean pending;

        ReadBinding(RoamingProgram.Compiled program, Consumer<Double> apply) {
            this.program = program;
            this.apply = apply;
        }
    }
}
