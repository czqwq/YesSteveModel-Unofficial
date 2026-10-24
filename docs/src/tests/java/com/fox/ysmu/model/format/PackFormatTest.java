package com.fox.ysmu.model.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads OpenYSM pack manifests from a real directory tree. This is the server half of the GUI folder feature: the
 * name, description, translations and cover read here are what the client later shows on a folder tile.
 */
class PackFormatTest {

    private static Map<String, ServerPackData> scan(Path root) {
        Map<String, ServerPackData> packs = new HashMap<>();
        PackFormat.cacheAllPacks(root, packs);
        return packs;
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void readsManifestTextTranslationsAndCover(@TempDir Path root) throws IOException {
        Path pack = root.resolve("wine_fox");
        Files.createDirectories(pack);
        Files.write(
            pack.resolve("ysm-pack.json"),
            ("{\"name\":\"Wine Fox & Friends\",\"description\":\"Cute Wine Fox and her friends\","
                + "\"lang\":{\"zh_cn\":{\"name\":\"酒狐与小伙伴\",\"description\":\"可爱酒狐和她的小伙伴们\"}}}")
                    .getBytes(StandardCharsets.UTF_8));
        byte[] cover = png(3, 2);
        Files.write(pack.resolve("ysm-pack.png"), cover);

        Map<String, ServerPackData> packs = scan(root);

        assertEquals(1, packs.size());
        ServerPackData data = packs.get("wine_fox/");
        assertNotNull(data);
        assertEquals("Wine Fox & Friends", data.name);
        assertEquals("Cute Wine Fox and her friends", data.description);
        assertEquals("酒狐与小伙伴", data.lang.get("zh_cn").get("name"));
        assertArrayEquals(cover, data.icon);
        assertEquals(3, data.iconWidth);
        assertEquals(2, data.iconHeight);
        assertEquals(2, data.iconFormat);
    }

    @Test
    void nestedPacksAreKeyedByTheirFullHierarchy(@TempDir Path root) throws IOException {
        Path nested = root.resolve("group").resolve("wine_fox");
        Files.createDirectories(nested);
        Files.write(nested.resolve("ysm-pack.json"), "{\"name\":\"Nested\"}".getBytes(StandardCharsets.UTF_8));

        Map<String, ServerPackData> packs = scan(root);

        assertEquals(1, packs.size());
        assertEquals("Nested", packs.get("group/wine_fox/").name);
    }

    @Test
    void manifestWithoutNameOrCoverStillRegistersThePack(@TempDir Path root) throws IOException {
        Path pack = root.resolve("bare");
        Files.createDirectories(pack);
        Files.write(pack.resolve("ysm-pack.json"), "{}".getBytes(StandardCharsets.UTF_8));

        ServerPackData data = scan(root).get("bare/");

        assertNotNull(data, "a manifest alone makes the directory a pack");
        assertEquals("", data.name);
        assertEquals("", data.description);
        assertNull(data.icon);
        assertTrue(data.lang.isEmpty());
    }

    @Test
    void directoryWithoutManifestIsNotAPack(@TempDir Path root) throws IOException {
        Path model = root.resolve("steve");
        Files.createDirectories(model);
        Files.write(model.resolve("ysm-pack.png"), png(1, 1));
        Files.write(model.resolve("main.json"), "{}".getBytes(StandardCharsets.UTF_8));

        assertTrue(scan(root).isEmpty());
    }

    @Test
    void brokenManifestDoesNotStopTheScan(@TempDir Path root) throws IOException {
        Path broken = root.resolve("broken");
        Files.createDirectories(broken);
        Files.write(broken.resolve("ysm-pack.json"), "{ not json".getBytes(StandardCharsets.UTF_8));
        Path good = root.resolve("good");
        Files.createDirectories(good);
        Files.write(good.resolve("ysm-pack.json"), "{\"name\":\"Good\"}".getBytes(StandardCharsets.UTF_8));

        Map<String, ServerPackData> packs = scan(root);

        assertEquals(1, packs.size());
        assertFalse(packs.containsKey("broken/"));
        assertEquals("Good", packs.get("good/").name);
    }
}
