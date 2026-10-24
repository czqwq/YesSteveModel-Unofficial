package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Why a model texture has to be uploaded clamped, which is the platform default upstream relies on.
 * <p>
 * A pack says "there is no face here" by zeroing that face's {@code uv_size}
 * ({@code wine_fox/05_magical}'s {@code ysmGlowdamofazhen3} declares {@code south} as {@code uv [256, 0],
 * uv_size [0, 0]}). Upstream builds those faces anyway - its {@code GeoBuilder:186-193} only skips a face whose uv
 * data is *missing* - so all four of that face's uvs normalize to {@code u = 1.0}. Which texel that is depends
 * entirely on the sampler's wrap mode:
 *
 * <pre>
 * CLAMP_TO_EDGE (upstream's platform default, and what OuterFileTexture now uses) -&gt; the last texel of the row
 * GL_REPEAT    (what it used to use)                                           -&gt; wraps to the first texel
 * </pre>
 *
 * This test pins the two texels, because the whole fix rests on them: the last one must be transparent so the face is
 * invisible, and the wrapped one must be opaque so that the old behaviour really did produce an opaque slab.
 */
class OuterFileTextureWrapTest {

    private static final Path TEXTURES = Paths.get(
        "run",
        "client",
        "config",
        "ysmu",
        "custom",
        "wine_fox",
        "05_magical",
        "textures");

    private static BufferedImage decode(String name) throws Exception {
        File file = TEXTURES.resolve(name)
            .toFile();
        assumeTrue(file.isFile(), "sample pack not present");
        return OuterFileTexture.decode(Files.readAllBytes(file.toPath()));
    }

    @Test
    void theTexelClampSamplesIsTransparentAndTheTexelRepeatSamplesIsNot() throws Exception {
        BufferedImage magic = decode("magic.png");

        // u = 1.0, v = 0.0 -> the face's whole uv span. Clamped, that is the last texel of the row.
        int clamped = magic.getRGB(magic.getWidth() - 1, 0) >>> 24;
        assertEquals(0, clamped, "the clamped edge texel must be fully transparent, or the face would be visible");

        // Wrapped, the same coordinate is the first texel instead - opaque, which is the grey slab the owner saw.
        int wrapped = magic.getRGB(0, 0) >>> 24;
        assertEquals(255, wrapped, "the wrapped texel is opaque, which is what produced the grey slab");
    }

    @Test
    void everySkinOfThePackIsTransparentWhereClampSamples() throws Exception {
        // The half the fix depends on, and it holds for all six skins: whatever the player selects, the clamped
        // sample of a zero-uv face is transparent, so those faces are invisible.
        for (String name : new String[] { "magic.png", "winefox.png", "ice.png", "flower.png", "blood.png",
            "water.png" }) {
            BufferedImage image = decode(name);
            assertEquals(0, image.getRGB(image.getWidth() - 1, 0) >>> 24, name + " should be transparent at (last,0)");
        }
    }

    @Test
    void theOpaqueWrappedTexelIsSpecificToTheSkinsThatShowedTheSlab() throws Exception {
        // Measured, not assumed: of the six skins only magic and water are opaque at (0, 0), so the old repeat
        // behaviour could only produce the grey slab for those two - and magic is the pack's first texture, i.e. the
        // default one, which is what the owner's screenshots were showing. The other four were transparent either
        // way, which is why the defect looked skin-dependent.
        assertEquals(255, decode("magic.png").getRGB(0, 0) >>> 24, "magic.png is the default skin and is opaque there");
        assertEquals(255, decode("water.png").getRGB(0, 0) >>> 24, "water.png is opaque there too");
        for (String name : new String[] { "winefox.png", "ice.png", "flower.png", "blood.png" }) {
            assertEquals(
                0,
                decode(name).getRGB(0, 0) >>> 24,
                name + " is transparent at (0,0), so repeat never showed a slab for it");
        }
    }
}
