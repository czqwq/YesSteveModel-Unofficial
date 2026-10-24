package com.fox.ysmu.client.audio;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SoundInstanceRegistryTest {
    static final class Fake implements SoundInstanceRegistry.Instance {
        boolean stopped;
        @Override public boolean isStopped() { return stopped; }
        @Override public void stop() { stopped = true; }
    }

    @Test void idsAreIndependentEvenForOneAssetAndReplacementIsExplicit() {
        SoundInstanceRegistry manager = new SoundInstanceRegistry();
        Fake a = new Fake(), b = new Fake(), replacement = new Fake();
        assertTrue(manager.play(-1, false, () -> a));
        assertTrue(manager.play(-2, false, () -> b));
        assertFalse(manager.play(-1, false, () -> { fail("busy id must not create another source"); return null; }));
        assertTrue(manager.play(-1, true, () -> replacement));
        assertTrue(a.stopped);
        assertFalse(b.stopped);
        assertTrue(manager.stop(-1));
        assertTrue(replacement.stopped);
        assertFalse(b.stopped);
    }

    @Test void zeroIsAnonymousAndOnlyStopAllStopsIt() {
        SoundInstanceRegistry manager = new SoundInstanceRegistry();
        Fake a = new Fake(), b = new Fake();
        assertTrue(manager.play(0, false, () -> a));
        assertTrue(manager.play(0, false, () -> b));
        assertFalse(manager.stop(0));
        manager.stopAll();
        assertTrue(a.stopped && b.stopped);
    }

    @Test void failedReplacementDoesNotDiscardOldAndFinishedSourceCanReplay() {
        SoundInstanceRegistry manager = new SoundInstanceRegistry();
        Fake a = new Fake(), b = new Fake();
        manager.play(123, false, () -> a);
        assertFalse(manager.play(123, true, () -> null));
        assertFalse(a.stopped);
        a.stopped = true;
        assertTrue(manager.play(123, false, () -> b));
        manager.trim();
        assertTrue(manager.stop(123));
        assertTrue(b.stopped);
    }

    @Test void globalSelectsAnimatableNotAllOwnersAndClosingOnlyRestores() {
        Object owner = new Object(), animation = new Object(), other = new Object();
        try (MolangSoundContext outer = MolangSoundContext.push(owner, null, null, animation, true, true)) {
            assertSame(owner, outer.manager(true));
            assertSame(animation, outer.manager(false));
            try (MolangSoundContext inner = MolangSoundContext.push(other, null, null, null, true, false)) {
                assertSame(other, inner.manager(false));
                assertFalse(inner.allowsEmitting());
            }
            assertSame(outer, MolangSoundContext.current());
        }
        assertNull(MolangSoundContext.current());
    }
}
