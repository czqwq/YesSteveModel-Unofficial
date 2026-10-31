package com.fox.ysmu.model.resource;

import static com.fox.ysmu.model.ServerModelManager.ARM_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.EXTRA_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.MAIN_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.defaultAnimationFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import software.bernie.geckolib3.geo.raw.pojo.Bone;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.FormatVersion;
import software.bernie.geckolib3.geo.raw.pojo.MinecraftGeometry;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;
import software.bernie.geckolib3.geo.raw.pojo.PolyMesh;
import software.bernie.geckolib3.geo.raw.pojo.PolysEnum;
import software.bernie.geckolib3.geo.raw.pojo.PolysUnion;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;

public final class RawYsmModelAdapter {

    private static final byte[] EMPTY_ANIMATION = "{\"animations\":{}}".getBytes(StandardCharsets.UTF_8);
    private static final String ANIMATION_FORMAT_VERSION = "1.8.0";
    private static final int RGBA_FORMAT = -1;
    private static final int PNG_FORMAT = 2;
    private static final int EXTRA_ANIMATION_SLOT_COUNT = 8;
    private static final String[] LOCALE_PREFERENCE = new String[] { "en_us", "en_US", "zh_cn", "zh_CN" };

    private RawYsmModelAdapter() {}

    public static boolean isBridgeable(RawYsmModel raw) {
        if (raw == null || raw.mainEntity == null) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
                if (raw == null) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: raw is null");
                else ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: mainEntity is null for {}", raw.modelId);
            }
            return false;
        }
        if (!hasGeometry(raw.mainEntity.mainModel)) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: no mainModel geometry for {}", raw.modelId);
            return false;
        }
        if (!hasGeometry(raw.mainEntity.armModel)) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: no armModel geometry for {}", raw.modelId);
            return false;
        }
        for (RawYsmModel.RawTexture texture : raw.mainEntity.textures.values()) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
                ysmu.LOG.info("[YSMU-MODEL]   check texture {}: format={}, dataLen={}, w={}, h={}, ok={}",
                    texture.name, texture.imageFormat,
                    texture.data == null ? 0 : texture.data.length,
                    texture.width, texture.height, hasLegacyTextureData(texture));
            }
            if (hasLegacyTextureData(texture)) {
                return true;
            }
        }
        return false;
    }

    /**
     * M-01:"能否产出可用的 OpenYSM 二进制载荷"的独立判定。
     * OpenYSM 二进制只序列化 bones/cubes/faces 与关键帧(见
     * {@link YSMBinarySerializer#writeGeometry}),不写 {@code geometry.sourceJson},
     * 所以这里必须忽略 sourceJson、只认已烘焙的面。对 dev 基线的散件模型
     * ({@code YSMFolderDeserializer.parseGeometry} 只填 bones、不填 cubes)它返回 false,
     * 从而跳过 {@code ModelCacheWriter.writeOpenYsm},不再下发一个几何为空的缓存。
     */
    public static boolean isBinaryPayloadBridgeable(RawYsmModel raw) {
        if (raw == null || raw.mainEntity == null) {
            return false;
        }
        if (countBakedFaces(raw.mainEntity.mainModel) <= 0 || countBakedFaces(raw.mainEntity.armModel) <= 0) {
            return false;
        }
        for (RawYsmModel.RawTexture texture : raw.mainEntity.textures.values()) {
            if (hasLegacyTextureData(texture)) {
                return true;
            }
        }
        return false;
    }

    /** 已烘焙(可直接序列化)的面数;只看 bones/cubes/faces,与 sourceJson 无关。 */
    public static int countBakedFaces(RawYsmModel.RawGeometry geometry) {
        if (geometry == null) {
            return 0;
        }
        int faces = 0;
        for (RawYsmModel.RawBone bone : geometry.bones) {
            for (RawYsmModel.RawCube cube : bone.cubes) {
                faces += cube.faces.size();
            }
        }
        return faces;
    }

    public static ModelData toLegacyModelData(RawYsmModel raw, String modelId) throws IOException {
        return toLegacyModelData(raw, modelId, false);
    }

    /**
     * Bridges a parsed model into the client payload, optionally keeping the binary channel's structured geometry
     * instead of flattening it to JSON and parsing it back.
     * <p>
     * The OpenYSM binary payload already carries bones/cubes/faces ({@code RawYsmModel}), and re-emitting them as
     * pretty-printed JSON only to have {@code Converter.fromJsonString} parse them again is the single largest cost
     * on the client's model-loading path (16.4 MB of JSON for a 933-bone pack). With {@code structured} on,
     * every sub-model that has no {@code sourceJson} (i.e. everything on the binary channel) is built straight into
     * engine POJOs and parked in {@link ModelData#getPreParsedGeometry()}; sub-models that DO have a {@code sourceJson}
     * (folder models, the built-in default, and the legacy container) keep their JSON bytes untouched, because that
     * text also carries fields this port's folder bake does not reproduce (see the note in {@code createGeometryPojo}).
     * <p>
     * Callers that write a container ({@code EncryptTools}, {@code OpenYsmFormat}) or that need bytes for any other
     * reason keep using the two-argument overload.
     */
    public static ModelData toLegacyModelData(RawYsmModel raw, String modelId, boolean structured)
        throws IOException {
        if (!isBridgeable(raw)) {
            throw new IOException("RawYsmModel cannot be bridged to legacy ModelData");
        }

        Map<String, byte[]> model = new LinkedHashMap<>();
        Map<String, RawGeoModel> preParsed = new LinkedHashMap<>();
        putGeometry(model, preParsed, raw, raw.mainEntity.mainModel, "main", true, structured);
        putGeometry(model, preParsed, raw, raw.mainEntity.armModel, "arm", false, structured);

        // Projectile sub-entity geometry (a pack's files.projectiles entry, e.g. #arrow). The key is
        // "projectile_<matchId>" and ClientModelManager turns it back into the sub-model id
        // "<model>/projectile_<matchId>" that the renderer looks up.
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub == null || sub.model == null) {
                continue;
            }
            for (String matchId : matchIdsOf(sub)) {
                try {
                    putGeometry(model, preParsed, raw, sub.model, "projectile_" + matchId, false,
                        structured);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to convert projectile geometry {} for model {}", matchId, modelId, e);
                }
            }
        }

        // Vehicle sub-entity geometry (a pack's files.vehicles entry, e.g. #minecraft:boat). Same key shape as the
        // projectile block above - "vehicle_<matchId>" - and ClientModelManager turns it back into the sub-model id
        // "<model>/vehicle_<matchId>" that VehicleRenderer looks up.
        //
        // Upstream carries the same data as a render target of kind RENDER_TARGET_KIND_VEHICLE
        // (RawModelAssembler.java:224-228) and bakes it into the same "main" geometry/animation domain as a
        // projectile (ModelRenderTargetLoader.java:259-271). This port has no render-target kind, so the sub-model id
        // is the whole carrier - exactly as it is for projectiles.
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.vehicles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub == null || sub.model == null) {
                continue;
            }
            for (String matchId : matchIdsOf(sub)) {
                try {
                    putGeometry(model, preParsed, raw, sub.model, "vehicle_" + matchId, false, structured);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to convert vehicle geometry {} for model {}", matchId, modelId, e);
                }
            }
        }

        Map<String, byte[]> textures = new LinkedHashMap<>();
        Map<String, Map<Integer, byte[]>> components = new LinkedHashMap<>();
        for (RawYsmModel.RawTexture texture : raw.mainEntity.textures.values()) {
            if (texture.data == null) {
                continue;
            }
            byte[] textureData = getLegacyTextureData(texture);
            if (textureData == null) {
                ysmu.LOG.warn(
                    "Skipping unsupported OpenYSM texture {} (format {}) in model {}",
                    textureName(texture),
                    texture.imageFormat,
                    modelId);
                continue;
            }
            String fileName = texture.sourceFileName;
            if (StringUtils.isBlank(fileName)) {
                fileName = texture.name.endsWith(".png") ? texture.name : texture.name + ".png";
            }
            textures.put(fileName, textureData);
            components.put(fileName, pbrComponents(texture));
        }
        // Projectile sub-entity textures. The key carries the matchId as well as the file name, because the renderer
        // picks a projectile's texture by searching for "projectile_<matchId>_" in the id.
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub == null) {
                continue;
            }
            for (String matchId : matchIdsOf(sub)) {
                for (RawYsmModel.RawTexture texture : sub.textures.values()) {
                    if (texture.data == null) {
                        continue;
                    }
                    byte[] textureData = getLegacyTextureData(texture);
                    if (textureData == null) {
                        ysmu.LOG.warn(
                            "Skipping unsupported projectile texture {} (format {}) for {}",
                            textureName(texture),
                            texture.imageFormat,
                            matchId);
                        continue;
                    }
                    String textureKey = "projectile_" + matchId + "_" + texture.name;
                    if (!textureKey.endsWith(".png")) {
                        textureKey += ".png";
                    }
                    textures.put(textureKey, textureData);
                    components.put(textureKey, pbrComponents(texture));
                }
            }
        }
        // Vehicle sub-entity textures, keyed like the projectile ones: "vehicle_<matchId>_<file>". VehicleRenderer
        // picks a vehicle's texture by searching for "vehicle_<matchId>_" in the registered texture ids, so the key
        // carries the matchId as well as the file name.
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.vehicles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub == null) {
                continue;
            }
            for (String matchId : matchIdsOf(sub)) {
                for (RawYsmModel.RawTexture texture : sub.textures.values()) {
                    if (texture.data == null) {
                        continue;
                    }
                    byte[] textureData = getLegacyTextureData(texture);
                    if (textureData == null) {
                        ysmu.LOG.warn(
                            "Skipping unsupported vehicle texture {} (format {}) for {}",
                            textureName(texture),
                            texture.imageFormat,
                            matchId);
                        continue;
                    }
                    String textureKey = "vehicle_" + matchId + "_" + texture.name;
                    if (!textureKey.endsWith(".png")) {
                        textureKey += ".png";
                    }
                    textures.put(textureKey, textureData);
                    components.put(textureKey, pbrComponents(texture));
                }
            }
        }
        if (textures.isEmpty()) {
            throw new IOException("RawYsmModel has no legacy-compatible player textures");
        }

        Map<String, byte[]> animations = new LinkedHashMap<>();
        Map<String, RawYsmModel.RawAnimationFile> preParsedAnimations = new LinkedHashMap<>();
        putAnimation(animations, preParsedAnimations, raw, "main", MAIN_ANIMATION_FILE_NAME, structured);
        putAnimation(animations, preParsedAnimations, raw, "arm", ARM_ANIMATION_FILE_NAME, structured);
        putAnimation(animations, preParsedAnimations, raw, "extra", EXTRA_ANIMATION_FILE_NAME, structured);
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> entry : raw.mainEntity.animationFiles.entrySet()) {
            if (!animations.containsKey(entry.getKey())
                && !preParsedAnimations.containsKey(entry.getKey())) {
                putAnimationFile(animations, preParsedAnimations, entry.getKey(), entry.getValue(), structured);
            }
        }
        // Projectile animations and controllers. Both are complete data on a sub-entity
        // (RawSubEntity.animationFiles / animationControllerFiles) and were previously thrown away here - the only
        // place that handled them was the server-sync path. Losing them is not "one animation fewer": with no
        // AnimationFile for the projectile the renderer returns before sampling anything, the geometry stays in its
        // bind pose, and every sub-model the pack hides by scaling a bone (bow, crossbow, the effect boxes) is drawn
        // at once.
        //
        // The keys match FolderFormat's: animation "projectile_<matchId>", controller "projectile_ctrl_<matchId>".
        // ClientModelManager resolves both to the same sub-model id, so the local and synced paths agree.
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub == null) {
                continue;
            }
            for (String matchId : matchIdsOf(sub)) {
                for (RawYsmModel.RawAnimationFile animationFile : sub.animationFiles.values()) {
                    putAnimationFile(animations, preParsedAnimations, "projectile_" + matchId, animationFile,
                        structured);
                }
                for (RawYsmModel.RawAnimationControllerFile controllerFile : sub.animationControllerFiles) {
                    if (controllerFile == null) {
                        continue;
                    }
                    byte[] data = controllerFile.sourceJson == null ? createControllerJson(controllerFile)
                        : controllerFile.sourceJson;
                    if (data != null && data.length > 0) {
                        animations.put("projectile_ctrl_" + matchId, data);
                    }
                }
            }
        }
        // Vehicle animations and controllers, keyed like the projectile ones: animation "vehicle_<matchId>",
        // controller "vehicle_ctrl_<matchId>". They must NOT merge into the player's animation namespace - a pack's
        // boat set and its player set can both define a clip name, and merging lets one overwrite the other - so
        // ClientModelManager registers each under the vehicle's own sub-model id, exactly as it does for projectiles.
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.vehicles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub == null) {
                continue;
            }
            for (String matchId : matchIdsOf(sub)) {
                for (RawYsmModel.RawAnimationFile animationFile : sub.animationFiles.values()) {
                    putAnimationFile(animations, preParsedAnimations, "vehicle_" + matchId, animationFile,
                        structured);
                }
                for (RawYsmModel.RawAnimationControllerFile controllerFile : sub.animationControllerFiles) {
                    if (controllerFile == null) {
                        continue;
                    }
                    byte[] data = controllerFile.sourceJson == null ? createControllerJson(controllerFile)
                        : controllerFile.sourceJson;
                    if (data != null && data.length > 0) {
                        animations.put("vehicle_ctrl_" + matchId, data);
                    }
                }
            }
        }
        putAnimationControllers(animations, raw);

        // The pack's GUI presentation images: its ysm.json's gui_background / gui_foreground, read into
        // RawYsmModel.RawImage by both parsers (YSMFolderDeserializer.loadGuiImage:240-251 passes the ids
        // "gui_foreground" / "gui_background" into RawYsmModel.RawImage.name). Upstream reads the same two paths into
        // the model's settings as presentation image blobs (format/parser/ModelInfoBuilder.java:57-64) and its catalog
        // card requests them per model (client/gui/button/CatalogModelCardState.java:238-246); the port has no
        // manifest, so they travel beside the payload in ModelData#getGuiImages and the selection tile draws them.
        //
        // They are normalised to PNG here, like the model textures above: the folder parser already did it
        // (YSMFolderDeserializer.parseImage:1091-1104), but a binary .ysm keeps the pack's own image format and
        // upstream normalises those while mapping the model for the client (YSMClientMapper.buildExtraTextures:634-644
        // calls its toPng over exactly these background images).
        //
        // Deliberately NOT the two maps next door: `textures` is the model's texture *list* - ServerModelInfo is
        // built from its key set (ModelData's constructor) and the selection screen offers it as selectable skins, so
        // a GUI background in there would become a skin and move the texture count - and `model` is a map of geometry
        // files that ClientModelManager parses as JSON key by key.
        Map<String, byte[]> guiImages = new LinkedHashMap<>();
        for (RawYsmModel.RawImage image : raw.properties.backgroundImages) {
            if (image == null || image.data == null || StringUtils.isBlank(image.name)) {
                continue;
            }
            guiImages.put(image.name, toLegacyImageData(image));
        }

        // The model's metadata links travel beside the wheel declarations for the same reason they do: this bridge is
        // the only place a parsed RawYsmModel becomes a client payload, so both sync channels get them here - the
        // OpenYSM channel calls this from OpenYsmModelSyncClient, and the legacy container is written from the
        // ModelData this returns (EncryptTools.LINKS_MODEL_VERSION carries the field on that channel).
        // Upstream draws the first two on its information screen (client/gui/ModelInfoScreen.java:111-124).
        //
        // The GUI preview declaration rides here for the same reason, and because this bridge is also the route the
        // built-in default's payload takes: `loadDefaultModel` reads `builtin/default` with YSMFolderDeserializer and
        // hands the parsed model straight to this method (ClientModelManager:2430), so without this the fallback -
        // which declares `"preview_animation": "gui"` - would reach the GUI registration with nothing to register.
        // Upstream has both values in the loaded model's settings and reads them off it where the card needs them
        // (client/gui/button/CatalogModelCardState.java:228, :160).
        //
        // The language tables ride here too, and for the same reason: this is the only place a parsed RawYsmModel
        // becomes a client payload, so both sync channels get them - the OpenYSM channel calls this from
        // OpenYsmModelSyncClient, and the legacy container is written from the ModelData this returns. Upstream
        // resolves a name at display time against the tables it collected from the pack
        // (format/schema/model/views/ModelInfoView.java:45-58 collects them, :95-111 translate() = requested locale
        // else en_us). Only the table is carried: RawLanguageFile.hash is never read upstream, and the client display
        // layer stores the plain nested map.
        Map<String, Map<String, String>> languageFiles = new LinkedHashMap<>();
        if (raw.languageFiles != null) {
            for (Map.Entry<String, RawYsmModel.RawLanguageFile> entry : raw.languageFiles.entrySet()) {
                RawYsmModel.RawLanguageFile file = entry.getValue();
                if (entry.getKey() == null || file == null || file.data == null || file.data.isEmpty()) {
                    continue;
                }
                languageFiles.put(entry.getKey(), new LinkedHashMap<>(file.data));
            }
        }
        ModelData data = new ModelData(modelId, Type.FOLDER, model, textures, animations, guiImages,
            com.fox.ysmu.data.WheelDeclarations.from(raw.properties), components,
            raw.metadata == null ? null : raw.metadata.links,
            raw.properties == null ? null : raw.properties.previewAnimation,
            raw.properties != null && raw.properties.disablePreviewRotation,
            languageFiles);
        if (!preParsed.isEmpty()) {
            data.setPreParsedGeometry(preParsed);
        }
        if (!preParsedAnimations.isEmpty()) {
            data.setPreParsedAnimations(preParsedAnimations);
        }
        return data;
    }

    /**
     * Writes one sub-model either as JSON bytes or as engine POJOs, depending on where it came from.
     * <p>
     * The choice is per geometry, not per payload: a folder model has a {@code sourceJson} for each file and keeps
     * it, a binary payload has none for any of them. See
     * {@link #toLegacyModelData(RawYsmModel, String, boolean)}.
     */
    private static void putGeometry(Map<String, byte[]> bytes, Map<String, RawGeoModel> pojos, RawYsmModel raw,
        RawYsmModel.RawGeometry geometry, String key, boolean includeModelInfo, boolean structured)
        throws IOException {
        if (structured && geometry.sourceJson == null) {
            pojos.put(key, createGeometryPojo(raw, geometry, includeModelInfo));
        } else {
            bytes.put(key, toGeometryJson(raw, geometry, includeModelInfo));
        }
    }

    /**
     * The entity ids a sub-entity (projectile or vehicle) answers to: its declared {@code match} list, or its
     * identifier when it declares none.
     * <p>
     * One sub-entity may match several entity types (a pack's {@code #arrow} covering both {@code minecraft:arrow} and
     * {@code minecraft:spectral_arrow}), and each needs its own entry under its own id, because the renderer looks a
     * sub-entity up by the entity type it is drawing. A leading {@code #} is upstream's entity-type-tag syntax
     * ({@code RawModelAssembler.java:253-255} writes the string verbatim into {@code RenderTarget.match}); 1.7.10 has
     * no data-driven entity tags, so the consumer strips it and compares the rest - the id written here stays exactly
     * as the pack wrote it.
     */
    private static java.util.List<String> matchIdsOf(RawYsmModel.RawSubEntity sub) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (sub.matchIds != null && sub.matchIds.length > 0) {
            for (String matchId : sub.matchIds) {
                if (StringUtils.isNotBlank(matchId)) {
                    ids.add(matchId);
                }
            }
            return ids;
        }
        if (StringUtils.isNotBlank(sub.identifier)) {
            ids.add(sub.identifier);
        }
        return ids;
    }

    private static boolean hasGeometry(RawYsmModel.RawGeometry geometry) {
        if (geometry == null) {
            return false;
        }
        if (geometry.sourceJson != null) {
            return true;
        }
        for (RawYsmModel.RawBone bone : geometry.bones) {
            for (RawYsmModel.RawCube cube : bone.cubes) {
                if (!cube.faces.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasLegacyTextureData(RawYsmModel.RawTexture texture) {
        if (texture == null || texture.data == null) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: texture null or data null");
            return false;
        }
        if (texture.imageFormat == PNG_FORMAT) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: PNG format, OK");
            return true;
        }
        if (texture.imageFormat == RGBA_FORMAT) {
            boolean ok = canConvertRgba(texture);
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE && ok) {
                ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: RGBA format, OK");
            }
            return ok;
        }
        // BMP/JPEG/WebP/AVIF: getLegacyTextureData decodes them through ModelImageConverter and re-encodes PNG,
        // exactly as upstream normalises model images on its server->client mapping (YSMClientMapper.toPng:63-109,
        // called by buildParsedBundle:113-135). A folder model is normalised earlier, while it is parsed
        // (YSMFolderDeserializer.parseTexture:360-372), but a binary .ysm keeps the pack's own image bytes and
        // format - so without this branch a format-26 pack whose only player texture is WebP parses completely and
        // is then rejected as "cannot be bridged to legacy ModelData" (Endfield_Rossi_v1.1.ysm).
        boolean convertible = isConvertibleImageFormat(texture.imageFormat);
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: format {} normalisable to PNG={}", texture.imageFormat, convertible);
        }
        return convertible;
    }

    /**
     * The image formats {@link ModelImageConverter} decodes and re-encodes as PNG, i.e. everything the model
     * formats can carry besides PNG itself: 1=BMP, 3=JPEG, 4=WEBP, 5=AVIF. Raw RGBA is {@link #RGBA_FORMAT} and is
     * converted by the branch next to it.
     */
    private static boolean isConvertibleImageFormat(int imageFormat) {
        return imageFormat == 1 || imageFormat == 3 || imageFormat == 4 || imageFormat == 5;
    }

    private static Map<Integer, byte[]> pbrComponents(RawYsmModel.RawTexture texture) {
        Map<Integer, byte[]> output = new LinkedHashMap<>();
        // Legacy YSMClientMapper:117-126: 1=NORMAL, 2=SPECULAR, attached to this base, not skins.
        for (RawYsmModel.RawTexture.SubTexture sub : texture.subTextures) {
            if (sub.data == null || sub.specularType != 1 && sub.specularType != 2) continue;
            RawYsmModel.RawTexture component = new RawYsmModel.RawTexture();
            component.data = sub.data;
            component.imageFormat = sub.imageFormat;
            component.width = sub.width;
            component.height = sub.height;
            byte[] png = getLegacyTextureData(component);
            if (png != null) output.put(sub.specularType, png);
        }
        return output;
    }

    private static byte[] getLegacyTextureData(RawYsmModel.RawTexture texture) {
        if (texture.imageFormat == PNG_FORMAT) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: PNG, returning as-is ({} bytes)", texture.data.length);
            return texture.data;
        }
        if (texture.imageFormat == RGBA_FORMAT && canConvertRgba(texture)) {
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: RGBA->PNG ({}x{})", texture.width, texture.height);
            return convertRgbaToPng(texture.data, texture.width, texture.height);
        }
        if (!isConvertibleImageFormat(texture.imageFormat)) {
            return null;
        }
        // A binary .ysm keeps the pack's own image format; the client texture path (and ModelData itself) carries
        // PNG only. This is the port of upstream's YSMClientMapper.toPng, which decodes BMP/JPEG/WebP/AVIF with the
        // ImageStream decoders and re-encodes PNG while mapping a model for the client (:63-109; the main textures
        // at :113-128, sub-textures at :117-125, sub-entity textures at :592-597/:623-628). ModelImageConverter is
        // the 1.7.10 port of that step and passes PNG through untouched/keeps undecodable bytes, so a failure here
        // means "this texture cannot be shipped" and the caller skips it (existing behaviour for unknown formats).
        ModelImageConverter.Result converted = ModelImageConverter.toPng(texture.data, textureName(texture));
        if (!converted.isPng()) {
            return null;
        }
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: format {} -> PNG ({} bytes)", texture.imageFormat, converted.data.length);
        }
        return converted.data;
    }

    private static boolean canConvertRgba(RawYsmModel.RawTexture texture) {
        long requiredBytes = (long) texture.width * texture.height * 4L;
        return texture.width > 0 && texture.height > 0 && texture.data.length >= requiredBytes;
    }

    private static byte[] convertRgbaToPng(byte[] rgbaData, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int r = rgbaData[i * 4] & 0xFF;
            int g = rgbaData[i * 4 + 1] & 0xFF;
            int b = rgbaData[i * 4 + 2] & 0xFF;
            int a = rgbaData[i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (ImageIO.write(image, "PNG", output)) {
                return output.toByteArray();
            }
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to convert OpenYSM RGBA texture to PNG", e);
        }
        return null;
    }

    /**
     * The bytes of a GUI presentation image to ship: PNG whenever the image can be decoded, the original bytes
     * otherwise - exactly {@link ModelImageConverter#toPng}'s contract.
     * <p>
     * A folder model's images are already normalised while the folder is parsed
     * ({@code YSMFolderDeserializer.parseImage:1091-1104}), so this is mostly a pass-through for them; a binary
     * {@code .ysm} keeps the pack's own image format, and upstream normalises those while mapping the model for the
     * client ({@code YSMClientMapper.buildExtraTextures:634-644} calls its {@code toPng} over the same
     * {@code backgroundImages}). Without it a binary model whose background is WebP would hand the client's PNG-only
     * texture path a WebP payload.
     */
    private static byte[] toLegacyImageData(RawYsmModel.RawImage image) {
        if (image == null || image.data == null) {
            return null;
        }
        return ModelImageConverter.toPng(image.data, StringUtils.defaultIfBlank(image.name, "gui_image")).data;
    }

    private static byte[] toGeometryJson(RawYsmModel raw, RawYsmModel.RawGeometry geometry, boolean includeModelInfo)
        throws IOException {
        JsonObject root;
        if (geometry.sourceJson != null) {
            root = parseJsonObject(geometry.sourceJson, geometry.identifier);
        } else {
            root = createGeometryJson(geometry);
        }

        JsonObject description = getOrCreateDescription(root);
        // RawModelAssembler:204,270 gives player/arm and replacement targets the same active settings.
        if (raw != null) description.addProperty("ysm_all_cutout", raw.properties.allCutout);
        if (includeModelInfo) {
            applyOpenYsmModelInfo(raw, description);
        }
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject parseJsonObject(byte[] bytes, String sourceName) throws IOException {
        JsonElement element;
        try {
            element = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("Invalid geometry JSON " + sourceName, e);
        }
        if (element == null || !element.isJsonObject()) {
            throw new IOException("Expected geometry JSON object " + sourceName);
        }
        return element.getAsJsonObject();
    }

    private static JsonObject createGeometryJson(RawYsmModel.RawGeometry geometry) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        JsonArray geometries = new JsonArray();
        JsonObject model = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty(
            "identifier",
            StringUtils.defaultIfBlank(geometry.identifier, "geometry.ysmu.generated"));
        description.addProperty("texture_width", (double) defaultPositive(geometry.textureWidth, 64f));
        description.addProperty("texture_height", (double) defaultPositive(geometry.textureHeight, 64f));
        if (geometry.visibleBoundsWidth > 0f) {
            description.addProperty("visible_bounds_width", (double) geometry.visibleBoundsWidth);
        }
        if (geometry.visibleBoundsHeight > 0f) {
            description.addProperty("visible_bounds_height", (double) geometry.visibleBoundsHeight);
        }
        if (geometry.visibleBoundsOffset != null && geometry.visibleBoundsOffset.length > 0) {
            description.add("visible_bounds_offset", floatArray(geometry.visibleBoundsOffset));
        }
        model.add("description", description);

        JsonArray bones = new JsonArray();
        for (RawYsmModel.RawBone rawBone : geometry.bones) {
            bones.add(createBoneJson(rawBone, geometry));
        }
        model.add("bones", bones);
        geometries.add(model);
        root.add("minecraft:geometry", geometries);
        return root;
    }

    private static JsonObject createBoneJson(RawYsmModel.RawBone rawBone, RawYsmModel.RawGeometry geometry) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", StringUtils.defaultIfBlank(rawBone.name, "bone"));
        if (StringUtils.isNotBlank(rawBone.parentName)) {
            bone.addProperty("parent", rawBone.parentName);
        }
        bone.add("pivot", generatedPivotArray(rawBone.pivot));
        bone.add("rotation", generatedRotationArray(rawBone.rotation));
        JsonObject polyMesh = createPolyMeshJson(rawBone);
        if (polyMesh != null) {
            bone.add("poly_mesh", polyMesh);
        }
        return bone;
    }

    private static JsonObject createPolyMeshJson(RawYsmModel.RawBone rawBone) {
        JsonArray positions = new JsonArray();
        JsonArray normals = new JsonArray();
        JsonArray uvs = new JsonArray();

        for (RawYsmModel.RawCube rawCube : rawBone.cubes) {
            for (RawYsmModel.RawFace face : rawCube.faces) {
                for (int i = 0; i < 4; i++) {
                    float[] position = face.positions[i];
                    positions.add(new JsonPrimitive((double) getVectorValue(position, 0)));
                    positions.add(new JsonPrimitive((double) getVectorValue(position, 1)));
                    positions.add(new JsonPrimitive((double) getVectorValue(position, 2)));

                    normals.add(new JsonPrimitive((double) getVectorValue(face.normal, 0)));
                    normals.add(new JsonPrimitive((double) getVectorValue(face.normal, 1)));
                    normals.add(new JsonPrimitive((double) getVectorValue(face.normal, 2)));

                    uvs.add(new JsonPrimitive((double) face.u[i]));
                    uvs.add(new JsonPrimitive((double) face.v[i]));
                }
            }
        }

        if (positions.size() == 0) {
            return null;
        }

        JsonObject mesh = new JsonObject();
        mesh.addProperty("normalized_uvs", true);
        mesh.addProperty("polys", "quad_list");
        mesh.add("positions", positions);
        mesh.add("normals", normals);
        mesh.add("uvs", uvs);
        return mesh;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Direct construction of the engine POJOs (the OpenYSM binary channel's alternative to "JSON then parse")
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The direct-construction twin of {@link #createGeometryJson}: the same values in the same units, handed to the
     * engine as POJOs instead of as text. Used only for sub-models that have no {@code sourceJson}, i.e. everything
     * the binary payload carries.
     * <p>
     * The two sign conventions are not obvious and are copied verbatim from the JSON writer above:
     * {@code pivot} is written as {@code (-x, +y, +z)} and {@code rotation} as
     * {@code (-toDegrees(x), -toDegrees(y), +toDegrees(z))}, because {@code GeoBuilder.constructBone} negates the
     * pivot's X and the rotation's X/Y and then converts back to radians. Writing the raw values here would mirror
     * every model.
     * <p>
     * Deliberately not used for folder models: their own JSON is kept verbatim, because the folder bake drops fields
     * this builder does not reproduce ({@code uv_rotation}, {@code locators}, {@code never_render},
     * {@code texture_meshes}, {@code material_instance} - see {@code YSMFolderDeserializer.parseCube/bakeFace}), and
     * re-emitting those models through POJOs would silently lose them.
     */
    private static RawGeoModel createGeometryPojo(RawYsmModel raw, RawYsmModel.RawGeometry geometry,
        boolean includeModelInfo) {
        RawGeoModel root = new RawGeoModel();
        root.setFormatVersion(FormatVersion.VERSION_1_12_0);

        MinecraftGeometry model = new MinecraftGeometry();
        model.setProperties(createModelPropertiesPojo(raw, geometry, includeModelInfo));

        Bone[] bones = new Bone[geometry.bones.size()];
        for (int index = 0; index < geometry.bones.size(); index++) {
            bones[index] = createBonePojo(geometry.bones.get(index));
        }
        model.setBones(bones);

        root.setMinecraftGeometry(new MinecraftGeometry[] { model });
        return root;
    }

    private static ModelProperties createModelPropertiesPojo(RawYsmModel raw, RawYsmModel.RawGeometry geometry,
        boolean includeModelInfo) {
        ModelProperties properties = new ModelProperties();
        properties.setIdentifier(StringUtils.defaultIfBlank(geometry.identifier, "geometry.ysmu.generated"));
        properties.setTextureWidth((double) defaultPositive(geometry.textureWidth, 64f));
        properties.setTextureHeight((double) defaultPositive(geometry.textureHeight, 64f));
        if (geometry.visibleBoundsWidth > 0f) {
            properties.setVisibleBoundsWidth((double) geometry.visibleBoundsWidth);
        }
        if (geometry.visibleBoundsHeight > 0f) {
            properties.setVisibleBoundsHeight((double) geometry.visibleBoundsHeight);
        }
        if (geometry.visibleBoundsOffset != null && geometry.visibleBoundsOffset.length > 0) {
            double[] offset = new double[geometry.visibleBoundsOffset.length];
            for (int index = 0; index < offset.length; index++) {
                offset[index] = geometry.visibleBoundsOffset[index];
            }
            properties.setVisibleBoundsOffset(offset);
        }
        // Written for every geometry (main, arm, projectile, vehicle), like the JSON writer does at :556.
        properties.setForceCulling(raw.properties.allCutout);
        if (includeModelInfo) {
            properties.setHeightScale((double) raw.properties.heightScale);
            properties.setWidthScale((double) raw.properties.widthScale);
            properties.setRenderLayersFirst(raw.properties.renderLayersFirst);
            if (raw.properties.defaultTexture != null && !raw.properties.defaultTexture.isEmpty()) {
                properties.setDefaultTexture(raw.properties.defaultTexture);
            }
            properties.setExtraInfo(createExtraInfoPojo(raw));
        }
        return properties;
    }

    private static ExtraInfo createExtraInfoPojo(RawYsmModel raw) {
        ExtraInfo info = new ExtraInfo();
        info.setName(raw.metadata.name);
        info.setTips(raw.metadata.tips);
        String[] extraAnimationNames = getExtraAnimationNames(raw);
        if (extraAnimationNames.length > 0) {
            info.setExtraAnimationNames(extraAnimationNames);
        }
        List<String> authors = new ArrayList<>();
        for (RawYsmModel.RawMetadata.Author author : raw.metadata.authors) {
            if (StringUtils.isBlank(author.name)) {
                continue;
            }
            if (StringUtils.isBlank(author.role)) {
                authors.add(author.name);
            } else {
                authors.add(author.name + " (" + author.role + ")");
            }
        }
        if (!authors.isEmpty()) {
            info.setAuthors(authors.toArray(new String[0]));
        }
        // Left at the class default ("All Rights Reserved") when the pack declares nothing, which is exactly what
        // Jackson leaves there when the JSON omits the key.
        String license = StringUtils.defaultIfBlank(raw.metadata.licenseType, raw.metadata.licenseDescription);
        if (StringUtils.isNotBlank(license)) {
            info.setLicense(license);
        }
        return info;
    }

    private static Bone createBonePojo(RawYsmModel.RawBone rawBone) {
        Bone bone = new Bone();
        bone.setName(StringUtils.defaultIfBlank(rawBone.name, "bone"));
        if (StringUtils.isNotBlank(rawBone.parentName)) {
            bone.setParent(rawBone.parentName);
        }
        float pivotX = getVectorValue(rawBone.pivot, 0);
        float pivotY = getVectorValue(rawBone.pivot, 1);
        float pivotZ = getVectorValue(rawBone.pivot, 2);
        bone.setPivot(new double[] { (double) -pivotX, (double) pivotY, (double) pivotZ });

        float rotationX = getVectorValue(rawBone.rotation, 0);
        float rotationY = getVectorValue(rawBone.rotation, 1);
        float rotationZ = getVectorValue(rawBone.rotation, 2);
        bone.setRotation(
            new double[] { -Math.toDegrees(rotationX), -Math.toDegrees(rotationY), Math.toDegrees(rotationZ) });

        PolyMesh mesh = createPolyMeshPojo(rawBone);
        if (mesh != null) {
            bone.setPolyMesh(mesh);
        }
        return bone;
    }

    private static PolyMesh createPolyMeshPojo(RawYsmModel.RawBone rawBone) {
        int quadCount = 0;
        for (RawYsmModel.RawCube rawCube : rawBone.cubes) {
            quadCount += rawCube.faces.size();
        }
        if (quadCount == 0) {
            return null;
        }
        int vertexCount = quadCount * 4;
        double[] positions = new double[vertexCount * 3];
        double[] normals = new double[vertexCount * 3];
        double[] uvs = new double[vertexCount * 2];
        int positionIndex = 0;
        int uvIndex = 0;
        for (RawYsmModel.RawCube rawCube : rawBone.cubes) {
            for (RawYsmModel.RawFace face : rawCube.faces) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    float[] position = face.positions[vertex];
                    positions[positionIndex++] = (double) getVectorValue(position, 0);
                    positions[positionIndex++] = (double) getVectorValue(position, 1);
                    positions[positionIndex++] = (double) getVectorValue(position, 2);

                    normals[positionIndex - 3] = (double) getVectorValue(face.normal, 0);
                    normals[positionIndex - 2] = (double) getVectorValue(face.normal, 1);
                    normals[positionIndex - 1] = (double) getVectorValue(face.normal, 2);

                    uvs[uvIndex++] = (double) face.u[vertex];
                    uvs[uvIndex++] = (double) face.v[vertex];
                }
            }
        }
        PolyMesh mesh = new PolyMesh();
        mesh.setNormalizedUvs(Boolean.TRUE);
        PolysUnion polys = new PolysUnion();
        polys.enumValue = PolysEnum.QUAD_LIST;
        mesh.setPolys(polys);
        mesh.setPositions(positions);
        mesh.setNormals(normals);
        mesh.setUvs(uvs);
        return mesh;
    }

    private static JsonObject getOrCreateDescription(JsonObject root) {        JsonObject geometry = getOrCreateGeometry(root);
        JsonObject description;
        if (geometry.has("description") && geometry.get("description").isJsonObject()) {
            description = geometry.getAsJsonObject("description");
        } else {
            description = new JsonObject();
            geometry.add("description", description);
        }
        return description;
    }

    private static JsonObject getOrCreateGeometry(JsonObject root) {
        JsonArray geometries;
        if (root.has("minecraft:geometry") && root.get("minecraft:geometry").isJsonArray()) {
            geometries = root.getAsJsonArray("minecraft:geometry");
        } else {
            geometries = new JsonArray();
            root.add("minecraft:geometry", geometries);
        }
        if (geometries.size() > 0 && geometries.get(0).isJsonObject()) {
            return geometries.get(0).getAsJsonObject();
        }
        JsonObject geometry = new JsonObject();
        geometries.add(geometry);
        return geometry;
    }

    private static void applyOpenYsmModelInfo(RawYsmModel raw, JsonObject description) {
        description.addProperty("ysm_height_scale", (double) raw.properties.heightScale);
        description.addProperty("ysm_width_scale", (double) raw.properties.widthScale);
        // The renderer orders its layer pass by this, and the packed payload is the only place the client can read it
        // from, so it travels with the other host-specific model properties.
        description.addProperty("ysm_render_layers_first", raw.properties.renderLayersFirst);
        // The texture the model declares as its own default. Upstream resolves the texture that gets drawn inside
        // the model that was loaded - the requested name, then this declared default, then the first entry
        // (ModelManifestLookup:10-29) - and the packed payload is the only place the client can read it from, so it
        // travels with the other host-specific properties, exactly like render_layers_first above. The client still
        // validates it against the model's own texture list before binding it, the way upstream's containsTexture
        // does, so the field's own "default" sentinel and a stale name fall through instead of binding nothing.
        if (raw.properties.defaultTexture != null && !raw.properties.defaultTexture.isEmpty()) {
            description.addProperty("ysm_default_texture", raw.properties.defaultTexture);
        }
        // Upstream's forceCulling, which it parses from the pack's "all_cutout"
        // (format/parser/pojo/manifest/settings/ModelProperties.java:35-36 names the field forceCulling and gives it
        // @SerializedName("all_cutout")) and feeds to its bake. The port parses and syncs that field already
        // (YSMFolderDeserializer:174, YSMBinarySerializer:491 / YSMBinaryDeserializer:620) and carries it here under a
        // ysm_ name like the other host-specific properties, so the client holds what upstream's client holds.
        // Nothing consumes it yet: upstream's only consumer is the native bake, so that is recorded as a divergence
        // rather than filled with an invented consumer.
        description.addProperty("ysm_all_cutout", raw.properties.allCutout);

        JsonObject extraInfo = new JsonObject();
        extraInfo.addProperty("name", raw.metadata.name);
        extraInfo.addProperty("tips", raw.metadata.tips);
        String[] extraAnimationNames = getExtraAnimationNames(raw);
        if (extraAnimationNames.length > 0) {
            extraInfo.add("extra_animation_names", stringArray(extraAnimationNames));
        }

        List<String> authors = new ArrayList<>();
        for (RawYsmModel.RawMetadata.Author author : raw.metadata.authors) {
            if (StringUtils.isBlank(author.name)) {
                continue;
            }
            if (StringUtils.isBlank(author.role)) {
                authors.add(author.name);
            } else {
                authors.add(author.name + " (" + author.role + ")");
            }
        }
        if (!authors.isEmpty()) {
            extraInfo.add("authors", stringArray(authors.toArray(new String[0])));
        }

        String license = StringUtils.defaultIfBlank(raw.metadata.licenseType, raw.metadata.licenseDescription);
        if (StringUtils.isNotBlank(license)) {
            extraInfo.addProperty("license", license);
        }
        description.add("ysm_extra_info", extraInfo);
    }

    /**
     * The wheel's flat, baked label array: element {@code i} is the label of the {@code i}-th entry the pack declared.
     *
     * <p>Two shapes are handled, and the first one is byte-for-byte what this method always produced:</p>
     * <ul>
     *   <li><b>Legacy</b> - the pack's keys are literally {@code extra0..extra7}. Then slot {@code i} is
     *       {@code extra<i>}, which is also the animation name the play path uses, so labels and actions line up with
     *       no further mapping (M-14: only up to the last defined slot, trailing slots trimmed, a {@code '#'}-prefixed
     *       value not used as a label).</li>
     *   <li><b>Named</b> (YSM 2.3.0+) - the keys are the animation names themselves, in declaration order, and the
     *       <em>values</em> are the labels. LaPluma 2.5.3 declares exactly this: {@code HI}, {@code WHAT}, {@code Bird},
     *       {@code Illustration0}, {@code Illustration1} plus three {@code '#...'} sub-page markers, and its
     *       {@code extra.animation.json} really defines those named clips. Upstream's roulette works from the same
     *       ordered pairs and shows {@code LanguageManager.getI18n(model, "properties.extra_animation." + key, value)}
     *       ({@code tmp/YesSteveModel-dev-1.20/.../client/gui/AnimationRouletteScreen.java:719-750}), which is the rule
     *       {@link #extraAnimationLabel} applies here.</li>
     * </ul>
     *
     * <p>The declaration order is the slot order on purpose: it is the order the rich table arrives in
     * ({@code ModelConfigRegistry.wheelEntriesFor}, filled from the same map by the sync path), so element {@code i}
     * here is the label of entry {@code i} there. Both are backed by a {@code LinkedHashMap} that the folder parser
     * fills in JSON order and the binary serializer/deserializer round-trips in that order
     * ({@code YSMBinarySerializer:468-469} writes the entries in iteration order, {@code YSMBinaryDeserializer:687-689}
     * reads them back into a {@code LinkedHashMap}), so the mapping is stable across both channels and across
     * restarts.</p>
     */
    private static String[] getExtraAnimationNames(RawYsmModel raw) {
        if (raw.properties.extraAnimations.isEmpty()) {
            return new String[0];
        }
        // M-14:只回填到最后一个已定义的槽位。dev 基线固定返回 8 个元素,未定义槽位是 null,
        // 会写进引擎的 extra_animation_names 数组里(消费端按槽位下标取值,所以不能重排,
        // 但可以把尾部未定义的槽裁掉)。
        int lastDefined = -1;
        for (int i = 0; i < EXTRA_ANIMATION_SLOT_COUNT; i++) {
            if (raw.properties.extraAnimations.containsKey("extra" + i)) {
                lastDefined = i;
            }
        }
        if (lastDefined >= 0) {
            String[] names = new String[lastDefined + 1];
            for (int i = 0; i <= lastDefined; i++) {
                names[i] = legacyExtraAnimationLabel(raw, "extra" + i);
            }
            return names;
        }
        String[] names = new String[raw.properties.extraAnimations.size()];
        int index = 0;
        for (String key : raw.properties.extraAnimations.keySet()) {
            names[index++] = extraAnimationLabel(raw, key);
        }
        return names;
    }

    /**
     * The legacy {@code extra<i>} label rule, unchanged: the pack's language file for
     * {@code properties.extra_animation.<key>}, else the declared value when it is not a {@code '#'} reference, else
     * the key itself. A legacy pack's keys are the animation names, so its labels and its playable slots are the same
     * thing and nothing has to be substituted here.
     */
    private static String legacyExtraAnimationLabel(RawYsmModel raw, String key) {
        String label = getLocalizedValue(raw, "properties.extra_animation." + key);
        if (StringUtils.isBlank(label)) {
            String configured = raw.properties.extraAnimations.get(key);
            if (StringUtils.isNotBlank(configured) && !configured.startsWith("#")) {
                label = configured;
            }
        }
        return StringUtils.isBlank(label) ? key : label;
    }

    /**
     * The label of one named entry, exactly upstream's rule
     * ({@code AnimationRouletteScreen.java:719-750}): the pack's language file for
     * {@code properties.extra_animation.<key>} wins, then the declared value - except that a value which is a
     * {@code '#'} reference to a declared settings button is shown as that button's own {@code name}, which is what
     * upstream substitutes before the language lookup ({@code :726-736}); if the reference names no known button it is
     * shown as written. A blank result falls back to the key, so a sector is never unlabelled.
     * <p>
     * The {@code '#'} in a <em>key</em> needs no special case here: it is a sub-page marker, and its value (for
     * example {@code 更多动作}) is the label the pack wrote for it.
     */
    private static String extraAnimationLabel(RawYsmModel raw, String key) {
        String label = getLocalizedValue(raw, "properties.extra_animation." + key);
        if (StringUtils.isBlank(label)) {
            String configured = raw.properties.extraAnimations.get(key);
            if (StringUtils.isNotBlank(configured)) {
                if (configured.startsWith("#")) {
                    RawYsmModel.ExtraAnimationButton button =
                        findExtraAnimationButton(raw, configured.substring(1));
                    label = button != null && StringUtils.isNotBlank(button.name) ? button.name : configured;
                } else {
                    label = configured;
                }
            }
        }
        return StringUtils.isBlank(label) ? key : label;
    }

    /** One declared {@code extra_animation_buttons} entry by id, or {@code null} when the pack declares no such id. */
    private static RawYsmModel.ExtraAnimationButton findExtraAnimationButton(RawYsmModel raw, String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        for (RawYsmModel.ExtraAnimationButton button : raw.properties.extraAnimationButtons) {
            if (id.equals(button.id)) {
                return button;
            }
        }
        return null;
    }

    private static String getLocalizedValue(RawYsmModel raw, String key) {
        for (String locale : LOCALE_PREFERENCE) {
            RawYsmModel.RawLanguageFile file = raw.languageFiles.get(locale);
            if (file != null && file.data.containsKey(key)) {
                return file.data.get(key);
            }
        }
        for (RawYsmModel.RawLanguageFile file : raw.languageFiles.values()) {
            if (file.data.containsKey(key)) {
                return file.data.get(key);
            }
        }
        return "";
    }

    private static JsonArray stringArray(String[] values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(new JsonPrimitive(value == null ? "" : value));
        }
        return array;
    }

    private static JsonArray floatArray(float[] values) {
        JsonArray array = new JsonArray();
        if (values == null) {
            return array;
        }
        for (float value : values) {
            array.add(new JsonPrimitive((double) value));
        }
        return array;
    }

    private static JsonArray generatedPivotArray(float[] pivot) {
        float x = getVectorValue(pivot, 0);
        float y = getVectorValue(pivot, 1);
        float z = getVectorValue(pivot, 2);
        return doubleArray(-x, y, z);
    }

    private static JsonArray generatedRotationArray(float[] rotation) {
        float x = getVectorValue(rotation, 0);
        float y = getVectorValue(rotation, 1);
        float z = getVectorValue(rotation, 2);
        return doubleArray(-Math.toDegrees(x), -Math.toDegrees(y), Math.toDegrees(z));
    }

    private static float getVectorValue(float[] values, int index) {
        return values != null && values.length > index ? values[index] : 0f;
    }

    private static JsonArray doubleArray(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(new JsonPrimitive(value));
        }
        return array;
    }

    private static float defaultPositive(float value, float defaultValue) {
        return value > 0f ? value : defaultValue;
    }

    private static String textureName(RawYsmModel.RawTexture texture) {
        return StringUtils.defaultIfBlank(texture.sourceFileName, texture.name);
    }

    private static void putAnimation(Map<String, byte[]> animations,
        Map<String, RawYsmModel.RawAnimationFile> preParsed, RawYsmModel raw, String key, String defaultFileName,
        boolean structuredMode) throws IOException {
        if (putAnimationFile(animations, preParsed, key, raw.mainEntity.animationFiles.get(key), structuredMode)) {
            return;
        }
        animations.put(key, readDefaultAnimation(defaultFileName));
    }

    private static boolean putAnimationFile(Map<String, byte[]> animations,
        Map<String, RawYsmModel.RawAnimationFile> preParsed, String key,
        RawYsmModel.RawAnimationFile animationFile, boolean structuredMode) throws IOException {
        if (animationFile == null) {
            return false;
        }
        if (animationFile.sourceJson != null) {
            // Preserve unknown source metadata, replacing only timeline with parser-normalized statements (G06).
            try {
                JsonObject root = new com.google.gson.JsonParser().parse(
                    new String(animationFile.sourceJson, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject sourceAnimations = root.getAsJsonObject("animations");
                if (sourceAnimations != null) {
                    for (RawYsmModel.RawAnimation animation : animationFile.animations.values()) {
                        JsonElement entry = sourceAnimations.get(animation.name);
                        if (entry != null && entry.isJsonObject()) {
                            JsonObject json = entry.getAsJsonObject();
                            json.remove("timeline");
                            putTimeline(json, animation.timelineEvents);
                        }
                    }
                }
                animations.put(key, ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException failure) {
                throw new IOException("Could not normalize source animation timelines for " + key, failure);
            }
            return true;
        }
        if (!animationFile.animations.isEmpty()) {
            if (structuredMode) {
                // The binary channel: the client builds the engine Animation objects straight from these structures
                // (RawAnimationBuilder), so no JSON text is produced here at all.
                preParsed.put(key, animationFile);
            } else {
                animations.put(key, createAnimationJson(animationFile));
            }
            return true;
        }
        return false;
    }

    private static byte[] createAnimationJson(RawYsmModel.RawAnimationFile animationFile) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", ANIMATION_FORMAT_VERSION);
        JsonObject animations = new JsonObject();
        for (RawYsmModel.RawAnimation animation : animationFile.animations.values()) {
            if (StringUtils.isBlank(animation.name)) {
                continue;
            }
            animations.add(animation.name, createAnimationJson(animation));
        }
        root.add("animations", animations);
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject createAnimationJson(RawYsmModel.RawAnimation animation) {
        JsonObject json = new JsonObject();
        if (isFinite(animation.length) && animation.length > 0f) {
            // 长度必须和时间线/声音/粒子 key 落在**同一个十进制精度**上：那些 key 由 {@code timeKey}
            // 用 {@code Float.toString} 写出（0.0101f → "0.0101"），引擎再 {@code Double.parseDouble(key) * 20}
            // 得到 tick；这里若直接写 {@code (double) float}，得到的是 0.010099999606609344，×20 后
            // 比同一时间点小 7.9e-9 tick。引擎回绕时只以 {@code animation_length} 调
            // {@code processKeyFrameEvents}，其 `tick >= startTick` 于是恒假 —— 写在动画结尾上的时间线事件
            // 永不执行。实测：{@code misc/4_default_controllers} 的 Hair_Physics（长度 0.0101、结尾事件写
            // v.HP_x_1）因此把 v.HP_x 从"每帧增量"退化成"绝对角度"，子发束拿到 10 倍值，上下转视角时
            // 整束头发转圈穿模。二进制通道没有 sourceJson，是唯一走这里的路径。
            json.addProperty("animation_length", Double.parseDouble(Float.toString(animation.length)));
        }
        putLoopMode(json, animation.loopMode);
        if (animation.blendWeight != null) {
            json.add("blend_weight", molangValue(animation.blendWeight));
        }
        // Bedrock's per-animation `anim_speed` / `anim_time_update`. They have to be re-emitted here because this
        // method IS the animation JSON for a baked payload: `putAnimationFile` prefers `RawAnimationFile.sourceJson`,
        // and a payload has none (`YSMBinarySerializer` does not write it), so every binary-channel model's animation
        // JSON is generated from the structure - and a field this method does not write is a field the engine never
        // sees. `ClientModelManager.attachAnimSpeed` is the reader on the other end.
        putOptionalText(json, "anim_speed", animation.animSpeed);
        putOptionalText(json, "anim_time_update", animation.animTimeUpdate);

        JsonObject bones = new JsonObject();
        for (RawYsmModel.RawBoneAnimation boneAnimation : animation.boneAnimations) {
            if (StringUtils.isBlank(boneAnimation.boneName)) {
                continue;
            }
            JsonObject bone = new JsonObject();
            putChannel(bone, "rotation", boneAnimation.rotation);
            putChannel(bone, "position", boneAnimation.position);
            putChannel(bone, "scale", boneAnimation.scale);
            if (!bone.entrySet().isEmpty()) {
                bones.add(boneAnimation.boneName, bone);
            }
        }
        if (!bones.entrySet().isEmpty()) {
            json.add("bones", bones);
        }

        putTimeline(json, animation.timelineEvents);
        putSoundEffects(json, animation.soundEffects);
        return json;
    }

    /**
     * M-17:内部 loopMode 到 JSON 的映射。1 = LOOP、3 = HOLD_ON_LAST_FRAME、0 = PLAY_ONCE;
     * 2 不写 loop 字段(交给引擎默认值)。散件解析侧必须产出同一套编号,否则
     * hold_on_last_frame 会被降级成"引擎默认"。
     */
    private static void putLoopMode(JsonObject json, int loopMode) {
        if (loopMode == 1) {
            json.addProperty("loop", true);
        } else if (loopMode == 3) {
            json.addProperty("loop", "hold_on_last_frame");
        } else if (loopMode == 0) {
            json.addProperty("loop", false);
        }
    }

    /**
     * Adds {@code key} as a string property when the value is non-blank. Bedrock writes these as either a number or
     * a MoLang expression, and the engine's own fields are Strings, so a number that came in as text stays text.
     */
    private static void putOptionalText(JsonObject json, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            json.addProperty(key, value);
        }
    }

    private static void putChannel(JsonObject bone, String channelName, List<RawYsmModel.RawKeyframe> keyframes) {        if (keyframes.isEmpty()) {
            return;
        }
        if (keyframes.size() == 1) {
            RawYsmModel.RawKeyframe keyframe = keyframes.get(0);
            if (!keyframe.hasPreData && isZeroTime(keyframe.timestamp)) {
                bone.add(channelName, molangArray(keyframe.postData));
                return;
            }
        }
        JsonObject channel = new JsonObject();
        for (RawYsmModel.RawKeyframe keyframe : keyframes) {
            channel.add(timeKey(keyframe.timestamp), createKeyframeJson(keyframe));
        }
        bone.add(channelName, channel);
    }

    private static JsonElement createKeyframeJson(RawYsmModel.RawKeyframe keyframe) {
        String lerpMode = lerpMode(keyframe.interpolationMode);
        if (!keyframe.hasPreData) {
            JsonArray value = molangArray(keyframe.postData);
            if (StringUtils.isBlank(lerpMode)) {
                return value;
            }
            JsonObject json = new JsonObject();
            json.add("post", value);
            json.addProperty("lerp_mode", lerpMode);
            return json;
        }
        JsonObject json = new JsonObject();
        json.add("pre", molangArray(keyframe.preData));
        json.add("post", molangArray(keyframe.postData));
        if (StringUtils.isNotBlank(lerpMode)) {
            json.addProperty("lerp_mode", lerpMode);
        }
        return json;
    }

    private static String lerpMode(int interpolationMode) {
        if (interpolationMode == 1) {
            return "step";
        }
        if (interpolationMode == 2) {
            return "catmullrom";
        }
        return "";
    }

    private static JsonArray molangArray(Object[] values) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < 3; i++) {
            array.add(molangValue(values != null && values.length > i ? values[i] : Float.valueOf(0f)));
        }
        return array;
    }

    private static JsonPrimitive molangValue(Object value) {
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        return new JsonPrimitive(value == null ? "0" : value.toString());
    }

    private static void putTimeline(JsonObject animationJson, List<RawYsmModel.RawTimelineEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        JsonObject timeline = new JsonObject();
        for (RawYsmModel.RawTimelineEvent event : events) {
            if (event.events.isEmpty()) {
                continue;
            }
            String key = timeKey(event.timestamp);
            if (event.events.size() == 1 && !timeline.has(key)) {
                timeline.addProperty(key, event.events.get(0));
            } else {
                JsonArray array = timeline.has(key) && timeline.get(key).isJsonArray()
                    ? timeline.getAsJsonArray(key)
                    : new JsonArray();
                if (timeline.has(key) && timeline.get(key).isJsonPrimitive()) {
                    array.add(new JsonPrimitive(timeline.get(key).getAsString()));
                }
                for (String instruction : event.events) {
                    array.add(new JsonPrimitive(instruction));
                }
                timeline.add(key, array);
            }
        }
        if (!timeline.entrySet().isEmpty()) {
            animationJson.add("timeline", timeline);
        }
    }

    private static void putSoundEffects(JsonObject animationJson, List<RawYsmModel.RawSoundEffect> effects) {
        if (effects.isEmpty()) {
            return;
        }
        JsonObject soundEffects = new JsonObject();
        for (RawYsmModel.RawSoundEffect effect : effects) {
            if (StringUtils.isBlank(effect.effectName)) {
                continue;
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("effect", effect.effectName);
            soundEffects.add(timeKey(effect.timestamp), frame);
        }
        if (!soundEffects.entrySet().isEmpty()) {
            animationJson.add("sound_effects", soundEffects);
        }
    }

    private static String timeKey(float seconds) {
        return isFinite(seconds) ? Float.toString(seconds) : "0.0";
    }

    private static boolean isZeroTime(float seconds) {
        return isFinite(seconds) && Math.abs(seconds) < 0.000001f;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static byte[] readDefaultAnimation(String fileName) throws IOException {
        // SOURCE 在这里（:1238-1249）加的回退是 BUILT/default/<fileName>，那条路径在本仓永远不命中：内置
        // default 现在是现代 ysm.json 包，动画在 builtin/default/animations/ 下，根目录只有 ysm.json 与
        // models|textures|avatar|lang。实际解析见 ServerModelManager.defaultAnimationFile（先 custom/default，
        // 再 builtin/default/animations）。
        Path defaultPath = defaultAnimationFile(fileName);
        if (Files.isRegularFile(defaultPath)) {
            return Files.readAllBytes(defaultPath);
        }
        return EMPTY_ANIMATION;
    }

    private static void putAnimationControllers(Map<String, byte[]> animations, RawYsmModel raw) {
        int index = 0;
        for (RawYsmModel.RawAnimationControllerFile file : raw.mainEntity.animationControllerFiles) {
            if (file == null || (file.sourceJson == null && file.controllers.isEmpty())) {
                index++;
                continue;
            }
            byte[] data = file.sourceJson == null ? createControllerJson(file) : file.sourceJson;
            animations.put(YsmControllerResources.resourceName(file.name, index), data);
            index++;
        }
    }

    private static byte[] createControllerJson(RawYsmModel.RawAnimationControllerFile file) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.19.0");
        JsonObject controllers = new JsonObject();
        for (RawYsmModel.RawAnimationController controller : file.controllers.values()) {
            controllers.add(controller.animationName, createControllerJson(controller));
        }
        root.add("animation_controllers", controllers);
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject createControllerJson(RawYsmModel.RawAnimationController controller) {
        JsonObject json = new JsonObject();
        if (StringUtils.isNotBlank(controller.initialState)) {
            json.addProperty("initial_state", controller.initialState);
        }
        JsonObject states = new JsonObject();
        for (RawYsmModel.RawControllerState state : controller.states) {
            states.add(state.name, createStateJson(state));
        }
        json.add("states", states);
        return json;
    }

    private static JsonObject createStateJson(RawYsmModel.RawControllerState state) {
        JsonObject json = new JsonObject();
        JsonArray animations = new JsonArray();
        for (Map.Entry<String, String> entry : state.animations.entrySet()) {
            if (StringUtils.isBlank(entry.getValue())) {
                animations.add(new JsonPrimitive(entry.getKey()));
            } else {
                JsonObject conditional = new JsonObject();
                conditional.addProperty(entry.getKey(), entry.getValue());
                animations.add(conditional);
            }
        }
        if (animations.size() > 0) {
            json.add("animations", animations);
        }

        JsonArray transitions = new JsonArray();
        for (Map.Entry<String, String> entry : state.transitions.entrySet()) {
            JsonObject transition = new JsonObject();
            transition.addProperty(entry.getKey(), entry.getValue());
            transitions.add(transition);
        }
        if (transitions.size() > 0) {
            json.add("transitions", transitions);
        }
        putStringArray(json, "on_entry", state.onEntry);
        putStringArray(json, "on_exit", state.onExit);
        putStringArray(json, "sound_effects", state.soundEffects);
        if (!state.blendTransitions.isEmpty()) {
            JsonObject blendTransitions = new JsonObject();
            for (Map.Entry<Float, Float> entry : state.blendTransitions.entrySet()) {
                blendTransitions.addProperty(Float.toString(entry.getKey()), entry.getValue());
            }
            json.add("blend_transition", blendTransitions);
        } else if (state.blendTransitionValue > 0f) {
            json.addProperty("blend_transition", state.blendTransitionValue);
        }
        if (state.blendViaShortestPath) {
            json.addProperty("blend_via_shortest_path", true);
        }
        return json;
    }

    private static void putStringArray(JsonObject json, String name, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(new JsonPrimitive(value));
        }
        json.add(name, array);
    }

}
