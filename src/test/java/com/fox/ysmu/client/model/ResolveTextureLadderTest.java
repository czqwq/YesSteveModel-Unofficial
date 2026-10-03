package com.fox.ysmu.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.util.ModelIdUtil;

/**
 * The ladder that decides which texture a model is drawn with, which is where a pack's own declared default has to
 * be honoured.
 * <p>
 * Upstream resolves the texture inside the model that was loaded ({@code format/schema/model/ModelManifestLookup.java
 * :10-29}): the requested name, then the model's own {@code settings.defaultTexture} - filled with the first declared
 * texture by {@code RawModelAssembler:189-196} when the pack declares none - and only then the first entry, each rung
 * checked against that model's texture list ({@code containsTexture}, {@code :41-49}). The port used to put the global
 * {@code Config.DEFAULT_MODEL_TEXTURE} where the declared default belongs, so a pack that declares one was ignored.
 * These tests drive the ladder directly, because it needs no game: only ids and a list.
 */
class ResolveTextureLadderTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "wine_fox_05_magical");

    private static final ResourceLocation MAGIC = ModelIdUtil.getSubModelId(MODEL, "magic.png");

    private static final ResourceLocation WINEFOX = ModelIdUtil.getSubModelId(MODEL, "winefox.png");

    /** A selection that belongs to a pack this model replaced, so it is not one of this model's textures. */
    private static final ResourceLocation FOREIGN = ModelIdUtil.getSubModelId(MODEL, "default.png");

    private static List<ResourceLocation> textures() {
        return new ArrayList<>(Arrays.asList(MAGIC, WINEFOX));
    }

    @Test
    void aSelectionThisModelHasWins() {
        assertEquals(WINEFOX, CustomPlayerModel.resolveTexture(MODEL, textures(), WINEFOX, MAGIC));
    }

    @Test
    void theModelsOwnDeclaredDefaultIsUsedWhenTheSelectionIsNotOneOfItsTextures() {
        // The rung the port was missing: the pack declared magic.png, the synced selection names a texture this model
        // does not have, so the declared default is what gets bound - not the global config value.
        assertEquals(MAGIC, CustomPlayerModel.resolveTexture(MODEL, textures(), FOREIGN, MAGIC));
    }

    @Test
    void aDeclarationThisModelDoesNotHaveFallsThrough() {
        // RawProperties initialises the field to the literal "default", and a pack can name a texture it does not
        // ship. Upstream's containsTexture rejects both, and so must this ladder.
        ResourceLocation configured = ModelIdUtil.getSubModelId(MODEL, Config.DEFAULT_MODEL_TEXTURE);
        ResourceLocation expected = textures().contains(configured) ? configured : MAGIC;

        assertEquals(expected, CustomPlayerModel.resolveTexture(MODEL, textures(), FOREIGN, FOREIGN));
        assertEquals(expected, CustomPlayerModel.resolveTexture(MODEL, textures(), null, null));
        assertEquals(expected, CustomPlayerModel.resolveTexture(MODEL, textures(), null, FOREIGN));
    }

    @Test
    void theConfiguredDefaultStillWorksWhenTheModelDeclaresNothing() {
        // The port keeps Config.DEFAULT_MODEL_TEXTURE as its own rung - it is also the initial per-player selection -
        // so a model that declares no default and does not have the configured one still lands on its first texture.
        List<ResourceLocation> withConfigured = textures();
        ResourceLocation configured = ModelIdUtil.getSubModelId(MODEL, Config.DEFAULT_MODEL_TEXTURE);
        withConfigured.add(configured);

        assertEquals(configured, CustomPlayerModel.resolveTexture(MODEL, withConfigured, FOREIGN, null));
        // And with neither a usable selection nor a declaration, the configured texture beats the first entry.
        assertEquals(configured, CustomPlayerModel.resolveTexture(MODEL, withConfigured, null, FOREIGN));
    }

    @Test
    void anEmptyOrMissingListFallsBackToTheBuiltInDefaultTexture() {
        // Nothing is published yet, or the model has no textures at all: the built-in default model's texture is the
        // only safe answer, and the caller never gets a null it would have to handle.
        assertEquals(CustomPlayerModel.DEFAULT_TEXTURE, CustomPlayerModel.resolveTexture(MODEL, null, MAGIC, MAGIC));
        assertEquals(
            CustomPlayerModel.DEFAULT_TEXTURE,
            CustomPlayerModel.resolveTexture(MODEL, new ArrayList<>(), MAGIC, MAGIC));
    }
}
