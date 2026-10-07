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

    public void setMd5(String md5) {
        this.info.setMd5(md5);
    }

    public ServerModelInfo getInfo() {
        return info;
    }
}
