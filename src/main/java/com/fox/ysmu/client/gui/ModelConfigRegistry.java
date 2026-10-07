package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The per-model wheel and settings declarations a pack makes, remembered on the client from the OpenYSM sync payload.
 * <p>
 * Three blocks of a pack's {@code properties} arrive here:
 * <ul>
 * <li>{@code extra_animation} - an <b>ordered</b> map. Its key is the animation name to play, or a {@code '#<classifyId>'}
 * sub-page marker, or {@code '#return'}; its value is the display label, or a {@code '#<buttonId>'} reference that turns
 * the entry into the entry point of that button's settings panel. This is the wheel's table, and it is the reason the
 * wheel cannot work from a bare label array: which entry plays, which descends and which opens a panel is decided by
 * <em>both</em> halves of the pair. Upstream carries the same pairs
 * ({@code format/schema/model/views/ModelInfoView.java:133-135} returns {@code settings.extraAnimation()} as an ordered
 * list of pairs) and dispatches on them in
 * {@code client/gui/AnimationRouletteScreen.java:582-608}.</li>
 * <li>{@code extra_animation_buttons} - each button carries {@code config_forms}. Every form has a {@code type}
 * ({@code range}, {@code checkbox} or {@code radio}), a title, and a {@code value} that is a MoLang expression naming
 * the {@code v.roaming.<name>} variable it reads and writes. The folder parser and the binary payload already carry
 * this block ({@code YSMFolderDeserializer.parseExtraAnimationButtons}, {@code YSMBinarySerializer}); until this
 * registry existed nothing on the client read it, which is why a pack's {@code 模型设置} panel could not be opened at
 * all.</li>
 * <li>{@code extra_animation_classify} - the sub-pages a {@code '#<classifyId>'} entry descends into. Each carries its
 * own ordered {@code extra_animation} map, so a sub-page uses the same three-way dispatch as the root.</li>
 * </ul>
 * The presence of a button with at least one form is what makes a model configurable; the presence of wheel entries is
 * what makes it have a wheel.
 * <p>
 * <b>Port shape.</b> The reference keeps the same three tables in a separate class
 * ({@code client/ExtraWheelData.java}, registered by {@code ClientModelManager.registerExtraWheel}). This port keeps
 * them in the registry that already owned the other two blocks, because a second class holding the same two maps would
 * be the "same table in two places" defect: every reader here ({@code AnimationRouletteScreen}, {@code ModelConfigScreen},
 * {@code PlayerModelScreen}) already asks this class.
 */
@SideOnly(Side.CLIENT)
public final class ModelConfigRegistry {

    /** Model id -> the model's declared buttons, in declaration order. */
    private static final Map<ResourceLocation, List<RawYsmModel.ExtraAnimationButton>> BUTTONS = new ConcurrentHashMap<>();

    /** Model id -> the model's declared animation categories, in declaration order. */
    private static final Map<ResourceLocation, List<RawYsmModel.ExtraAnimationClassify>> CLASSIFIES = new ConcurrentHashMap<>();

    /** Model id -> the model's root {@code extra_animation} entries (name/marker -> label/button reference). */
    private static final Map<ResourceLocation, Map<String, String>> WHEEL = new ConcurrentHashMap<>();

    private ModelConfigRegistry() {}

    /**
     * Records what a model declared for its settings panel, without wheel entries. Kept for callers that only have the
     * buttons and categories; the sync path uses the four-argument overload so the wheel gets its table too.
     */
    public static void accept(ResourceLocation modelId, @Nullable List<RawYsmModel.ExtraAnimationButton> buttons,
        @Nullable List<RawYsmModel.ExtraAnimationClassify> classifies) {
        accept(modelId, buttons, classifies, null);
    }

    /** Records everything a model declared for its wheel and settings panel; called when a model arrives over sync. */
    public static void accept(ResourceLocation modelId, @Nullable List<RawYsmModel.ExtraAnimationButton> buttons,
        @Nullable List<RawYsmModel.ExtraAnimationClassify> classifies, @Nullable Map<String, String> extraAnimations) {
        if (modelId == null) {
            return;
        }
        if (buttons == null || buttons.isEmpty()) {
            BUTTONS.remove(modelId);
        } else {
            BUTTONS.put(modelId, Collections.unmodifiableList(new ArrayList<>(buttons)));
        }
        if (classifies == null || classifies.isEmpty()) {
            CLASSIFIES.remove(modelId);
        } else {
            CLASSIFIES.put(modelId, Collections.unmodifiableList(new ArrayList<>(classifies)));
        }
        // Self-healing, like the two blocks above: a payload that declares no wheel must not inherit the previous
        // bytes' table, so an empty one removes rather than merely skipping the put.
        if (extraAnimations == null || extraAnimations.isEmpty()) {
            WHEEL.remove(modelId);
        } else {
            WHEEL.put(modelId, Collections.unmodifiableMap(new LinkedHashMap<>(extraAnimations)));
        }
    }

