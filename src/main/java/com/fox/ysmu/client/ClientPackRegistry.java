package com.fox.ysmu.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.texture.CustomTextureManager;
import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.client.texture.TextureHolder;
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
    /**
     * The published cover textures, keyed by the id they are published under, with the holder that keeps each
     * registration alive.
     * <p>
     * They are registered through {@link CustomTextureManager}, not straight into the platform's texture map, so that
     * {@link #clear()} can release them: 1.7.10's {@code TextureManager.mapTextureObjects} holds the texture object -
     * and with it the decoded {@code BufferedImage} - for the rest of the session, so dropping the
     * {@code ResourceLocation} from {@link ClientPackInfo#icon} alone frees neither the GL texture nor the heap. This is
     * the same lifecycle {@code ClientModelMetadataRegistry} gives its author avatars; upstream's GUI textures use the
     * same owner (its {@code CustomTextureManager} registers them and its pages release them when they close).
     * <p>
     * The holder is kept as a field of {@link PackIcon}: dropping it is what arms {@code CustomTextureManager}'s
     * delayed release, so it must stay reachable for exactly as long as the cover is wanted.
     */
    private static final Map<ResourceLocation, PackIcon> ICONS = new LinkedHashMap<>();
    private static volatile int version;

    /** One published pack-cover texture; see {@link #ICONS}. */
    private static final class PackIcon {

        private final OuterFileTexture texture;
        private final TextureHolder holder;

        private PackIcon(OuterFileTexture texture, TextureHolder holder) {
            this.texture = texture;
            this.holder = holder;
        }
    }

    private ClientPackRegistry() {}

    /**
     * Publishes the packs of one sync round. Must run on the client thread: it uploads the cover textures.
     */
    public static void accept(List<ClientPackInfo> packs) {
        // The previous round's covers belong to the previous round; release them (and their platform registrations)
        // before publishing this round's, exactly as clear() does. A pack that survives the round keeps its id - it is
        // derived from the hierarchy - so its cover is re-uploaded under the same id and the released registration is
        // discarded by CustomTextureManager's identity check (see MinecraftHost#release).
        releaseIcons();
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
        releaseIcons();
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
            // Idempotent, like the avatar registry: a cover re-registered under the same id releases the previous
            // registration first, so its GL texture and decoded image do not stay reachable from the platform's map.
            releaseIcon(pack.icon);
            OuterFileTexture texture = new OuterFileTexture(pack.iconData);
            ICONS.put(pack.icon, new PackIcon(texture, CustomTextureManager.register(texture, pack.icon)));
        } catch (RuntimeException e) {
            pack.icon = null;
            ysmu.LOG.warn("Failed to upload cover of model pack {}", pack.hierarchy, e);
        }
    }

    /** Starts the delayed release of every published cover and forgets them; client thread only. */
    private static void releaseIcons() {
        for (PackIcon icon : ICONS.values()) {
            CustomTextureManager.release(icon.texture);
        }
        ICONS.clear();
    }

    /** Starts the delayed release of one published cover and forgets it; a {@code null} or unknown id is a no-op. */
    private static void releaseIcon(ResourceLocation id) {
        if (id == null) {
            return;
        }
        PackIcon icon = ICONS.remove(id);
        if (icon != null) {
            CustomTextureManager.release(icon.texture);
        }
    }

    /**
     * Manifest text for the running game language, falling back to the primary language subtag ({@code zh_cn} ->
     * {@code zh}) and then the manifest's untranslated value.
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
        return Objects.requireNonNullElse(fallback, "");
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
