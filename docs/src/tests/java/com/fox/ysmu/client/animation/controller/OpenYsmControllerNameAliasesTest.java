package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guards the pack-facing controller name table in {@link OpenYsmPlayerControllerRuntime}.
 * <p>
 * The port names its GeckoLib controllers after its own Java wiring, while packs are written against upstream's
 * names, so a single controller has to answer to several spellings. Getting that table wrong is invisible: the pack
 * simply never matches and the controller silently falls back to the Java predicate, which is exactly how the
 * per-slot armor controllers were dead - upstream keys them {@code player.armor_<slot>}
 * (client/controller/ArmorControllerDiscovery.java:35), the shipped wine_fox/14_momo pack declares
 * {@code player.armor_head} (controller/armor.animation_controllers.json:4), and this port named its controller
 * {@code head_controller} and never tried the upstream key.
 * <p>
 * These are pure string and table assertions; no Minecraft classes are touched.
 */
class OpenYsmControllerNameAliasesTest {

    /** GeckoLib controller name -> the slot word upstream puts into `player.armor_<slot>`. */
    private static final String[][] ARMOR_CONTROLLERS = { { "feet_controller", "feet" }, { "legs_controller", "legs" },
        { "chest_controller", "chest" }, { "head_controller", "head" } };

    @Test
    void armorControllersAnswerToTheUpstreamKey() {
        for (String[] pair : ARMOR_CONTROLLERS) {
            String geckoName = pair[0];
            String slot = pair[1];
            assertEquals(slot, OpenYsmPlayerControllerRuntime.armorSlotOf(geckoName), geckoName);
            List<String> names = OpenYsmPlayerControllerRuntime.candidateControllerNames(geckoName);
            assertTrue(
                names.contains("player.armor_" + slot),
                geckoName + " must answer to the key packs are written against, got " + names);
            assertTrue(names.contains("armor_" + slot), geckoName + " must also answer to the bare slot key " + names);
        }
    }

    @Test
    void armorAliasesDoNotLeakOntoOtherControllers() {
        assertNull(OpenYsmPlayerControllerRuntime.armorSlotOf(null));
        assertNull(OpenYsmPlayerControllerRuntime.armorSlotOf(""));
        // `head` is the stripped form of the head controller, not a slot key of its own.
        assertNull(OpenYsmPlayerControllerRuntime.armorSlotOf("head"));
        assertNull(OpenYsmPlayerControllerRuntime.armorSlotOf("main_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.armorSlotOf("parallel_3_controller"));
        for (String controller : new String[] { "main_controller", "swing_controller", "parallel_3_controller" }) {
            List<String> names = OpenYsmPlayerControllerRuntime.candidateControllerNames(controller);
            assertFalse(
                names.stream()
                    .anyMatch(name -> name.startsWith("player.armor_")),
                controller + " is not an armor controller but resolved armor keys " + names);
        }
    }

    @Test
    void theControllersJavaWiringNamesStillTryTheirUpstreamNames() {
        // No `parallel_<i>` entry here on purpose: the port used to offer the grouped `player.parallel_0` to every
        // parallel controller as a fallback, which made 14_momo's per-index definitions play the wrong clips. The
        // per-index name is now the only one tried (see aParallelControllerIsBoundToItsClipByNameNotByPosition).
        Object[][] expectations = { { "main_controller", "player.main" },
            { "hold_mainhand_controller", "player.hold_mainhand" },
            { "hold_offhand_controller", "player.hold_offhand" }, { "swing_controller", "player.swing" },
            { "use_controller", "player.use" }, { "cap_controller", "player.cap" } };
        for (Object[] expectation : expectations) {
            String geckoName = (String) expectation[0];
            String upstreamName = (String) expectation[1];
            assertTrue(
                OpenYsmPlayerControllerRuntime.candidateControllerNames(geckoName)
                    .contains(upstreamName),
                geckoName + " must still try " + upstreamName);
        }
    }

    @Test
    void theControllerItsOwnSpellingStaysReachableAndComesFirst() {
        List<String> names = OpenYsmPlayerControllerRuntime.candidateControllerNames("head_controller");
        assertTrue(names.contains("head_controller"), names.toString());
        // The name both sides already agreed on is tried before the upstream alias.
        assertTrue(
            names.indexOf("head_controller") < names.indexOf("player.armor_head"),
            "the agreed spelling must win over the alias: " + names);
        assertTrue(
            OpenYsmPlayerControllerRuntime.candidateControllerNames("parallel_3_controller")
                .contains("parallel_3"),
            "the stripped controller name must stay reachable");
    }

    /**
     * Upstream pairs controller {@code parallel_<i>} with clip {@code parallel<i>} by suffix, which is why the order
     * of a grouped {@code animations} list cannot matter (upstream plays every entry of a state at once). Binding by
     * position instead made 14_momo's {@code [parallel0, parallel2, parallel3, parallel1, ...]} play the wrong clip.
     */
    @Test
    void aParallelControllerIsBoundToItsClipByNameNotByPosition() {
        // Controller names carry the underscore (`parallel_4_controller`), the clips do not (`parallel4`).
        assertEquals("parallel4", OpenYsmPlayerControllerRuntime.parallelAnimationName("parallel_4_controller"));
        assertEquals("parallel0", OpenYsmPlayerControllerRuntime.parallelAnimationName("parallel_0_controller"));
        assertEquals(
            "pre_parallel2",
            OpenYsmPlayerControllerRuntime.parallelAnimationName("pre_parallel_2_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parallelAnimationName("main_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parallelAnimationName("head_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parallelAnimationName("parallel_controller"));
    }
}
