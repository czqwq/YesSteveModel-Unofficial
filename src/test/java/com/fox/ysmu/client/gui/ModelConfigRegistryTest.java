package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * Which model offers a settings panel, and which button that panel shows (see
 * {@code .agent/phase15-roaming-variables.md}, Milestone 5). The model-selection screen decides whether to draw its
 * settings entry from this, so a wrong answer means either a dead icon or a panel with nothing in it.
 */
class ModelConfigRegistryTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "ellen");
    private static final ResourceLocation OTHER = new ResourceLocation("ysmu", "other");

    @BeforeEach
    void reset() {
        ModelConfigRegistry.clear();
    }

    @Test
    void aModelWithoutDeclarationsOffersNoSettings() {
        assertFalse(ModelConfigRegistry.hasSettings(MODEL));
        assertFalse(ModelConfigRegistry.hasSettings(null));
        assertTrue(ModelConfigRegistry.buttonsFor(MODEL).isEmpty());
        assertTrue(ModelConfigRegistry.buttonsFor(null).isEmpty());
        // The fallback button is empty rather than null, so callers can read its form list without a null check.
        assertTrue(ModelConfigRegistry.settingsButtonFor(MODEL).forms.isEmpty());
        assertTrue(ModelConfigRegistry.settingsButtonFor(null).forms.isEmpty());
    }

    @Test
    void aButtonWithFormsIsThePanel() {
        RawYsmModel.ExtraAnimationButton empty = new RawYsmModel.ExtraAnimationButton();
        empty.id = "not_a_panel";
        RawYsmModel.ExtraAnimationButton panel = new RawYsmModel.ExtraAnimationButton();
        panel.id = "模型设置";
        panel.name = "模型设置";
        RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
        form.type = "range";
        form.defaultValue = "v.roaming.player_size";
        panel.forms.add(form);

        ModelConfigRegistry.accept(MODEL, java.util.Arrays.asList(empty, panel), Collections.emptyList());

        assertTrue(ModelConfigRegistry.hasSettings(MODEL));
        // The button that carries forms wins, even though it is not the first one declared.
        assertEquals("模型设置", ModelConfigRegistry.settingsButtonFor(MODEL).id);
        assertEquals(
            "v.roaming.player_size",
            ModelConfigRegistry.settingsButtonFor(MODEL).forms.get(0).defaultValue);

        // Another model is unaffected.
        assertFalse(ModelConfigRegistry.hasSettings(OTHER));
    }

    @Test
    void aButtonWithoutFormsIsNotAPanel() {
        RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton();
        button.id = "吃糖";
        ModelConfigRegistry.accept(MODEL, Collections.singletonList(button), Collections.emptyList());

        assertFalse(ModelConfigRegistry.hasSettings(MODEL));
    }

    @Test
    void aLaterAcceptReplacesAndAnEmptyOneClears() {
        RawYsmModel.ExtraAnimationButton panel = new RawYsmModel.ExtraAnimationButton();
        panel.forms.add(new RawYsmModel.ConfigForm());
        ModelConfigRegistry.accept(MODEL, Collections.singletonList(panel), Collections.emptyList());
        assertTrue(ModelConfigRegistry.hasSettings(MODEL));

        // A model that arrives again without forms (an older pack, or a re-sync) must stop offering the panel.
        ModelConfigRegistry.accept(MODEL, Collections.emptyList(), Collections.emptyList());
        assertFalse(ModelConfigRegistry.hasSettings(MODEL));

        ModelConfigRegistry.accept(MODEL, Collections.singletonList(panel), Collections.emptyList());
        ModelConfigRegistry.clear();
        assertFalse(ModelConfigRegistry.hasSettings(MODEL));
    }
}
