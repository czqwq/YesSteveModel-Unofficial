package com.fox.ysmu.client.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.molang.EntityQueryScope;
import com.fox.ysmu.client.event.EntityQueryFrameEvents;

import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Source guard: the two halves of the entity-scoped publication are wired where they have to be - plus the one piece of
 * that wiring a unit JVM can execute for real.
 *
 * <p><b>The source guards are not a runtime test.</b> The call sites they guard cannot run here - they need a live
 * client, and two of them need an {@code EntityPlayer}, which 1.7.10 cannot construct without a {@code World}
 * ({@code EntityQueryBindingsTest}, {@code YsmCommandTest} record the limit). What is checkable here is the structure
 * that makes the fix real, the same way {@code ModelFormExecutionFrameHookTest} guards a render-order promise it also
 * cannot execute:</p>
 * <ol>
 * <li>every read in {@link MolangDebugSnapshot} that can reach the shared {@code MolangParser.VARIABLES} table opens
 * its entity-scoped publication <em>before</em> the read;</li>
 * <li>the {@code @sync} handler publishes the entity the script belongs to before it runs the script;</li>
 * <li>the render-tick pair opens the frame on START and closes it on END (the structural half - without it a reader
 * nobody has patched falls back to the registered default instead of another entity's value).</li>
 * </ol>
 *
 * <p>The third item is also driven for real below: {@code TickEvent.RenderTickEvent} has a public constructor
 * ({@code cpw/mods/fml/common/gameevent/TickEvent.java:59-66}), so the handler can be called without a client, and the
 * frame it opens is the same static one the client's render thread uses.</p>
 */
class MolangEntityScopeWiringTest {

    private static final Path SNAPSHOT = Path.of("src/main/java/com/fox/ysmu/client/debug/MolangDebugSnapshot.java");
    private static final Path SYNC =
        Path.of("src/main/java/com/fox/ysmu/network/message/S2CMolangSync.java");
    private static final Path FRAME_EVENTS =
        Path.of("src/main/java/com/fox/ysmu/client/event/EntityQueryFrameEvents.java");

    private static String source(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path), path + " must exist");
        return Files.readString(path);
    }

    /**
     * One method's slice: from its declaration to the declaration of the method after it. Explicit end markers rather
     * than brace counting, so a nested lambda inside the guarded method cannot truncate the slice.
     */
    private static String method(String source, String declaration, String nextDeclaration) {
        int start = source.indexOf(declaration);
        int end = source.indexOf(nextDeclaration);
        assertTrue(start >= 0, declaration + " must exist");
        assertTrue(end > start, nextDeclaration + " must follow it");
        return source.substring(start, end);
    }

    private static void assertScoped(String slice, String methodName, String firstTableRead) {
        int publication = slice.indexOf("publishForRead(");
        int read = slice.indexOf(firstTableRead);
        assertTrue(publication >= 0, methodName + " must publish an entity for the duration of its read");
        assertTrue(read >= 0, methodName + " must still read the table directly (the guard would be vacuous)");
        assertTrue(
            publication < read,
            methodName + " must open the publication before it reads " + firstTableRead);
        assertTrue(
            slice.contains("EntityQueryScope.Publication"),
            methodName + " must scope the publication with try-with-resources, not leave it open");
    }

    @Test
    void everyReadThatCanSeeTheSharedTablePublishesItsEntityFirst() throws Exception {
        String snapshot = source(SNAPSHOT);

        assertScoped(
            method(
                snapshot,
                "public static double queryVariable(String name) {",
                "private static EntityQueryScope.Publication publishForRead(EntityPlayer player) {"),
            "queryVariable",
            "MolangParser.VARIABLES.get(name)");
        assertScoped(
            method(
                snapshot,
                "public static Map<String, Double> queryWildcard(String pattern) {",
                "private static void addAnimationFinishedEntries(Map<String, Double> result) {"),
            "queryWildcard",
            "MolangParser.VARIABLES.entrySet()");
        assertScoped(
            method(
                snapshot,
                "public static Map<String, Double> getAllVariables(EntityPlayer player) {",
                "public static void printToChat(Map<String, Double> values) {"),
            "getAllVariables",
            "MolangParser.VARIABLES.entrySet()");
        assertScoped(
            method(
                snapshot,
                "public static Map<String, Double> resolveVariables(String pattern) {",
                "private static void addDynamicCtrlStates(Map<String, Double> result) {"),
            "resolveVariables",
            "MolangParser.VARIABLES.entrySet()");
        assertScoped(
            method(
                snapshot,
                "public static void evalToChat(String expression) {",
                "private static String sanitize(String text) {"),
            "evalToChat",
            "parser.parseExpression(expression)");

        // The helper itself: a read with no player (a unit JVM, a client not in a world) is a no-op publication, which
        // is the branch every one of those call sites already had.
        String helper = method(
            snapshot,
            "private static EntityQueryScope.Publication publishForRead(EntityPlayer player) {",
            "public static Map<String, Double> queryVariables(List<String> names) {");
        assertTrue(helper.contains("EntityQueryScope.NOTHING"), "a null player must publish nothing");
    }

    @Test
    void theSyncScriptRunsInsideItsOwnEntitysPublication() throws Exception {
        String sync = source(SYNC);

        int publication = sync.indexOf("publishForRead(");
        int hook = sync.indexOf("PackUserFunctions.fireHook(\"sync\"");
        assertTrue(publication >= 0, "the @sync read must publish the entity it belongs to");
        assertTrue(hook > publication, "and it must be published before the script runs");
        assertTrue(
            sync.contains("EntityQueryScope.Publication"),
            "the publication must be closed by the try-with-resources it is opened in");
    }

    @Test
    void theRenderTickOpensAndClosesTheFrame() throws Exception {
        String events = source(FRAME_EVENTS);

        assertTrue(
            events.contains("@EventBusSubscriber(side = Side.CLIENT)"),
            "the frame boundary is a client-only render-tick subscriber");
        int start = events.indexOf("TickEvent.Phase.START");
        int begin = events.indexOf("EntityQueryScope.beginFrame()");
        int end = events.indexOf("EntityQueryScope.endFrame()");
        assertTrue(start >= 0, "the handler must branch on the render tick's phase");
        assertTrue(begin > start, "START opens the frame");
        assertTrue(end > begin, "END closes it");
    }

    /**
     * The third item, executed instead of merely read: the render tick's START opens the frame and its END closes it,
     * and an unbalanced END stays a no-op. This is the backstop that makes an unpatched off-frame reader answer the
     * registered default rather than the last drawn entity's value.
     */
    @Test
    void theRenderTickHandlerOpensAndClosesTheFrameForReal() {
        EntityQueryScope.endFrame();
        assertFalse(EntityQueryScope.isFrameOpen());

        EntityQueryFrameEvents.onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START, 0.0F));
        assertTrue(EntityQueryScope.isFrameOpen(), "START opens the frame");

        EntityQueryFrameEvents.onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.END, 0.0F));
        assertFalse(EntityQueryScope.isFrameOpen(), "END closes it");

        EntityQueryFrameEvents.onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.END, 0.0F));
        assertFalse(EntityQueryScope.isFrameOpen(), "a second END changes nothing");
    }
}
