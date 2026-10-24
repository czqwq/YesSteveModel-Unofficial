package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.RuntimeState;

class OpenYsmStateProgressionTest {
    private static State state(String name, boolean animated) {
        State state = new State();
        state.name = name;
        if (animated) state.animations.add(new AnimationEntry(name, "0"));
        return state;
    }

    @Test
    void longEmptyChainStopsAtFirstPlayerEvenWhenItsConditionIsFalse() {
        List<State> states = new ArrayList<>();
        for (int i = 0; i < 8; i++) states.add(state("s" + i, i == 6));
        List<String> effects = new ArrayList<>();
        State end = OpenYsmPlayerControllerRuntime.advanceStates(states.get(0), true,
            s -> states.get(states.indexOf(s) + 1),
            (from, to) -> effects.add(from.name + ">" + to.name));
        assertSame(states.get(6), end);
        assertEquals(6, effects.size());
    }

    @Test
    void initialPlayableStateDoesNotTransitionUntilNextFrame() {
        State initial = state("initial", true);
        State next = state("next", true);
        assertSame(initial, OpenYsmPlayerControllerRuntime.advanceStates(initial, true,
            s -> next, (a, b) -> fail("initial playable state must process first")));
        assertSame(next, OpenYsmPlayerControllerRuntime.advanceStates(initial, false,
            s -> next, (a, b) -> {}));
    }

    @Test
    void cycleStopsBeforeRepeatedEntryAndExit() {
        State a = state("a", false);
        State b = state("b", false);
        List<String> entries = new ArrayList<>();
        assertSame(b, OpenYsmPlayerControllerRuntime.advanceStates(a, true,
            s -> s == a ? b : a, (from, to) -> entries.add(to.name)));
        assertEquals(List.of("b"), entries);
        entries.clear();
        assertSame(a, OpenYsmPlayerControllerRuntime.advanceStates(a, false,
            s -> s == a ? b : a, (from, to) -> entries.add(to.name)));
        assertEquals(List.of("b", "a"), entries);
    }

    @Test
    void anyAndAllReduceOnlyAppliedPlayersAndEmptyMeansBothTrue() {
        RuntimeState runtime = new RuntimeState();
        runtime.activeState = state("one", true);
        runtime.activeState.animations.add(new AnimationEntry("two", "1"));
        var mixed = runtime.finishedAnimations(e -> true, e -> e.animationName.equals("one"));
        assertTrue(mixed.any);
        assertFalse(mixed.all);
        var disabled = runtime.finishedAnimations(e -> false, e -> false);
        assertTrue(disabled.any);
        assertTrue(disabled.all);
        var one = runtime.finishedAnimations(e -> e.animationName.equals("one"), e -> true);
        assertTrue(one.any);
        assertTrue(one.all);
        runtime.activeState = state("empty", false);
        var empty = runtime.finishedAnimations(e -> true, e -> false);
        assertTrue(empty.any);
        assertTrue(empty.all);
    }
}
