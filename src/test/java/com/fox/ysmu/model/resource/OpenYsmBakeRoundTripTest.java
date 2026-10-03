package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * Guards the payload the OpenYSM 17-channel cache carries.
 * <p>
 * {@code YSMBinarySerializer.writeGeometry} only writes the bones, cubes and faces that the deserializer baked, so a
 * build that does not bake a model's JSON cubes writes a payload whose geometry is empty. The cache is named from the
 * model's sha256 rather than from the payload, so such a payload then stays "valid" and keeps being served: models
 * stay invisible across reloads and restarts while a fresh bake looks perfect. This test bakes the sample pack the
 * same way the server does and requires the round trip to keep every bone and every quad, plus at least one cube.
 * <p>
 * It uses the local sample pack and is skipped when that pack is not present.
 */
class OpenYsmBakeRoundTripTest {

    /** Models that must survive the round trip: three that were invisible, one that always rendered. */
    private static final String[] MODELS = { "01_taisho_maid", "16_tactics", "22_elf", "15_kluonoa", "02_new_year" };

    private static final Path PACK = Paths.get("run", "client", "config", "ysmu", "custom", "wine_fox");

    private static final int OPEN_YSM_SYNC_FORMAT = 32;

    @Test
    void bakedPayloadKeepsEveryBoneAndQuad() throws Exception {
        assumeTrue(Files.isDirectory(PACK), "sample pack not present");

        for (String name : MODELS) {
            Path dir = PACK.resolve(name);
            assumeTrue(Files.isDirectory(dir), "sample model " + name + " not present");

            try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
                RawYsmModel raw = deserializer.deserialize();
                try (rip.ysm.security.YSMByteBuf buffer = YSMBinarySerializer
                    .serialize(raw, OPEN_YSM_SYNC_FORMAT, true)) {
                    byte[] baked = buffer.toArray();
                    try (YSMBinaryDeserializer back = new YSMBinaryDeserializer(baked, OPEN_YSM_SYNC_FORMAT)) {
                        ModelData data = RawYsmModelAdapter.toLegacyModelData(back.deserializeKeepOpen(), name);

                        for (Map.Entry<String, byte[]> part : data.getModel()
                            .entrySet()) {
                            int[] source = sourceCount(dir, raw, part.getKey());
                            int[] roundTrip = count(new String(part.getValue(), StandardCharsets.UTF_8));

                            assertEquals(
                                source[0],
                                roundTrip[0],
                                name + "/" + part.getKey() + " lost bones in the baked payload");
                            assertEquals(
                                source[2],
                                roundTrip[2],
                                name + "/" + part.getKey() + " lost quads in the baked payload");
                            assertTrue(
                                roundTrip[1] > 0,
                                name + "/"
                                    + part.getKey()
                                    + " baked to a payload with no cubes, which renders as an invisible model");
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code render_layers_first} decides whether a model's held item and armor are drawn before the model or after
     * it, because a model whose geometry covers them has to draw them first. It crosses two repositories - the port
     * writes it into the geometry description as {@code ysm_render_layers_first}, the engine's {@code ModelProperties}
     * reads it back - so the round trip is what needs pinning rather than either half. The flag used to be parsed and
     * never consumed, which is exactly the kind of silence a one-sided change produces again.
     */
    @Test
    void theRenderLayersFirstFlagSurvivesTheBake() throws Exception {
        assumeTrue(Files.isDirectory(PACK), "sample pack not present");
        Path dir = PACK.resolve(MODELS[0]);
        assumeTrue(Files.isDirectory(dir), "sample model " + MODELS[0] + " not present");

        assertBakedRenderLayersFirst(dir, true, true);
        assertBakedRenderLayersFirst(dir, false, false);
    }

    private static void assertBakedRenderLayersFirst(Path dir, boolean declared, boolean expected) throws Exception {
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            raw.properties.renderLayersFirst = declared;
            try (rip.ysm.security.YSMByteBuf buffer = YSMBinarySerializer
                .serialize(raw, OPEN_YSM_SYNC_FORMAT, true)) {
                byte[] baked = buffer.toArray();
                try (YSMBinaryDeserializer back = new YSMBinaryDeserializer(baked, OPEN_YSM_SYNC_FORMAT)) {
                    ModelData data = RawYsmModelAdapter.toLegacyModelData(back.deserializeKeepOpen(), MODELS[0]);
                    // Only the main geometry carries the model info block; the arm one is geometry only.
                    byte[] main = data.getModel()
                        .get("main");
                    assertTrue(main != null, "the baked payload has no main geometry");
                    RawGeometryTree tree = RawGeometryTree.parseHierarchy(
                        Converter.fromJsonString(new String(main, StandardCharsets.UTF_8)));
                    assertTrue(tree.properties != null, "the baked main geometry carries no properties block");
                    assertEquals(
                        expected,
                        Boolean.TRUE.equals(tree.properties.getRenderLayersFirst()),
                        "baked payload lost the render_layers_first flag (declared=" + declared + ")");
                }
            }
        }
    }

    /**
     * The source geometry a payload key was bridged from.
     * <p>
     * A player geometry key ({@code main}, {@code arm}) names a file under {@code models/}. A projectile key does
     * not: it is {@code projectile_<matchId>} and the match id is an entity type ({@code minecraft:arrow}), which is
     * not a path and may not even be a legal file name. Its source geometry is the sub-entity the pack declared, so
     * it is looked up in the parsed model rather than on disk - keeping the comparison just as strict, against the
     * geometry that was really the input.
     */
    private static int[] sourceCount(Path dir, RawYsmModel raw, String key) throws Exception {
        if (key.startsWith(com.fox.ysmu.client.ClientModelManager.PROJECTILE_KEY_PREFIX)) {
            String matchId = key.substring(com.fox.ysmu.client.ClientModelManager.PROJECTILE_KEY_PREFIX.length());
            for (RawYsmModel.RawSubEntity sub : raw.projectiles.values()) {
                // Mirrors RawYsmModelAdapter's own rule: a declared match list names the entities, and the parsed
                // identifier is the fallback for a sub-entity that declares none.
                boolean matches = sub.matchIds != null && sub.matchIds.length > 0
                    ? java.util.Arrays.asList(sub.matchIds)
                        .contains(matchId)
                    : matchId.equals(sub.identifier);
                if (matches && sub.model != null && sub.model.sourceJson != null) {
                    return count(new String(sub.model.sourceJson, StandardCharsets.UTF_8));
                }
            }
            return new int[] { 0, 0, 0 };
        }
        Path file = dir.resolve("models")
            .resolve(key + ".json");
        return Files.isRegularFile(file) ? count(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            : new int[] { 0, 0, 0 };
    }

    /** bones / cubes / non-null quads of a geometry JSON, built through the engine. */
    private static int[] count(String json) throws Exception {
        RawGeoModel raw = Converter.fromJsonString(json);
        RawGeometryTree tree = RawGeometryTree.parseHierarchy(raw);
        GeoModel model = GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(tree);
        int[] totals = new int[3];
        for (GeoBone bone : model.topLevelBones) {
            walk(bone, totals);
        }
        return totals;
    }

    private static void walk(GeoBone bone, int[] totals) {
        totals[0]++;
        totals[1] += bone.childCubes.size();
        for (GeoCube cube : bone.childCubes) {
            if (cube.quads != null) {
                for (Object quad : cube.quads) {
                    if (quad != null) {
                        totals[2]++;
                    }
                }
            }
        }
        for (GeoBone child : bone.childBones) {
            walk(child, totals);
        }
    }
}
