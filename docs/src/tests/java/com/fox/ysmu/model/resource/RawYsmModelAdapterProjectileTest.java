package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonObject;

/**
 * A projectile sub-entity ({@code files.projectiles}) must bridge its <b>animation and controller</b> files along
 * with its geometry and texture.
 *
 * <p>Regression: {@code toLegacyModelData} bridged only the geometry and the texture, with a comment saying the rest
 * was "registered separately" - but only the server-sync path did that, so a locally loaded model had no projectile
 * animation at all. The consequence of a missing animation file is not "one animation fewer":
 * {@code ArrowProjectileRenderer} returns as soon as the lookup misses, the geometry stays in its bind pose, and
 * every sub-model the pack hides by scaling a bone (bow, crossbow, effect boxes) is drawn at once.</p>
 */
class RawYsmModelAdapterProjectileTest {

    private static final int PNG_FORMAT = 2;
    private static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";
    private static final String PROJECTILE_ANIMATION = "{\"format_version\":\"1.19.0\",\"animations\":{"
        + "\"air\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":1.0}}},"
        + "\"parallel0\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":0.0}}}}}";
    private static final String PROJECTILE_CONTROLLER = "{\"format_version\":\"1.19.0\",\"animation_controllers\":{"
        + "\"projectile.post_main\":{\"initial_state\":\"default\",\"states\":{"
        + "\"default\":{\"animations\":[\"air\"]}}}}}";

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

    private static RawYsmModel.RawSubEntity projectile(String identifier, String[] matchIds, byte[] animation,
        byte[] controller) {
        RawYsmModel.RawSubEntity sub = new RawYsmModel.RawSubEntity();
        sub.identifier = identifier;
        sub.matchIds = matchIds;
        sub.model = geometry();
        sub.textures.put("arrow.png", pngTexture("arrow.png"));
        if (animation != null) {
            RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
            file.sourceJson = animation;
            file.fileHash = "hash";
            sub.animationFiles.put("arrow.animation", file);
        }
        if (controller != null) {
            RawYsmModel.RawAnimationControllerFile file = new RawYsmModel.RawAnimationControllerFile();
            file.name = "arrow.controller";
            file.sourceJson = controller;
            sub.animationControllerFiles.add(file);
        }
        return sub;
    }

    private static RawYsmModel bridgeableModel() {
        RawYsmModel raw = new RawYsmModel();
        raw.modelId = "_test_projectile_bridge";
        raw.mainEntity.mainModel = geometry();
        raw.mainEntity.armModel = geometry();
        raw.mainEntity.textures.put("texture.png", pngTexture("texture.png"));
        return raw;
    }

    @Test
    void projectileAnimationAndControllerAreBridged() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put(
            "minecraft:arrow",
            projectile(
                "minecraft:arrow",
                new String[] { "minecraft:arrow" },
                PROJECTILE_ANIMATION.getBytes(StandardCharsets.UTF_8),
                PROJECTILE_CONTROLLER.getBytes(StandardCharsets.UTF_8)));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);

        assertTrue(
            data.getModel()
                .containsKey("projectile_minecraft:arrow"),
            data.getModel()
                .keySet()
                .toString());
        assertTrue(
            data.getAnimation()
                .containsKey("projectile_minecraft:arrow"),
            "the projectile animation must be bridged: " + data.getAnimation()
                .keySet());
        assertTrue(
            data.getAnimation()
                .containsKey("projectile_ctrl_minecraft:arrow"),
            "the projectile controller must be bridged: " + data.getAnimation()
                .keySet());
    }

    /** The bridged animation must carry the animations the renderer samples. */
    @Test
    void bridgedAnimationCarriesThePacksAnimations() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put(
            "#arrow",
            projectile("#arrow", null, PROJECTILE_ANIMATION.getBytes(StandardCharsets.UTF_8), null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        String json = new String(data.getAnimation().get("projectile_#arrow"), StandardCharsets.UTF_8);
        JsonObject root = ysmu.GSON.fromJson(json, JsonObject.class);
        JsonObject animations = root.getAsJsonObject("animations");

        assertNotNull(animations, json);
        assertTrue(animations.has("parallel0"), animations.entrySet()
            .toString());
        assertTrue(animations.has("air"), animations.entrySet()
            .toString());
    }

    /** Every id in the match list needs its own entry: one geometry can answer to several entity types. */
    @Test
    void everyMatchIdGetsItsOwnEntry() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put(
            "#arrow",
            projectile(
                "#arrow",
                new String[] { "minecraft:arrow", "minecraft:spectral_arrow" },
                PROJECTILE_ANIMATION.getBytes(StandardCharsets.UTF_8),
                PROJECTILE_CONTROLLER.getBytes(StandardCharsets.UTF_8)));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        for (String id : new String[] { "minecraft:arrow", "minecraft:spectral_arrow" }) {
            assertTrue(
                data.getAnimation()
                    .containsKey("projectile_" + id),
                data.getAnimation()
                    .keySet()
                    .toString());
            assertTrue(
                data.getAnimation()
                    .containsKey("projectile_ctrl_" + id),
                data.getAnimation()
                    .keySet()
                    .toString());
            assertTrue(
                data.getModel()
                    .containsKey("projectile_" + id),
                data.getModel()
                    .keySet()
                    .toString());
        }
    }

    /** A projectile with no animation data must not invent an animation key, and must not throw. */
    @Test
    void projectileWithoutAnimationDataAddsNoAnimationKey() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put("#arrow", projectile("#arrow", null, null, null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        assertTrue(
            data.getModel()
                .containsKey("projectile_#arrow"),
            data.getModel()
                .keySet()
                .toString());
        assertFalse(
            data.getAnimation()
                .containsKey("projectile_#arrow"),
            data.getAnimation()
                .keySet()
                .toString());
        assertFalse(
            data.getAnimation()
                .containsKey("projectile_ctrl_#arrow"),
            data.getAnimation()
                .keySet()
                .toString());
    }

    /** The player's own animations must be untouched by the projectile work. */
    @Test
    void mainEntityAnimationsStillPresent() throws Exception {
        RawYsmModel raw = bridgeableModel();
        RawYsmModel.RawAnimationFile main = new RawYsmModel.RawAnimationFile();
        main.sourceJson = "{\"format_version\":\"1.19.0\",\"animations\":{\"idle\":{\"loop\":true}}}"
            .getBytes(StandardCharsets.UTF_8);
        raw.mainEntity.animationFiles.put("main", main);
        raw.projectiles
            .put("#arrow", projectile("#arrow", null, PROJECTILE_ANIMATION.getBytes(StandardCharsets.UTF_8), null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        assertTrue(
            data.getAnimation()
                .containsKey("main"),
            data.getAnimation()
                .keySet()
                .toString());
        assertTrue(
            data.getAnimation()
                .containsKey("projectile_#arrow"),
            data.getAnimation()
                .keySet()
                .toString());
        // arm / extra fall back to the built-in defaults when the pack declares none, so only the keys and their
        // content are asserted here.
        for (Map.Entry<String, byte[]> entry : data.getAnimation()
            .entrySet()) {
            assertNotNull(entry.getValue(), entry.getKey());
        }
    }

    /**
     * A projectile's animation must not be merged into the player's animation file.
     * <p>
     * Both sets routinely define {@code parallel0}, and with one flat namespace per model whichever is installed
     * second wins - so the player's {@code parallel0} would be replaced by the arrow's, or the reverse.
     */
    @Test
    void projectileAnimationsAreNotMergedIntoThePlayerNamespace() throws Exception {
        RawYsmModel raw = bridgeableModel();
        RawYsmModel.RawAnimationFile main = new RawYsmModel.RawAnimationFile();
        main.sourceJson = ("{\"format_version\":\"1.19.0\",\"animations\":{"
            + "\"parallel0\":{\"animation_length\":1.0,\"loop\":true,\"bones\":{\"b\":{\"scale\":1.0}}}}}")
                .getBytes(StandardCharsets.UTF_8);
        raw.mainEntity.animationFiles.put("main", main);
        raw.projectiles
            .put("#arrow", projectile("#arrow", null, PROJECTILE_ANIMATION.getBytes(StandardCharsets.UTF_8), null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        String playerMain = new String(data.getAnimation().get("main"), StandardCharsets.UTF_8);
        String arrow = new String(data.getAnimation().get("projectile_#arrow"), StandardCharsets.UTF_8);

        // The player's one-second parallel0 and the arrow's zero-scale one are separate payloads.
        assertTrue(playerMain.contains("1.0"), playerMain);
        assertTrue(arrow.contains("parallel0"), arrow);
        assertFalse(
            playerMain.contains("0.0"),
            "the arrow's scale-0 parallel0 must not overwrite the player's: " + playerMain);
    }
}
