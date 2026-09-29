package com.fox.ysmu.client;

import java.util.Collections;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

/**
 * One model pack as the client received it in the sync index: the folder it owns, the text from its
 * {@code ysm-pack.json} manifest and - when the server could afford to inline it - the {@code ysm-pack.png} cover.
 * <p>
 * Built on the sync worker thread from raw bytes only (no Minecraft objects), then handed to
 * {@link ClientPackRegistry#accept} on the client thread, which uploads the cover and publishes the pack.
 */
public final class ClientPackInfo {

    /** Folder path with a trailing slash, for example {@code wine_fox/}; the GUI's folder key. */
    public final String hierarchy;
    /** Manifest name, or an empty string. */
    public final String name;
    /** Manifest description, or an empty string. */
    public final String description;
    /** Locale (lower case, for example {@code zh_cn}) to translation key to text map. */
    public final Map<String, Map<String, String>> lang;
    /** Cover image bytes, or {@code null} when the pack ships none (or the server could not afford to send it). */
    public final byte[] iconData;
    /** Texture id of the uploaded cover; set by {@link ClientPackRegistry#accept}, {@code null} without a cover. */
    public ResourceLocation icon;

    public ClientPackInfo(String hierarchy, String name, String description, Map<String, Map<String, String>> lang,
        byte[] iconData) {
        this.hierarchy = hierarchy;
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.lang = lang == null ? Collections.emptyMap() : lang;
        this.iconData = iconData;
    }
}
