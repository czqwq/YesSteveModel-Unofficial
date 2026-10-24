package com.fox.ysmu.client.gui.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.debug.MolangDebugSnapshot;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * The debug overlay's pure state machine (open/close, filter, search/reset), reached without GL.
 * <p>
 * {@link DebugOverlay#render} needs a live client and is not exercised here; what is pinned is the state the renderer
 * reads, through the package-private observation accessors, plus the switch that keeps the whole overlay off by
 * default ({@link Config#DEBUG_MOLANG_OVERLAY}) and the capture demand it feeds back to the engine.
 * <p>
 * The absolute row count is never asserted: {@code MolangParser.VARIABLES} is a JVM-wide static table that other test
 * classes also register into, so only relative effects are meaningful.
 */
class DebugOverlayStateTest {

    /** A name only this test registers, so the filter's row count is unambiguous. */
    private static final String FILTER_PROBE = "query.debug_alias_probe";

    /**
     * {@code DebugOverlay.refreshSnapshot()} 的默认路径要解析本地玩家，而本 JVM 的 runtime classpath 没有
     * lwjgl：{@code net.minecraft.client.Minecraft} 在那里连链接都做不到，第一次触碰抛
     * {@code NoClassDefFoundError: org/lwjgl/LWJGLException}（链路见
     * {@code .agent/phase37-unit-jvm-test-repair.md}），而不是返回调用点已经处理好的 {@code null}。
     * <p>
     * 这里装进取数点的不是"伪造的客户端"，而是这个 JVM 的<b>事实</b>：没有客户端。overlay 的刷新因此走
     * {@code getAllVariables(null)} 那条本来就存在的分支，读到的仍是 <b>真实</b>的
     * {@code MolangParser.VARIABLES} 表（{@link #FILTER_PROBE} 就注册在那里），而不是一份测试自造的变量表。
     */
    @BeforeEach
    void detachFromTheLiveClient() {
        DebugOverlay.snapshotPlayer = () -> null;
    }

    @AfterEach
    void restoreState() {
        if (DebugOverlay.isActive()) {
            DebugOverlay.toggle();
        }
        DebugOverlay.snapshotPlayer = null;
        Config.DEBUG_MOLANG_OVERLAY = false;
        MolangDebugSnapshot.refreshCaptureDemand();
        MolangParser.VARIABLES.remove(FILTER_PROBE);
        MolangPhysicsRuntime.clear();
    }

    @Test
    void theOverlayRefusesToOpenWhileTheSwitchIsOff() {
        Config.DEBUG_MOLANG_OVERLAY = false;

        DebugOverlay.toggle();

        assertFalse(DebugOverlay.isActive(), "the overlay is behind Config.DEBUG_MOLANG_OVERLAY, which defaults off");
        assertFalse(DebugOverlay.isSearching());
        assertFalse(
            MolangPhysicsRuntime.isSnapshotCaptureEnabled(),
            "a refused toggle must not ask the engine for a per-frame snapshot either");
    }

    @Test
    void toggleOpensResetsTheViewAndClosesAgain() {
        MolangParser.VARIABLES.put(FILTER_PROBE, new LazyVariable(FILTER_PROBE, 1.0D));
        Config.DEBUG_MOLANG_OVERLAY = true;
        DebugOverlay.setFilter("q.debug_alias_probe");
        assertEquals("q.debug_alias_probe", DebugOverlay.filterText());
        assertEquals(1, DebugOverlay.visibleEntryCount(), "the filter is in effect before the toggle");

        DebugOverlay.toggle();

        assertTrue(DebugOverlay.isActive());
        assertEquals("", DebugOverlay.filterText(), "opening clears the filter");
        assertEquals(0, DebugOverlay.scrollOffset(), "opening resets the scroll position");
        assertFalse(DebugOverlay.isSearching());
        assertTrue(
            DebugOverlay.visibleEntryCount() >= 1,
            "clearing the filter puts the whole snapshot back, so the probe is visible again");

        DebugOverlay.toggle();

        assertFalse(DebugOverlay.isActive());
    }

    @Test
    void filterEditsResetTheScrollOffsetAndBackspaceIsSafeAtTheStart() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        DebugOverlay.toggle();

        DebugOverlay.appendFilter('a');
        DebugOverlay.appendFilter('b');
        assertEquals("ab", DebugOverlay.filterText());

        DebugOverlay.setFilter("xyz");
        assertEquals("xyz", DebugOverlay.filterText());
        assertEquals(0, DebugOverlay.scrollOffset());

        DebugOverlay.backspaceFilter();
        assertEquals("xy", DebugOverlay.filterText());
        DebugOverlay.backspaceFilter();
        DebugOverlay.backspaceFilter();
        assertEquals("", DebugOverlay.filterText());

        // One keystroke too many must not throw or wrap around.
        DebugOverlay.backspaceFilter();
        assertEquals("", DebugOverlay.filterText());
    }

    @Test
    void keyInputIsIgnoredWhileTheOverlayIsClosed() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        assertFalse(DebugOverlay.isActive());

        // handleKeyInput returns before it touches LWJGL when the overlay is closed, which is what makes this
        // callable in a unit JVM at all.
        assertFalse(DebugOverlay.handleKeyInput());
    }

    @Test
    void turningTheSwitchOffClosesAnOpenOverlayAndStopsTheCapture() {
        Config.DEBUG_MOLANG_OVERLAY = true;
        DebugOverlay.toggle();
        assertTrue(DebugOverlay.isActive());
        assertTrue(MolangPhysicsRuntime.isSnapshotCaptureEnabled());

        Config.DEBUG_MOLANG_OVERLAY = false;
        DebugOverlay.toggle();

        assertFalse(DebugOverlay.isActive(), "a toggle still closes an overlay that is already open");
        assertFalse(MolangPhysicsRuntime.isSnapshotCaptureEnabled());
    }

    @Test
    void theFilterBoxAcceptsTheQueryAliasTheCommandAccepts() {
        // The filter matches on the expanded name, so "q.<x>" must find "query.<x>" - without the expansion the
        // literal substring "q.<x>" is not contained in "query.<x>" and the box would come up empty.
        MolangParser.VARIABLES.put(FILTER_PROBE, new LazyVariable(FILTER_PROBE, 1.0D));
        Config.DEBUG_MOLANG_OVERLAY = true;
        DebugOverlay.toggle();

        DebugOverlay.setFilter("q.debug_alias_probe");
        assertEquals(1, DebugOverlay.visibleEntryCount(), "q. must expand to query. before matching");

        DebugOverlay.setFilter("q.debug_alias_pro");
        assertEquals(1, DebugOverlay.visibleEntryCount());

        DebugOverlay.setFilter("q.no_such_probe");
        assertEquals(0, DebugOverlay.visibleEntryCount());
    }
}
