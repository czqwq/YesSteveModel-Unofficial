package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The per-model settings a pack declares, remembered on the client from the OpenYSM sync payload.
 * <p>
 * A pack's {@code ysm.json} carries {@code properties.extra_animation_buttons}, and each button carries
 * {@code config_forms}. Every form has a {@code type} ({@code range}, {@code checkbox} or {@code radio}), a title, and a
 * {@code value} that is a MoLang expression naming the {@code v.roaming.<name>} variable it reads and writes. The
 * folder parser and the binary payload already carry this block
 * ({@code YSMFolderDeserializer.parseExtraAnimationButtons}, {@code YSMBinarySerializer}); until this registry existed
 * nothing on the client read it, which is why a pack's {@code 模型设置} panel could not be opened at all.
 * <p>
 * Note that this is deliberately not fed from {@code RawYsmModelAdapter.getExtraAnimationNames}: that method only
 * fills the eight {@code extra0..extra7} slots and skips values that start with {@code '#'}, so the category marker a
 * pack uses for its settings panel ({@code "模型设置": "#模型设置"}) never reaches the extra-animation wheel. The
 * presence of a button with at least one form is what makes a model configurable.
 */
@SideOnly(Side.CLIENT)
public final class ModelConfigRegistry {

    /** Model id -> the model's declared buttons, in declaration order. */
    private static final Map<ResourceLocation, List<RawYsmModel.ExtraAnimationButton>> BUTTONS = new ConcurrentHashMap<>();

    /** Model id -> the model's declared animation categories, in declaration order. */
    private static final Map<ResourceLocation, List<RawYsmModel.ExtraAnimationClassify>> CLASSIFIES = new ConcurrentHashMap<>();

    private ModelConfigRegistry() {}

    /** Records what a model declared for its settings panel; called when a model arrives over the sync channel. */
    public static void accept(ResourceLocation modelId, @Nullable List<RawYsmModel.ExtraAnimationButton> buttons,
        @Nullable List<RawYsmModel.ExtraAnimationClassify> classifies) {
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
     * Whether a model has anything to configure, i.e. at least one button with at least one form. The model-selection
     * screen only offers its settings entry for such a model.
     */
    public static boolean hasSettings(@Nullable ResourceLocation modelId) {
        return !settingsButtonFor(modelId).forms.isEmpty();
    }

    /**
     * The button whose settings should be shown, or a button with an empty form list when there is nothing to show.
     * <p>
     * The first button that actually carries forms wins. A pack may mark its settings panel with a {@code '#'} value in
     * {@code extra_animation} (for example {@code "模型设置": "#模型设置"}), but that marker does not survive into
     * anything the client keeps, and a pack that only ships the settings panel declares no animation classification for
     * it either, so the forms themselves are the only reliable signal.
     */
    public static RawYsmModel.ExtraAnimationButton settingsButtonFor(@Nullable ResourceLocation modelId) {
        for (RawYsmModel.ExtraAnimationButton button : buttonsFor(modelId)) {
            if (!button.forms.isEmpty()) {
                return button;
            }
        }
        return EMPTY_BUTTON;
    }

    /** Drops every remembered declaration, for a resource reload or a server change. */
    public static void clear() {
        BUTTONS.clear();
        CLASSIFIES.clear();
    }

    private static final RawYsmModel.ExtraAnimationButton EMPTY_BUTTON = new RawYsmModel.ExtraAnimationButton();
}
