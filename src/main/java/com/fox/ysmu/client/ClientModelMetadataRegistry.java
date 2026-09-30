package com.fox.ysmu.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
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
