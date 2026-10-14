package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.gui.ModelPreviewRegistry;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.data.WheelDeclarations;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;

/**
 * The model's GUI preview declaration - {@code preview_animation} and {@code disable_preview_rotation} - reaches the
 * selection screen by riding the payload into {@link ClientModelManager#registerAll}, the one funnel both sync
 * channels (and the local read of the built-in default) hand a model to the client through.
 *
 * <p>Before this, the declaration was registered by the OpenYSM sync channel's own side call, so a model that arrived
 * any other way kept nothing: the built-in default - read from {@code builtin/default} and bridged locally - lost its
 * own {@code "preview_animation": "gui"} and the tile silently played {@code idle}. It is the same defect shape the
 * GUI presentation images had, and it is pinned here the same way: through the public registration path, not against
 * a private field.
 *
 * <p>Also pinned: the payload's fingerprint includes the declaration, or a re-registration whose only change is the
 * clip the tile plays would be skipped as "identical content" and the tile would keep playing the previous clip.
 */
class ClientModelManagerPreviewRegistrationTest {

    private static final int PNG_FORMAT = 2;
    private static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private final List<ResourceLocation> registered = new ArrayList<>();

    @AfterEach
    void forgetProbeModels() {
        for (ResourceLocation id : this.registered) {
            ClientModelManager.MODELS.remove(id);
        }
        this.registered.clear();
        // The declarations describe exactly the models dropped above, which is what clearRuntimeModelCaches does with
        // them on a real sync.
        ModelPreviewRegistry.clear();
    }

    /** A payload with no geometry, textures or animations: only the declaration is under test. */
    private static ModelData payload(String modelId, String previewAnimation, boolean disablePreviewRotation) {
        return new ModelData(
            modelId,
            Type.FOLDER,
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            null,
            WheelDeclarations.empty(),
            Collections.emptyMap(),
            null,
            previewAnimation,
            disablePreviewRotation);
    }

    private ResourceLocation register(String modelId, String previewAnimation, boolean disablePreviewRotation) {
        ClientModelManager.registerAll(payload(modelId, previewAnimation, disablePreviewRotation));
        ResourceLocation id = new ResourceLocation(ysmu.MODID, modelId);
        this.registered.add(id);
        return id;
    }

    /**
     * The route the built-in default takes: a parsed pack model is bridged into a payload
     * ({@code RawYsmModelAdapter.toLegacyModelData}, which {@code ClientModelManager.loadDefaultModel} calls for
     * {@code builtin/default}) and that payload is registered. Both halves are asserted, because either one missing
     * leaves the tile without the model's own clip: the bridge must carry the declaration, and registration must
     * remember it under the model's own id.
     */
    @Test
    void theBridgeAndTheRegistrationFunnelCarryTheModelsPreviewDeclaration() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.properties.previewAnimation = "gui";
        raw.properties.disablePreviewRotation = true;

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        assertEquals("gui", data.getPreviewAnimation(), "the adapter must bridge preview_animation onto the payload");
        assertTrue(data.isDisablePreviewRotation(), "and disable_preview_rotation with it");

        ResourceLocation id = new ResourceLocation(ysmu.MODID, raw.modelId);
        ClientModelManager.registerAll(data);
        this.registered.add(id);

        assertEquals("gui", ModelPreviewRegistry.previewFor(id), "registration must remember what the payload declared");
        assertTrue(ModelPreviewRegistry.previewRotationDisabled(id), "including the framing variant");
    }

    /**
     * A payload that declares none - an older payload, or one delivered by a channel that does not carry the field -
     * falls back to {@code idle} and <b>clears</b> the previous declaration rather than leaving it behind, the same
     * self-healing rule the GUI images follow.
     */
    @Test
    void aPayloadWithoutADeclarationFallsBackToIdleAndClearsThePreviousOne() {
        ResourceLocation id = register("preview_probe_selfheal", "gui", true);
        assertEquals("gui", ModelPreviewRegistry.previewFor(id));

        ClientModelManager.registerAll(payload("preview_probe_selfheal", null, false));

        assertEquals(
            AnimationRegister.IDLE,
            ModelPreviewRegistry.previewFor(id),
            "an undeclared preview_animation means idle, exactly as upstream's default");
        assertFalse(ModelPreviewRegistry.previewRotationDisabled(id), "and no framing variant is claimed");
    }

    /**
     * The fingerprint must include the declaration: a payload that changes only its {@code preview_animation} is a
     * different payload, and if it were treated as identical the tile would keep playing the old clip forever.
     */
    @Test
    void aPayloadThatOnlyChangesItsPreviewAnimationIsRegisteredAgain() {
        ResourceLocation id = register("preview_probe_signature", "gui", false);

        ClientModelManager.registerAll(payload("preview_probe_signature", "wave", false));

        assertEquals(
            "wave",
            ModelPreviewRegistry.previewFor(id),
            "a payload whose only change is its preview_animation must not be skipped as identical content");
    }

    // ── the raw-model fixture the adapter needs ──────────────────────────────

    private static RawYsmModel.RawGeometry geometry() {
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();
        geo.identifier = "geometry.test";
        geo.sourceJson = GEO.getBytes(StandardCharsets.UTF_8);
        return geo;
    }

    private static RawYsmModel.RawTexture pngTexture(String name) {
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = name;
        texture.sourceFileName = name;
        texture.imageFormat = PNG_FORMAT;
        texture.data = new byte[] { (byte) 0x89, 'P', 'N', 'G' };
        return texture;
    }

    private static RawYsmModel bridgeableModel() {
        RawYsmModel raw = new RawYsmModel();
        raw.modelId = "preview_probe_bridge";
        raw.mainEntity.mainModel = geometry();
        raw.mainEntity.armModel = geometry();
        raw.mainEntity.textures.put("texture.png", pngTexture("texture.png"));
        return raw;
    }
}
