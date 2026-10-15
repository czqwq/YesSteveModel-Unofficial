package com.fox.ysmu.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.format.Type;

/**
 * {@link ModelData}'s GUI presentation images: the pack's {@code gui_background} / {@code gui_foreground}, which the
 * selection tile draws behind and over the model preview.
 *
 * <p>Two behaviours matter and neither is cosmetic: a payload built without images must say so (an empty map, not
 * null), and the payload must not alias the map it was handed - the sync path builds the map per payload and a later
 * mutation of it must not change what the tile already indexed.
 */
class ModelDataGuiImagesTest {

    private static ModelData payload(Map<String, byte[]> guiImages) {
        Map<String, byte[]> model = new LinkedHashMap<>();
        model.put("main", new byte[] { 1, 2, 3 });
        Map<String, byte[]> textures = new LinkedHashMap<>();
        textures.put("skin.png", new byte[] { 4, 5 });
        Map<String, byte[]> animations = new LinkedHashMap<>();
        return new ModelData("gui_probe", Type.FOLDER, model, textures, animations, guiImages);
    }

    /** The five-argument constructor is the one every existing caller uses; it declares no images. */
    @Test
    void theFiveArgumentConstructorDeclaresNoImages() {
        ModelData data = new ModelData(
            "gui_probe",
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>());

        assertTrue(data.getGuiImages().isEmpty());
    }

    /** Both ids survive with their bytes, and the map is a copy: mutating the input afterwards changes nothing. */
    @Test
    void theSixArgumentConstructorKeepsTheImagesAndCopiesTheMap() {
        byte[] background = { 9, 8, 7 };
        byte[] foreground = { 6, 5 };
        Map<String, byte[]> input = new LinkedHashMap<>();
        input.put("gui_background", background);
        input.put("gui_foreground", foreground);

        ModelData data = payload(input);

        assertEquals(2, data.getGuiImages()
            .size());
        assertArrayEquals(background, data.getGuiImages()
            .get("gui_background"));
        assertArrayEquals(foreground, data.getGuiImages()
            .get("gui_foreground"));
        assertNotSame(input, data.getGuiImages(), "the payload must not alias the caller's map");

        // The defensive copy: replacing, removing and adding in the input map leaves the payload untouched.
        input.put("gui_background", new byte[] { 1 });
        input.remove("gui_foreground");
        input.put("gui_extra", new byte[] { 2 });

        assertEquals(2, data.getGuiImages()
            .size());
        assertArrayEquals(background, data.getGuiImages()
            .get("gui_background"));
        assertArrayEquals(foreground, data.getGuiImages()
            .get("gui_foreground"));
        assertFalse(
            data.getGuiImages()
                .containsKey("gui_extra"));
    }

    /** The payload's own view is read-only, so nothing can add an image to an already-published model. */
    @Test
    void thePayloadsImageViewIsReadOnly() {
        ModelData data = payload(new LinkedHashMap<>(java.util.Collections.singletonMap("gui_background", new byte[] {
            1 })));

        assertThrows(
            UnsupportedOperationException.class,
            () -> data.getGuiImages()
                .put("gui_foreground", new byte[] { 2 }));
    }

    /** {@code null} and an empty map both mean "this model declares none". */
    @Test
    void nullAndEmptyBothMeanNoImages() {
        assertTrue(
            payload(null).getGuiImages()
                .isEmpty());
        assertTrue(
            payload(new LinkedHashMap<>()).getGuiImages()
                .isEmpty());
    }
}
