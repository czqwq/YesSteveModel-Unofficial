package com.fox.ysmu.client.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.gui.debug.DebugOverlay;

import software.bernie.geckolib3.core.molang.IMolangPhysicsScope;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.processor.AnimationProcessor;

/**
 * The lazy-capture contract of the Molang debug snapshot.
 * <p>
 * <b>This is the port's one deliberate divergence from the reference branch.</b> Its
 * {@code MolangPhysicsRuntime.end()} copies the whole {@code v.*} scope into a new {@code HashMap} on every model
 * pass, i.e. it pays a per-frame allocation on the render hot path in normal play, purely so that a debug overlay and
 * {@code /ysm query} can read the values afterwards
 * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/animation/molang/MolangPhysicsRuntime.java:244-250}).
 * Here the copy happens only while {@link Config#DEBUG_MOLANG_OVERLAY} is on <em>and</em> something demands it - the
 * overlay is open, or a query has asked for a capture - and the config switch defaults off. These tests pin both
 * halves: with the switch off nothing is captured at all, and with it on a capture still only happens on demand.
 */
class MolangDebugCaptureTest {

    private static final String VARIABLE = "v.roaming.debug_probe";

    /** A detached preview scope: no entity, so its {@link MolangPhysicsRuntime} key carries no player. */
    private static final class PreviewScope implements IMolangPhysicsScope {

        @Override
        public EntityLivingBase getMolangEntity() {
            return null;
        }

        @Override
        public ResourceLocation getMolangModelId() {
            return new ResourceLocation("ysmu", "a_model/main");
        }

        @Override
        public ResourceLocation getMolangAnimationId() {
            return new ResourceLocation("ysmu", "a_model/main");
        }
    }

    /** The scope's processor is never asked for anything here; it only has to be non-null for {@code begin}. */
    private static final class StubAnimatable implements software.bernie.geckolib3.core.IAnimatable {

        @Override
        public void registerControllers(software.bernie.geckolib3.core.manager.AnimationData data) {}

        @Override
        public software.bernie.geckolib3.core.manager.AnimationFactory getFactory() {
            return new software.bernie.geckolib3.core.manager.AnimationFactory(this);
        }
    }

    /**
     * {@code DebugOverlay.toggle()} 打开 overlay 时会刷新快照，而刷新要解析本地玩家；本 JVM 的 runtime
     * classpath 没有 lwjgl，{@code net.minecraft.client.Minecraft} 在那里连链接都做不到，第一次触碰抛
     * {@code NoClassDefFoundError: org/lwjgl/LWJGLException}（链路见
     * {@code .agent/phase37-unit-jvm-test-repair.md}），而不是返回调用点已经处理好的 {@code null}。
     * <p>
     * 装进取数点的是这个 JVM 的<b>事实</b>——没有客户端——不是伪造的客户端；生产代码从不设置它。这样
     * {@code getAllVariables()} 走的仍是它本来就写好的"没有本地玩家"分支。
     */
    @BeforeEach
    void detachFromTheLiveClient() {
        MolangDebugSnapshot.localPlayerSource = () -> null;
    }

    @AfterEach
    void restoreState() {
        if (DebugOverlay.isActive()) {
            DebugOverlay.toggle();
        }
        MolangDebugSnapshot.releaseCapture();
        Config.DEBUG_MOLANG_OVERLAY = false;
        MolangDebugSnapshot.refreshCaptureDemand();
        MolangPhysicsRuntime.end();
        MolangPhysicsRuntime.clear();
        MolangDebugSnapshot.localPlayerSource = null;
    }

    @Test
    void theFeatureIsOffByDefault() {
        assertFalse(Config.DEBUG_MOLANG_OVERLAY, "a default install must not pay for the debug overlay");
    }

    @Test
    void withTheSwitchOffNothingIsCaptured() {
        Config.DEBUG_MOLANG_OVERLAY = false;
        // Even a caller that asks for a capture, and an overlay toggle, must not turn the engine's copy on.
        MolangDebugSnapshot.requestCapture();
        DebugOverlay.toggle();
        MolangDebugSnapshot.refreshCaptureDemand();

        assertTrue(MolangDebugSnapshot.isCaptureDemanded(), "the demand exists...");
        assertFalse(
            MolangPhysicsRuntime.isSnapshotCaptureEnabled(),
            "...but the switch is off, so the engine must not copy the scope");
        assertFalse(DebugOverlay.isActive(), "the overlay must refuse to open while the switch is off");

        MolangPhysicsRuntime.begin(new PreviewScope(), 0.0D, new AnimationProcessor<StubAnimatable>(null));
        MolangPhysicsRuntime.setVariable(VARIABLE, 7.0D);
        MolangPhysicsRuntime.end();

        assertTrue(
            MolangPhysicsRuntime.getLastFrameVariables()
                .isEmpty(),
            "no snapshot may be taken while the switch is off");
        assertNull(MolangPhysicsRuntime.getLastFrameVariable(VARIABLE));
    }

    @Test
    void withTheSwitchOnButNothingDemandingItNoCaptureIsTaken() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        MolangDebugSnapshot.refreshCaptureDemand();

        assertFalse(
            MolangPhysicsRuntime.isSnapshotCaptureEnabled(),
            "turning the switch on is not by itself a reason to allocate a copy every frame");

        captureFrame();

