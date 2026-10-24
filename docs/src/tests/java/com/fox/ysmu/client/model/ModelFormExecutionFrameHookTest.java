package com.fox.ysmu.client.model;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source-order guard: live client rendering itself cannot run in a unit JVM. Not a runtime rendering test. */
class ModelFormExecutionFrameHookTest {
    @Test
    void formsDrainAfterAnimationAndBeforeClosingTheRealScope() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/fox/ysmu/client/model/CustomPlayerModel.java"));
        int start = source.indexOf("if (animatable instanceof com.fox.ysmu.client.entity.CustomGuiPlayerEntity");
        int bind = source.indexOf("AnimationRegister.setParserValue", start);
        int animate = source.indexOf("super.setLivingAnimations", bind);
        int drain = source.indexOf("ModelFormExecution.drain(preview)", animate);
        int end = source.indexOf("MolangPhysicsRuntime.end()", drain);
        assertTrue(start >= 0 && bind > start && animate > bind && drain > animate && end > drain);
        int bodyDrain = source.indexOf("ModelFormExecution.drain(customPlayer)", end);
        assertTrue(bodyDrain > source.indexOf("super.setLivingAnimations", end));
        assertTrue(source.indexOf("MolangPhysicsRuntime.end()", bodyDrain) > bodyDrain);
    }

    @Test
    void previewRenderingPosesTheFakeQueryPlayerRatherThanTheWorldOwner() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/fox/ysmu/util/RenderUtil.java"));
        // `renderFormPreview` now delegates to the shared preview entry point instead of duplicating a `renderModel`
        // call, so the subject it hands over is asserted at that one implementation: the fake query player, drawn
        // through the preview's own renderer (`owner` is only the roaming identity, never the posed entity).
        int form = source.indexOf("public static boolean renderFormPreview(");
        assertTrue(form >= 0, "renderFormPreview must exist");
        int delegate = source.indexOf("renderEntityInInventory(", form);
        assertTrue(delegate > form, "renderFormPreview must delegate to renderEntityInInventory");

        int shared = source.indexOf("public static boolean renderEntityInInventory(");
        assertTrue(shared >= 0, "the shared preview entry point must exist");
        int model = source.indexOf("renderModel(", shared);
        assertTrue(model > shared, "the shared entry point must call renderModel");
        assertTrue(
            source.indexOf("preview.getQueryPlayer()", model) > model,
            "the shared entry point must pose the preview's fake query player, not the world owner");
        assertTrue(
            source.indexOf("preview.getPreviewRenderer(), preview", model) > model,
            "and must draw through the preview entity's own renderer");
    }
}
