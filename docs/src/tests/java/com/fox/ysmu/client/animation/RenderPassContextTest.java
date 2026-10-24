package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.entity.CustomPlayerEntity;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.geo.RenderContext;

/**
 * Track G's host half of the render-context port: the pass a frame belongs to is <em>attached</em> by the entry point
 * that draws it, and the two pack-facing queries read it back instead of inferring it.
 *
 * <p><b>What is pinned behaviourally</b> - {@link FrameRenderQueries#renderingInInventory} and
 * {@link FrameRenderQueries#renderingInPaperdoll} are pure projections of
 * {@link AnimationEvent#getRenderContext()} ({@code YSMBinding.java:99-100} in upstream reads exactly those two
 * flags). The old implementation derived them from {@code CustomPlayerRenderer.isPreviewRendering()} plus
 * {@code Minecraft.currentScreen}, so it could not be asserted at all in a test JVM; the new one can.
 *
 * <p><b>What is pinned as a source-order guard, and why it cannot be a runtime assertion.</b> The wiring itself - which
 * entry point attaches which pass, and which animatable declares immutability - lives behind
 * {@code RenderUtil#renderModel}, {@code CustomPlayerRenderer#buildAnimationEvent} and
 * {@code ClientEventHandler#renderSelfGuiPlayer}, and all three reach GL or {@code Minecraft.getMinecraft()} before
 * they would answer anything. A test JVM has no LWJGL, so {@code net.minecraft.client.Minecraft} cannot even be linked
 * from here - the same boundary {@code ImmutableFramePoseTest:29-35} and {@code ParallelAnimationExistenceGuardTest:54}
 * record. {@code CustomGuiPlayerEntity} is in the same category for a second reason: its own constructor builds a
 * {@code GeoReplacedEntityRenderer}, which reads {@code RenderManager.instance}
 * ({@code GeoReplacedEntityRenderer:62}).
 */
class RenderPassContextTest {

    /** The two queries, as a pair, for one attached pass. */
    private static void assertPass(RenderContext context, double inventory, double paperdoll) {
        AnimationEvent<CustomPlayerEntity> event = event();
        event.setRenderContext(context);
        assertEquals(
            inventory,
            FrameRenderQueries.renderingInInventory(event),
            0.0D,
            "ysm.rendering_in_inventory for " + context);
        assertEquals(
            paperdoll,
            FrameRenderQueries.renderingInPaperdoll(event),
            0.0D,
            "ysm.rendering_in_paperdoll for " + context);
    }

    @Test
    void eachPassAnswersExactlyItsOwnFlag() {
        // A model-selection / texture-screen tile and the settings panel's form preview (RenderUtil's tile entry plus
        // the event its renderModel builds).
        assertPass(RenderContext.inventoryPass(), 1.0D, 0.0D);
        // The HUD extra-player overlay (ClientEventHandler#renderSelfGuiPlayer with no screen open).
        assertPass(RenderContext.paperDollPass(), 0.0D, 1.0D);
        // The first-person arm's own frame; upstream's firstPersonMod, and neither GUI flag.
        assertPass(RenderContext.firstPersonPass(), 0.0D, 0.0D);
        // The world pass, and every event the engine builds on a path no preview bracket covers.
        assertPass(RenderContext.NONE, 0.0D, 0.0D);
        // A caller-declared immutability must not change what pass this is - the two are independent bits.
        assertPass(RenderContext.inventoryPass().withImmutable(true), 1.0D, 0.0D);
    }

    @Test
    void anEventNobodyAttachedAPassToAnswersNeitherFlag() {
        // The default of AnimationEvent is RenderContext.NONE, so an engine-built event (AnimatedGeoModel:69,
        // AnimationController:1084, GeoEntityRenderer:107) keeps the answer the world pass had before the context
        // existed: neither flag. This is what keeps the change additive.
        AnimationEvent<CustomPlayerEntity> event = event();
        assertEquals(0.0D, FrameRenderQueries.renderingInInventory(event), 0.0D);
        assertEquals(0.0D, FrameRenderQueries.renderingInPaperdoll(event), 0.0D);
        // A null event is the "no frame" answer, not a crash.
        assertEquals(0.0D, FrameRenderQueries.renderingInInventory(null), 0.0D);
        assertEquals(0.0D, FrameRenderQueries.renderingInPaperdoll(null), 0.0D);
    }