        assertTrue(MolangPhysicsRuntime.getLastFrameVariables().isEmpty());
    }

    @Test
    void anOpenOverlayCapturesAndClosingItStopsTheCapture() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        DebugOverlay.toggle();

        assertTrue(DebugOverlay.isActive());
        assertTrue(MolangPhysicsRuntime.isSnapshotCaptureEnabled(), "an open overlay is what the capture is for");

        captureFrame();

        Double captured = MolangPhysicsRuntime.getLastFrameVariable(VARIABLE);
        assertNotNull(
            captured,
            "the frame's scope must be readable after the frame ended - this is the accessor /ysm query and the "
                + "overlay read");
        assertEquals(7.0D, captured.doubleValue());
        assertTrue(
            MolangPhysicsRuntime.getLastFrameVariables()
                .containsKey(VARIABLE));

        DebugOverlay.toggle();

        assertFalse(DebugOverlay.isActive());
        assertFalse(MolangPhysicsRuntime.isSnapshotCaptureEnabled(), "closing the overlay must stop the per-frame copy");
    }

    @Test
    void aQueryRequestCapturesForItsWindowAndReleaseStopsIt() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        assertFalse(MolangPhysicsRuntime.isSnapshotCaptureEnabled());

        MolangDebugSnapshot.requestCapture();

        assertTrue(MolangDebugSnapshot.isQueryCaptureRequested());
        assertTrue(MolangDebugSnapshot.isCaptureDemanded());
        assertTrue(MolangPhysicsRuntime.isSnapshotCaptureEnabled());

        captureFrame();
        Number queried = MolangPhysicsRuntime.getLastFrameVariable(VARIABLE);
        assertNotNull(queried);
        assertEquals(7.0D, queried.doubleValue());

        MolangDebugSnapshot.releaseCapture();

        assertFalse(MolangDebugSnapshot.isQueryCaptureRequested());
        assertFalse(MolangDebugSnapshot.isCaptureDemanded());
        assertFalse(MolangPhysicsRuntime.isSnapshotCaptureEnabled());
    }

    @Test
    void theSwitchOffBeatsAQueryRequest() {
        Config.DEBUG_MOLANG_OVERLAY = false;
        MolangDebugSnapshot.requestCapture();

        assertTrue(MolangDebugSnapshot.isCaptureDemanded());
        assertFalse(MolangPhysicsRuntime.isSnapshotCaptureEnabled());

        captureFrame();

        assertTrue(MolangPhysicsRuntime.getLastFrameVariables().isEmpty());
    }

    @Test
    void clearLastFrameSnapshotEmptiesTheCapturedScope() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        MolangDebugSnapshot.requestCapture();
        captureFrame();
        Number captured = MolangPhysicsRuntime.getLastFrameVariable(VARIABLE);
        assertNotNull(captured);
        assertEquals(7.0D, captured.doubleValue());

        MolangPhysicsRuntime.clearLastFrameSnapshot();

        assertTrue(MolangPhysicsRuntime.getLastFrameVariables().isEmpty());
        assertNull(MolangPhysicsRuntime.getLastFrameVariable(VARIABLE));
    }

    /**
     * {@code clearPlayer} drops one player's {@code ScopeState}s, and the filter is what matters here: a scope carries
     * a player only when it was opened for an entity, which a unit JVM cannot build, so the reachable half is pinned
     * in two parts. A null id is a no-op, and a detached preview scope (no player on its key) is not swept up by
     * another player's reset - both are read back through a <em>new</em> frame, so the assertion really observes the
     * {@code STATES} map and not the state object the open frame still holds.
     * <p>
     * The pair {@code MolangReset.resetSelf} actually performs - {@code clearPlayer} then
     * {@code clearLastFrameSnapshot} - is what empties the overlay's data source; the second half is pinned by
     * {@link #clearLastFrameSnapshotEmptiesTheCapturedScope()}.
     */
    @Test
    void clearPlayerKeepsTheGuardAndLeavesAScopeWithNoPlayerAlone() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        MolangDebugSnapshot.requestCapture();
        captureFrame();

        MolangPhysicsRuntime.clearPlayer(null);
        MolangPhysicsRuntime.begin(new PreviewScope(), 1.0D, new AnimationProcessor<StubAnimatable>(null));
        assertEquals(7.0D, MolangPhysicsRuntime.getVariable(VARIABLE, -1.0D), "a null id clears nothing");
        MolangPhysicsRuntime.end();

        MolangPhysicsRuntime.clearPlayer(UUID.fromString("12345678-1234-5678-1234-567812345678"));
        MolangPhysicsRuntime.begin(new PreviewScope(), 2.0D, new AnimationProcessor<StubAnimatable>(null));
        assertEquals(
            7.0D,
            MolangPhysicsRuntime.getVariable(VARIABLE, -1.0D),
            "the scope's key carries no player, so another player's reset must leave it alone");
        MolangPhysicsRuntime.end();

        MolangPhysicsRuntime.clearLastFrameSnapshot();
        assertTrue(MolangPhysicsRuntime.getLastFrameVariables().isEmpty());
    }

    @Test
    void expandedQueryAliasIsSharedWithTheCommandAndTheFilterBox() {
        // expandSearchAlias is what lets both /ysm query and the overlay's filter box accept q.* for query.*.
        assertEquals("query.any_animation_finished", MolangDebugSnapshot.expandSearchAlias("q.any_animation_finished"));
        assertEquals("query.health", MolangDebugSnapshot.expandSearchAlias("Q.health"));
        assertEquals("ysm.foo", MolangDebugSnapshot.expandSearchAlias("ysm.foo"));
        assertEquals("q", MolangDebugSnapshot.expandSearchAlias("q"));
        assertNull(MolangDebugSnapshot.expandSearchAlias(null));
    }

    /** One captured frame with a value in its scope. */
    private static void captureFrame() {
        MolangPhysicsRuntime.begin(new PreviewScope(), 0.0D, new AnimationProcessor<StubAnimatable>(null));
        MolangPhysicsRuntime.setVariable(VARIABLE, 7.0D);
        MolangPhysicsRuntime.end();
    }
}
