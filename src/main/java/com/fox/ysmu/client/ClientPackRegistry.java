package com.fox.ysmu.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * The model packs the current server announced in its sync index, keyed by the folder hierarchy the model selection
 * GUI navigates with ({@code wine_fox/}).
 * <p>
 * Folders exist in that GUI even without any of this - they are derived from the model ids - but a folder derived from
 * ids can only be labelled with its directory name and has no cover. This registry is what lets the tile show the
 * pack's manifest name ("酒狐与小伙伴") and its {@code ysm-pack.png}. When the 17 channel is disabled or the server
 * sends no packs, the GUI falls back to the directory name and its generic folder tile.
 * <p>
 * The data belongs to one server session, so {@link ClientModelManager} clears it together with the model list.
 */
@SideOnly(Side.CLIENT)
public final class ClientPackRegistry {

    private static final Map<String, ClientPackInfo> PACKS = Maps.newConcurrentMap();
    private static volatile int version;

    private ClientPackRegistry() {}

    /**
     * Publishes the packs of one sync round. Must run on the client thread: it uploads the cover textures.
     */
    public static void accept(List<ClientPackInfo> packs) {
        PACKS.clear();
        if (packs != null) {
            for (ClientPackInfo pack : packs) {
                registerIcon(pack);
                PACKS.put(pack.hierarchy, pack);
            }
        }
        version++;
        ysmu.LOG.info("YSM client registered {} model pack(s): {}", PACKS.size(), PACKS.keySet());
    }

    public static void clear() {
        PACKS.clear();
        version++;
    }

    /** The pack that owns a folder, or {@code null} when the server announced none for it. */
    public static ClientPackInfo get(String hierarchy) {
        return hierarchy == null ? null : PACKS.get(hierarchy);
    }

    /**
     * Bumped whenever the pack list changes, so an open GUI can refresh its tiles without polling the model list.
     */
    public static int version() {
        return version;
    }

    /** Localized pack names by folder hierarchy; the value is empty when the manifest declares none. */
    public static Map<String, String> names() {
        Map<String, String> names = new LinkedHashMap<>();
        for (ClientPackInfo pack : PACKS.values()) {
            names.put(pack.hierarchy, localized(pack, "name", pack.name));
        }
        return names;
    }

    /** Localized pack descriptions by folder hierarchy; empty when the manifest declares none. */
    public static Map<String, String> descriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (ClientPackInfo pack : PACKS.values()) {
            descriptions.put(pack.hierarchy, localized(pack, "description", pack.description));
        }
        return descriptions;
    }

    /** Texture id of a pack's cover; deterministic from the hierarchy so tiles can be built before the upload. */
    public static ResourceLocation iconId(String hierarchy) {
        return new ResourceLocation(ysmu.MODID, "pack_icon/" + Integer.toHexString(hierarchy.hashCode()));
    }

    private static void registerIcon(ClientPackInfo pack) {
        if (pack.iconData == null) {
            return;
        }
        try {
            pack.icon = iconId(pack.hierarchy);
            TextureManager textureManager = Minecraft.getMinecraft()
                .getTextureManager();
            // loadTexture only replaces the map entry, so re-registering would leak the previous GL texture; the
            // delete keeps it idempotent, exactly like ClientModelManager.registerTexture.
            textureManager.deleteTexture(pack.icon);
            textureManager.loadTexture(pack.icon, new OuterFileTexture(pack.iconData));
        } catch (RuntimeException e) {
            pack.icon = null;
            ysmu.LOG.warn("Failed to upload cover of model pack {}", pack.hierarchy, e);
        }
    }

    /**
     * Manifest text for the running game language, falling back to the primary language subtag ({@code zh_cn} ->
     * {@code zh}) and then to the manifest's untranslated value.
     */
    private static String localized(ClientPackInfo pack, String key, String fallback) {
        String locale = currentLocale();
        if (!locale.isEmpty()) {
            String value = translation(pack, locale, key);
            if (value == null) {
                int separator = locale.indexOf('_');
                if (separator > 0) {
                    value = translation(pack, locale.substring(0, separator), key);
                }
            }
            if (value != null) {
                return value;
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static String translation(ClientPackInfo pack, String locale, String key) {
        Map<String, String> translations = pack.lang.get(locale);
        if (translations == null) {
            return null;
        }
        String value = translations.get(key);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String currentLocale() {
        try {
            return Minecraft.getMinecraft()
                .getLanguageManager()
                .getCurrentLanguage()
                .getLanguageCode()
                .toLowerCase(Locale.ENGLISH);
        } catch (RuntimeException e) {
            // Before a world is joined there is no language manager; no translations then.
            return "";
        }
    }
}
