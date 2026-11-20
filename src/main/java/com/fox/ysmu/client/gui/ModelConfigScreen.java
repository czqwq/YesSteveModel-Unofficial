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
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientModelMetadataRegistry;
import com.fox.ysmu.client.entity.CustomGuiPlayerEntity;
import com.fox.ysmu.client.gui.button.ConfigCheckBox;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.client.gui.button.FlatSlider;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.roaming.ConfigActionRuntime;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.RenderUtil;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The {@code 模型设置} panel of one model: a paged list of every button the pack declared, and one widget per config
 * form of the selected button.
 * <p>
 * A form's type decides the widget ({@code range} a slider, {@code checkbox} a toggle, {@code radio} a row of labelled
 * buttons).
 * <p>
 * <b>上游形态。</b>上游没有独立面板：它把设置表单画成轮盘同屏的右列
 * （{@code tmp/YesSteveModel-dev-1.20/.../client/gui/AnimationRouletteScreen.java:205-235} 建列、{@code :236-348} 建控件），
 * 读取直接落在本地玩家自己的 {@code PlayerAnimatableCapability} 上同步求值（{@code :238-257}，radio 另在每 tick 重读
 * {@code :297-306,399-406}），点击只提交"按钮/表单位置 + 类型化值"（{@code ClientProtocolGateway.java:104-129}），
 * 写程序由收到服务端广播回执的每个客户端从本地模型载荷里取（{@code ClientControlHandler.java:112-129}）。
 * 1.7.10 固定管线里没有可内嵌的滚动列，所以移植保留这个独立 Screen（左列分页按钮、右列分页控件），
 * 属已登记的移植特有 UI（契约 §8 第 7 条）。
 * <p>
 * <b>读写都走 {@link ConfigActionRuntime}，本屏不执行任何程序。</b>读取由 {@code ConfigActionRuntime.read} 在本地玩家
 * 真实的 (entity, model) 作用域里同步求值；range/checkbox/radio 的点击由 {@code ConfigActionRuntime.submit} 提交类型化
 * 动作——有远端通道时经服务端校验后广播给跟踪者与发送者自己，无远端通道/客户端独有模型时按契约 §8 第 3 条在本机世界
 * 作用域直接执行。旧实现的"把写程序排进 {@code ModelFormExecution}、再由本屏自带预览的渲染 pass 消费"正是
 * "预览变、世界不变、重开复位"的根因（{@code .agent/model-config-bug-audit.md} RC-1），已整段删除；wire 上也不再传
 * MoLang 源码（上游同一原则：{@code docs/standards/protocol-v1/README.md:104-112}）。
 * <p>
 * <b>只配置本地玩家当前穿戴的模型。</b>上游只允许配置自己当前的模型：服务端只接受 {@code entity == sender} 且必须是
 * 发送者当前模型（{@code ControlHandler.java:98-108,314-329}），GUI 也只拿本地玩家的 capability
 * （{@code client/input/AnimationRouletteKey.java:40-46} 把本地玩家的 {@code PlayerAnimatableCapability} 交给
 * {@code AnimationRouletteScreen.java:100-112}）。因此本屏在 {@link #initGui()} 与每个绘制帧用
 * {@link ConfigActionRuntime#isLocalModel} 复核；玩家换了模型（上游的安装代次变化，{@code :157-165,399-406}）
 * 就退回上一级。面板自己的 {@link CustomGuiPlayerEntity} 预览仍保留（1.7.10 GUI 的画布），但不再作为读写上下文。
 * <p>
 * <b>普通 {@code v.*} 不持久化</b>（上游语义，owner 已拍板）：它只存在于该实体当前模型安装的 MoLang 内存里，
 * 换模型/重连即回包内默认值；需要跨会话保持的设置必须写 {@code v.roaming.*}。本屏不为任何值做本地存档。
 * <p>
 * <b>Why a list.</b> A pack may declare many buttons - LaPluma 2.5.3 declares sixteen with 122 forms between them - and
 * upstream opens whichever one the wheel's {@code "#<buttonId>"} entry names, as a scrolling column of the roulette
 * ({@code client/gui/AnimationRouletteScreen.java:564-569} opens it, {@code :205-235} lays it out). This port's panel
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
     * The panel's fixed design size. It is wider than a 854x480 window's automatic GUI scale (427 scaled pixels), so
     * both the panel origin and the preview below are clamped to the screen: without that clamp the preview is drawn at
     * {@code panelX + 470} with a negative {@code panelX} and lands completely off-screen, which reads exactly like
     * "the settings preview is gone" on the development window.
     */
    private static final int PANEL_WIDTH = 520;
    private static final int PANEL_HEIGHT = 235;
    /** Horizontal gap between the panel's right edge and where the preview is drawn, and its vertical anchor. */
    private static final int PREVIEW_X_OFFSET = 470;
    private static final int PREVIEW_Y_OFFSET = 190;
    /** The preview is kept this far from the right edge when the panel does not fit the window. */
    private static final int PREVIEW_RIGHT_MARGIN = 80;

    /**
     * Upstream's text-key templates, verbatim
     * ({@code client/gui/AnimationRouletteScreen.java:59-61}); the pack's own language files declare these keys and
     * the raw {@code ysm.json} string is the fallback, which is what upstream passes as the default to
     * {@code LanguageManager.getI18n} (:273-285, :310-311, :353-355, :370-372).
     */
    private static final String CONFIG_TITLE = "properties.extra_animation_buttons.%s.config_forms.%d.title";
    private static final String CONFIG_DESC = "properties.extra_animation_buttons.%s.config_forms.%d.description";
    private static final String CONFIG_LABELS = "properties.extra_animation_buttons.%s.config_forms.%d.labels.%d";

    private final GuiScreen parent;
    /** 本屏配置的模型；入口保证它是本地玩家当前穿戴的模型，见类 javadoc。 */
    private final ResourceLocation modelId;
    /** 独立的预览画布（移植特有 UI）；只负责画，不再是读写的 MoLang 上下文。 */
    private CustomGuiPlayerEntity formPreview;
    /**
     * 当前页每个表单的控件组：每帧 {@link #refreshConfigValues()} 用它在本地玩家真实作用域里读回当前值。
     * 每次 {@link #initGui()} 重建；不存在"已提交/待回填"状态。
     */
    private final List<VisibleForm> visibleForms = new ArrayList<>();
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

    /** 控件 id -> 点击时要提交的 form 路由；radio 条目另带被点击 label 的声明名与声明序。 */
    private final Map<Integer, FormRoute> formRoutes = new HashMap<>();
    /** Text drawn by {@link #drawScreen} instead of by a widget: form titles and the unusable note. */
    private final List<Caption> captions = new ArrayList<>();
    /** One form prepared for display, in declaration order; rebuilt by {@link #initGui}. */
    private List<ResolvedForm> resolvedForms = Collections.emptyList();
    /** Which page each entry of {@link #resolvedForms} landed on; rebuilt whenever the form list is. */
    private int[] formPageOfForm = new int[0];
    /** Total number of form pages; 1 when there is one page or none. */
    private int formPageTotal = 1;
    /** Hover rectangles carrying a form's description, which upstream shows as a tooltip (:289-292, :362, :394). */
    private final List<HoverTip> hoverTips = new ArrayList<>();

    private int nextWidgetId = FORM_WIDGET_FIRST_ID;

    /** 打开本地玩家当前穿戴的模型的设置面板，选中第一个带 config form 的按钮。 */
    public ModelConfigScreen(GuiScreen parent, ResourceLocation modelId) {
        this(parent, modelId, null);
    }

    /**
     * Opens the panel of one specific button.
     * <p>
     * That is what a wheel entry whose value is {@code "#<buttonId>"} needs: upstream opens that button's forms as a
     * column of the roulette ({@code clickConfig}, {@code client/gui/AnimationRouletteScreen.java:564-569}), and this
     * port's settings panel is its own screen, so the route has to land on the right button. {@code null} means "the
     * first button that carries forms", which is what the model-selection screen's settings icon opens.
     * <p>
     * 两个入口交给本屏的都必须是 <b>本地玩家当前穿戴</b>的模型：模型屏的设置图标只在"所选模型 == 本地玩家当前模型"
     * 时出现；轮盘入口读移植 {@code client/gui/AnimationRouletteScreen.java:149-160} 里 {@code ExtendedModelInfo} 的
     * 当前模型，也只会是本地玩家自己的。上游没有"配置未穿戴模型"的状态（{@code ControlHandler.java:98-108}），
     * 本屏在 {@link #initGui()} 与每帧用
     * {@link ConfigActionRuntime#isLocalModel} 复核，不再为任意目录模型提供服务。
     */
    public ModelConfigScreen(GuiScreen parent, ResourceLocation modelId, @Nullable String initialButtonId) {
        this.parent = parent;
        this.modelId = modelId;
        this.formPreview = new CustomGuiPlayerEntity();
        this.initialButtonId = initialButtonId;
    }

    @Override
    public void initGui() {
        // 只服务本地玩家当前穿戴的模型：玩家换了模型/离开世界就退回上一级（上游在 init 里比对安装代次，
        // AnimationRouletteScreen.java:157-165）。这里必须在建控件之前退出，避免旧模型的路由落进新安装。
        if (shouldCloseForModelChange()) {
            closeToParent();
            return;
        }
        if (this.formPreview == null) this.formPreview = new CustomGuiPlayerEntity();
        this.buttonList.clear();
        this.formRoutes.clear();
        this.visibleForms.clear();
        this.captions.clear();
        this.hoverTips.clear();
        this.resolvedForms = Collections.emptyList();
        this.formPageOfForm = new int[0];
        this.formPageTotal = 1;
        this.nextWidgetId = FORM_WIDGET_FIRST_ID;

        this.panelX = Math.max(0, (this.width - PANEL_WIDTH) / 2);
        this.panelY = Math.max(0, (this.height - PANEL_HEIGHT) / 2);

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
     * 本屏是否必须因为"不再是本地玩家当前穿戴、且本地有声明的模型"而关闭。
     * <p>
     * 两个条件缺一不可：本地玩家不能为空（没有本地世界就没有可读写的真实作用域），且
     * {@link ConfigActionRuntime#isLocalModel} 认可该模型（当前穿戴 + 本地声明存在）。上游在 {@code init}/
     * {@code tick} 里比对安装代次并在变化时 {@code onClose}（{@code AnimationRouletteScreen.java:157-165,399-406}）；
     * 移植没有"安装代次"这个对外概念，等价物就是运行时对 (本地玩家, 当前模型) 的这条判定取反。
     */
    private boolean shouldCloseForModelChange() {
        boolean close = this.mc == null || this.mc.thePlayer == null || !ConfigActionRuntime.isLocalModel(this.modelId);
        if (close && this.mc != null && this.mc.thePlayer != null && Config.DEBUG_MODEL_LOAD) {
            // 静默关闭会让"点了没反应"无从排查：把三个条件各自的答案打出来（phase78 回归诊断）。
            ExtendedModelInfo info = ExtendedModelInfo.get(this.mc.thePlayer);
            ysmu.LOG
                .info("[YSMU-CONFIG] ModelConfigScreen closing: model={} playerModel={} hasSettings={} localModel=false",
                    this.modelId,
                    info == null ? null : info.getModelId(),
                    ModelConfigRegistry.hasSettings(this.modelId));
        }
        return close;
    }

    /** 兜底退出：回到上一级，行为等价于上游代次变化时的 {@code onClose}（{@code AnimationRouletteScreen.java:162-165}）。 */
    private void closeToParent() {
        if (this.mc != null) {
            this.mc.displayGuiScreen(this.parent);
        }
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

    /** The pack's own name for a button, falling back to its id; upstream substitutes the same string (:643-647). */
    private static String buttonLabel(RawYsmModel.ExtraAnimationButton panel) {
        return panel.name == null || panel.name.isEmpty() ? panel.id : panel.name;
    }

    private int listPageCount() {
        return Math.max(1, (this.panels.size() + LIST_ROWS - 1) / LIST_ROWS);
    }

    /**
     * Prepares every form of one button for display: the pack-declared text is resolved for the running locale, the
     * form keeps its declaration route ({@code buttonId} + {@code formIndex}) and a radio form's line layout is decided
     * from the width of its longest label.
     * <p>
     * Upstream does the same where it builds each widget ({@code AnimationRouletteScreen.java:236-397}
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
     * branch then submits those label actions, {@code AnimationRouletteScreen.java:287-366}). The old code tested the
     * value first and showed such a form as unsupported, which made every reset/apply-preset button of the built-in
     * {@code wine_fox/08_sta} and {@code tmp/Endfield_Rossi_v1.1} packs unreachable. 读取的 {@code "0"} 常量由
     * {@link ConfigActionRuntime#read} 按同一规则给出（空 value 的 action-only radio 读作 {@code "0"}）。
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
        // 空 value 的判定仍然看原始声明：action-only radio 合法，checkbox/range 空 value 上游直接拒绝整个模型，
        // 移植保留模型可用性并把这一项标成"不支持"。读/写程序本身不再由本屏拼装。
        boolean blank = form.defaultValue == null || form.defaultValue.trim()
            .isEmpty();
        if ("radio".equals(type) && !(blank && form.labels.isEmpty())) {
            List<Label> labels = resolveLabels(panel.id, form, formIndex, locale, metadata);
            int[] layout = radioLayout(labels);
            return new ResolvedForm(
                panel.id,
                formIndex,
                form,
                type,
                title,
                description,
                false,
                labels,
                layout[0],
                layout[1],
                layout[2]);
        }
        boolean unsupported = blank || !("range".equals(type) || "checkbox".equals(type));
        return new ResolvedForm(
            panel.id,
            formIndex,
            form,
            type,
            title,
            description,
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
     * {@code AnimationRouletteScreen.java:59-61, 310-311, 321-330}), the raw label text being the fallback exactly as
     * {@code label.name()} is upstream.
     * <p>
     * 每个 label 同时保留它的<b>声明名</b>（{@code form.labels} 的 key，上游 {@code label.name()}，也就是
     * {@code submitConfigLabel} 的 {@code labelValue}）与<b>声明序</b>（本循环的序号，上游的 {@code labelIndex}）：
     * 点击时把这一对交给 {@link ConfigActionRuntime}，运行时用声明名取本地 label 程序并核对声明序一致
     * （契约 §5.1；上游服务端在 {@code validCatalogAction} 里逐字比对 {@code labels[index].name}，
     * {@code ControlHandler.java:277-293}）。注意合法的提交值是原始声明名，不是本地化后的显示文本。
     * <p>
     * <b>Declaration order is kept on purpose.</b> Upstream's payload builder sorts a folder model's labels by their
     * text before serializing them ({@code format/parser/ModelInfoBuilder.java:170-178}), but every built-in pack's own
     * language files index those labels by declaration order - measured on {@code wine_fox/08_sta}: form
     * {@code 变更服装.config_forms.2} declares {@code fumo笑, 无语, 0.0, ???, 爱心} and {@code en_us.json} translates
     * labels 0..4 as {@code Fumo Smile, Speechless, 0.0, ???, Heart}. Copying the sort would pair each translation with
     * the wrong label and shift the value-to-index selection with it, so the port keeps the order the pack authored and
     * records the difference instead (see {@code .agent/phase64-model-config-screen-review.md}). The submitted
     * {@code labelIndex} therefore follows declaration order too, which is the order the runtime validates against.
     */
    private static List<Label> resolveLabels(String buttonId, RawYsmModel.ConfigForm form, int formIndex, String locale,
        @Nullable ClientModelMetadataRegistry.Metadata metadata) {
        List<Label> labels = new ArrayList<>(form.labels.size());
        int labelIndex = 0;
        for (Map.Entry<String, String> entry : form.labels.entrySet()) {
            String name = entry.getKey();
            String text = name;
            if (metadata != null && buttonId != null) {
                String resolved = metadata
                    .localized(String.format(CONFIG_LABELS, buttonId, formIndex, labelIndex), locale);
                if (resolved != null) {
                    text = resolved;
                }
            }
            labels.add(new Label(text == null ? "" : text, name == null ? "" : name, labelIndex));
            labelIndex++;
        }
        return labels;
    }

    /**
     * How many radio buttons fit on one line and how wide each is.
     * <p>
     * Upstream derives the count from the longest label it has to draw ({@code AnimationRouletteScreen.java:270-281}:
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
            // (AnimationRouletteScreen.java:290, :343) rounded into this screen's row grid.
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
     * {@code checkbox} a {@link ConfigCheckBox}, a {@code radio} a row of labelled buttons. Each widget is bound to the
     * form's declaration route only ({@code buttonId} + {@code formIndex} + type, plus the label identity for a radio);
     * no MoLang source is compiled, stored or sent here. The initial value comes from the same synchronous read the
     * per-frame refresh uses, exactly where upstream reads it while building the widget
     * ({@code AnimationRouletteScreen.java:238-257}).
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
            double current = readCurrent(resolved);
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
                Double.isFinite(current) ? (float) current : 0f,
                // 上游同理：滑条只在 applyValue() 里提交新值（FlatSlider.java:33-41），widget 参数只是为了接口签名。
                (changed, value) -> ConfigActionRuntime
                    .submit(this.modelId, resolved.buttonId, resolved.formIndex, value));
            this.buttonList.add(slider);
            this.visibleForms.add(VisibleForm.range(resolved, slider));
        } else if ("checkbox".equals(resolved.type)) {
            int id = this.nextWidgetId++;
            double current = readCurrent(resolved);
            ConfigCheckBox checkbox = ConfigCheckBox.forLabel(
                id,
                formsX,
                cursor + 10,
                FORMS_WIDTH,
                this.fontRendererObj.trimStringToWidth(resolved.title, FORMS_WIDTH - RADIO_TEXT_INSET),
                Double.isFinite(current) && current > 0d);
            this.buttonList.add(checkbox);
            this.formRoutes.put(id, new FormRoute(resolved));
            this.visibleForms.add(VisibleForm.checkbox(resolved, checkbox));
        } else if ("radio".equals(resolved.type)) {
            this.captions.add(new Caption(resolved.title, formsX, cursor, 0xFFF3EFE0));
            int selected = radioIndex(readCurrent(resolved), resolved.labels.size());
            List<ConfigCheckBox> radios = new ArrayList<>(resolved.labels.size());
            int index = 0;
            for (Label label : resolved.labels) {
                int id = this.nextWidgetId++;
                int labelX = formsX + resolved.radioStep * (index % resolved.perLine);
                int labelY = cursor + 12 + 20 * (index / resolved.perLine);
                ConfigCheckBox radio = ConfigCheckBox.forLabel(
                    id,
                    labelX,
                    labelY,
                    resolved.radioWidth,
                    this.fontRendererObj.trimStringToWidth(label.text, resolved.radioWidth - RADIO_TEXT_INSET),
                    index == selected);
                radios.add(radio);
                this.buttonList.add(radio);
                index++;
            }
            // 每个单选框点击时提交"该 label 的声明名 + 声明序"；同一组的控件引用用于点击后的乐观翻转
            // （上游 FlatCheckbox 先翻自己的状态再提交，FlatCheckbox.java:38-42）。
            for (Label label : resolved.labels) {
                ConfigCheckBox radio = radios.get(label.index);
                this.formRoutes.put(radio.id, new FormRoute(resolved, radios, label));
            }
            this.visibleForms.add(VisibleForm.radio(resolved, radios));
        }
        addDescriptionHover(resolved, formsX, cursor);
        return cursor + blockHeight(resolved);
    }

    /**
     * Registers a form's description as a hover tooltip, which is where upstream shows it
     * ({@code AnimationRouletteScreen.java:288-292, 358-362, 375-394} call {@code Tooltip.create} on the description
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
        FormRoute route = this.formRoutes.get(button.id);
        if (route == null || !(button instanceof ConfigCheckBox box)) {
            return;
        }
        if (route.label != null && route.radioGroup != null) {
            // 乐观翻转：上游 FlatCheckbox 先翻转自己的状态再提交（FlatCheckbox.java:38-42），
            // 下一次绘制里的同步读回会按真实作用域校正。这里不排任何"待回填"状态。
            for (int i = 0; i < route.radioGroup.size(); i++) {
                route.radioGroup.get(i)
                    .setChecked(i == route.label.index);
            }
            ConfigActionRuntime.submit(
                this.modelId,
                route.form.buttonId,
                route.form.formIndex,
                route.label.name,
                route.label.index);
            return;
        }
        box.doPress();
        ConfigActionRuntime.submit(this.modelId, route.form.buttonId, route.form.formIndex, box.isChecked());
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
        // 每帧同一道守卫：面板开着时玩家换了模型/离开世界，就立即退回上一级，不再向已不存在的模型安装读写
        // （上游每 tick 比对安装代次，AnimationRouletteScreen.java:399-406）。
        if (shouldCloseForModelChange()) {
            closeToParent();
            return;
        }
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

    /**
     * 面板右侧的独立预览（移植特有 UI）：只负责画，不再是读写的 MoLang 上下文。
     * <p>
     * 上游的设置列没有独立预览，它直接读本地玩家自己的 animatable（{@code AnimationRouletteScreen.java:230-258}）；
     * 1.7.10 固定管线的独立 Screen 需要一个画布，所以保留。预览显示的变量由
     * {@code CustomGuiPlayerEntity#getMolangVariables} 的 read-through 提供（契约 §5.4，由 task-4 接线），
     * 数值仍然是本地玩家真实 (entity, model) 作用域的值。
     */
    private void renderFormPreview() {
        if (this.mc == null || this.mc.thePlayer == null || this.formPreview == null) {
            return;
        }
        ResourceLocation texture = CustomPlayerModel.resolveTexture(
            this.modelId,
            ClientModelManager.MODELS.get(this.modelId),
            null,
            ClientModelManager.DECLARED_DEFAULT_TEXTURES.get(this.modelId));
        // The panel is 520 design pixels wide and the preview sits at +470; a window whose scaled width is below that
        // would otherwise draw the model off the right edge (see PANEL_WIDTH). Clamp it back into the window.
        int previewX = this.panelX + PREVIEW_X_OFFSET;
        if (previewX + PREVIEW_RIGHT_MARGIN > this.width) {
            previewX = Math.max(0, this.width - PREVIEW_RIGHT_MARGIN);
        }
        int previewY = this.panelY + PREVIEW_Y_OFFSET;
        if (previewY > this.height - 40) {
            previewY = Math.max(40, this.height - 40);
        }
        RenderUtil.renderFormPreview(
            previewX,
            previewY,
            45,
            this.mc.thePlayer,
            this.modelId,
            texture,
            this.formPreview);
    }

    /**
     * 从本地玩家真实 (entity, model) 作用域同步读一个表单的当前值；{@code NaN} 表示运行时不可用（模型未安装/无程序）。
     * <p>
     * 程序由 {@link ConfigActionRuntime} 现场拼装并按源码缓存编译结果，本屏既不保存也不重编译任何 MoLang 源码。
     */
    private double readCurrent(ResolvedForm resolved) {
        return ConfigActionRuntime.read(this.modelId, resolved.buttonId, resolved.formIndex);
    }

    /**
     * 从本地玩家真实作用域读回 radio 的当前勾选；checkbox/range 不在每帧重读。
     * <p>
     * <b>为什么只有 radio 每帧读。</b>上游建控件时同步读一次所有 form
     * （{@code AnimationRouletteScreen.java:238-257}），之后只有 radio 在每 tick 重新读并重设勾选
     * （{@code :297-306,399-406}）；checkbox/range 的控件状态是用户自己的选择，服务端回执行完写程序后回执值与用户点下
     * 去的值相同，所以不需要（也不应该）每帧覆盖：发送端照上游不本地执行，若每帧用回执前的旧值覆盖，控件会在一个 RTT
     * 内先弹回旧值（{@code .agent/phase78-review.md} RK-1）。radio 必须每帧读，因为它要表达"当前选中第几个"而不是
     * 一次点击。
     * <p>
     * 读到 {@code NaN}（运行时不可用）时保持控件当前显示值。
     */
    private void refreshConfigValues() {
        for (VisibleForm visible : this.visibleForms) {
            if (visible.radios == null) {
                continue;
            }
            double value = readCurrent(visible.form);
            if (!Double.isFinite(value)) {
                continue;
            }
            int selected = radioIndex(value, visible.radios.size());
            for (int i = 0; i < visible.radios.size(); i++) {
                visible.radios.get(i)
                    .setChecked(i == selected);
            }
        }
    }

    /**
     * The radio index a read value selects: upstream rounds the read float and clamps an out-of-range index to the
     * first label ({@code AnimationRouletteScreen.java:262-266}).
     * <p>
     * 移植沿用旧实现的口径：{@code NaN} 或越界返回 -1（该组一个都不勾选），因为移植的读程序在运行时不可用时返回
     * {@code NaN}，不能把它当成"选中 0 号"。
     */
    static int radioIndex(double value, int labelCount) {
        if (!Double.isFinite(value)) return -1;
        long index = Math.round(value);
        return index >= 0 && index < labelCount ? (int) index : -1;
    }

    /**
     * Upstream's roulette does not pause the game while the settings column is open
     * ({@code client/gui/AnimationRouletteScreen.java:702-705}, {@code isPauseScreen()} returns {@code false}), and
     * every other screen of this port's model GUI chain answers {@code false} too
     * ({@code PlayerModelScreen.java:560}, {@code client/gui/AnimationRouletteScreen.java:481}). Pausing would freeze
     * the world the model is being configured in and, in single player, hold the integrated server so the
     * server-authoritative config-action/roaming echo cannot arrive until the panel is closed.
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        this.visibleForms.clear();
        this.formRoutes.clear();
        this.hoverTips.clear();
        this.resolvedForms = Collections.emptyList();
        this.formPageOfForm = new int[0];
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

        /** The pack's declared button id this form belongs to; part of the (buttonId, formIndex) read/write route. */
        private final String buttonId;
        /** Declaration index of this form inside its button; the other half of the read/write route. */
        private final int formIndex;
        private final RawYsmModel.ConfigForm form;
        private final String type;
        private final String title;
        private final String description;
        private final boolean unsupported;
        private final List<Label> labels;
        private final int perLine;
        private final int radioStep;
        private final int radioWidth;

        private ResolvedForm(String buttonId, int formIndex, RawYsmModel.ConfigForm form, String type, String title,
            String description, boolean unsupported, List<Label> labels, int perLine, int radioStep, int radioWidth) {
            this.buttonId = buttonId;
            this.formIndex = formIndex;
            this.form = form;
            this.type = type;
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.unsupported = unsupported;
            this.labels = labels;
            this.perLine = perLine;
            this.radioStep = radioStep;
            this.radioWidth = radioWidth;
        }
    }

    /** One radio label: the text to draw plus the declared name and declaration index a click submits. */
    private static final class Label {

        private final String text;
        /** {@code form.labels}'s key, i.e. upstream's {@code label.name()} (the unlocalized declared name). */
        private final String name;
        /** Declaration position in {@code form.labels}, i.e. upstream's {@code labelIndex}. */
        private final int index;

        private Label(String text, String name, int index) {
            this.text = text;
            this.name = name;
            this.index = index;
        }
    }

    /**
     * A widget's route back to the declaration it edits: only {@code buttonId} + {@code formIndex} + type (plus, for a
     * radio, the clicked label's declared identity). No MoLang source is kept here.
     */
    private static final class FormRoute {

        private final ResolvedForm form;
        /** Non-null only for a radio widget: the rest of its group, for the optimistic flip. */
        @Nullable
        private final List<ConfigCheckBox> radioGroup;
        /** Non-null only for a radio widget: which label this widget stands for. */
        @Nullable
        private final Label label;

        private FormRoute(ResolvedForm form) {
            this(form, null, null);
        }

        private FormRoute(ResolvedForm form, @Nullable List<ConfigCheckBox> radioGroup, @Nullable Label label) {
            this.form = form;
            this.radioGroup = radioGroup;
            this.label = label;
        }
    }

    /**
     * One form of the current page together with the widget(s) drawing it: {@link #refreshConfigValues()} uses this to
     * write the value read from the real scope back into the widget. Exactly one widget field is non-null.
     */
    private static final class VisibleForm {

        private final ResolvedForm form;
        @Nullable
        private final FlatSlider slider;
        @Nullable
        private final ConfigCheckBox checkbox;
        @Nullable
        private final List<ConfigCheckBox> radios;

        private VisibleForm(ResolvedForm form, @Nullable FlatSlider slider, @Nullable ConfigCheckBox checkbox,
            @Nullable List<ConfigCheckBox> radios) {
            this.form = form;
            this.slider = slider;
            this.checkbox = checkbox;
            this.radios = radios;
        }

        private static VisibleForm range(ResolvedForm form, FlatSlider slider) {
            return new VisibleForm(form, slider, null, null);
        }

        private static VisibleForm checkbox(ResolvedForm form, ConfigCheckBox checkbox) {
            return new VisibleForm(form, null, checkbox, null);
        }

        private static VisibleForm radio(ResolvedForm form, List<ConfigCheckBox> radios) {
            return new VisibleForm(form, null, null, radios);
        }
    }
}
