package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * {@code settingsButtonsFor} 是设置面板翻页用的列表：**全部**带 form 的按钮、按声明序、跳过没有 form 的；
     * {@code settingsButtonFor} 仍是它的第 0 个（只开一个面板的老调用方）。
     */
    @Test
    void settingsButtonsForKeepsEveryPanelInOrderAndSkipsFormlessButtons() {
        RawYsmModel.ExtraAnimationButton formless = button("吃糖");
        RawYsmModel.ExtraAnimationButton firstPanel = panel("模型设置", "range");
        RawYsmModel.ExtraAnimationButton secondPanel = panel("附加设置", "checkbox");
        ModelConfigRegistry.accept(MODEL, Arrays.asList(formless, firstPanel, secondPanel), Collections.emptyList());

        List<RawYsmModel.ExtraAnimationButton> panels = ModelConfigRegistry.settingsButtonsFor(MODEL);
        assertEquals(2, panels.size(), "两个带 form 的按钮都是面板");
        assertSame(firstPanel, panels.get(0), "必须保持声明序");
        assertSame(secondPanel, panels.get(1));
        assertSame(panels.get(0), ModelConfigRegistry.settingsButtonFor(MODEL), "单面板入口仍等于第 0 个");

        // 只有无 form 的按钮 = 没有设置面板（老调用方拿到的是空 fallback 按钮，而不是 null）。
        ModelConfigRegistry.accept(OTHER, Collections.singletonList(formless), Collections.emptyList());
        assertTrue(ModelConfigRegistry.settingsButtonsFor(OTHER).isEmpty());
        assertTrue(ModelConfigRegistry.settingsButtonFor(OTHER).forms.isEmpty());
        assertTrue(ModelConfigRegistry.settingsButtonsFor(null).isEmpty());
    }

    /** 轮盘根表：保序、后一次 accept 整体替换、空/null 的 accept 清空，且不会串到别的模型。 */
    @Test
    void wheelEntriesForKeepsOrderReplacesWhollyAndClears() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("HI", "打招呼");
        first.put("#page", "更多");
        first.put("#return", "返回");
        ModelConfigRegistry.accept(MODEL, null, null, first);
        assertEquals(
            Arrays.asList("HI", "#page", "#return"),
            new ArrayList<>(ModelConfigRegistry.wheelEntriesFor(MODEL).keySet()),
            "轮盘的顺序就是声明顺序（扇区与按键按它排布）");

        Map<String, String> second = new LinkedHashMap<>();
        second.put("Bye", "再见");
        ModelConfigRegistry.accept(MODEL, null, null, second);
        assertEquals(
            Collections.singletonList("Bye"),
            new ArrayList<>(ModelConfigRegistry.wheelEntriesFor(MODEL).keySet()),
            "后一次 accept 整体替换，不与上一次合并");
        assertEquals("再见", ModelConfigRegistry.wheelEntriesFor(MODEL).get("Bye"));

        ModelConfigRegistry.accept(MODEL, null, null, Collections.emptyMap());
        assertTrue(ModelConfigRegistry.wheelEntriesFor(MODEL).isEmpty(), "空表 = 清空");

        ModelConfigRegistry.accept(MODEL, null, null, first);
        ModelConfigRegistry.accept(MODEL, null, null, null);
        assertTrue(ModelConfigRegistry.wheelEntriesFor(MODEL).isEmpty(), "null 表同样清空");
        assertTrue(ModelConfigRegistry.wheelEntriesFor(null).isEmpty());
        assertTrue(ModelConfigRegistry.wheelEntriesFor(OTHER).isEmpty(), "不得把上一个模型的表留在别的模型上");
    }

    /** 按 id 查按钮/子页：命中返回同一个对象；未知 id、空串、null 一律 null（调用方靠它决定不下钻）。 */
    @Test
    void buttonAndClassifyLookupsHitByIdAndMissForUnknownBlankOrNull() {
        RawYsmModel.ExtraAnimationButton panel = panel("模型设置", "range");
        RawYsmModel.ExtraAnimationButton plain = button("吃糖");
        RawYsmModel.ExtraAnimationClassify classify = classify("表情");
        ModelConfigRegistry.accept(MODEL, Arrays.asList(panel, plain), Collections.singletonList(classify));

        assertSame(panel, ModelConfigRegistry.buttonFor(MODEL, "模型设置"));
        assertSame(plain, ModelConfigRegistry.buttonFor(MODEL, "吃糖"));
        assertNull(ModelConfigRegistry.buttonFor(MODEL, "不存在"));
        assertNull(ModelConfigRegistry.buttonFor(MODEL, ""));
        assertNull(ModelConfigRegistry.buttonFor(MODEL, null));
        assertNull(ModelConfigRegistry.buttonFor(null, "模型设置"));

        assertSame(classify, ModelConfigRegistry.classifyFor(MODEL, "表情"));
        assertNull(ModelConfigRegistry.classifyFor(MODEL, "不存在"));
        assertNull(ModelConfigRegistry.classifyFor(MODEL, ""));
        assertNull(ModelConfigRegistry.classifyFor(MODEL, null));
        assertNull(ModelConfigRegistry.classifyFor(null, "表情"));
    }

    private static RawYsmModel.ExtraAnimationButton button(String id) {
        RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton();
        button.id = id;
        return button;
    }

    private static RawYsmModel.ExtraAnimationButton panel(String id, String formType) {
        RawYsmModel.ExtraAnimationButton button = button(id);
        button.name = id;
        RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
        form.type = formType;
        button.forms.add(form);
        return button;
    }

    private static RawYsmModel.ExtraAnimationClassify classify(String id) {
        RawYsmModel.ExtraAnimationClassify classify = new RawYsmModel.ExtraAnimationClassify();
        classify.id = id;
        return classify;
    }
}
