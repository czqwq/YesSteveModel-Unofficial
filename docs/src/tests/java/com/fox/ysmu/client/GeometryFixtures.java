package com.fox.ysmu.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Geometry payloads for the model-loading tests.
 * <p>
 * A test that needs bytes to build used to read the reference pack under {@code tmp/} and skip itself when the pack
 * was absent - which is every fresh clone and every CI run, so five of the nine model-loading tests executed no
 * assertions there at all, including the ones written to pin the readiness predicates. Skipping is the wrong answer
 * for a property that has already regressed once: the test must run.
 * <p>
 * So the pack is used when it is present, because parsing a real 744 KB model is worth something, and a minimal
 * Bedrock document is used when it is not. Both build; the guards under test do not care which bytes they were handed.
 */
public final class GeometryFixtures {

    /** One bone, one cube: enough for the engine to build a model, small enough to inline and to build in a millisecond. */
    public static final String INLINE_GEOMETRY = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.probe\",\"texture_width\":64,\"texture_height\":64,"
        + "\"visible_bounds_width\":1,\"visible_bounds_height\":1,\"visible_bounds_offset\":[0,0,0]},"
        + "\"bones\":[{\"name\":\"body\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static final File REFERENCE_PACK = new File("tmp/\u827e\u83b2\u00b7\u4e541.4.0/models");

    private GeometryFixtures() {}

    /**
     * A file that is known to build: the reference pack's when it is present, the inline document otherwise. Never
     * skips, so a test that uses it always runs.
     */
    public static byte[] buildableGeometry(String fileName) {
        File file = new File(REFERENCE_PACK, fileName);
        if (file.isFile()) {
            try {
                return Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                // Fall through: the inline document is a valid substitute for anything that has to build.
            }
        }
        return INLINE_GEOMETRY.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the reference pack is present, for a test that genuinely wants the real parser and nothing else. */
    public static boolean referencePackPresent() {
        return new File(REFERENCE_PACK, "main.json").isFile();
    }
}
