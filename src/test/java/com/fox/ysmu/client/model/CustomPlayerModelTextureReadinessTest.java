package com.fox.ysmu.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.GeometryFixtures;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * A model's own texture may only be handed to the renderer once that model has been built, because building is what
 * uploads its textures.
 * <p>
 * This is the regression that on-demand loading caused in game: the renderer binds a model's texture by id, while the
 * model was still queued. {@code TextureManager} answers an id it has never seen by creating a resource-pack lookup
 * for it, which cannot succeed for a model texture - it logs {@code Failed to load texture} with a
 * {@code FileNotFoundException} and draws the shared missing texture.
 * <p>
 * The test deliberately installs the <em>built-in default</em> model first, because that is the condition the game is
 * always in and the one that hid a wrong first attempt at this guard. The guard originally asked about
 * {@code CustomPlayerEntity.getMainModel()}, which answers with the built-in default exactly while the requested model
 * is unbuilt - so it was asking whether the default was installed, and the moment it was, the requested model's
 * not-yet-uploaded id went straight through. With a default installed here, that mistake fails this test.
 */
class CustomPlayerModelTextureReadinessTest {

    private static final ResourceLocation DEFAULT_MODEL_ID = new ResourceLocation(ysmu.MODID, "default");
    private static final ResourceLocation MODEL_ID = new ResourceLocation(ysmu.MODID, "texture_readiness_probe");
    // Its own id: GEOMETRY_READY has no public reset, so two tests sharing an id would let the first one's publish
    // decide the second one's result.
    private static final ResourceLocation LADDER_MODEL_ID = new ResourceLocation(ysmu.MODID, "texture_ladder_probe");

    /** The texture the probe entity claims to wear; it is never uploaded, and must not be handed out because of it. */
    private static final ResourceLocation CLAIMED_TEXTURE = new ResourceLocation(
        ysmu.MODID,
        "texture_readiness_probe/skin.png");

    private static ResourceLocation mainIdOf(ResourceLocation modelId) {
        return ModelIdUtil.getMainId(modelId);
    }

    @AfterEach
    void release() {
        for (ResourceLocation id : new ResourceLocation[] { mainIdOf(MODEL_ID), mainIdOf(LADDER_MODEL_ID),
            mainIdOf(DEFAULT_MODEL_ID) }) {
            GeckoLibCache.getInstance()
                .getGeoModels()
                .remove(id);
            ClientModelManager.SCALE_INFO.remove(id);
            ClientModelManager.RENDER_LAYERS_FIRST.remove(id);
            ClientModelManager.EXTRA_INFO.remove(id);
            ClientModelManager.EXTRA_ANIMATION_NAME.remove(id);
        }
        ClientModelManager.MODELS.remove(MODEL_ID);
        ClientModelManager.MODELS.remove(LADDER_MODEL_ID);
        ClientModelManager.MODELS.remove(DEFAULT_MODEL_ID);
    }

    /**
     * The texture ladder, which is upstream's: the requested texture when the model has it, otherwise the model's
     * configured default when it has that, otherwise the model's first texture. Upstream resolves exactly this in
     * {@code ClientModelRenderTargetManager.resolveEffectiveKey}, and it is what makes a selection left over from a
     * pack that renamed its files fall back inside the model instead of reaching a texture nobody registered.
     * <p>
     * The model's texture list is written directly because a test cannot upload a texture; that list is exactly what
     * {@code indexTextures} records for the GUI, so seeding it is the same state, not a shortcut around the code
     * under test.
     */
    @Test
    void aSelectionOutsideTheModelsOwnTexturesFallsBackInsideTheModel() throws Exception {
        byte[] geometry = GeometryFixtures.buildableGeometry("main.json");
        ClientModelManager.registerAll(payload(LADDER_MODEL_ID.getResourcePath(), geometry), true);
        assertTrue(ClientModelManager.isModelPublished(mainIdOf(LADDER_MODEL_ID)), "the model must publish");

        ResourceLocation firstTexture = ModelIdUtil.getSubModelId(LADDER_MODEL_ID, "first.png");
        ResourceLocation secondTexture = ModelIdUtil.getSubModelId(LADDER_MODEL_ID, "second.png");
        ClientModelManager.MODELS.put(LADDER_MODEL_ID, Lists.newArrayList(firstTexture, secondTexture));

        CustomPlayerEntity entity = new CustomPlayerEntity();
        entity.setMainModel(mainIdOf(LADDER_MODEL_ID));

        entity.setTexture(secondTexture);
        assertEquals(
            secondTexture,
            CustomPlayerModel.textureFor(entity),
            "a texture the model has is used as asked");

        entity.setTexture(ModelIdUtil.getSubModelId(LADDER_MODEL_ID, "renamed-away.png"));
        assertEquals(
            firstTexture,
            CustomPlayerModel.textureFor(entity),
            "a texture the model does not have falls back inside the model, not to a global constant");
    }

