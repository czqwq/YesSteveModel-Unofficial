package com.fox.ysmu.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.Language;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;

/**
 * Model metadata the model information screen shows: the name, tips and license from a model's geometry, plus its
 * authors with - when the sync channel carried them - their avatars.
 * <p>
 * Two sources feed it, and the richer one wins. Every model that reaches the client carries an {@code extra_info}
 * block inside its geometry, which has names but no avatars and no roles (they are already folded into one string).
 * The OpenYSM binary sync payload additionally carries the structured author list, including the avatar images the
 * {@code ysm.json} manifest points at ({@code "avatar": "avatar/zljjxmm.png"}), which is what upstream renders in its
 * model information screen. {@link #acceptStructuredAuthors} therefore replaces the flat list.
 * <p>
 * Avatar uploads happen on the client thread; the registry is client-only state that lives as long as the models do
 * (see {@code ClientModelManager.clearRuntimeModelCaches}).
 */
@SideOnly(Side.CLIENT)
public final class ClientModelMetadataRegistry {

    /** One author as the information screen renders it. */
    public static final class Author {

        public final String name;
        public final String role;
        public final String comment;
        public final Map<String, String> contacts;
        /** Uploaded avatar texture, or {@code null} when the model carries no avatar image. */
        public ResourceLocation avatar;

        public Author(String name, String role, String comment, Map<String, String> contacts) {
            this.name = name == null ? "" : name;
            this.role = role == null ? "" : role;
            this.comment = comment == null ? "" : comment;
            this.contacts = contacts == null ? Collections.emptyMap() : contacts;
        }
    }

    /** What the information screen needs for one model. */
    public static final class Metadata {

        public String name = "";
        public String tips = "";
        public String license = "";
        public final List<Author> authors = new ArrayList<>();
        /**
         * The model's language tables, locale to key/value, exactly as its pack's {@code lang/*.json} declare them
         * ({@code ModelData#getLanguageFiles}). Empty when the model declares none, which is also what every payload
         * that arrived over a channel predating the field carries.
         */
        public final Map<String, Map<String, String>> languageFiles = new LinkedHashMap<>();

        /**
         * A key resolved for a display locale, using upstream's exact lookup: the requested locale, else
         * {@code en_us} ({@code format/schema/model/views/ModelInfoView.java:95-102} {@code translate} tries the
         * locale and then {@code en_us}; {@code :104-111} is the single-table lookup underneath it).
         *
         * @return the value, or {@code null} when neither table declares the key
         */
        @Nullable
        public String localized(String key, String locale) {
            String value = localizedIn(key, locale);
            return value != null ? value : localizedIn(key, "en_us");
        }

        @Nullable
        private String localizedIn(String key, String locale) {
            if (key == null || locale == null) {
                return null;
            }
            Map<String, String> table = languageFiles.get(locale);
            return table == null ? null : table.get(key);
        }
    }

    private static final Map<ResourceLocation, Metadata> METADATA = Maps.newConcurrentMap();
    /** Avatar formats already reported as undecodable, so one warning covers every avatar of that format. */
    private static final Set<Integer> WARNED_AVATAR_FORMATS = Collections
        .newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static volatile int version;

    private ClientModelMetadataRegistry() {}

    /**
     * Records the text a model's geometry declares. Authors are taken from here only while nothing richer is known,
     * so a later {@link #acceptStructuredAuthors} replaces them instead of being merged with the flat names.
     */
    public static void acceptExtraInfo(ResourceLocation modelId, ExtraInfo extraInfo) {
        if (modelId == null || extraInfo == null) {
            return;
        }
        Metadata metadata = metadata(modelId);
        if (isNotBlank(extraInfo.getName())) {
            metadata.name = extraInfo.getName();
        }
        if (isNotBlank(extraInfo.getTips())) {
            metadata.tips = extraInfo.getTips();
        }
        if (isNotBlank(extraInfo.getLicense())) {
            metadata.license = extraInfo.getLicense();
        }
        if (metadata.authors.isEmpty() && extraInfo.getAuthors() != null) {
            for (String author : extraInfo.getAuthors()) {
                if (isNotBlank(author)) {
                    metadata.authors.add(new Author(author, "", "", Collections.emptyMap()));
                }
            }
        }
        version++;
    }

    /**
     * Records the structured authors of a model, uploading their avatars. Must run on the client thread.
     */
    public static void acceptStructuredAuthors(ResourceLocation modelId, List<RawYsmModel.RawMetadata.Author> authors) {
        if (modelId == null || authors == null || authors.isEmpty()) {
            return;
        }
        Metadata metadata = metadata(modelId);
        List<Author> structured = new ArrayList<>(authors.size());
        for (int index = 0; index < authors.size(); index++) {
            RawYsmModel.RawMetadata.Author raw = authors.get(index);
            if (raw == null || !isNotBlank(raw.name)) {
                continue;
            }
            Author author = new Author(raw.name, raw.role, raw.comment, raw.contacts);
            author.avatar = uploadAvatar(modelId, index, raw.avatarImage);
            structured.add(author);
        }
        if (structured.isEmpty()) {
            return;
        }
        metadata.authors.clear();
        metadata.authors.addAll(structured);
        version++;
    }

    /** Metadata for a model, or {@code null} when the client never received any. */
    public static Metadata get(ResourceLocation modelId) {
        return modelId == null ? null : METADATA.get(modelId);
    }

