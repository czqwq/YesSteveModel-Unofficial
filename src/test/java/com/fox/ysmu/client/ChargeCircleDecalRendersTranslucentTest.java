package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.texture.OuterFileTexture;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.YsmRenderType;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;

/**
 * The charge-circle decal, followed from the pack's own data to the render type it is drawn with - the chain the
 * owner reported as broken, pinned end to end without a client.
 * <p>
 * Every link is upstream's: the decal is a cube with one zero dimension whose art sits on a single face
 * ({@code ysmGlowdamofazhen3}, {@code size [60.5, 60.5, 0]}, {@code north} at 65x65 and everything else zeroed);
 * upstream builds those zeroed faces anyway ({@code GeoBuilder:186-193} skips only a face whose uv data is missing);
 * the face that would cover the art is one texel of the atlas, opaque grey in {@code magic.png}; and the draw is
 * chosen by {@code getRenderType}, which returns the translucent branch - blending and culling - when the model has a
 * translucent vertex ({@code IGeoRenderer:33-39}, {@code GeoModelState:73-74}). Culling is what removes the coincident
 * back face, and the blending is what lets the 93% transparent circle art show the world through it.
 */
class ChargeCircleDecalRendersTranslucentTest {

    private static final Path PACK = Paths.get("run", "client", "config", "ysmu", "custom", "wine_fox", "05_magical");

    private static GeoModel model() throws Exception {
        Path file = PACK.resolve("models")
            .resolve("main.json");
        assumeTrue(Files.isRegularFile(file), "sample pack not present");
        RawGeoModel raw = Converter.fromJsonString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        return GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(RawGeometryTree.parseHierarchy(raw));
    }

    private static IGeoRenderer<Object> renderer() {
        return new IGeoRenderer<Object>() {

            @Override
            public GeoModelProvider getGeoModelProvider() {
                return null;
            }

            @Override
            public ResourceLocation getTextureLocation(Object instance) {
                return null;
            }
        };
    }

    @Test
    void theDecalsTextureIsTranslucentSoTheModelTakesTheTranslucentBranch() throws Exception {
        File texture = PACK.resolve("textures")
            .resolve("magic.png")
            .toFile();
        assumeTrue(texture.isFile(), "sample texture not present");
        GeoModel main = model();

        // 1. The model samples non-opaque texels of this texture - what upstream's bake counts, and what the port's
        // documented stand-in for it reads.
        assertTrue(
            ClientModelManager
                .samplesTranslucentTexel(OuterFileTexture.decode(Files.readAllBytes(texture.toPath())), main),
            "the charge-circle art is drawn on a transparent background, so the model has a translucent vertex");

        // 2. So getRenderType takes the translucent branch: blending, and culling - the culling is what stops the
        // decal's coincident zero-uv back face from covering it with one stretched opaque texel.
        YsmRenderType type = renderer()
            .getRenderType(new ResourceLocation("ysmu", "test/magic.png"), true, false, true);
        assertNotNull(type);
        assertTrue(type.isBlend(), "the translucent branch blends");
        assertTrue(type.isCull(), "the translucent branch culls");

        // 3. And an opaque model keeps the branch it had before the render type existed.
        YsmRenderType opaque = renderer()
            .getRenderType(new ResourceLocation("ysmu", "test/opaque.png"), true, false, false);
        assertNotNull(opaque);
        assertFalse(opaque.isCull(), "an opaque model keeps the no-cull behaviour it had");
    }
}