    /** A payload with one real geometry file and no textures; uploading textures needs a running game. */
    private static ModelData payload(String name, byte[] geometry) {
        Map<String, byte[]> models = new LinkedHashMap<>();
        models.put("main", geometry);
        return new ModelData(name, Type.FOLDER, models, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    @Test
    void theModelsOwnTextureIsWithheldUntilThatModelHasBeenBuilt() throws Exception {
        // The reference pack when it is present, an inline model when it is not. This test must run everywhere: the
        // property it pins has already regressed once, and a version that skips itself on a fresh clone or in CI would
        // have said nothing at all.
        byte[] geometry = GeometryFixtures.buildableGeometry("main.json");

        // The built-in default, installed the way the game installs it: eagerly, before anything is parked. Without
        // this the guard could pass by accident, because getMainModel() would substitute the default and the default
        // would itself be missing.
        ClientModelManager.registerAll(payload(DEFAULT_MODEL_ID.getResourcePath(), geometry), true);
        assertTrue(
            ClientModelManager.isModelPublished(mainIdOf(DEFAULT_MODEL_ID)),
            "installing the built-in default eagerly must make it drawable; if it does not, this test can no longer "
                + "tell the two predicates apart and would pass for the wrong reason");

        ClientModelManager.registerAll(payload(MODEL_ID.getResourcePath(), geometry), false);

        CustomPlayerEntity entity = new CustomPlayerEntity();
        entity.setMainModel(mainIdOf(MODEL_ID));
        entity.setTexture(CLAIMED_TEXTURE);
        CustomPlayerModel provider = new CustomPlayerModel();

        // getMainModel() is the substitution: it answers with the default while this model is unbuilt, which is why
        // the guard must not use it.
        assertEquals(
            mainIdOf(DEFAULT_MODEL_ID),
            entity.getMainModel(),
            "the entity substitutes the default while its own model is unbuilt - that is the trap this test is about");

        assertEquals(
            CustomPlayerModel.DEFAULT_TEXTURE,
            provider.getTextureLocation(entity),
            "while the model is only queued the renderer draws the built-in default, so it must be given the "
                + "default's texture; handing out the model's own id makes TextureManager search a resource pack, log "
                + "a FileNotFoundException and draw the shared missing texture");

        ClientModelManager.ensureGeometry(mainIdOf(MODEL_ID));
        GeoModel published = null;
        for (int tick = 0; tick < 400 && published == null; tick++) {
            ClientModelManager.tick();
            published = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(mainIdOf(MODEL_ID));
            if (published == null) {
                Thread.sleep(10L);
            }
        }
        assertTrue(
            published != null,
            "the loader must publish the model; a loader that never publishes is the regression this class exists to "
                + "catch, so it fails here rather than skipping the texture half");
        assertTrue(
            ClientModelManager.isModelPublished(mainIdOf(MODEL_ID)),
            "and publishing is what makes the model drawable at all");

        // The claimed texture is still not bindable, and that is the invariant rather than a limitation: this payload
        // declares no textures (a test cannot upload one - that needs a running game), so binding the id the entity
        // asks for would be binding an id this client never registered, which is the resource-pack fallback and the
        // shared missing texture. The positive case - a published model whose own texture is uploaded - is the game's
        // normal path and is not reachable here.
        assertEquals(
            CustomPlayerModel.DEFAULT_TEXTURE,
            provider.getTextureLocation(entity),
            "only an id this client uploaded may be bound, whatever the entity asks for");
        assertFalse(
            CLAIMED_TEXTURE.equals(provider.getTextureLocation(entity)),
            "and the model's own id must not be handed out merely because the model is now published");
    }
}