    /**
     * Records the language tables a model's pack declares ({@code lang/*.json}). Must run on the client thread, like
     * the rest of this registry.
     * <p>
     * The tables are kept exactly as the pack wrote them - the locale keys are its own file names ({@code zh_cn},
     * {@code en_us}) - because which locale to resolve is a display-time question: the callers pass the running locale
     * in, normalized by {@link #currentLocale()}. Upstream does the same, collecting the tables when it builds a
     * model's info view and translating a key where the card is actually drawn
     * ({@code format/schema/model/views/ModelInfoView.java:45-58,95-111}).
     */
    public static void acceptLanguageFiles(ResourceLocation modelId, Map<String, Map<String, String>> files) {
        if (modelId == null || files == null || files.isEmpty()) {
            return;
        }
        Metadata metadata = metadata(modelId);
        metadata.languageFiles.clear();
        for (Map.Entry<String, Map<String, String>> entry : files.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue()
                .isEmpty()) {
                continue;
            }
            metadata.languageFiles.put(
                entry.getKey(),
                Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        version++;
    }

    /**
     * The running game locale in the form the packs name their language files: 1.7.10 reports {@code zh_CN} /
     * {@code en_US} while the files are {@code zh_cn.json} / {@code en_us.json}, so the code is lower-cased and
     * {@code '-'} folded to {@code '_'} ({@code Language.getLanguageCode():23}).
     */
    public static String currentLocale() {
        Language language = Minecraft.getMinecraft()
            .getLanguageManager()
            .getCurrentLanguage();
        return language == null ? "" : normalizeLocale(language.getLanguageCode());
    }

    /** Lower-cases a locale code and folds {@code '-'} to {@code '_'} (see {@link #currentLocale()}). */
    static String normalizeLocale(@Nullable String locale) {
        return locale == null ? "" : locale.toLowerCase(Locale.ROOT)
            .replace('-', '_');
    }

    /**
     * The name to draw for a model: the pack's own localized {@code metadata.name} for the running locale, else the
     * name the model's geometry declares, else the caller's leaf name.
     * <p>
     * That is upstream's fallback chain: {@code ModelInfoView.translateOr:91-93} returns the caller's default when
     * neither the requested locale nor {@code en_us} declares the key, and the port's default for the name is the
     * geometry's {@code extra_info} name followed by the leaf name - the order the three name-drawing sites already
     * used before they consulted a language table at all.
     *
     * @param locale the running locale, as produced by {@link #currentLocale()}. It is passed in rather than read
     *               here so the resolution rule is testable without a Minecraft instance.
     * @param leafName the last resort, normally the model's file name
     */
    public static String displayName(ResourceLocation modelId, String locale, String leafName) {
        Metadata metadata = get(modelId);
        if (metadata != null) {
            String localized = metadata.localized("metadata.name", locale);
            if (isNotBlank(localized)) {
                return localized;
            }
            if (isNotBlank(metadata.name)) {
                return metadata.name;
            }
        }
        return leafName == null ? "" : leafName;
    }

    /** Bumped on every change, so an open GUI can refresh instead of polling. */
    public static int version() {
        return version;
    }

    public static void clear() {
        METADATA.clear();
        version++;
    }

    private static Metadata metadata(ResourceLocation modelId) {
        return METADATA.computeIfAbsent(modelId, key -> new Metadata());
    }

    private static ResourceLocation uploadAvatar(ResourceLocation modelId, int index, RawYsmModel.RawImage image) {
        if (image == null || image.data == null || image.data.length == 0) {
            return null;
        }
        if (!isDecodable(image.data)) {
            // TextureManager logs a full stack trace for every texture it cannot read, and the Wine Fox pack alone
            // ships 23 AVIF and 3 WebP avatars, so this would be one stack per author. The JRE has no reader for
            // either format and no decoder is bundled, so the avatar is skipped - the author tile then draws its
            // placeholder - and the reason is reported once per format instead of once per avatar.
            int format = YSMFolderDeserializer.detectFormat(image.data);
            if (WARNED_AVATAR_FORMATS.add(format)) {
                ysmu.LOG.warn(
                    "Author avatars in {} cannot be decoded by this JVM (no ImageIO reader, no decoder bundled);"
                        + " those avatars are skipped and their tiles show a placeholder",
                    formatName(format));
            }
            return null;
        }
        ResourceLocation id = new ResourceLocation(
            ysmu.MODID,
            "avatar/" + Integer.toHexString(modelId.getResourcePath()
                .hashCode()) + "/" + index);
        try {
            TextureManager textureManager = Minecraft.getMinecraft()
                .getTextureManager();
            // Idempotent, like ClientModelManager.registerTexture: loadTexture alone would leak the previous GL
            // texture when the same model is registered twice.
            textureManager.deleteTexture(id);
            textureManager.loadTexture(id, new OuterFileTexture(image.data));
            return id;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Failed to upload author avatar {} of model {}", index, modelId, e);
            return null;
        }
    }

    /** Whether this JVM has an {@code ImageIO} reader for the payload; only the header is examined. */
    private static boolean isDecodable(byte[] data) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            return input != null && ImageIO.getImageReaders(input)
                .hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /** Format names for the warning, matching {@code YSMFolderDeserializer.detectFormat}. */
    private static String formatName(int format) {
        switch (format) {
            case 1:
                return "BMP";
            case 2:
                return "PNG";
            case 3:
                return "JPEG";
            case 4:
                return "WebP";
            case 5:
                return "AVIF/HEIF";
            default:
                return "an unknown format";
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim()
            .isEmpty();
    }
}