    /** Every declared button of a model, in declaration order; empty when the model declares none. */
    public static List<RawYsmModel.ExtraAnimationButton> buttonsFor(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return Collections.emptyList();
        }
        List<RawYsmModel.ExtraAnimationButton> buttons = BUTTONS.get(modelId);
        return buttons == null ? Collections.emptyList() : buttons;
    }

    /** Every declared animation category of a model, in declaration order; empty when the model declares none. */
    public static List<RawYsmModel.ExtraAnimationClassify> classifiesFor(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return Collections.emptyList();
        }
        List<RawYsmModel.ExtraAnimationClassify> classifies = CLASSIFIES.get(modelId);
        return classifies == null ? Collections.emptyList() : classifies;
    }

    /**
     * The model's root {@code extra_animation} entries in declaration order: key = the animation name, or a
     * {@code '#'}-prefixed sub-page / return marker; value = the display label, or a {@code '#'}-prefixed button id.
     * Empty when the model declares no wheel (then the flat, baked label array is all there is - see
     * {@code ClientModelManager.EXTRA_ANIMATION_NAME}).
     */
    public static Map<String, String> wheelEntriesFor(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return Collections.emptyMap();
        }
        Map<String, String> entries = WHEEL.get(modelId);
        return entries == null ? Collections.emptyMap() : entries;
    }

    /** One declared sub-page by id, or {@code null} when the model declares no such category. */
    @Nullable
    public static RawYsmModel.ExtraAnimationClassify classifyFor(@Nullable ResourceLocation modelId, String classifyId) {
        if (classifyId == null || classifyId.isEmpty()) {
            return null;
        }
        for (RawYsmModel.ExtraAnimationClassify classify : classifiesFor(modelId)) {
            if (classifyId.equals(classify.id)) {
                return classify;
            }
        }
        return null;
    }

    /** One declared button by id, or {@code null} when the model declares no such button. */
    @Nullable
    public static RawYsmModel.ExtraAnimationButton buttonFor(@Nullable ResourceLocation modelId, String buttonId) {
        if (buttonId == null || buttonId.isEmpty()) {
            return null;
        }
        for (RawYsmModel.ExtraAnimationButton button : buttonsFor(modelId)) {
            if (buttonId.equals(button.id)) {
                return button;
            }
        }
        return null;
    }

    /**
     * Whether a model has anything to configure, i.e. at least one button with at least one form. The model-selection
     * screen only offers its settings entry for such a model.
     */
    public static boolean hasSettings(@Nullable ResourceLocation modelId) {
        return !settingsButtonsFor(modelId).isEmpty();
    }

    /**
     * Every declared button that actually carries forms, in declaration order - the list the settings screen pages
     * through.
     * <p>
     * A button without forms has nothing to show, so it is not a panel. The forms themselves are the only reliable
     * signal: a pack may mark its settings panel with a {@code '#'} value in {@code extra_animation} (for example
     * {@code "模型设置": "#模型设置"}), but that marker is not what makes it configurable.
     * <p>
     * Upstream keeps the same set as a {@code Map<id, ExtraAnimationButton>} and opens one of them by id
     * ({@code format/schema/model/views/ModelInfoView.java:69-81}; the panel itself is
     * {@code client/gui/AnimationRouletteScreen.java:205-235}). This port keeps the declaration order as a list,
     * because its settings panel is its own screen ({@code ModelConfigScreen}) rather than a column of the roulette,
     * and a screen that shows one button at a time needs an order to page through.
     */
    public static List<RawYsmModel.ExtraAnimationButton> settingsButtonsFor(@Nullable ResourceLocation modelId) {
        List<RawYsmModel.ExtraAnimationButton> buttons = buttonsFor(modelId);
        if (buttons.isEmpty()) {
            return Collections.emptyList();
        }
        List<RawYsmModel.ExtraAnimationButton> panels = new ArrayList<>();
        for (RawYsmModel.ExtraAnimationButton button : buttons) {
            if (!button.forms.isEmpty()) {
                panels.add(button);
            }
        }
        return panels.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(panels);
    }

    /**
     * The first button whose settings should be shown, or a button with an empty form list when there is nothing to
     * show. Kept for the callers that only ever open one panel ({@code PlayerModelScreen}'s settings icon); the
     * settings screen itself uses {@link #settingsButtonsFor} so every button is reachable.
     */
    public static RawYsmModel.ExtraAnimationButton settingsButtonFor(@Nullable ResourceLocation modelId) {
        List<RawYsmModel.ExtraAnimationButton> panels = settingsButtonsFor(modelId);
        return panels.isEmpty() ? EMPTY_BUTTON : panels.get(0);
    }

    /** Drops every remembered declaration, for a resource reload or a server change. */
    public static void clear() {
        BUTTONS.clear();
        CLASSIFIES.clear();
        WHEEL.clear();
    }

    private static final RawYsmModel.ExtraAnimationButton EMPTY_BUTTON = new RawYsmModel.ExtraAnimationButton();
}
