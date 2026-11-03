package com.fox.ysmu.model.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The legacy {@code info.json} manifest: the metadata file a flat (pre-{@code ysm.json}) model directory, or an old
 * file-list {@code .ysm} container, carries next to {@code main.json} / {@code arm.json}.
 *
 * <h2>Upstream</h2>
 * Upstream reads exactly this file when the source has no {@code ysm.json}, and it is the <b>only</b> metadata source on
 * that path: {@code tmp/YesSteveModel-dev-1.20/format/parser/LegacyManifestBuilder.java:18,72-93} loads
 * {@code info.json} as an {@code ExtraInfo} and copies
 * {@code name}, {@code tips}, {@code license}, {@code authors} (a plain list of names),
 * {@code extra_animation_names} (mapped onto the wheel slots {@code extra0..extraN}) and {@code free} into the
 * manifest. The {@code ysm_extra_info} block baked into a legacy geometry is parsed by upstream too
 * ({@code GeoBuilder.java:46-50} returns it as {@code Result.legacyInfo}) but never consumed, so it is not the
 * manifest source upstream uses.
 *
 * <h2>Why this class also folds the manifest into the geometry</h2>
 * This port's legacy payload ({@code data.ModelData}) has no metadata field at all - the legacy sync container only
 * carries geometry, textures, animations, GUI images, links, the preview declaration and language tables. The channel
 * through which a legacy model's name / tips / authors / license / extra labels reach the client is the engine's
 * {@code ysm_extra_info} block inside the geometry JSON: the client parses the geometry, and
 * {@code ClientModelManager.java:2015-2018} files that block into
 * {@code ClientModelMetadataRegistry.acceptExtraInfo} and {@code ClientModelManager.EXTRA_ANIMATION_NAME}. The same
 * carrier is what the OpenYSM binary bridge already fills from {@code RawProperties}/{@code metadata}
 * ({@code RawYsmModelAdapter.applyOpenYsmModelInfo:938-965}). Folding {@code info.json} into that existing block is
 * therefore the minimal port-shaped way to make the manifest visible, and it applies the precedence the task requires:
 * every field the geometry already declares wins, {@code info.json} only fills what is missing.
 */
public final class LegacyInfo {

    /** The manifest's file name (upstream {@code LegacyManifestBuilder.INFO_FILE_NAME:18}). */
    public static final String FILE_NAME = "info.json";

    private static final String EXTRA_INFO_KEY = "ysm_extra_info";
    private static final String GEOMETRY_KEY = "minecraft:geometry";
    private static final String DESCRIPTION_KEY = "description";

    private LegacyInfo() {}

