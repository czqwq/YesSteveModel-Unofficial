package com.fox.ysmu.model.resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import software.bernie.geckolib3.geo.raw.pojo.Converter;

public class YSMFolderDeserializer implements AutoCloseable {

    private static final String YSM_JSON = "ysm.json";
    private static final String MAIN_JSON = "main.json";
    private static final String ARM_JSON = "arm.json";
    private static final String MAIN_ANIMATION_JSON = "main.animation.json";
    private static final String ARM_ANIMATION_JSON = "arm.animation.json";
    private static final String EXTRA_ANIMATION_JSON = "extra.animation.json";

    private final Map<String, String> readFilesMd5Map = new TreeMap<>();
    private final Path rootPath;
    private final RawYsmModel model = new RawYsmModel();
    private String finalFolderHash = "";

    public YSMFolderDeserializer(Path sourcePath) throws IOException {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            throw new FileNotFoundException("Model source not found: " + sourcePath);
        }
        if (!Files.isDirectory(sourcePath)) {
            throw new IllegalArgumentException("Expected an OpenYSM model directory: " + sourcePath);
        }
        this.rootPath = sourcePath.toAbsolutePath().normalize();
        this.model.formatVersion = 65535;
        this.model.modelId = sourcePath.getFileName() == null ? "" : sourcePath.getFileName().toString();
    }

    public RawYsmModel deserialize() throws IOException {
        byte[] ysmJsonBytes = readResource(YSM_JSON);
        if (ysmJsonBytes != null) {
            JsonObject ysmJson = parseObject(ysmJsonBytes, YSM_JSON);
            parseYsmJson(ysmJson);
        } else {
            parseLegacyFormat();
        }

        parseGlobalResources();
        this.finalFolderHash = calculateFinalFolderHash();
        // S-03:字段名沿用 RawProperties.sha256，但这里写入的是"已读文件表"的 MD5 十六进制串，
        // 不是真正的 SHA-256。该值会经 ModelCacheWriter.getHashSource 参与 OpenYSM 二进制缓存名
        // 与哈希计算(rip.ysm.security.YsmCrypt.calculateModelHashes)，客户端侧用同一份数据得出
        // 同一个值，属于已上线的协议耦合，因此保留字段名与内容，仅在此说明。
        this.model.properties.sha256 = this.finalFolderHash;
        this.model.footer.version = 65535;
        validateMainPlayerModel();
        return this.model;
    }

    @Override
    public void close() {}

    public String getFolderHash() {
        return this.finalFolderHash;
    }

    private byte[] readResource(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        String normalizedRelative = normalizeResourcePath(relativePath);
        Path target = this.rootPath.resolve(normalizedRelative).normalize();
        if (!target.startsWith(this.rootPath) || !Files.isRegularFile(target)) {
            return null;
        }
        byte[] data = Files.readAllBytes(target);
        if (!this.readFilesMd5Map.containsKey(normalizedRelative)) {
            this.readFilesMd5Map.put(normalizedRelative, md5Hex(data));
        }
        return data;
    }

    private static String normalizeResourcePath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private void parseYsmJson(JsonObject ysmJson) throws IOException {
        if (hasObject(ysmJson, "metadata")) {
            parseMetadata(ysmJson.getAsJsonObject("metadata"));
        }
        if (hasObject(ysmJson, "properties")) {
            parseProperties(ysmJson.getAsJsonObject("properties"));
        }
        if (!hasObject(ysmJson, "files")) {
            throw new IOException("OpenYSM model is missing files section");
        }

        JsonObject files = ysmJson.getAsJsonObject("files");
        if (hasObject(files, "player")) {
            parseMainEntity(files.getAsJsonObject("player"));
        }
        if (files.has("vehicles")) {
            parseSubEntities(files.get("vehicles"), this.model.vehicles, "vehicle");
        }
        if (files.has("projectiles")) {
            parseSubEntities(files.get("projectiles"), this.model.projectiles, "projectile");
        }
    }

    private void parseMetadata(JsonObject metaObj) throws IOException {
        this.model.metadata.name = getStr(metaObj, "name", "");
        this.model.metadata.tips = getStr(metaObj, "tips", "");
        if (hasObject(metaObj, "license")) {
            JsonObject license = metaObj.getAsJsonObject("license");
            this.model.metadata.licenseType = getStr(license, "type", "");
            this.model.metadata.licenseDescription = getStr(license, "desc", "");
        }

        if (hasArray(metaObj, "authors")) {
            for (JsonElement elem : metaObj.getAsJsonArray("authors")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject authorObj = elem.getAsJsonObject();
                RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                author.name = getStr(authorObj, "name", "");
                author.role = getStr(authorObj, "role", "");
                author.comment = getStr(authorObj, "comment", "");
                if (hasObject(authorObj, "contact")) {
                    copyStringMap(authorObj.getAsJsonObject("contact"), author.contacts);
                }
                author.avatar = getStr(authorObj, "avatar", "");
                if (!author.avatar.isEmpty()) {
                    byte[] avatarData = readResource(author.avatar);
                    if (avatarData != null) {
                        author.avatarImage = parseImage(author.name, avatarData);
                    }
                }
                this.model.metadata.authors.add(author);
            }
        }

        if (hasObject(metaObj, "link")) {
            copyStringMap(metaObj.getAsJsonObject("link"), this.model.metadata.links);
        }
    }

    private void parseProperties(JsonObject propsObj) throws IOException {
        this.model.properties.widthScale = (float) getDouble(propsObj, "width_scale", 0.7d);
        this.model.properties.heightScale = (float) getDouble(propsObj, "height_scale", 0.7d);
        this.model.properties.defaultTexture = getStr(propsObj, "default_texture", "default");
        this.model.properties.previewAnimation = getStr(propsObj, "preview_animation", "");
        this.model.properties.isFree = getBool(propsObj, "free", false);
        this.model.properties.renderLayersFirst = getBool(propsObj, "render_layers_first", false);
        this.model.properties.allCutout = getBool(propsObj, "all_cutout", false);
        this.model.properties.disablePreviewRotation = getBool(propsObj, "disable_preview_rotation", false);
        this.model.properties.guiNoLighting = getBool(propsObj, "gui_no_lighting", false);
        this.model.properties.mergeMultilineExpr = getBool(propsObj, "merge_multiline_expr", true);
        this.model.properties.guiForeground = getStr(propsObj, "gui_foreground", "");
        this.model.properties.guiBackground = getStr(propsObj, "gui_background", "");

        if (hasObject(propsObj, "extra_animation")) {
            copyStringMap(propsObj.getAsJsonObject("extra_animation"), this.model.properties.extraAnimations);
        }
        if (hasArray(propsObj, "extra_animation_classify")) {
            for (JsonElement elem : propsObj.getAsJsonArray("extra_animation_classify")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject classifyObj = elem.getAsJsonObject();
                RawYsmModel.ExtraAnimationClassify classify = new RawYsmModel.ExtraAnimationClassify();
                classify.id = getStr(classifyObj, "id", "");
                if (hasObject(classifyObj, "extra_animation")) {
                    copyStringMap(classifyObj.getAsJsonObject("extra_animation"), classify.extras);
                }
                this.model.properties.extraAnimationClassifies.add(classify);
            }
        }
        if (hasArray(propsObj, "extra_animation_buttons")) {
            parseExtraAnimationButtons(propsObj.getAsJsonArray("extra_animation_buttons"));
        }

        loadGuiImage(this.model.properties.guiForeground, "gui_foreground");
        loadGuiImage(this.model.properties.guiBackground, "gui_background");
    }

    private void parseExtraAnimationButtons(JsonArray buttons) {
        for (JsonElement elem : buttons) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject buttonObj = elem.getAsJsonObject();
            RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton();
            button.id = getStr(buttonObj, "id", "");
            button.name = getStr(buttonObj, "name", "");
            button.description = getStr(buttonObj, "description", "");
            if (hasArray(buttonObj, "config_forms")) {
                for (JsonElement formElem : buttonObj.getAsJsonArray("config_forms")) {
                    if (!formElem.isJsonObject()) {
                        continue;
                    }
                    JsonObject formObj = formElem.getAsJsonObject();
                    RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
                    form.type = getStr(formObj, "type", "");
                    form.title = getStr(formObj, "title", "");
                    form.description = getStr(formObj, "description", "");
                    form.defaultValue = getStr(formObj, "value", "");
                    form.step = (float) getDouble(formObj, "step", 0d);
                    form.min = (float) getDouble(formObj, "min", 0d);
                    form.max = (float) getDouble(formObj, "max", 0d);
                    if (hasObject(formObj, "labels")) {
                        copyStringMap(formObj.getAsJsonObject("labels"), form.labels);
                    }
                    button.forms.add(form);
                }
            }
            this.model.properties.extraAnimationButtons.add(button);
        }
    }

    private void loadGuiImage(String path, String id) throws IOException {
        if (path == null || path.isEmpty()) {
            return;
        }
        byte[] data = readResource(path);
        if (data == null) {
            data = readResource("background/" + id + ".png");
        }
        if (data != null) {
            this.model.properties.backgroundImages.add(parseImage(id, data));
        }
    }

    private void parseMainEntity(JsonObject playerObj) throws IOException {
        if (!hasObject(playerObj, "model")) {
            throw new IOException("OpenYSM player section is missing model object");
        }
        JsonObject modelObj = playerObj.getAsJsonObject("model");
        if (modelObj.has("main")) {
            byte[] data = readResource(modelObj.get("main").getAsString());
            if (data != null) {
                this.model.mainEntity.mainModel = parseGeometry(data, 1, modelObj.get("main").getAsString());
            }
        }
        if (modelObj.has("arm")) {
            byte[] data = readResource(modelObj.get("arm").getAsString());
            if (data != null) {
                this.model.mainEntity.armModel = parseGeometry(data, 2, modelObj.get("arm").getAsString());
            }
        }

        if (playerObj.has("texture")) {
            Iterable<JsonElement> textureElements = asIterable(playerObj.get("texture"));
            for (JsonElement elem : textureElements) {
                parseTextureReference(elem, this.model.mainEntity.textures);
            }
        }

        if (hasObject(playerObj, "animation")) {
            JsonObject animObj = playerObj.getAsJsonObject("animation");
            for (Map.Entry<String, JsonElement> entry : animObj.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                byte[] animData = readResource(entry.getValue().getAsString());
                if (animData != null) {
                    RawYsmModel.RawAnimationFile animationFile = parseAnimations(animData);
                    animationFile.sourceJson = animData;
                    animationFile.fileHash = sha256Hex(animData);
                    animationFile.animType = getAnimTypeFromKey(entry.getKey());
                    this.model.mainEntity.animationFiles.put(entry.getKey(), animationFile);
                }
            }
        }

        if (hasArray(playerObj, "animation_controllers")) {
            for (JsonElement elem : playerObj.getAsJsonArray("animation_controllers")) {
                if (!elem.isJsonPrimitive()) {
                    continue;
                }
                String path = elem.getAsString();
                byte[] data = readResource(path);
                if (data != null) {
                    RawYsmModel.RawAnimationControllerFile file = new RawYsmModel.RawAnimationControllerFile();
                    file.name = extractFileName(path);
                    file.hash = sha256Hex(data);
                    file.sourceJson = data;
                    parseAnimationControllers(data, file.controllers);
                    this.model.mainEntity.animationControllerFiles.add(file);
                }
            }
        }
    }

    private void parseTextureReference(JsonElement elem, Map<String, RawYsmModel.RawTexture> textures)
        throws IOException {
        String texturePath = null;
        if (elem.isJsonPrimitive()) {
            texturePath = elem.getAsString();
        } else if (elem.isJsonObject() && elem.getAsJsonObject().has("uv")) {
            texturePath = elem.getAsJsonObject().get("uv").getAsString();
        }
        if (texturePath == null || texturePath.isEmpty()) {
            return;
        }
        byte[] textureData = readResource(texturePath);
        if (textureData == null) {
            return;
        }

        RawYsmModel.RawTexture texture = parseTexture(texturePath, textureData);
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            if (obj.has("normal")) {
                addSubTexture(texture, obj.get("normal").getAsString(), 1);
            }
            if (obj.has("specular")) {
                addSubTexture(texture, obj.get("specular").getAsString(), 2);
            }
        }
        textures.put(texture.name, texture);
    }

    private void addSubTexture(RawYsmModel.RawTexture texture, String path, int specularType) throws IOException {
        byte[] data = readResource(path);
        if (data == null) {
            return;
        }
        ModelImageConverter.Result converted = ModelImageConverter.toPng(data, path);
        RawYsmModel.RawTexture.SubTexture subTexture = new RawYsmModel.RawTexture.SubTexture();
        subTexture.specularType = specularType;
        subTexture.hash = sha256Hex(converted.data);
        subTexture.width = converted.width;
        subTexture.height = converted.height;
        subTexture.imageFormat = converted.format;
        subTexture.data = converted.data;
        subTexture.unknownFlag = 1;
        texture.subTextures.add(subTexture);
    }

    private RawYsmModel.RawTexture parseTexture(String path, byte[] data) throws IOException {
        ModelImageConverter.Result converted = ModelImageConverter.toPng(data, path);
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = extractFileName(path);
        texture.sourceFileName = extractFileNameWithExtension(path);
        texture.hash = sha256Hex(converted.data);
        texture.width = converted.width;
        texture.height = converted.height;
        texture.imageFormat = converted.format;
        texture.data = converted.data;
        texture.unknownFlag = 1;
        return texture;
    }

    private void parseSubEntities(JsonElement sectionElem, Map<String, RawYsmModel.RawSubEntity> targetMap,
        String defaultIdentifier) throws IOException {
        if (!sectionElem.isJsonArray() && !sectionElem.isJsonObject()) {
            return;
        }
        List<JsonObject> items = new ArrayList<>();
        if (sectionElem.isJsonArray()) {
            for (JsonElement elem : sectionElem.getAsJsonArray()) {
                if (elem.isJsonObject()) {
                    items.add(elem.getAsJsonObject());
                }
            }
        } else {
            JsonObject object = sectionElem.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    JsonObject item = entry.getValue().getAsJsonObject();
                    if (!item.has("match")) {
                        item.addProperty("__temp_identifier", entry.getKey());
                    }
                    items.add(item);
                }
            }
        }

        int index = 0;
        for (JsonObject item : items) {
            // 单个子实体（载具/投掷物）失败不得拖垮整个玩家模型：01_taisho_maid 的 models/foxcar.json 声明
            // format_version 1.21.0，以前会让整只"酒狐"从模型列表里消失；现在只丢这一个子实体。
            String identifier = getStr(item, "__temp_identifier", defaultIdentifier + "_" + index);
            try {
                RawYsmModel.RawSubEntity sub = new RawYsmModel.RawSubEntity();
                sub.identifier = identifier;
                if (item.has("match")) {
                    sub.matchIds = readStringArray(item.get("match"));
                }
                if (item.has("model")) {
                    byte[] modelData = readResource(item.get("model").getAsString());
                    if (modelData != null) {
                        sub.model = parseGeometry(modelData, 0, item.get("model").getAsString());
                    }
                }
                if (item.has("texture")) {
                    for (JsonElement textureElem : asIterable(item.get("texture"))) {
                        parseTextureReference(textureElem, sub.textures);
                    }
                }
                if (item.has("animation")) {
                    for (JsonElement animElem : asIterable(item.get("animation"))) {
                        if (!animElem.isJsonPrimitive()) {
                            continue;
                        }
                        byte[] animData = readResource(animElem.getAsString());
                        if (animData != null) {
                            RawYsmModel.RawAnimationFile animationFile = parseAnimations(animData);
                            animationFile.sourceJson = animData;
                            animationFile.fileHash = sha256Hex(animData);
                            sub.animationFiles.put(extractFileName(animElem.getAsString()), animationFile);
                        }
                    }
                }
                targetMap.put(sub.identifier, sub);
            } catch (Exception e) {
                ysmu.LOG.warn("Skipping {} '{}' of model {}: it cannot be parsed", defaultIdentifier, identifier, e);
            }
            index++;
        }
    }

    private RawYsmModel.RawGeometry parseGeometry(byte[] data, int modelType, String sourcePath) throws IOException {
        String json = new String(data, StandardCharsets.UTF_8);
        Converter.fromJsonString(json);

        RawYsmModel.RawGeometry geometry = new RawYsmModel.RawGeometry();
        geometry.modelType = modelType;
        geometry.sha256 = sha256Hex(data);
        geometry.sourceJson = data;
        JsonObject root = parseObject(data, sourcePath);
        JsonObject description = findGeometryDescription(root);
        if (description != null) {
            geometry.identifier = getStr(description, "identifier", "");
            geometry.textureWidth = (float) getDouble(description, "texture_width", 64d);
            geometry.textureHeight = (float) getDouble(description, "texture_height", 64d);
            geometry.visibleBoundsWidth = (float) getDouble(description, "visible_bounds_width", 0d);
            geometry.visibleBoundsHeight = (float) getDouble(description, "visible_bounds_height", 0d);
            geometry.visibleBoundsOffset = getFloatArray(description, "visible_bounds_offset", 3);
        }
        JsonArray bones = findGeometryBones(root);
        if (bones != null) {
            for (JsonElement boneElem : bones) {
                if (!boneElem.isJsonObject()) {
                    continue;
                }
                JsonObject boneObj = boneElem.getAsJsonObject();
                RawYsmModel.RawBone bone = new RawYsmModel.RawBone();
                bone.name = getStr(boneObj, "name", "");
                bone.parentName = getStr(boneObj, "parent", "");
                // M-03:内部约定见 RawYsmModelAdapter.generatedPivotArray/generatedRotationArray：
                // 枢轴 X 取反；旋转以弧度保存，且 X/Y 取反、Z 保持。ysm.json / main.json 里是
                // 未取反的角度值，所以在这里做一次转换。
                float[] pivot = getFloatArray(boneObj, "pivot", 3);
                bone.pivot = new float[] { -pivot[0], pivot[1], pivot[2] };
                float[] rotation = getFloatArray(boneObj, "rotation", 3);
                bone.rotation = new float[] { (float) -Math.toRadians(rotation[0]),
                    (float) -Math.toRadians(rotation[1]), (float) Math.toRadians(rotation[2]) };

                float boneInflate = (float) getDouble(boneObj, "inflate", 0d);
                boolean boneMirror = getBool(boneObj, "mirror", false);
                if (hasArray(boneObj, "cubes")) {
                    for (JsonElement cubeElem : boneObj.getAsJsonArray("cubes")) {
                        if (!cubeElem.isJsonObject()) {
                            continue;
                        }
                        // M-02:把 JSON 立方体烘焙成二进制的 face 列表，否则 OpenYSM 二进制同步
                        // 载荷(YSMBinarySerializer.writeGeometry 只写 bones/cubes/faces)会退化成
                        // 一个没有任何几何的模型。
                        bone.cubes.add(parseCube(geometry, cubeElem.getAsJsonObject(), boneInflate, boneMirror));
                    }
                }
                geometry.bones.add(bone);
            }
        }
        return geometry;
    }

    /**
     * 把 ysm.json/main.json 里的一个 cube 烘焙成二进制格式使用的四边面列表。
     * 坐标约定与 {@code OpenYSM} 参考实现一致(位置除以 16，X 轴取反)。
     */
    private static RawYsmModel.RawCube parseCube(RawYsmModel.RawGeometry geometry, JsonObject cubeObj,
        float boneInflate, boolean boneMirror) {
        RawYsmModel.RawCube cube = new RawYsmModel.RawCube();

        float inflate = cubeObj.has("inflate") ? cubeObj.get("inflate").getAsFloat() : boneInflate;
        boolean mirror = cubeObj.has("mirror") ? cubeObj.get("mirror").getAsBoolean() : boneMirror;

        float[] origin = getFloatArray(cubeObj, "origin", 3);
        float[] size = getFloatArray(cubeObj, "size", 3);

        float x = -origin[0] - size[0] - inflate;
        float y = origin[1] - inflate;
        float z = origin[2] - inflate;
        float width = size[0] + inflate * 2f;
        float height = size[1] + inflate * 2f;
        float depth = size[2] + inflate * 2f;

        CubeBakeTransform bake = null;
        if (cubeObj.has("rotation") || cubeObj.has("pivot")) {
            bake = new CubeBakeTransform(getFloatArray(cubeObj, "pivot", 3), getFloatArray(cubeObj, "rotation", 3));
        }

        JsonElement uvElem = cubeObj.get("uv");
        if (uvElem == null || uvElem.isJsonNull()) {
            return cube;
        }
        JsonObject faceUvs;
        if (uvElem.isJsonArray()) {
            JsonArray uvArray = uvElem.getAsJsonArray();
            float uvX = uvArray.size() > 0 ? uvArray.get(0).getAsFloat() : 0f;
            float uvY = uvArray.size() > 1 ? uvArray.get(1).getAsFloat() : 0f;
            faceUvs = createBoxUv(uvX, uvY, (float) Math.floor(size[0]), (float) Math.floor(size[1]),
                (float) Math.floor(size[2]));
        } else if (uvElem.isJsonObject()) {
            faceUvs = uvElem.getAsJsonObject();
        } else {
            return cube;
        }

        bakeFace(cube, faceUvs, "north", "north", mirror, x, y, z, width, height, depth, geometry, bake);
        bakeFace(cube, faceUvs, "south", "south", mirror, x, y, z, width, height, depth, geometry, bake);
        bakeFace(cube, faceUvs, "east", mirror ? "west" : "east", mirror, x, y, z, width, height, depth, geometry,
            bake);
        bakeFace(cube, faceUvs, "west", mirror ? "east" : "west", mirror, x, y, z, width, height, depth, geometry,
            bake);
        bakeFace(cube, faceUvs, "up", "up", mirror, x, y, z, width, height, depth, geometry, bake);
        bakeFace(cube, faceUvs, "down", "down", mirror, x, y, z, width, height, depth, geometry, bake);
        return cube;
    }

    private static void bakeFace(RawYsmModel.RawCube cube, JsonObject faceUvs, String faceType, String uvFaceName,
        boolean mirror, float x, float y, float z, float width, float height, float depth,
        RawYsmModel.RawGeometry geometry, CubeBakeTransform bake) {
        if (!hasObject(faceUvs, uvFaceName)) {
            return;
        }
        JsonObject faceData = faceUvs.getAsJsonObject(uvFaceName);
        float[] uv = getFloatArray(faceData, "uv", 2);
        float[] uvSize = getFloatArray(faceData, "uv_size", 2);

        float textureWidth = geometry.textureWidth > 0f ? geometry.textureWidth : 64f;
        float textureHeight = geometry.textureHeight > 0f ? geometry.textureHeight : 64f;
        float u0 = uv[0] / textureWidth;
        float v0 = uv[1] / textureHeight;
        float u1 = (uv[0] + uvSize[0]) / textureWidth;
        float v1 = (uv[1] + uvSize[1]) / textureHeight;
        if (!mirror) {
            float swap = u0;
            u0 = u1;
            u1 = swap;
        }

        float[] rawNormal;
        switch (faceType) {
            case "west":
                rawNormal = new float[] { -1f, 0f, 0f };
                break;
            case "east":
                rawNormal = new float[] { 1f, 0f, 0f };
                break;
            case "north":
                rawNormal = new float[] { 0f, 0f, -1f };
                break;
            case "south":
                rawNormal = new float[] { 0f, 0f, 1f };
                break;
            case "up":
                rawNormal = new float[] { 0f, 1f, 0f };
                break;
            default:
                rawNormal = new float[] { 0f, -1f, 0f };
                break;
        }

        float x1 = x / 16f;
        float x2 = (x + width) / 16f;
        float y1 = y / 16f;
        float y2 = (y + height) / 16f;
        float z1 = z / 16f;
        float z2 = (z + depth) / 16f;

        float[] p1 = { x1, y1, z1 };
        float[] p2 = { x1, y1, z2 };
        float[] p3 = { x1, y2, z1 };
        float[] p4 = { x1, y2, z2 };
        float[] p5 = { x2, y1, z1 };
        float[] p6 = { x2, y1, z2 };
        float[] p7 = { x2, y2, z1 };
        float[] p8 = { x2, y2, z2 };

        float[][] corners;
        switch (faceType) {
            case "west":
                corners = new float[][] { p4, p3, p1, p2 };
                break;
            case "east":
                corners = new float[][] { p7, p8, p6, p5 };
                break;
            case "north":
                corners = new float[][] { p3, p7, p5, p1 };
                break;
            case "south":
                corners = new float[][] { p8, p4, p2, p6 };
                break;
            case "up":
                corners = new float[][] { p4, p8, p7, p3 };
                break;
            default:
                corners = new float[][] { p1, p5, p6, p2 };
                break;
        }

        RawYsmModel.RawFace face = new RawYsmModel.RawFace();
        if (bake == null) {
            face.normal = rawNormal;
            for (int i = 0; i < 4; i++) {
                face.positions[i] = corners[i];
            }
        } else {
            bake.rotateNormal(rawNormal, face.normal);
            for (int i = 0; i < 4; i++) {
                bake.apply(corners[i], face.positions[i]);
            }
        }
        face.u = new float[] { u0, u1, u1, u0 };
        face.v = new float[] { v0, v0, v1, v1 };
        cube.faces.add(face);
    }

    /** 把 box-uv 数组形式(uv 偏移 + 尺寸)展开成六个面的 uv 节点。 */
    private static JsonObject createBoxUv(float uvX, float uvY, float dx, float dy, float dz) {
        JsonObject uv = new JsonObject();
        uv.add("north", createFaceUvNode(uvX + dz, uvY + dz, dx, dy));
        uv.add("south", createFaceUvNode(uvX + dz + dx + dz, uvY + dz, dx, dy));
        uv.add("east", createFaceUvNode(uvX, uvY + dz, dz, dy));
        uv.add("west", createFaceUvNode(uvX + dz + dx, uvY + dz, dz, dy));
        uv.add("up", createFaceUvNode(uvX + dz, uvY, dx, dz));
        uv.add("down", createFaceUvNode(uvX + dz + dx, uvY + dz, dx, -dz));
        return uv;
    }

    private static JsonObject createFaceUvNode(float u, float v, float width, float height) {
        JsonObject node = new JsonObject();
        JsonArray uv = new JsonArray();
        // t41:JsonArray.add 只接受 JsonElement,float 不会隐式转换(dev 树曾因此 BUILD FAILED)。
        // 这里用 box 成 Number 的 JsonPrimitive 显式构造,与仓库既有写法一致
        // (RawYsmModelAdapter.java:650 的 new JsonPrimitive((Number) value)、:467 的
        //  new JsonPrimitive((double) value));写进去的仍是原来的四个 float 值。
        uv.add(new JsonPrimitive(Float.valueOf(u)));
        uv.add(new JsonPrimitive(Float.valueOf(v)));
        JsonArray size = new JsonArray();
        size.add(new JsonPrimitive(Float.valueOf(width)));
        size.add(new JsonPrimitive(Float.valueOf(height)));
        node.add("uv", uv);
        node.add("uv_size", size);
        return node;
    }

    private RawYsmModel.RawAnimationFile parseAnimations(byte[] data) {
        RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
        JsonObject root = parseObject(data, "animation");
        if (!hasObject(root, "animations")) {
            return file;
        }
        JsonObject animations = root.getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation();
            animation.name = entry.getKey();
            if (entry.getValue().isJsonObject()) {
                JsonObject animObj = entry.getValue().getAsJsonObject();
                // M-20（非目标）：`animation_length` 缺失时的默认值仍按 dev 保持 0d，
                // 与参考实现的 Float.POSITIVE_INFINITY 不同，本阶段不改。
                animation.length = (float) getDouble(animObj, "animation_length", 0d);
                animation.loopMode = parseLoopMode(animObj.get("loop"));
                if (animObj.has("blend_weight") && !animObj.get("blend_weight").isJsonNull()) {
                    JsonElement blend = animObj.get("blend_weight");
                    if (blend.isJsonPrimitive() && blend.getAsJsonPrimitive().isNumber()) {
                        animation.blendWeight = blend.getAsFloat();
                    } else {
                        animation.blendWeight = blend.getAsString();
                    }
                }
                if (hasObject(animObj, "bones")) {
                    for (Map.Entry<String, JsonElement> boneEntry : animObj.getAsJsonObject("bones").entrySet()) {
                        if (!boneEntry.getValue().isJsonObject()) {
                            continue;
                        }
                        JsonObject boneObj = boneEntry.getValue().getAsJsonObject();
                        RawYsmModel.RawBoneAnimation bone = new RawYsmModel.RawBoneAnimation();
                        bone.boneName = boneEntry.getKey();
                        // M-02:关键帧必须真的落进 RawKeyframe，否则二进制载荷只有骨名没有轨道。
                        parseChannelToKeyframes(boneObj, "rotation", bone.rotation);
                        parseChannelToKeyframes(boneObj, "position", bone.position);
                        parseChannelToKeyframes(boneObj, "scale", bone.scale);
                        animation.boneAnimations.add(bone);
                    }
                }
                parseTimeline(animObj, animation);
                parseSoundEffects(animObj, animation);
            }
            file.animations.put(animation.name, animation);
        }
        return file;
    }

    /** M-02:把一条骨骼轨道的 JSON 关键帧解析成内部 {@link RawYsmModel.RawKeyframe} 列表。 */
    private static void parseChannelToKeyframes(JsonObject boneObj, String channel,
        List<RawYsmModel.RawKeyframe> target) {
        JsonElement channelElem = boneObj.get(channel);
        if (channelElem == null || channelElem.isJsonNull()) {
            return;
        }
        if (!channelElem.isJsonObject()) {
            RawYsmModel.RawKeyframe keyframe = new RawYsmModel.RawKeyframe();
            keyframe.timestamp = 0f;
            keyframe.interpolationMode = 0;
            keyframe.hasPreData = false;
            keyframe.postData = molangArray(channelElem);
            target.add(keyframe);
            return;
        }

        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(channelElem.getAsJsonObject().entrySet());
        entries.sort((left, right) -> Float.compare(parseTimestamp(left.getKey()), parseTimestamp(right.getKey())));
        for (Map.Entry<String, JsonElement> entry : entries) {
            float timestamp = parseTimestamp(entry.getKey());
            if (timestamp < 0f) {
                continue;
            }
            RawYsmModel.RawKeyframe keyframe = new RawYsmModel.RawKeyframe();
            keyframe.timestamp = timestamp;
            keyframe.interpolationMode = 0;
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                JsonObject valueObj = value.getAsJsonObject();
                if (valueObj.has("lerp_mode") && !valueObj.get("lerp_mode").isJsonNull()) {
                    String lerpMode = valueObj.get("lerp_mode")
                        .getAsString();
                    if ("catmullrom".equals(lerpMode)) {
                        keyframe.interpolationMode = 2;
                    } else if ("step".equals(lerpMode)) {
                        keyframe.interpolationMode = 1;
                    }
                } else {
                    keyframe.interpolationMode = 1;
                }
                if (valueObj.has("pre") && valueObj.has("post")) {
                    keyframe.hasPreData = true;
                    keyframe.preData = molangArray(valueObj.get("pre"));
                    keyframe.postData = molangArray(valueObj.get("post"));
                } else {
                    keyframe.hasPreData = false;
                    keyframe.postData = molangArray(
                        valueObj.has("post") ? valueObj.get("post")
                            : valueObj.has("pre") ? valueObj.get("pre") : valueObj);
                }
            } else {
                keyframe.hasPreData = false;
                keyframe.postData = molangArray(value);
            }
            target.add(keyframe);
        }
    }

    private static void parseTimeline(JsonObject animObj, RawYsmModel.RawAnimation animation) {
        if (!hasObject(animObj, "timeline")) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : animObj.getAsJsonObject("timeline")
            .entrySet()) {
            float timestamp = parseTimestamp(entry.getKey());
            if (timestamp < 0f) {
                continue;
            }
            RawYsmModel.RawTimelineEvent event = new RawYsmModel.RawTimelineEvent();
            event.timestamp = timestamp;
            for (JsonElement value : asIterable(entry.getValue())) {
                if (!value.isJsonNull()) {
                    event.events.add(value.getAsString());
                }
            }
            animation.timelineEvents.add(event);
        }
    }

    private static void parseSoundEffects(JsonObject animObj, RawYsmModel.RawAnimation animation) {
        if (!hasObject(animObj, "sound_effects")) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : animObj.getAsJsonObject("sound_effects")
            .entrySet()) {
            float timestamp = parseTimestamp(entry.getKey());
            if (timestamp < 0f || !entry.getValue()
                .isJsonObject()) {
                continue;
            }
            RawYsmModel.RawSoundEffect effect = new RawYsmModel.RawSoundEffect();
            effect.timestamp = timestamp;
            effect.effectName = getStr(entry.getValue().getAsJsonObject(), "effect", "");
            animation.soundEffects.add(effect);
        }
    }

    /** Keyframe/timeline 的时间键，非法键返回 -1 以便调用方跳过。 */
    private static float parseTimestamp(String key) {
        try {
            return Float.parseFloat(key);
        } catch (NumberFormatException e) {
            return -1f;
        }
    }

    private static Object[] molangArray(JsonElement elem) {
        Object[] values = new Object[] { 0f, 0f, 0f };
        if (elem == null || elem.isJsonNull()) {
            return values;
        }
        if (elem.isJsonArray()) {
            JsonArray array = elem.getAsJsonArray();
            for (int i = 0; i < Math.min(3, array.size()); i++) {
                JsonElement value = array.get(i);
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive()
                    .isNumber()) {
                    values[i] = value.getAsFloat();
                } else {
                    values[i] = value.getAsString();
                }
            }
        } else {
            Object scalar = elem.isJsonPrimitive() && elem.getAsJsonPrimitive()
                .isNumber() ? elem.getAsFloat() : elem.getAsString();
            values[0] = scalar;
            values[1] = scalar;
            values[2] = scalar;
        }
        return values;
    }

    /**
     * ysm.json 的 {@code loop} 取值到内部 loopMode 的映射：1 = LOOP、3 = HOLD_ON_LAST_FRAME、
     * 0 = PLAY_ONCE。M-17 只修 {@code hold_on_last_frame} 的编号（dev 基线错写成 2）；
     * {@code loop} 缺失时的默认值仍按 M-20 保持 dev 的 0（参考实现为 2，属非目标）。
     */
    private static int parseLoopMode(JsonElement loop) {
        if (loop == null || loop.isJsonNull()) {
            return 0;
        }
        if (loop.isJsonPrimitive() && loop.getAsJsonPrimitive()
            .isBoolean()) {
            return loop.getAsBoolean() ? 1 : 0;
        }
        if (loop.isJsonPrimitive()) {
            String value = loop.getAsString();
            if ("true".equals(value) || "loop".equals(value)) {
                return 1;
            }
            if ("hold_on_last_frame".equals(value)) {
                return 3;
            }
        }
        return 0;
    }

    private void parseAnimationControllers(byte[] data,
        Map<String, RawYsmModel.RawAnimationController> targetMap) {
        JsonObject root = parseObject(data, "animation_controller");
        if (!hasObject(root, "animation_controllers")) {
            return;
        }
        JsonObject controllers = root.getAsJsonObject("animation_controllers");
        for (Map.Entry<String, JsonElement> entry : controllers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject controllerObj = entry.getValue().getAsJsonObject();
            RawYsmModel.RawAnimationController controller = new RawYsmModel.RawAnimationController();
            controller.animationName = entry.getKey();
            controller.initialState = getStr(controllerObj, "initial_state", "");
            if (hasObject(controllerObj, "states")) {
                JsonObject states = controllerObj.getAsJsonObject("states");
                for (Map.Entry<String, JsonElement> stateEntry : states.entrySet()) {
                    if (!stateEntry.getValue().isJsonObject()) {
                        continue;
                    }
                    controller.states.add(parseControllerState(stateEntry.getKey(), stateEntry.getValue().getAsJsonObject()));
                }
            }
            targetMap.put(controller.animationName, controller);
        }
    }

    private RawYsmModel.RawControllerState parseControllerState(String name, JsonObject stateObj) {
        RawYsmModel.RawControllerState state = new RawYsmModel.RawControllerState();
        state.name = name;
        if (hasArray(stateObj, "animations")) {
            for (JsonElement elem : stateObj.getAsJsonArray("animations")) {
                if (elem.isJsonPrimitive()) {
                    state.animations.put(elem.getAsString(), "");
                } else if (elem.isJsonObject()) {
                    copyStringMap(elem.getAsJsonObject(), state.animations);
                }
            }
        }
        if (hasArray(stateObj, "transitions")) {
            for (JsonElement elem : stateObj.getAsJsonArray("transitions")) {
                if (elem.isJsonObject()) {
                    copyStringMap(elem.getAsJsonObject(), state.transitions);
                }
            }
        }
        if (hasArray(stateObj, "on_entry")) {
            addStringArray(stateObj.getAsJsonArray("on_entry"), state.onEntry);
        }
        if (hasArray(stateObj, "on_exit")) {
            addStringArray(stateObj.getAsJsonArray("on_exit"), state.onExit);
        }
        if (hasArray(stateObj, "sound_effects")) {
            for (JsonElement elem : stateObj.getAsJsonArray("sound_effects")) {
                if (elem.isJsonObject()) {
                    state.soundEffects.add(getStr(elem.getAsJsonObject(), "effect", ""));
                } else if (!elem.isJsonNull()) {
                    state.soundEffects.add(elem.getAsString());
                }
            }
        }
        if (stateObj.has("blend_transition")) {
            JsonElement blend = stateObj.get("blend_transition");
            if (blend.isJsonPrimitive() && blend.getAsJsonPrimitive().isNumber()) {
                state.blendTransitionValue = blend.getAsFloat();
            } else if (blend.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : blend.getAsJsonObject().entrySet()) {
                    state.blendTransitions.put(Float.parseFloat(entry.getKey()), entry.getValue().getAsFloat());
                }
            }
        }
        state.blendViaShortestPath = getBool(stateObj, "blend_via_shortest_path", false);
        return state;
    }

    private void parseGlobalResources() throws IOException {
        try (Stream<Path> stream = Files.walk(this.rootPath)) {
            for (Path path : iterable(stream)) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String relative = normalizeResourcePath(this.rootPath.relativize(path).toString());
                if (relative.startsWith("sounds/") || relative.endsWith(".ogg")) {
                    byte[] data = readResource(relative);
                    if (data != null) {
                        this.model.soundFiles.put(extractFileName(relative), new RawYsmModel.RawDataFile(sha256Hex(data), data));
                    }
                } else if (relative.startsWith("functions/") && relative.endsWith(".molang")) {
                    byte[] data = readResource(relative);
                    if (data != null) {
                        this.model.functionFiles.put(extractFileName(relative), new RawYsmModel.RawDataFile(sha256Hex(data), data));
                    }
                } else if (relative.startsWith("lang/") && relative.endsWith(".json")) {
                    byte[] data = readResource(relative);
                    if (data != null) {
                        this.model.languageFiles.put(parseLocale(relative), parseLanguageFile(data));
                    }
                }
            }
        }
    }

    private RawYsmModel.RawLanguageFile parseLanguageFile(byte[] data) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonObject root = parseObject(data, "lang");
        copyStringMap(root, values);
        return new RawYsmModel.RawLanguageFile(sha256Hex(data), values);
    }

    private void parseLegacyFormat() throws IOException {
        byte[] mainData = readResource(MAIN_JSON);
        byte[] armData = readResource(ARM_JSON);
        if (mainData == null) {
            throw new IOException("Legacy model missing main.json");
        }
        if (armData == null) {
            throw new IOException("Legacy model missing arm.json");
        }
        this.model.mainEntity.mainModel = parseGeometry(mainData, 1, MAIN_JSON);
        this.model.mainEntity.armModel = parseGeometry(armData, 2, ARM_JSON);

        boolean hasTexture = false;
        try (Stream<Path> stream = Files.list(this.rootPath)) {
            for (Path path : iterable(stream)) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".png")) {
                    byte[] textureData = readResource(fileName);
                    RawYsmModel.RawTexture texture = parseTexture(fileName, textureData);
                    this.model.mainEntity.textures.put(texture.name, texture);
                    hasTexture = true;
                }
            }
        }
        if (!hasTexture) {
            throw new IOException("Legacy model requires at least one PNG texture");
        }

        readLegacyAnimation(MAIN_ANIMATION_JSON, "main");
        readLegacyAnimation(ARM_ANIMATION_JSON, "arm");
        readLegacyAnimation(EXTRA_ANIMATION_JSON, "extra");
        if (!this.model.mainEntity.textures.isEmpty()) {
            this.model.properties.defaultTexture = this.model.mainEntity.textures.keySet().iterator().next();
        }
    }

    private void readLegacyAnimation(String fileName, String key) throws IOException {
        byte[] data = readResource(fileName);
        if (data == null) {
            return;
        }
        RawYsmModel.RawAnimationFile animationFile = parseAnimations(data);
        animationFile.sourceJson = data;
        animationFile.fileHash = sha256Hex(data);
        animationFile.animType = getAnimTypeFromKey(key);
        this.model.mainEntity.animationFiles.put(key, animationFile);
    }

    private void validateMainPlayerModel() throws IOException {
        if (this.model.mainEntity.mainModel == null) {
            throw new IOException("OpenYSM model missing player main model");
        }
        if (this.model.mainEntity.armModel == null) {
            throw new IOException("OpenYSM model missing player arm model");
        }
        boolean hasPng = false;
        for (RawYsmModel.RawTexture texture : this.model.mainEntity.textures.values()) {
            if (texture.imageFormat == 2 && texture.data != null) {
                hasPng = true;
                break;
            }
        }
        if (!hasPng) {
            throw new IOException("OpenYSM model requires at least one PNG player texture");
        }
    }

    private RawYsmModel.RawImage parseImage(String name, byte[] data) throws IOException {
        // Normalise to PNG here, where every image of an OpenYSM model is read, so both sync channels and the
        // server cache only ever carry PNG (upstream does the same when it maps a model for the client).
        ModelImageConverter.Result converted = ModelImageConverter.toPng(data, name);
        RawYsmModel.RawImage image = new RawYsmModel.RawImage();
        image.name = name;
        image.data = converted.data;
        image.width = converted.width;
        image.height = converted.height;
        image.format = converted.format;
        image.unknownFlag = 1;
        image.isPng = converted.isPng();
        return image;
    }

    public static int detectFormat(byte[] data) {
        if (data == null || data.length < 2) {
            return 0;
        }
        if (data.length >= 2 && data[0] == 0x42 && data[1] == 0x4D) {
            return 1;
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E
            && data[3] == 0x47) {
            return 2;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return 3;
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return 4;
        }
        if (data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            return 5;
        }
        return 0;
    }

    public static int getAnimTypeFromKey(String key) {
        if ("main".equals(key)) {
            return 1;
        }
        if ("arm".equals(key)) {
            return 2;
        }
        if ("extra".equals(key)) {
            return 3;
        }
        if ("tac".equals(key)) {
            return 4;
        }
        if ("arrow".equals(key)) {
            return 5;
        }
        if ("carryon".equals(key)) {
            return 6;
        }
        if ("parcool".equals(key)) {
            return 7;
        }
        if ("slashblade".equals(key)) {
            return 9;
        }
        if ("tlm".equals(key)) {
            return 10;
        }
        if ("fp_arm".equals(key)) {
            return 11;
        }
        if ("immersive_melodies".equals(key)) {
            return 12;
        }
        if ("irons_spell_books".equals(key) || "iss".equals(key)) {
            return 13;
        }
        return 0;
    }

    public static String getAnimKeyFromType(int type) {
        switch (type) {
            case 1:
                return "main";
            case 2:
                return "arm";
            case 3:
                return "extra";
            case 4:
                return "tac";
            case 5:
                return "arrow";
            case 6:
                return "carryon";
            case 7:
                return "parcool";
            case 9:
                return "slashblade";
            case 10:
                return "tlm";
            case 11:
                return "fp_arm";
            case 12:
                return "immersive_melodies";
            case 13:
                return "irons_spell_books";
            default:
                return "unknown_" + type;
        }
    }

    public static boolean isModelFolder(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        if (Files.isRegularFile(dir.resolve(YSM_JSON))) {
            return true;
        }
        return Files.isRegularFile(dir.resolve(MAIN_JSON)) && Files.isRegularFile(dir.resolve(ARM_JSON));
    }

    private static JsonObject findGeometryDescription(JsonObject root) {
        JsonObject geometry = findFirstGeometry(root);
        if (geometry != null && hasObject(geometry, "description")) {
            return geometry.getAsJsonObject("description");
        }
        return null;
    }

    private static JsonArray findGeometryBones(JsonObject root) {
        JsonObject geometry = findFirstGeometry(root);
        if (geometry != null && hasArray(geometry, "bones")) {
            return geometry.getAsJsonArray("bones");
        }
        return null;
    }

    private static JsonObject findFirstGeometry(JsonObject root) {
        if (hasArray(root, "minecraft:geometry")) {
            JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
            for (JsonElement elem : geometries) {
                if (elem.isJsonObject()) {
                    return elem.getAsJsonObject();
                }
            }
        }
        return root;
    }

    private static JsonObject parseObject(byte[] data, String sourceName) {
        JsonElement element = new JsonParser().parse(new String(data, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected JSON object in " + sourceName);
        }
        return element.getAsJsonObject();
    }

    private static String[] readStringArray(JsonElement elem) {
        if (elem.isJsonArray()) {
            JsonArray array = elem.getAsJsonArray();
            String[] values = new String[array.size()];
            for (int i = 0; i < array.size(); i++) {
                values[i] = array.get(i).getAsString();
            }
            return values;
        }
        return new String[] { elem.getAsString() };
    }

    private static Iterable<JsonElement> asIterable(JsonElement elem) {
        if (elem == null || elem.isJsonNull()) {
            return Collections.emptyList();
        }
        if (elem.isJsonArray()) {
            return elem.getAsJsonArray();
        }
        return Collections.singletonList(elem);
    }

    private static <T> Iterable<T> iterable(final Stream<T> stream) {
        return stream::iterator;
    }

    private static void copyStringMap(JsonObject source, Map<String, String> target) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                target.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    private static void addStringArray(JsonArray array, List<String> target) {
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                target.add(elem.getAsString());
            }
        }
    }

    private static float[] getFloatArray(JsonObject obj, String key, int size) {
        float[] values = new float[size];
        if (hasArray(obj, key)) {
            JsonArray array = obj.getAsJsonArray(key);
            for (int i = 0; i < Math.min(size, array.size()); i++) {
                values[i] = array.get(i).getAsFloat();
            }
        }
        return values;
    }

    private static String parseLocale(String relativePath) {
        String name = relativePath.substring("lang/".length());
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        return name;
    }

    private static String extractFileName(String fullPath) {
        String fileName = extractFileNameWithExtension(fullPath);
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extractFileNameWithExtension(String fullPath) {
        String name = normalizeResourcePath(fullPath);
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static boolean hasObject(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonObject();
    }

    private static boolean hasArray(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray();
    }

    private static String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : def;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : def;
    }

    /**
     * S-03:散件模型的"文件夹哈希"。名字容易误解 —— 它用 MD5(不是 SHA-256)拼接
     * "相对路径 + 文件 MD5" 后取摘要，结果存入 {@code RawProperties.sha256}。
     * 保持现有算法与字段名是为了与已发布客户端的缓存名/哈希协议一致；若要改成真
     * SHA-256，必须同时升级两侧协议。
     */
    private String calculateFinalFolderHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            for (Map.Entry<String, String> entry : this.readFilesMd5Map.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String md5Hex(byte[] data) {
        return digestHex("MD5", data);
    }

    private static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    private static String digestHex(String algorithm, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return toHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " digest is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            output[i * 2] = hex[value >>> 4];
            output[i * 2 + 1] = hex[value & 0x0F];
        }
        return new String(output);
    }

    /**
     * cube 级旋转的烘焙变换，等价于参考实现的
     * {@code T(-pivot/16) * Rz(rot.z) * Ry(-rot.y) * Rx(-rot.x) * T(pivot/16)}(JOML 后乘约定)。
     * 这里用普通 float 数学实现，避免为一个 cube 再引入矩阵库。
     */
    private static final class CubeBakeTransform {

        private final float pivotX;
        private final float pivotY;
        private final float pivotZ;
        private final float cosZ;
        private final float sinZ;
        private final float cosY;
        private final float sinY;
        private final float cosX;
        private final float sinX;

        private CubeBakeTransform(float[] pivot, float[] rotation) {
            this.pivotX = pivot[0] / 16f;
            this.pivotY = pivot[1] / 16f;
            this.pivotZ = pivot[2] / 16f;
            this.cosZ = (float) Math.cos(Math.toRadians(rotation[2]));
            this.sinZ = (float) Math.sin(Math.toRadians(rotation[2]));
            this.cosY = (float) Math.cos(-Math.toRadians(rotation[1]));
            this.sinY = (float) Math.sin(-Math.toRadians(rotation[1]));
            this.cosX = (float) Math.cos(-Math.toRadians(rotation[0]));
            this.sinX = (float) Math.sin(-Math.toRadians(rotation[0]));
        }

        private void apply(float[] in, float[] out) {
            float[] rotated = new float[3];
            rotate(new float[] { in[0] + this.pivotX, in[1] - this.pivotY, in[2] - this.pivotZ }, rotated);
            out[0] = rotated[0] - this.pivotX;
            out[1] = rotated[1] + this.pivotY;
            out[2] = rotated[2] + this.pivotZ;
        }

        private void rotateNormal(float[] in, float[] out) {
            rotate(in, out);
            float length = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
            if (length > 1.0E-6f) {
                out[0] /= length;
                out[1] /= length;
                out[2] /= length;
            }
        }

        private void rotate(float[] in, float[] out) {
            float x = in[0] * this.cosZ - in[1] * this.sinZ;
            float y = in[0] * this.sinZ + in[1] * this.cosZ;
            float z = in[2];
            float rotatedX = x * this.cosY + z * this.sinY;
            z = -x * this.sinY + z * this.cosY;
            x = rotatedX;
            float rotatedY = y * this.cosX - z * this.sinX;
            z = y * this.sinX + z * this.cosX;
            out[0] = x;
            out[1] = rotatedY;
            out[2] = z;
        }
    }
}
