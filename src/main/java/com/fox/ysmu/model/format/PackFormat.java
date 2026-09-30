package com.fox.ysmu.model.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads OpenYSM model pack manifests - the {@code ysm-pack.json} text and the {@code ysm-pack.png} cover next to a
 * pack's model folders.
 * <p>
 * This is the server-side half of the folder feature in the model selection GUI. The GUI derives its folders from the
 * model ids it receives, which is enough to navigate, but a folder derived that way can only be labelled with its
 * directory name; the manifest is what turns {@code wine_fox/} into "酒狐与小伙伴" with the pack's own cover. The
 * manifest has to travel to the client through the sync index (see {@code OpenYsmModelSyncServer}), because a client
 * connected to a server does not have the server's files.
 * <p>
 * Packs may sit at any depth under the scanned root ({@code custom/group/pack/ysm-pack.json}), and a pack inside a
 * pack is read as well: the walk never stops at a pack root.
 */
public final class PackFormat {

    /** Manifest keys, matching upstream's {@code ysm-pack.json}. */
    public static final String NAME_KEY = "name";
    public static final String DESCRIPTION_KEY = "description";
    public static final String LANG_KEY = "lang";

    private PackFormat() {}

    /**
     * Walks {@code rootPath} and registers every pack root it finds into {@code target}, keyed by the pack's hierarchy
     * (folder path with a trailing slash). Failures are logged per pack so one broken manifest cannot stop the scan.
     */
    public static void cacheAllPacks(Path rootPath, Map<String, ServerPackData> target) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return;
        }
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(rootPath) || !ServerModelManager.isPackRoot(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        ServerPackData pack = read(dir, OpenYsmFormat.toModelName(rootPath, dir) + "/");
                        target.put(pack.hierarchy, pack);
                    } catch (Exception | LinkageError e) {
                        ysmu.LOG.warn("Failed to read model pack manifest under {}", dir, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to scan model packs under {}", rootPath, e);
        }
    }

    private static ServerPackData read(Path dir, String hierarchy) throws IOException {
        String name = "";
        String description = "";
        Map<String, Map<String, String>> lang = new HashMap<>();
        Path manifest = dir.resolve(ServerModelManager.PACK_INFO_FILE_NAME);
        JsonElement root = new JsonParser().parse(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8));
        if (root != null && root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            name = getString(object, NAME_KEY);
            description = getString(object, DESCRIPTION_KEY);
            if (object.has(LANG_KEY) && object.get(LANG_KEY)
                .isJsonObject()) {
                for (Map.Entry<String, JsonElement> locale : object.getAsJsonObject(LANG_KEY)
                    .entrySet()) {
                    if (!locale.getValue()
                        .isJsonObject()) {
                        continue;
                    }
                    Map<String, String> translations = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry : locale.getValue()
                        .getAsJsonObject()
                        .entrySet()) {
                        if (entry.getValue()
                            .isJsonPrimitive()) {
                            translations.put(
                                entry.getKey(),
                                entry.getValue()
                                    .getAsString());
                        }
                    }
                    lang.put(locale.getKey(), translations);
                }
            }
        }

        byte[] icon = null;
        int iconWidth = 0;
        int iconHeight = 0;
        int iconFormat = 0;
        Path cover = dir.resolve(ServerModelManager.PACK_ICON_FILE_NAME);
        if (Files.isRegularFile(cover)) {
            icon = Files.readAllBytes(cover);
            iconFormat = YSMFolderDeserializer.detectFormat(icon);
            int[] size = readImageSize(icon);
            iconWidth = size[0];
            iconHeight = size[1];
        }
        return new ServerPackData(hierarchy, name, description, lang, icon, iconWidth, iconHeight, iconFormat);
    }

    /**
     * Cover dimensions. They are decoration on the wire (the client uploads the bytes as a texture and lets OpenGL
     * scale the tile), so an unreadable image degrades to 0x0 instead of failing the pack.
     */
    private static int[] readImageSize(byte[] data) {
        try {
            java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image != null) {
                return new int[] { image.getWidth(), image.getHeight() };
            }
        } catch (Exception e) {
            ysmu.LOG.debug("Cannot read model pack cover dimensions", e);
        }
        return new int[] { 0, 0 };
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return "";
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
