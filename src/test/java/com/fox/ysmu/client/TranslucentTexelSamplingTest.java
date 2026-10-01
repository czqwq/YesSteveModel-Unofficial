package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.texture.OuterFileTexture;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * The rule that decides whether a model is drawn with back-face culling - the port's half of upstream's render-type
 * choice ({@code entityTranslucent}, which culls, for a model with a translucent vertex; {@code entityCutoutNoCull}
 * otherwise, {@code com/elfmcys/ysm/geckolib3/geo/IGeoRenderer.java:33-39}).
 * <p>
 * Two things have to hold, and the second one matters more than the first: the flat decal that exposed this must be
 * detected, and an opaque texture must never trip the rule - a predicate that fires too widely is what broke other
 * models the last time this was attempted.
 */
class TranslucentTexelSamplingTest {

    private static final Path PACK = Paths.get("run", "client", "config", "ysmu", "custom", "wine_fox", "05_magical");

    private static GeoModel model(String part) throws Exception {
        Path file = PACK.resolve("models")
            .resolve(part + ".json");
        assumeTrue(Files.isRegularFile(file), "sample pack not present");
        RawGeoModel raw = Converter.fromJsonString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        return GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(RawGeometryTree.parseHierarchy(raw));
    }

    private static BufferedImage texture(String name) throws Exception {
        File file = PACK.resolve("textures")
            .resolve(name + ".png")
            .toFile();
        assumeTrue(file.isFile(), "sample texture not present");
        return OuterFileTexture.decode(Files.readAllBytes(file.toPath()));
    }

    @Test
    void theFlatDecalTextureIsDetected() throws Exception {
        // Every one of this pack's textures carries the charge-circle art on a transparent background, and the model
        // samples it, so upstream would draw this model with the culling render type - which is what removes the
        // coincident back face of the decal.
        assertTrue(ClientModelManager.samplesTranslucentTexel(texture("magic"), model("main")));
        assertTrue(ClientModelManager.samplesTranslucentTexel(texture("winefox"), model("main")));
    }

    @Test
    void anOpaqueTextureIsNeverDetected() throws Exception {
        // The guard against over-reach: the same geometry sampled against a fully opaque image must come back false,
        // so a model whose art has no transparency keeps exactly the rendering it has today.
        BufferedImage opaque = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < opaque.getHeight(); y++) {
            for (int x = 0; x < opaque.getWidth(); x++) {
                opaque.setRGB(x, y, 0xFF204060);
            }
        }
        assertFalse(ClientModelManager.samplesTranslucentTexel(opaque, model("main")));
        assertFalse(ClientModelManager.samplesTranslucentTexel(opaque, model("arm")));
    }

    @Test
    void aSingleTransparentTexelUnderAFaceIsEnough() throws Exception {
        // The rule reads the texels a face actually covers, which is what upstream's bake has to work from too - a
        // whole-image test would mark every model translucent, because these atlases are 58-65% empty space.
        BufferedImage nearlyOpaque = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < nearlyOpaque.getHeight(); y++) {
            for (int x = 0; x < nearlyOpaque.getWidth(); x++) {
                nearlyOpaque.setRGB(x, y, 0xFF204060);
            }
        }
        GeoModel main = model("main");
        assertFalse(ClientModelManager.samplesTranslucentTexel(nearlyOpaque, main), "baseline must be opaque");

        // The rule samples a face's vertices, exactly as upstream counts translucent vertices, so the hole has to sit
        // on one: the decal's uv corner is (191, 191) of the 256x256 atlas.
        nearlyOpaque.setRGB(191, 191, 0x00204060);
        assertTrue(ClientModelManager.samplesTranslucentTexel(nearlyOpaque, main));
    }
}
