package com.fox.ysmu.client.gui.button;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.util.ModelIdUtil;

class ModelButtonDefaultTextureTest {

    @Test
    void declaredDefaultBeatsBothListOrderAndGlobalDefault() {
        ResourceLocation model = new ResourceLocation("ysmu", "tile_default_test");
        ResourceLocation first = ModelIdUtil.getSubModelId(model, "first.png");
        ResourceLocation declared = ModelIdUtil.getSubModelId(model, "declared.png");
        ResourceLocation configured = ModelIdUtil.getSubModelId(model, Config.DEFAULT_MODEL_TEXTURE);
        ResourceLocation previous = ClientModelManager.DECLARED_DEFAULT_TEXTURES.put(model, declared);
        try {
            assertEquals(declared, ModelButton.defaultTexture(model, Arrays.asList(first, configured, declared)));
            ClientModelManager.DECLARED_DEFAULT_TEXTURES.put(model, ModelIdUtil.getSubModelId(model, "missing.png"));
            assertEquals(configured, ModelButton.defaultTexture(model, Arrays.asList(first, configured)));
            assertEquals(first, ModelButton.defaultTexture(model, Collections.singletonList(first)));
            assertNull(ModelButton.defaultTexture(model, Collections.emptyList()));
            assertNull(ModelButton.defaultTexture(model, null));
        } finally {
            if (previous == null) {
                ClientModelManager.DECLARED_DEFAULT_TEXTURES.remove(model);
            } else {
                ClientModelManager.DECLARED_DEFAULT_TEXTURES.put(model, previous);
            }
        }
    }
}
