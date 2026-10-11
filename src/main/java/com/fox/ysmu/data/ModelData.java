package com.fox.ysmu.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.Type;

public final class ModelData {

    private final String modelId;
    private final Map<String, byte[]> model;
    private final Map<String, byte[]> texture;
    private final Map<String, byte[]> animation;
    /**
     * The model's GUI presentation images - a pack's {@code gui_background} / {@code gui_foreground} - keyed by the
     * id the parser gives them ({@code RawYsmModel.RawImage.name}, i.e. {@code "gui_background"} /
     * {@code "gui_foreground"}).
     * <p>
     * They are neither model textures nor geometry, which is why they are not in the two maps beside this one:
     * {@link #texture} is the model's texture *list* ({@code ServerModelInfo} is built from its key set and the
     * selection screen lists it as selectable skins), and {@link #model} is a map of geometry files that
     * {@code ClientModelManager} parses as JSON one key at a time. Upstream carries the same two images as
     * presentation image blobs in the model's settings/manifest
     * ({@code format/parser/ModelInfoBuilder.java:57-64} reads them into {@code settings.setGuiForeground/Background},
     * and {@code model/resource/client/asset/ClientAssetRepository.java:193-208} serves them as
     * {@code GUI_FOREGROUND} / {@code GUI_BACKGROUND} assets), i.e. beside the model's data rather than among its
     * textures or its geometry - the same split this map makes.
     */
    private final Map<String, byte[]> guiImages;
    private final ServerModelInfo info;
    private final WheelDeclarations wheelDeclarations;
    /**
     * The model's declared metadata links - an <b>ordered</b> map of link <em>type</em> to URL, straight from the
     * pack's {@code ysm.json} {@code metadata.links}
     * ({@code YSMFolderDeserializer.java:163} parses it into
     * {@link com.fox.ysmu.model.resource.pojo.RawYsmModel.RawMetadata#links}, and the OpenYSM
     * binary payload already carries it both ways: {@code YSMBinarySerializer:486-490} /
     * {@code YSMBinaryDeserializer:605-608}).
     * <p>
     * It reaches the client on both sync channels: the OpenYSM channel hands the parsed raw model to
     * {@code RawYsmModelAdapter.toLegacyModelData}, and the legacy encrypted container carries it in its own
     * version-6 section ({@code EncryptTools.LINKS_MODEL_VERSION}). Upstream has the same field and draws its two
     * first entries as buttons on the information screen
     * ({@code tmp/YesSteveModel-dev-1.20/.../client/gui/ModelInfoScreen.java:111-124}: {@code Math.min(links.size(), 2)},
     * label from a {@code home}/{@code donate} preset else the type itself, click opens the URL).
     * <p>
     * Declaration order is part of the value: "the first two links" is what the screen shows, and the folder parser
     * and the binary deserializer both read them into a {@link LinkedHashMap}.
     */
    private final Map<String, String> links;
    /** Explicit base key -> component role (1 normal, 2 specular) -> PNG. Never selectable skins. */
    private final Map<String, Map<Integer, byte[]>> pbrComponents;

    public ModelData(String modelId, Type type, Map<String, byte[]> geo, Map<String, byte[]> texture,
        Map<String, byte[]> animation) {
        this(modelId, type, geo, texture, animation, null);
    }

    /**
     * The same payload with its GUI presentation images. Kept as a second constructor rather than a sixth parameter
     * on the one above so every existing caller - the legacy cache reader ({@code EncryptTools.decryptModel}), the
     * three format bakers and the tests - keeps compiling unchanged; a payload built without images simply has none.
     *
     * @param guiImages image id to PNG bytes; {@code null} and an empty map both mean "this model declares none"
     */
    public ModelData(String modelId, Type type, Map<String, byte[]> geo, Map<String, byte[]> texture,
        Map<String, byte[]> animation, @Nullable Map<String, byte[]> guiImages) {
        this(modelId, type, geo, texture, animation, guiImages, WheelDeclarations.empty());
    }

    public ModelData(String modelId, Type type, Map<String, byte[]> geo, Map<String, byte[]> texture,
        Map<String, byte[]> animation, @Nullable Map<String, byte[]> guiImages, WheelDeclarations wheelDeclarations) {
        this(modelId, type, geo, texture, animation, guiImages, wheelDeclarations, Collections.emptyMap());
    }

    public ModelData(String modelId, Type type, Map<String, byte[]> geo, Map<String, byte[]> texture,
        Map<String, byte[]> animation, @Nullable Map<String, byte[]> guiImages, WheelDeclarations wheelDeclarations,
        Map<String, Map<Integer, byte[]>> components) {
        this(modelId, type, geo, texture, animation, guiImages, wheelDeclarations, components, null);
    }

    /**
     * The full payload, with the model's metadata links (see {@link #links}).
     * <p>
     * Kept as one more overload rather than a tenth parameter on the constructor above so every existing caller - the
     * legacy cache reader, the three format bakers, the client bridge and the tests - keeps compiling unchanged; a
     * payload built without links simply has none, which is also what an older payload decodes to.
     *
     * @param links link type to URL, in declaration order; {@code null} and an empty map both mean "declares none"
     */
    public ModelData(String modelId, Type type, Map<String, byte[]> geo, Map<String, byte[]> texture,
        Map<String, byte[]> animation, @Nullable Map<String, byte[]> guiImages, WheelDeclarations wheelDeclarations,
        Map<String, Map<Integer, byte[]>> components, @Nullable Map<String, String> links) {
        Map<String, Map<Integer, byte[]>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, byte[]>> base : components.entrySet()) {
            if (!texture.containsKey(base.getKey())) throw new IllegalArgumentException("PBR base is not a texture");
            Map<Integer, byte[]> roles = new LinkedHashMap<>();
            for (Map.Entry<Integer, byte[]> role : base.getValue().entrySet()) {
                if (role.getKey() != 1 && role.getKey() != 2) throw new IllegalArgumentException("Unknown PBR role");
                roles.put(role.getKey(), role.getValue().clone());
            }
            copied.put(base.getKey(), Collections.unmodifiableMap(roles));
        }
        this.pbrComponents = Collections.unmodifiableMap(copied);
        this.wheelDeclarations = wheelDeclarations;
        this.links = links == null || links.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(links));
        this.modelId = modelId;
        this.model = geo;
        this.texture = texture;
        this.animation = animation;
        this.guiImages = guiImages == null || guiImages.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(guiImages));
        this.info = new ServerModelInfo(texture.keySet(), type);
    }

    public String getModelId() {
        return modelId;
    }

    public Map<String, byte[]> getModel() {
        return model;
    }

    public Map<String, byte[]> getTexture() {
        return texture;
    }

    public Map<String, byte[]> getAnimation() {
        return animation;
    }

    /** The model's GUI presentation images (see the field); empty when it declares none. */
    public Map<String, byte[]> getGuiImages() {
        return guiImages;
    }

    public Map<String, Map<Integer, byte[]>> getPbrComponents() {
        return pbrComponents;
    }

    public WheelDeclarations getWheelDeclarations() {
        return wheelDeclarations;
    }

    /**
     * The model's metadata links, link type to URL, in declaration order (see the field); empty when the model
     * declares none or arrived over a channel that predates the field.
     */
    public Map<String, String> getLinks() {
        return links;
    }

    public void setMd5(String md5) {
        this.info.setMd5(md5);
    }

    public ServerModelInfo getInfo() {
        return info;
    }
}
