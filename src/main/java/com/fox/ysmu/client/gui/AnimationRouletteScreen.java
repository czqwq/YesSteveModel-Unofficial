package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fox.ysmu.api.EntityAnimationApi;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.input.ExtraAnimationKey;
import com.fox.ysmu.Config;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetPlayAnimation;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.*;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * The extra-animation wheel: eight sectors around the screen centre, one per {@link ExtraAnimationKey} binding.
 *
 * <h3>What a sector means</h3>
 * The wheel is driven by the pack's ordered {@code properties.extra_animation} map (its <em>key</em> is the animation
 * name or a {@code '#'} marker, its <em>value</em> is the label or a {@code '#'} reference to a settings button), which
 * the sync path files in {@link ModelConfigRegistry#wheelEntriesFor}. A click dispatches on both halves, exactly as
 * upstream does in its {@code mouseClicked} ({@code tmp/YesSteveModel-dev-1.20/.../client/gui/
 * AnimationRouletteScreen.java:582-608}):
 * <ul>
 *   <li><b>key {@code "#return"}</b> - leave the current sub-page (right-click and ESC do the same here; upstream
 *       needs its own entry or its Return button, {@code :686-694}).</li>
 *   <li><b>key starts with {@code '#'}</b> - descend into the sub-page of that {@code extra_animation_classify} id
 *       ({@code clickClassify}, {@code :669-684}; nesting is capped at {@link #MAX_SUBMENU_DEPTH}, upstream's own
 *       "最多让你套 5 层"). A marker is not an animation and is never sent as a play request.</li>
 *   <li><b>value starts with {@code '#'}</b> - the entry points at a settings button, and the <em>inner</em> ring opens
 *       that button's panel ({@code clickConfig}, {@code :638-643}). Upstream draws the panel as a column of the same
 *       screen; this port's settings panel is its own screen, so the route lands on {@link ModelConfigScreen} opened at
 *       that button. The outer ring still plays the key, which is why LaPluma's {@code Bird} can both be played and
 *       open {@code #鸦之配置}.</li>
 *   <li><b>anything else</b> - play the key by name ({@code clickDefault}, {@code :645-667}).</li>
 * </ul>
 *
 * <h3>What this port does differently</h3>
 * <ul>
 *   <li><b>Paging.</b> Upstream pages eight entries at a time ({@code MAX_ROULETTE_COUNT}, {@code pageUp/pageDown},
 *       {@code :562-571}); so does this wheel (scroll wheel, page indicator), because a sub-page may declare more than
 *       eight entries (LaPluma's {@code §5默认动作} declares 22) and the rest would be unreachable otherwise.</li>
 *   <li><b>No lock / stop / return buttons and no config column.</b> Upstream adds a movement-lock button, a stop
 *       button, a Return button and a scrolling config column ({@code :175-235}). The port has no movement lock, its
 *       stop path is the existing "move key" handler, and its settings panel is {@link ModelConfigScreen}; porting
 *       those three would mean inventing the features behind them.</li>
 *   <li><b>The label.</b> Element {@code i} of the baked flat array
 *       ({@code ClientModelManager.EXTRA_ANIMATION_NAME}, written by {@code RawYsmModelAdapter.getExtraAnimationNames})
 *       is the language-resolved label of entry {@code i} - both are in declaration order - so it wins when present;
 *       the value-derived label is the fallback for sub-pages and for a model with no rich table.</li>
 * </ul>
 */
public class AnimationRouletteScreen extends GuiScreen {

    /** 轮盘扇区数，必须与 ExtraAnimationKey.EXTRA_ANIMATION_KEYS 的长度一致。 */
    private static final int KEY_COUNT = 8;
    /** A <em>key</em> starting with this names a sub-page; a <em>value</em> starting with it names a config button. */
    private static final String SPEC_PREFIX = "#";
    /** The entry that leaves the current sub-page (upstream's {@code SPEC_RETURN}). */
    private static final String SPEC_RETURN = "#return";
    /** How deep sub-pages may nest (upstream {@code :669-671}). */
    private static final int MAX_SUBMENU_DEPTH = 5;
    /** Outer ring hit box (play / descend / return) and inner ring hit box (open a settings panel). */
    private static final int OUTER_MIN_RADIUS = 50;
    private static final int OUTER_MAX_RADIUS = 100;
    private static final int INNER_MIN_RADIUS = 20;
    private static final int INNER_MAX_RADIUS = 50;

    private int x;
    private int y;
    /** Outer-ring selection: the index within the current page, or -1. */
    private int selectId = -1;
    /** Inner-ring selection: the index within the current page of the config entry under the mouse, or -1. */
    private int configId = -1;
    /** The model whose wheel this screen shows; see {@link #resolveNames}. */
    private ResourceLocation modelId;
    private String[] names;
    /** CU-16: 按键提示文本只在 initGui 时构造一次，避免每帧重复建 ChatComponent 并格式化。 */
    private String[] keyTexts = new String[KEY_COUNT];
    /** The entries of the level being shown: key = animation name / '#' marker, value = label / '#buttonId'. */
    private final Map<String, String> entries = new LinkedHashMap<>();
    /** The same entries as a list, rebuilt only when {@link #entries} changes. */
    private List<Map.Entry<String, String>> entryList = Collections.emptyList();
    /** The sub-page ids the user descended through, outermost first; empty at the root. */
    private final List<String> navigation = new ArrayList<>();
    /** Which page of {@link #entryList} is shown; eight entries per page. */
    private int page;

    public AnimationRouletteScreen() {}

    /**
     * Opens the wheel already inside {@code classifyId}'s sub-page, which is what a key binding whose root entry is a
     * {@code '#'} marker needs: upstream turns that key into "open the roulette there" through a static seed plus
     * {@code addRootClassify} ({@code client/input/ExtraAnimationKey.java:68-79}); the port passes the same fact
     * through the constructor so two wheels can never inherit each other's path.
     */
    public AnimationRouletteScreen(@Nullable String classifyId) {
        if (classifyId != null && !classifyId.isEmpty()) {
            this.navigation.add(classifyId);
        }
    }

    @Override
    public void initGui() {
        this.x = width / 2;
        this.y = height / 2 - 8;
        this.buildKeyTexts();

        if (mc != null && mc.thePlayer != null) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
            if (eep != null) {
                ResourceLocation modelId = eep.getModelId();
                // Ask for the model's geometry. This only *queues* the build - the table the labels come from is
                // filled when the build is published on a later tick - which is why the labels are re-resolved while
                // the screen is open rather than captured once here; see resolveNames.
                ClientModelManager.ensureGeometry(modelId);
                this.modelId = modelId;
                resolveNames();
                this.setEntries(this.entriesForLevel());
                if (Config.DEBUG_WHEEL) {
                    dumpWheelData(modelId);
                }
            }
        }
    }

    /**
     * Re-reads the model's extra-animation labels, which arrive asynchronously.
     * <p>
     * The table is written when the model is published, and this screen can be opened before that has happened - the
     * first open after a join is exactly that case. Upstream has no such gap: the labels are part of the model's
     * manifest, which its catalog holds before the model is ever drawn. The port keeps them in the geometry payload
     * and parses that payload on demand, so the equivalent is to ask again once the payload has landed, which is what
     * the tooltip and the information screen already do by re-reading per frame.
     */
    private void resolveNames() {
        if (this.modelId == null) {
            return;
        }
        String[] resolved = ClientModelManager.EXTRA_ANIMATION_NAME.get(ModelIdUtil.getMainId(this.modelId));
        if (resolved != null) {
            this.names = resolved;
        }
    }

    /**
     * The wheel's table for one model: the declared {@code extra_animation} entries when the payload carried them,
     * else the baked flat labels mapped onto the legacy slot keys.
     * <p>
     * Static because {@code ExtraAnimationKey} resolves a key binding without opening this screen. The fallback is the
     * reference's {@code flatToEntries} (its {@code client/gui/AnimationRouletteScreen.java:125-133}): those keys are
     * the animation names the play path uses ({@code extra0..extra7}), so a legacy model keeps working unchanged.
     */
    public static Map<String, String> wheelEntriesFor(@Nullable ResourceLocation modelId) {
        Map<String, String> declared = ModelConfigRegistry.wheelEntriesFor(modelId);
        if (!declared.isEmpty()) {
            return declared;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        if (modelId == null) {
            return entries;
        }
        String[] flat = ClientModelManager.EXTRA_ANIMATION_NAME.get(ModelIdUtil.getMainId(modelId));
        if (flat != null) {
            // One entry per slot, gaps included: the legacy shape is indexed by {@code extra<i>}, so compacting the
            // array would move a sparse pack's sectors (and its key bindings) off the slot each key means. A gap's
            // label is blank and {@link #labelFor} falls back to the key.
            for (int i = 0; i < flat.length; i++) {
                entries.put("extra" + i, flat[i] == null ? "" : flat[i]);
            }
        }
        return entries;
    }

    /** The entries of the level {@link #navigation} names; an id that no longer resolves drops out of the path. */
    private Map<String, String> entriesForLevel() {
        while (!this.navigation.isEmpty()) {
            String classifyId = this.navigation.get(this.navigation.size() - 1);
            RawYsmModel.ExtraAnimationClassify classify = ModelConfigRegistry.classifyFor(this.modelId, classifyId);
            if (classify != null) {
                return classify.extras;
            }
            this.navigation.remove(this.navigation.size() - 1);
        }
        return wheelEntriesFor(this.modelId);
    }

    /** Replaces the shown level and returns to its first page. */
    private void setEntries(Map<String, String> level) {
        this.entries.clear();
        if (level != null) {
            this.entries.putAll(level);
        }
        this.entryList = new ArrayList<>(this.entries.entrySet());
        this.page = 0;
        this.selectId = -1;
        this.configId = -1;
    }

    private int pageCount() {
        return Math.max(1, (this.entryList.size() + KEY_COUNT - 1) / KEY_COUNT);
    }

    /** The entries of the page being shown; at most {@link #KEY_COUNT}. */
    private List<Map.Entry<String, String>> pageEntries() {
        int from = Math.min(this.page * KEY_COUNT, this.entryList.size());
        int to = Math.min(from + KEY_COUNT, this.entryList.size());
        return this.entryList.subList(from, to);
    }

    /** Whether an entry's value references a settings button this model declares ("#坐标/大小"). */
    private boolean isConfigEntry(@Nullable String value) {
        return value != null && value.startsWith(SPEC_PREFIX)
            && ModelConfigRegistry.buttonFor(this.modelId, value.substring(SPEC_PREFIX.length())) != null;
    }

    /**
     * The label of one entry: the baked flat label for this index when there is one (it is the pack's language file
     * value for {@code properties.extra_animation.<key>}, resolved at bake time - the client does not keep the pack's
     * language files), else the value - with a {@code '#'} reference to a declared button shown as that button's own
     * name, which is what upstream substitutes before its language lookup
     * ({@code AnimationRouletteScreen.java:726-736}) - else the key.
     */
    private String labelFor(int index, String key, @Nullable String value) {
        if (this.navigation.isEmpty() && this.names != null && index < this.names.length
            && StringUtils.isNoneBlank(this.names[index])) {
            return this.names[index];
        }
        if (StringUtils.isNotBlank(value)) {
            if (value.startsWith(SPEC_PREFIX)) {
                RawYsmModel.ExtraAnimationButton button =
                    ModelConfigRegistry.buttonFor(this.modelId, value.substring(SPEC_PREFIX.length()));
                if (button != null && StringUtils.isNotBlank(button.name)) {
                    return button.name;
                }
            }
            return value;
        }
        return key;
    }

    /**
     * 行 47 DEBUG_WHEEL：转储本仓轮盘真正持有的数据（参考分支 client/gui/AnimationRouletteScreen.java:75-110）。
     * <p>
     * 参考分支转储的是它的 {@code ExtraWheelData}（entries / classifies / configButtons 三张表）。本仓现在有同样的三份
     * 数据：根表与子表来自 {@link ModelConfigRegistry#wheelEntriesFor} / {@link ModelConfigRegistry#classifiesFor}
     * （同步载荷里的 key+value 成对数据），按钮来自 {@link ModelConfigRegistry#buttonsFor}。前缀与参考分支一致
     * （{@code [YSMU-WHEEL]}），页号按参考分支的 {@code idx / ITEMS_PER_PAGE}（本类 {@link #KEY_COUNT}）计算。
     * 条目类型照参考分支的三分法标出：{@code [SUB]} = 子菜单（key 以 {@code #} 开头）、{@code [CONFIG]} = 内圈配置入口
     * （value 以 {@code #} 开头且指向一个已声明的按钮）、{@code [ANIM]} = 直接播放。
     * <p>
     * 只在 {@link #initGui} 里调用一次：扁平标签表（{@code EXTRA_ANIMATION_NAME}）是模型发布时才写入的
     * （{@link #resolveNames} 的异步间隙），所以 legacy 兜底那一段可能显示 0 个扇区而按钮/分类已在；参考分支没有这个
     * 间隙（清单在模型绘制之前就存在）。根表来自同步载荷，没有这个间隙。
     */
    private void dumpWheelData(ResourceLocation modelId) {
        Map<String, String> wheel = ModelConfigRegistry.wheelEntriesFor(modelId);
        ysmu.LOG.info("[YSMU-WHEEL] === Wheel Data for {} ===", modelId);
        ysmu.LOG.info("[YSMU-WHEEL] Total entries: {} ({} pages)", wheel.size(),
            (wheel.size() + KEY_COUNT - 1) / KEY_COUNT);
        int index = 0;
        for (Map.Entry<String, String> entry : wheel.entrySet()) {
            String marker = entry.getKey().startsWith(SPEC_PREFIX) ? "[SUB]"
                : isConfigEntry(entry.getValue()) ? "[CONFIG]" : "[ANIM]";
            ysmu.LOG.info("[YSMU-WHEEL]   page={} {} key='{}' label='{}'", index / KEY_COUNT, marker, entry.getKey(),
                labelFor(index, entry.getKey(), entry.getValue()));
            index++;
        }
        if (wheel.isEmpty() && this.names != null) {
            // The legacy-channel fallback: no rich table, only the baked labels mapped onto the slot keys.
            for (int i = 0; i < this.names.length; i++) {
                if (StringUtils.isNotBlank(this.names[i])) {
                    ysmu.LOG
                        .info("[YSMU-WHEEL]   page={} [ANIM] key='extra{}' label='{}'", i / KEY_COUNT, i, this.names[i]);
                }
            }
        }

        List<RawYsmModel.ExtraAnimationClassify> classifies = ModelConfigRegistry.classifiesFor(modelId);
        if (!classifies.isEmpty()) {
            ysmu.LOG.info("[YSMU-WHEEL] === Sub-pages ({} total) ===", classifies.size());
            for (RawYsmModel.ExtraAnimationClassify classify : classifies) {
                ysmu.LOG.info("[YSMU-WHEEL]   Classify '{}': {} entries", classify.id, classify.extras.size());
                for (Map.Entry<String, String> entry : classify.extras.entrySet()) {
                    ysmu.LOG.info("[YSMU-WHEEL]     key='{}' label='{}'", entry.getKey(), entry.getValue());
                }
            }
        }

        List<RawYsmModel.ExtraAnimationButton> buttons = ModelConfigRegistry.buttonsFor(modelId);
        if (!buttons.isEmpty()) {
            ysmu.LOG.info("[YSMU-WHEEL] === Config Buttons ({} total) ===", buttons.size());
            for (RawYsmModel.ExtraAnimationButton button : buttons) {
                ysmu.LOG.info("[YSMU-WHEEL]   btn id='{}' name='{}' desc='{}' forms={}", button.id, button.name,
                    button.description, button.forms.size());
                for (int i = 0; i < button.forms.size(); i++) {
                    RawYsmModel.ConfigForm form = button.forms.get(i);
                    ysmu.LOG.info(
                        "[YSMU-WHEEL]     form[{}] type='{}' title='{}' desc='{}' default='{}' min={} max={} step={} labels={}",
                        i, form.type, form.title, form.description, form.defaultValue, form.min, form.max, form.step,
                        form.labels);
                }
            }
        }
        ysmu.LOG.info("[YSMU-WHEEL] === End Wheel Dump ===");
    }

    private void buildKeyTexts() {
        for (int i = 0; i < KEY_COUNT; i++) {
            if (i >= ExtraAnimationKey.EXTRA_ANIMATION_KEYS.size()) {
                this.keyTexts[i] = "";
                continue;
            }
            ChatComponentText keyText = new ChatComponentText("[ ");
            keyText.getChatStyle()
                .setColor(EnumChatFormatting.YELLOW);
            KeyBinding keyMapping = ExtraAnimationKey.EXTRA_ANIMATION_KEYS.get(i);
            if (keyMapping.getKeyCode() == Keyboard.KEY_NONE) {
                keyText.appendSibling(new ChatComponentTranslation("key.yes_steve_model.extra_animation.none"));
            } else {
                String keyName = Keyboard.getKeyName(keyMapping.getKeyCode());
                keyText.appendSibling(new ChatComponentText(keyName));
            }
            keyText.appendSibling(new ChatComponentText(" ]"));
            this.keyTexts[i] = keyText.getFormattedText();
        }
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        // The labels arrive with the model's publish, which can happen while this screen is open; asking again here
        // costs one map lookup and means a wheel opened during the build fills in rather than staying bare.
        resolveNames();
        drawRoulette(pMouseX, pMouseY);
        drawRouletteText();
        drawPageText();
    }

    @Override
    protected void mouseClicked(int pMouseX, int pMouseY, int pButton) {
        // Right-click is the port's "back" idiom (PlayerModelScreen uses it to leave a folder): it leaves a sub-page
        // and closes the wheel at the root. Upstream needs a "#return" entry or its Return button for the same thing
        // (AnimationRouletteScreen.java:686-694), and LaPluma ships neither - only a hint entry.
        if (pButton == 1) {
            this.goBack();
            return;
        }
        // The inner ring wins when the mouse is over it: an entry can both be playable and open a panel (LaPluma's
        // Bird -> #鸦之配置), which is exactly why upstream splits the two rings.
        if (this.configId >= 0) {
            this.openConfig(this.configId);
            return;
        }
        if (this.selectId >= 0) {
            this.activate(this.selectId);
            return;
        }
        super.mouseClicked(pMouseX, pMouseY, pButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // ESC leaves a sub-page first and only closes the wheel at the root, so a mis-click into a sub-page is never a
        // dead end (1.7.10's GuiScreen.keyTyped closes the screen on ESC).
        if (keyCode == Keyboard.KEY_ESCAPE && !this.navigation.isEmpty()) {
            this.goBack();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getDWheel();
        if (dWheel == 0 || this.pageCount() <= 1) {
            return;
        }
        // Upstream pages the wheel from the scroll wheel when the pointer is over its left half
        // (AnimationRouletteScreen.java:540-560); this wheel is the whole screen, so any scroll pages it.
        if (dWheel > 0) {
            this.pageUp();
        } else {
            this.pageDown();
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void pageUp() {
        if (this.page > 0) {
            this.page--;
        }
    }

    private void pageDown() {
        if (this.page < this.pageCount() - 1) {
            this.page++;
        }
    }

    /** What clicking a sector does: the same dispatch a key binding performs on that entry's key. */
    private void activate(int index) {
        List<Map.Entry<String, String>> entries = pageEntries();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        this.playClickSound();
        this.dispatch(entries.get(index).getKey());
    }

    /**
     * The three-way dispatch on an entry's <em>key</em>, shared by the click path and the key bindings
     * (upstream {@code mouseClicked:582-596} and {@code ExtraAnimationKey:66-83}).
     */
    private void dispatch(String key) {
        if (SPEC_RETURN.equals(key)) {
            this.goBack();
        } else if (key.startsWith(SPEC_PREFIX)) {
            // A sub-page marker names a category, not an animation: it must never be sent as a play request (the
            // server would park a name no model declares in the EEP). Descend instead.
            this.enterClassify(key.substring(SPEC_PREFIX.length()));
        } else {
            this.playEntry(key);
        }
    }

    private void enterClassify(String classifyId) {
        RawYsmModel.ExtraAnimationClassify classify = ModelConfigRegistry.classifyFor(this.modelId, classifyId);
        if (classify == null) {
            ysmu.LOG.info("[YSMU-WHEEL] {} declares no sub-page '{}'", this.modelId, classifyId);
            return;
        }
        if (this.navigation.size() >= MAX_SUBMENU_DEPTH) {
            // Upstream's own bound and message (AnimationRouletteScreen.java:669-676).
            if (this.mc.thePlayer != null) {
                this.mc.thePlayer.addChatMessage(new ChatComponentTranslation("gui.yes_steve_model.roulette.too_long"));
            }
            return;
        }
        this.navigation.add(classifyId);
        this.setEntries(classify.extras);
    }

    /** Leaves one sub-page, or closes the wheel when there is none to leave. */
    private void goBack() {
        if (this.navigation.isEmpty()) {
            this.mc.displayGuiScreen(null);
            return;
        }
        this.navigation.remove(this.navigation.size() - 1);
        this.setEntries(this.entriesForLevel());
    }

    /** Opens the settings panel of the button an entry's <em>value</em> references ("#坐标/大小"). */
    private void openConfig(int index) {
        List<Map.Entry<String, String>> entries = pageEntries();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        String value = entries.get(index).getValue();
        if (value == null || !value.startsWith(SPEC_PREFIX)) {
            return;
        }
        String buttonId = value.substring(SPEC_PREFIX.length());
        if (ModelConfigRegistry.buttonFor(this.modelId, buttonId) == null) {
            return;
        }
        this.playClickSound();
        // Upstream opens the same forms as a column of the roulette (its clickConfig, :638-643); this port's settings
        // panel is its own screen, so the route lands there - on the button that was clicked, which is what
        // ModelConfigScreen's button-id argument is for.
        this.mc.displayGuiScreen(new ModelConfigScreen(this, this.modelId, buttonId));
    }

    /** Sends a play request for one animation name, after the client-side existence gate. */
    private void playEntry(String name) {
        if (!sendPlayRequest(this.mc, this.modelId, name)) {
            return;
        }
        if (this.mc.thePlayer != null && Config.PRINT_ANIMATION_ROULETTE_MSG) {
            // CU-01: 1.7.10 的 ChatComponentTranslation 只支持 %s/%<n>$s，%d 会在聊天栏渲染时抛
            // ChatComponentTranslationFormatException（客户端崩溃），因此这里传字符串。
            this.mc.thePlayer.addChatMessage(
                new ChatComponentTranslation("message.yes_steve_model.model.animation_roulette.play", name));
        }
        this.mc.displayGuiScreen(null);
    }

    /**
     * The client-side half of the "a name no model declares must not reach the EEP" gate.
     * <p>
     * The EEP is broadcast to every tracking client and each of them asks the engine to play whatever it holds, every
     * frame; a name the model does not declare makes {@code AnimationController#setAnimation} take its early return
     * and print "Could not load animation: &lt;name&gt;. Is it missing?" once per frame - the defect class this port
     * has already fixed twice (see {@code AnimationManager#playParallelIfPresent} and
     * {@code AnimationManager#playPreviewChannel}). {@link EntityAnimationApi#modelHasClip} asks the same existence
     * question against the animation file the renderer will actually read, including the built-in default fallback, so
     * a name that would be dropped is never sent.
     *
     * @return {@code true} when the request was sent.
     */
    static boolean sendPlayRequest(Minecraft mc, @Nullable ResourceLocation modelId, String name) {
        if (mc == null || mc.thePlayer == null || StringUtils.isBlank(name) || name.startsWith(SPEC_PREFIX)) {
            return false;
        }
        if (!EntityAnimationApi.modelHasClip(mc.thePlayer, name)) {
            ysmu.LOG.info("[YSMU-WHEEL] {} does not declare '{}'; not sending it", modelId, name);
            return false;
        }
        NetworkHandler.CHANNEL.sendToServer(new SetPlayAnimation(name));
        return true;
    }

    /**
     * The key bindings' entry point: plays the {@code slot}-th <b>root</b> wheel entry of the local player's model,
     * with the same three-way dispatch as a click on that sector.
     * <p>
     * Upstream does exactly this from the same table ({@code client/input/ExtraAnimationKey.java:58-85}): a
     * {@code "#return"} entry stops the animation, a {@code "#<classifyId>"} entry opens the roulette inside that
     * sub-page, and anything else is played by name. The port used to send the slot index and let the server spell
     * {@code "extra" + index}, which only ever reached legacy packs.
     */
    public static void activateRootSlot(int slot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || slot < 0) {
            return;
        }
        ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
        ResourceLocation modelId = eep == null ? null : eep.getModelId();
        List<Map.Entry<String, String>> entries = new ArrayList<>(wheelEntriesFor(modelId).entrySet());
        if (slot >= entries.size()) {
            return;
        }
        String key = entries.get(slot).getKey();
        if (SPEC_RETURN.equals(key)) {
            if (eep != null && eep.isPlayAnimation()) {
                NetworkHandler.CHANNEL.sendToServer(SetPlayAnimation.stop());
            }
            return;
        }
        if (key.startsWith(SPEC_PREFIX)) {
            mc.displayGuiScreen(new AnimationRouletteScreen(key.substring(SPEC_PREFIX.length())));
            return;
        }
        sendPlayRequest(mc, modelId, key);
    }

    private void playClickSound() {
        if (this.mc != null) {
            this.mc.getSoundHandler()
                .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
        }
    }

    /** Page indicator below the wheel; upstream draws its own above it ({@code :533-537}). */
    private void drawPageText() {
        int pages = pageCount();
        if (pages <= 1) {
            return;
        }
        this.drawCenteredString(this.fontRendererObj, (this.page + 1) + "/" + pages, x, y + 112, 0xAAAAAA);
    }

    private void drawRouletteText() {
        List<Map.Entry<String, String>> entries = pageEntries();
        int count = KEY_COUNT;
        float startDeg = (float) (Math.PI / count);
        for (int i = 0; i < count; i++) {
            int r = 65;

            // CU-16: 按键文本在 initGui 已缓存，这里不再每帧构造 ChatComponent。
            String keyText = i < this.keyTexts.length && this.keyTexts[i] != null ? this.keyTexts[i] : "";
            int textX = (int) (x + r * MathHelper.cos(startDeg));
            int textY = (int) (y + r * MathHelper.sin(startDeg) - (float) this.fontRendererObj.FONT_HEIGHT / 2);
            if (i < entries.size()) {
                Map.Entry<String, String> entry = entries.get(i);
                int index = this.page * KEY_COUNT + i;
                int color = entry.getKey().startsWith(SPEC_PREFIX) ? 0xFF5555
                    : isConfigEntry(entry.getValue()) ? 0x55FFFF : 0xF3EFE0;
                drawLabelLines(labelFor(index, entry.getKey(), entry.getValue()), textX, textY, color);
                // Key-binding hints belong to the root page only: a sub-page entry has no binding of its own
                // (upstream: AnimationRouletteScreen.java:751-755).
                if (this.page == 0 && this.navigation.isEmpty()) {
                    this.drawCenteredString(fontRendererObj, keyText, textX, textY + 4, 0xF3EFE0);
                }
            } else if (this.entryList.isEmpty()) {
                // No table at all (a model whose payload declares no extra_animation, or one that has not been
                // published yet): keep the bare wheel this screen always drew, with the slot number in each sector.
                this.drawCenteredString(fontRendererObj, String.valueOf(i), textX, textY - 8, 0xF3EFE0);
            }
            startDeg = (float) (startDeg + 2 * Math.PI / count);
        }
    }

    /**
     * Draws a sector label, split at 50px like upstream's {@code drawAnimationName}
     * ({@code AnimationRouletteScreen.java:772-791}) so a long label (LaPluma's
     * {@code 以防你不知道右边有个返回}) does not run over its neighbours.
     */
    private void drawLabelLines(String label, int textX, int textY, int color) {
        List<String> lines = this.fontRendererObj.listFormattedStringToWidth(label, 50);
        int lineHeight = this.fontRendererObj.FONT_HEIGHT;
        int firstY = textY - 8 - (lines.size() - 1) * lineHeight;
        for (int i = 0; i < lines.size(); i++) {
            this.drawCenteredString(this.fontRendererObj, lines.get(i), textX, firstY + i * lineHeight, color);
        }
    }

    private void drawRoulette(int mouseX, int mouseY) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;

        int count = KEY_COUNT;
        List<Map.Entry<String, String>> entries = pageEntries();
        float theta = (float) Math.atan2(mouseY - y, mouseX - x);
        if (theta < 0) {
            theta = (float) (Math.PI * 2 + theta);
        }
        float distance = MathHelper.sqrt_float((mouseY - y) * (mouseY - y) + (mouseX - x) * (mouseX - x));
        boolean isSelected = false;
        boolean isConfigSelected = false;
        for (int i = 0; i < count; i++) {
            float spacingDeg = (float) (Math.PI / 90);
            float startDeg = (float) ((2 * Math.PI / count) * i + spacingDeg);
            float endDeg = (float) ((2 * Math.PI / count) * (i + 1) - spacingDeg);
            boolean inSector = startDeg < theta && theta < endDeg;
            // Outer ring (50..100): play / descend / return. Inner ring (20..50): only for an entry whose value
            // references a declared settings button - upstream's split (AnimationRouletteScreen.java:816-835).
            boolean configEntry = i < entries.size() && isConfigEntry(entries.get(i).getValue());
            if (inSector && OUTER_MIN_RADIUS < distance && distance < OUTER_MAX_RADIUS && i < entries.size()) {
                drawFan(tessellator, 25, 105, startDeg, endDeg, 0xf0FFB100);
                isSelected = true;
                this.selectId = i;
            } else {
                drawFan(tessellator, 25, 105, startDeg, endDeg, 0x90000000);
            }
            if (configEntry) {
                if (inSector && INNER_MIN_RADIUS < distance && distance < INNER_MAX_RADIUS) {
                    drawFan(tessellator, 15, 50, startDeg, endDeg, 0xf000ceff);
                    isConfigSelected = true;
                    this.configId = i;
                } else {
                    drawFan(tessellator, 20, 50, startDeg, endDeg, 0x7000ceff);
                }
            }
        }
        if (!isSelected) {
            this.selectId = -1;
        }
        if (!isConfigSelected) {
            this.configId = -1;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private void drawFan(Tessellator tessellator, float rIn, float rOut, float startDeg, float endDeg, int color) {
        float alpha = (color >> 24 & 255) / 255.0F;
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        tessellator.startDrawing(GL11.GL_QUADS);
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        tessellator.addVertex(x + rOut * MathHelper.cos(startDeg), y + rOut * MathHelper.sin(startDeg), 0);
        tessellator.addVertex(x + rIn * MathHelper.cos(startDeg), y + rIn * MathHelper.sin(startDeg), 0);
        tessellator.addVertex(x + rIn * MathHelper.cos(endDeg), y + rIn * MathHelper.sin(endDeg), 0);
        tessellator.addVertex(x + rOut * MathHelper.cos(endDeg), y + rOut * MathHelper.sin(endDeg), 0);
        tessellator.draw();
    }
}