    /**
     * Reads {@code info.json} from a flat model directory, or {@code null} when the file is absent or unreadable.
     * An unreadable manifest is not a model failure: the model still loads, only without the metadata.
     */
    @Nullable
    public static JsonObject readFile(@Nullable Path modelDir) {
        if (modelDir == null) {
            return null;
        }
        Path file = modelDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return parse(Files.readAllBytes(file));
        } catch (IOException | RuntimeException unreadable) {
            ysmu.LOG.warn("Cannot read the legacy model manifest {}", file, unreadable);
            return null;
        }
    }

    /** Parses {@code info.json} bytes, or {@code null} when they are empty or not a JSON object. */
    @Nullable
    public static JsonObject parse(@Nullable byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            JsonElement root = new JsonParser().parse(new String(data, StandardCharsets.UTF_8));
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Fills a raw model's metadata from {@code info.json}, exactly upstream's {@code applyLegacyInfo}
     * ({@code LegacyManifestBuilder.java:78-93}): name, tips, license, author names and the wheel slots
     * {@code extra0..extraN} built from {@code extra_animation_names}.
     * <p>
     * Fields that already hold a value are left alone, so a source that also carries metadata (an {@code ysm.json}
     * model never reaches this path, but the guard keeps the helper safe if that changes) is not overwritten.
     */
    public static void applyToRawModel(@Nullable RawYsmModel model, @Nullable JsonObject info) {
        if (model == null || info == null) {
            return;
        }
        RawYsmModel.RawMetadata metadata = model.metadata;
        if (metadata == null) {
            metadata = new RawYsmModel.RawMetadata();
            model.metadata = metadata;
        }
        String name = text(info, "name");
        if (isBlank(metadata.name) && !isBlank(name)) {
            metadata.name = name;
        }
        String tips = text(info, "tips");
        if (isBlank(metadata.tips) && !isBlank(tips)) {
            metadata.tips = tips;
        }
        String license = text(info, "license");
        if (isBlank(metadata.licenseType) && !isBlank(license)) {
            metadata.licenseType = license;
        }
        if (metadata.authors.isEmpty()) {
            JsonArray authors = array(info, "authors");
            if (authors != null) {
                for (JsonElement element : authors) {
                    String authorName = authorName(element);
                    if (isBlank(authorName)) {
                        continue;
                    }
                    RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                    author.name = authorName;
                    metadata.authors.add(author);
                }
            }
        }
        JsonArray extraAnimationNames = array(info, "extra_animation_names");
        if (model.properties.extraAnimations.isEmpty() && extraAnimationNames != null) {
            for (int index = 0; index < extraAnimationNames.size(); index++) {
                String label = elementText(extraAnimationNames.get(index));
                if (isBlank(label)) {
                    continue;
                }
                // Upstream puts them into the wheel slots in declaration order: extra0, extra1, ...
                model.properties.extraAnimations.put("extra" + index, label);
            }
        }
        if (info.has("free") && info.get("free").isJsonPrimitive()) {
            model.properties.isFree = info.get("free").getAsBoolean();
        }
    }

    /**
     * Folds {@code info.json} into a legacy {@code main.json} geometry as {@code description.ysm_extra_info}, filling
     * only the fields the geometry does not already declare (see this class's javadoc for why this carrier is used).
     * <p>
     * The bytes are returned <b>unchanged</b> (same array) when there is nothing to add, so a model without
     * {@code info.json} keeps its payload byte-for-byte.
     *
     * @param mainJson the legacy main geometry JSON bytes
     * @param info     the parsed manifest, or {@code null} when the model has none
     * @return the geometry bytes to store, possibly the same array
     */
    public static byte[] mergeIntoGeometry(@Nullable byte[] mainJson, @Nullable JsonObject info) {
        if (mainJson == null || mainJson.length == 0 || info == null) {
            return mainJson;
        }
        JsonObject fields = newExtraInfoFields(info);
        if (fields.entrySet().isEmpty()) {
            return mainJson;
        }
        JsonObject root;
        try {
            JsonElement parsed = new JsonParser().parse(new String(mainJson, StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()) {
                return mainJson;
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            return mainJson;
        }
        JsonObject description = firstGeometryDescription(root);
        if (description == null) {
            return mainJson;
        }
        JsonObject target;
        if (description.has(EXTRA_INFO_KEY) && description.get(EXTRA_INFO_KEY).isJsonObject()) {
            target = description.getAsJsonObject(EXTRA_INFO_KEY);
        } else {
            target = new JsonObject();
            description.add(EXTRA_INFO_KEY, target);
        }
        boolean changed = false;
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            if (isDeclared(target, field.getKey())) {
                // The geometry's own declaration wins; info.json only fills gaps.
                continue;
            }
            target.add(field.getKey(), field.getValue());
            changed = true;
        }
        if (!changed && description.has(EXTRA_INFO_KEY)) {
            // Nothing new to carry; keep the geometry text untouched (only the empty ysm_extra_info wrapper may have
            // been added above, and that wrapper is only added when fields exist, which is this method's entry gate).
            return mainJson;
        }
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /** The geometry ExtraInfo fields {@code info.json} declares, in the engine's {@code ExtraInfo} naming. */
    private static JsonObject newExtraInfoFields(JsonObject info) {
        JsonObject fields = new JsonObject();
        putText(fields, "name", text(info, "name"));
        putText(fields, "tips", text(info, "tips"));
        putText(fields, "license", text(info, "license"));
        JsonArray authors = authorNames(array(info, "authors"));
        if (authors.size() > 0) {
            fields.add("authors", authors);
        }
        JsonArray extraAnimationNames = stringArray(array(info, "extra_animation_names"));
        if (extraAnimationNames.size() > 0) {
            fields.add("extra_animation_names", extraAnimationNames);
        }
        return fields;
    }

    private static void putText(JsonObject target, String key, String value) {
        if (!isBlank(value)) {
            target.addProperty(key, value);
        }
    }

    /** Whether the target already declares a usable (non-empty) value for {@code key}. */
    private static boolean isDeclared(JsonObject target, String key) {
        JsonElement element = target.get(key);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            return !isBlank(element.getAsString());
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size() > 0;
        }
        return true;
    }

    /**
     * The first {@code minecraft:geometry} entry's {@code description}, created when it is missing. The model loader
     * has already checked that this is a geometry object (the legacy readers validate it before calling), so a missing
     * description is the only shape this needs to cope with.
     */
    @Nullable
    private static JsonObject firstGeometryDescription(JsonObject root) {
        JsonElement geometries = root.get(GEOMETRY_KEY);
        if (geometries == null || !geometries.isJsonArray()) {
            return null;
        }
        for (JsonElement element : geometries.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject geometry = element.getAsJsonObject();
            if (geometry.has(DESCRIPTION_KEY) && geometry.get(DESCRIPTION_KEY).isJsonObject()) {
                return geometry.getAsJsonObject(DESCRIPTION_KEY);
            }
            JsonObject description = new JsonObject();
            geometry.add(DESCRIPTION_KEY, description);
            return description;
        }
        return null;
    }

    /** Author names from a legacy {@code authors} array (plain strings; an object element contributes its name). */
    private static JsonArray authorNames(@Nullable JsonArray authors) {
        JsonArray names = new JsonArray();
        if (authors == null) {
            return names;
        }
        for (JsonElement element : authors) {
            String name = authorName(element);
            if (!isBlank(name)) {
                // The runtime's Gson predates JsonArray#add(String); mirror the explicit JsonPrimitive style the rest
                // of this repository uses (e.g. YSMFolderDeserializer.createFaceUvNode).
                names.add(new JsonPrimitive(name));
            }
        }
        return names;
    }

    private static JsonArray stringArray(@Nullable JsonArray values) {
        JsonArray strings = new JsonArray();
        if (values == null) {
            return strings;
        }
        for (JsonElement element : values) {
            String text = elementText(element);
            if (!isBlank(text)) {
                strings.add(new JsonPrimitive(text));
            }
        }
        return strings;
    }

    @Nullable
    private static String authorName(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return text(element.getAsJsonObject(), "name");
        }
        return elementText(element);
    }

    @Nullable
    private static String elementText(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    @Nullable
    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    @Nullable
    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
