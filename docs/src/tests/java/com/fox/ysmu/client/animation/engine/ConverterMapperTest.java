package com.fox.ysmu.client.animation.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.GeometryFixtures;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;

/**
 * The engine's geometry parser keeps its Jackson reader and writer in a holder class, so the JVM initialises them
 * once and publishes them safely.
 * <p>
 * They used to be two lazily assigned non-volatile statics with a check-then-act, which was fine while the only
 * caller was single-threaded and stopped being fine when geometry parsing moved onto a pool. The reason this test
 * exists rather than relying on the parse tests is coverage of the <em>writer</em>: nothing in either repository calls
 * {@code toJsonString}, so without this a mistake in the holder - forgetting to expose the writer, or building it
 * from the wrong mapper - would be invisible until someone first needed it.
 */
class ConverterMapperTest {

    /**
     * The fallback that makes the model-loading tests run on a fresh clone and in CI is asserted here rather than
     * assumed: if the inline document did not parse into a supported layout, those tests would fail for a reason that
     * has nothing to do with what they test, and the temptation would be to put the skip back.
     */
    @Test
    void theInlineFallbackGeometryIsAUsableDocument() throws Exception {
        RawGeoModel parsed = Converter.fromJsonString(GeometryFixtures.INLINE_GEOMETRY);
        assertNotNull(parsed, "the inline document must parse");
        assertNotNull(parsed.getFormatVersion(), "and declare a format version, or the engine refuses the layout");
    }

    @Test
    void theSharedReaderAndWriterBothWork() throws Exception {
        // A geometry document that is always available, because this test pins both fields of the engine's mapper
        // holder and the write path has no production caller to exercise it later.
        String json = new String(GeometryFixtures.buildableGeometry("main.json"), StandardCharsets.UTF_8);

        RawGeoModel parsed = Converter.fromJsonString(json);
        assertNotNull(parsed, "the shared reader must parse a real geometry document");

        String serialised = Converter.toJsonString(parsed);
        assertTrue(
            serialised != null && !serialised.isEmpty(),
            "the shared writer must serialise a parsed document; nothing else in either repository calls it");

        RawGeoModel reparsed = Converter.fromJsonString(serialised);
        assertNotNull(reparsed, "the shared writer's own output must parse again through the shared reader");
    }
}
