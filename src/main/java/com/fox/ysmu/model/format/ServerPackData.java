package com.fox.ysmu.model.format;

import java.util.Collections;
import java.util.Map;

/**
 * One OpenYSM model pack (a folder holding several models) as the server knows it: the text from its
 * {@code ysm-pack.json} manifest plus its {@code ysm-pack.png} cover.
 * <p>
 * Immutable and free of Minecraft types, so it can be built while scanning the disk and then serialized into the sync
 * index. {@link #hierarchy} is the pack's folder path including the trailing slash ({@code wine_fox/}), which is the
 * same key the folder-aware model selection GUI uses for its folder tiles.
 */
public final class ServerPackData {

    /** Folder path with a trailing slash, for example {@code wine_fox/}. */
    public final String hierarchy;
    /** Manifest name, or an empty string when the manifest declares none. */
    public final String name;
    /** Manifest description, or an empty string. */
    public final String description;
    /** Locale (lower case, for example {@code zh_cn}) to translation key ({@code name}/{@code description}) map. */
    public final Map<String, Map<String, String>> lang;
    /** Cover image bytes, or {@code null} when the pack ships no {@code ysm-pack.png}. */
    public final byte[] icon;
    public final int iconWidth;
    public final int iconHeight;
    /** Image format as reported by {@code YSMFolderDeserializer.detectFormat} (2 = PNG). */
    public final int iconFormat;

    public ServerPackData(String hierarchy, String name, String description, Map<String, Map<String, String>> lang,
        byte[] icon, int iconWidth, int iconHeight, int iconFormat) {
        this.hierarchy = hierarchy;
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.lang = lang == null ? Collections.emptyMap() : lang;
        this.icon = icon;
        this.iconWidth = iconWidth;
        this.iconHeight = iconHeight;
        this.iconFormat = iconFormat;
    }
}
