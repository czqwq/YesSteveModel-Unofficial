package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * A pack's GUI presentation images ({@code gui_background} / {@code gui_foreground}) must bridge into the payload's
 * own map, and nowhere else.
 *
 * <p>"Nowhere else" is the load-bearing half: {@code ModelData.texture} is the model's texture <em>list</em>, which
 * the selection screen offers as selectable skins and which {@code ServerModelInfo} is built from, and
 * {@code ModelData.model} is a map of geometry files that {@code ClientModelManager} parses as JSON one key at a time.
 * A GUI background landing in either would become a skin or a parse failure - which is why the two images travel
 * beside the payload instead.
 */
class RawYsmModelAdapterGuiImagesTest {

    private static final int PNG_FORMAT = 2;
    private static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static RawYsmModel.RawGeometry geometry() {
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();
        geo.identifier = "geometry.test";
        geo.sourceJson = GEO.getBytes(StandardCharsets.UTF_8);
        return geo;
    }

    private static RawYsmModel.RawTexture pngTexture(String name) {
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = name;
        texture.sourceFileName = name;
        texture.imageFormat = PNG_FORMAT;
        texture.data = new byte[] { (byte) 0x89, 'P', 'N', 'G' };
        return texture;
    }

    private static RawYsmModel.RawImage guiImage(String name, byte[] data) {
        RawYsmModel.RawImage image = new RawYsmModel.RawImage();
        image.name = name;
        image.data = data;
        image.isPng = true;
        image.format = PNG_FORMAT;
        return image;
    }

    private static RawYsmModel bridgeableModel() {
        RawYsmModel raw = new RawYsmModel();
        raw.modelId = "_test_gui_image_bridge";
        raw.mainEntity.mainModel = geometry();
        raw.mainEntity.armModel = geometry();
        raw.mainEntity.textures.put("texture.png", pngTexture("texture.png"));
        return raw;
    }

    /** The two declared images arrive with their bytes, and only in the GUI image map. */
    @Test
    void theTwoGuiImagesTravelBesideThePayload() throws Exception {
        RawYsmModel raw = bridgeableModel();
        byte[] background = { 1, 2, 3 };
        byte[] foreground = { 4, 5, 6 };
        raw.properties.backgroundImages.add(guiImage("gui_background", background));
        raw.properties.backgroundImages.add(guiImage("gui_foreground", foreground));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);

        assertEquals(2, data.getGuiImages()
            .size());
        assertArrayEquals(background, data.getGuiImages()
            .get("gui_background"));
        assertArrayEquals(foreground, data.getGuiImages()
            .get("gui_foreground"));

        // Neither the selectable texture list nor the geometry map may carry them.
        assertFalse(
            data.getTexture()
                .containsKey("gui_background"),
            data.getTexture()
                .keySet()
                .toString());
        assertFalse(
            data.getTexture()
                .containsKey("gui_foreground"),
            data.getTexture()
                .keySet()
                .toString());
        assertFalse(
            data.getModel()
                .containsKey("gui_background"),
            data.getModel()
                .keySet()
                .toString());
    }

    /** A pack that declares none bridges an empty map rather than null. */
    @Test
    void aPackWithoutGuiImagesBridgesAnEmptyMap() throws Exception {
        ModelData data = RawYsmModelAdapter.toLegacyModelData(bridgeableModel(), "_test_gui_image_bridge");

        assertTrue(
            data.getGuiImages()
                .isEmpty());
    }

    @Test
    void adapterCarriesRichDeclarationsBesideImagesAndGeometry() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.properties.sha256 = "12345678";
        raw.properties.extraAnimations.put("Wave", "#settings");
        raw.properties.extraAnimations.put("#page", "Page");
        RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton();
        button.id = "settings";
        RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
        form.type = "range";
        form.defaultValue = "v.roaming.size";
        button.forms.add(form);
        raw.properties.extraAnimationButtons.add(button);
        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        assertEquals(java.util.List.of("Wave", "#page"),
            new java.util.ArrayList<>(data.getWheelDeclarations().values().animations.keySet()));
        assertEquals("v.roaming.size", data.getWheelDeclarations().values().buttons.get(0).forms.get(0).defaultValue);
        assertEquals("12345678", data.getWheelDeclarations().values().sha256);
        assertTrue(data.getGuiImages().isEmpty());
        raw.properties.extraAnimations.clear();
        assertEquals(2, data.getWheelDeclarations().values().animations.size());
    }

    /** A null entry, a blank/empty name or no bytes at all is skipped instead of producing an unusable image. */
    @Test
    void nullBlankAndDataLessEntriesAreSkipped() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.properties.backgroundImages.add(null);
        raw.properties.backgroundImages.add(guiImage("   ", new byte[] { 1 }));
        raw.properties.backgroundImages.add(guiImage("", new byte[] { 1 }));
        raw.properties.backgroundImages.add(guiImage("gui_background", null));
        raw.properties.backgroundImages.add(guiImage("gui_foreground", new byte[] { 7 }));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);

        assertEquals(
            1,
            data.getGuiImages()
                .size(),
            data.getGuiImages()
                .keySet()
                .toString());
        assertArrayEquals(
            new byte[] { 7 },
            data.getGuiImages()
                .get("gui_foreground"));
    }
}