    @Test
    void onlyTheGuiPreviewDeclaresItselfImmutable() throws IOException {
        // Upstream client/gui/CustomGuiPlayerEntity.java:43-46. The catalog tiles and the settings panel's form
        // preview draw this class, which is what makes them present one pose per frame (AnimatableEntity:341-389).
        String preview = source("com/fox/ysmu/client/entity/CustomGuiPlayerEntity.java");
        int declaration = preview.indexOf("public boolean determineImmutableContext(");
        assertTrue(declaration > 0, "the GUI preview must declare determineImmutableContext");
        assertTrue(
            preview.indexOf("return true;", declaration) > declaration,
            "the GUI preview must answer true, the way upstream's does");

        // The world animatable must stay mutable: upstream's own default answers false for the GUI passes, and
        // CustomPlayerEntity is what the world, the vehicles and the HUD doll draw with.
        String world = source("com/fox/ysmu/client/entity/CustomPlayerEntity.java");
        assertTrue(
            world.indexOf("determineImmutableContext") < 0,
            "CustomPlayerEntity must keep IAnimatable's default (false), not declare immutability");
    }

    @Test
    void everyRenderPathAttachesAPassToTheEventItHandsTheEngine() throws IOException {
        // The host builds this event itself (RenderUtil#renderModel), so it attaches the bracket's pass by hand.
        String renderUtil = source("com/fox/ysmu/util/RenderUtil.java");
        assertTrue(
            renderUtil.indexOf("predicate.setRenderContext(CustomPlayerRenderer.currentPreviewContext());") > 0,
            "the GUI tile / form preview event must carry the pass its bracket declared");
        assertTrue(
            renderUtil.indexOf("CustomPlayerRenderer.beginPreviewRender(RenderContext.inventoryPass());") > 0,
            "both of those entry points are the inventory pass");

        // The world pass, the HUD doll and the first-person arm take the event the engine builds, so the renderer's
        // override is the only place that can attach theirs.
        String renderer = source("com/fox/ysmu/client/renderer/CustomPlayerRenderer.java");
        assertTrue(
            renderer.indexOf("event.setRenderContext(renderContextFor(eventAnimatable));") > 0,
            "CustomPlayerRenderer#buildAnimationEvent must attach the pass to the events the engine builds");
        assertTrue(
            renderer.indexOf("return RenderContext.firstPersonPass();") > 0,
            "the first-person arm's own animatable is the first-person pass");

        // The single HUD/world-with-a-screen entry point: the screen decides, which is the rule the queries used to
        // infer. It is also exactly where both residuals of FrameRenderQueries' class comment come from.
        String handler = source("com/fox/ysmu/client/ClientEventHandler.java");
        assertTrue(
            handler.indexOf(
                "Minecraft.getMinecraft().currentScreen != null ? RenderContext.inventoryPass()") > 0,
            "a screen open means the inventory pass");
        assertTrue(handler.indexOf(": RenderContext.paperDollPass());") > 0, "no screen means the paper doll");
    }

    /**
     * The cache-isolation invariant this port actually has: {@code AnimatableCacheUtil.ANIMATABLE_CACHE} holds the
     * first-person arm and nothing else - no tile, no preview.
     *
     * <p>It used to hold both, keyed by model id for the tiles and by the model's arm id for the arm. After the
     * catalog moved to per-tile {@code CustomGuiPlayerEntity} instances (upstream {@code PlayerModelScreen.java:56})
     * the only reader left is {@code FirstPersonHandRenderer}, and no screen invalidates it any more. Pinning it here
     * keeps a later change from quietly reintroducing a second population into that cache, where one screen's draw
     * order would decide what another path draws with.
     */
    @Test
    void theAnimatableCacheIsTheArmCacheOnly() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> readers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString()
                .endsWith(".java"))
                .collect(Collectors.toList())) {
                if (Files.readString(file)
                    .contains("ANIMATABLE_CACHE.get(")) {
                    readers.add(
                        file.getFileName()
                            .toString());
                }
            }
        }
        assertEquals(
            List.of("FirstPersonHandRenderer.java"),
            readers,
            "ANIMATABLE_CACHE must be read only by the first-person arm; a tile or preview sharing it is the defect "
                + "this cache split exists to prevent");
    }

    private static AnimationEvent<CustomPlayerEntity> event() {
        return new AnimationEvent<>(new CustomPlayerEntity(), 0.0F, 0.0F, 0.0F, false, new ArrayList<>());
    }

    private static String source(String relativeToMainJava) throws IOException {
        return Files.readString(Path.of("src/main/java", relativeToMainJava));
    }
}
