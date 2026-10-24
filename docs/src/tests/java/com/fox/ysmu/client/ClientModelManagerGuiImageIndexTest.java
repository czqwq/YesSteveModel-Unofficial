package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

/**
 * {@link ClientModelManager}'s GUI presentation image index: which bytes a model declares, under which key, and what
 * happens when a model is re-registered.
 *
 * <p>Three behaviours, all of them a shipped defect waiting to happen:
 * <ul>
 * <li>the index is keyed by the <b>model id</b> the registration used - not by the main sub-model id - so the writer
 * and the reader cannot drift apart;</li>
 * <li>a payload that declares no images <b>removes</b> the entry (self-healing), so a re-sync cannot leave the
 * previous payload's background behind;</li>
 * <li>a payload whose <b>only</b> change is its images must not be skipped as "identical content", or the tile keeps
 * drawing the previous background. That is {@code contentSignature} including {@code guiImages}, and it is asserted
 * through the public registration path rather than against the private fingerprint.</li>
 * </ul>
 */
class ClientModelManagerGuiImageIndexTest {

    private static final String BACKGROUND = ClientModelManager.GUI_IMAGE_BACKGROUND;
    private static final String FOREGROUND = ClientModelManager.GUI_IMAGE_FOREGROUND;

    private final List<ResourceLocation> registered = new ArrayList<>();

    @AfterEach
    void forgetProbeModels() {
        for (ResourceLocation id : this.registered) {
            // Re-registering without images is the self-healing path, so it also removes the index entry.
            ClientModelManager.registerAll(payload(id.getResourcePath(), null));
            ClientModelManager.MODELS.remove(id);
        }
        this.registered.clear();
    }

    /** A payload with no geometry, textures or animations: only the images are under test. */
    private static ModelData payload(String modelId, Map<String, byte[]> guiImages) {
        return new ModelData(
            modelId,
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            guiImages);
    }

    private ResourceLocation register(String modelId, Map<String, byte[]> guiImages) {
        ClientModelManager.registerAll(payload(modelId, guiImages));
        ResourceLocation id = new ResourceLocation(ysmu.MODID, modelId);
        this.registered.add(id);
        return id;
    }

    private static Map<String, byte[]> images(String background, byte[] bytes) {
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put(background, bytes);
        return images;
    }

    /** Registration indexes both images under the model's own id, the same key {@code MODELS} uses. */
    @Test
    void aRegisteredPayloadIndexesItsGuiImagesUnderTheModelsOwnId() {
        byte[] background = { 1, 2, 3 };
        byte[] foreground = { 4, 5, 6 };
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put(BACKGROUND, background);
        images.put(FOREGROUND, foreground);

        ResourceLocation id = register("gui_probe_register", images);

        assertArrayEquals(background, ClientModelManager.guiImageBytesFor(id, BACKGROUND));
        assertArrayEquals(foreground, ClientModelManager.guiImageBytesFor(id, FOREGROUND));
        assertTrue(ClientModelManager.MODELS.containsKey(id), "the registration key is the model id");
        assertNull(
            ClientModelManager.guiImageBytesFor(ModelIdUtil.getMainId(id), BACKGROUND),
            "GUI images are keyed by the model id, not by the main sub-model id");
    }

    /** A re-registration without images removes the entry instead of leaving the previous payload's background. */
    @Test
    void aPayloadWithoutImagesRemovesTheEntry() {
        ResourceLocation id = register("gui_probe_selfheal", images(BACKGROUND, new byte[] { 1, 2 }));

        assertNotNull(ClientModelManager.guiImageBytesFor(id, BACKGROUND));

        ClientModelManager.registerAll(payload("gui_probe_selfheal", null));

        assertNull(ClientModelManager.guiImageBytesFor(id, BACKGROUND), "no images means no entry");
    }

    /** Unknown ids, null ids and blank image ids all answer null rather than throwing. */
    @Test
    void unknownNullAndBlankLookupsAnswerNull() {
        ResourceLocation id = register("gui_probe_lookup", images(BACKGROUND, new byte[] { 1 }));

        assertNull(ClientModelManager.guiImageBytesFor(new ResourceLocation("ysmu", "gui_probe_never_registered"), BACKGROUND));
        assertNull(ClientModelManager.guiImageBytesFor(null, BACKGROUND));
        assertNull(ClientModelManager.guiImageBytesFor(id, null));
        assertNull(ClientModelManager.guiImageBytesFor(id, ""));
        assertNull(ClientModelManager.guiImageBytesFor(id, FOREGROUND), "this model declares no foreground");
    }

    /**
     * The fingerprint must include the images: a payload that changes only its {@code gui_background} is a different
     * payload, and if it were treated as identical the selection tile would keep the old image forever.
     */
    @Test
    void aPayloadThatOnlyChangesItsGuiImageIsRegisteredAgain() {
        byte[] first = { 1, 2, 3 };
        byte[] second = { 9, 9, 9 };
        ResourceLocation id = register("gui_probe_signature", images(BACKGROUND, first));

        assertArrayEquals(first, ClientModelManager.guiImageBytesFor(id, BACKGROUND));

        ClientModelManager.registerAll(payload("gui_probe_signature", images(BACKGROUND, second)));

        assertArrayEquals(
            second,
            ClientModelManager.guiImageBytesFor(id, BACKGROUND),
            "a payload whose only change is its GUI image must not be skipped as identical content");
    }
}
