package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.PlayerMotionPredicatesTest;

import software.bernie.geckolib3.molang.util.MolangUtils;

/**
 * A-1: {@code query.time_of_day} is the fraction of a day in [0,1) and {@code query.time_stamp} is the raw world time,
 * on <em>both</em> evaluation paths and through one implementation.
 *
 * <p>What was wrong. The controller path had the two names in a single branch returning
 * {@code mc.theWorld.getWorldTime()} - the raw, ever-growing world time - so a controller condition written as
 * {@code query.time_of_day < 0.5} ("night") was false in every normal world and {@code > 0.5} true. The keyframe path
 * called the <em>legacy</em> {@code MolangUtils#normalizeTime}
 * ({@code software/bernie/geckolib3/util/MolangUtils.java:8-10}), which is {@code timestamp / 24000} - no modulo and no
 * phase - so 1.7.10's {@code World#getWorldTime()} (thousands of ticks, {@code WorldInfo#getWorldTime} is not
 * day-bounded) made it a number like 41.7 instead of a fraction, and its phase was a quarter-day off from upstream's
 * even on day one. Upstream's own implementation is {@code ((float)(timestamp + 6000L) / 24000) % 1}
 * ({@code tmp/YesSteveModel-dev-1.20 .../geckolib3/util/MolangUtils.java:30-32}), and the port already carries exactly
 * that method - in the newer MoLang tree, {@code software/bernie/geckolib3/molang/util/MolangUtils.java:29-31}. Both
 * paths now call it, and neither contains a copy of the arithmetic.
 */
class TimeOfDayPathAgreementTest {

    /**
     * The implementation both paths call: upstream's phase, and a value that is always a fraction of a day.
     * <p>
     * The phase matters as much as the range: 0.0 is midnight (day time 18000), 0.25 is 06:00 (day time 0, the value
     * every 1.7.10 world starts at) and 0.5 is noon, which is what a pack's {@code query.time_of_day > 0.5} test
     * assumes when it means "after noon".
     */
    @Test
    void theSharedNormalizerIsAFractionOfADayWithUpstreamsPhase() {
        assertEquals(0.25F, MolangUtils.normalizeTime(0L), 1.0E-6F, "day time 0 is 06:00");
        assertEquals(0.5F, MolangUtils.normalizeTime(6000L), 1.0E-6F, "day time 6000 is noon");
        assertEquals(0.0F, MolangUtils.normalizeTime(18000L), 1.0E-6F, "day time 18000 is midnight");
        assertEquals(
            0.25F,
            MolangUtils.normalizeTime(24000L),
            1.0E-6F,
            "t=0 and t=24000 are the same hour of the same clock (06:00), so they answer the same value");
        assertEquals(0.25F, MolangUtils.normalizeTime(24000L * 400L), 1.0E-6F, "and so does day 400");
        // The `% 1` does wrap, but only at 18000 mod 24000: (t + 6000) is a whole number of days exactly when
        // t ≡ 18000 (mod 24000), so 18000 + 24000 is midnight again while 24000 (the case above) is not.
        assertEquals(0.0F, MolangUtils.normalizeTime(18000L + 24000L), 1.0E-6F, "t ≡ 18000 (mod 24000) is midnight");
    }

    @Test
    void theNormalizerStaysInsideTheUnitIntervalForAnyWorldTime() {
        for (long time = 0L; time < 240000L; time += 137L) {
            float value = MolangUtils.normalizeTime(time);
            assertTrue(value >= 0.0F && value < 1.0F, "out of [0,1) at world time " + time + ": " + value);
        }
        // The value 1.7.10 actually reaches in a long-lived world: 24000 * 1000 is exactly a whole number of days.
        assertEquals(0.25F, MolangUtils.normalizeTime(24_000_000L), 1.0E-6F);
    }

    /**
     * The sharing, and the absence of a second copy of the arithmetic: both the keyframe registration and the
     * controller branch call the same (already vendored) implementation, and neither file computes the phase itself.
     * Comments are stripped, because both files quote upstream's formula in their javadoc.
     */
    @Test
    void bothPathsCallThatOneImplementationAndNeitherCopiesTheArithmetic() throws Exception {
        String keyframe = PlayerMotionPredicatesTest.stripComments(
            Files.readString(Path.of("src/main/java/com/fox/ysmu/client/animation/AnimationRegister.java")));
        String controller = PlayerMotionPredicatesTest.stripComments(
            Files.readString(
                Path.of("src/main/java/com/fox/ysmu/client/animation/controller/OpenYsmControllerExpressionEvaluator.java")));

        assertTrue(
            keyframe.contains("software.bernie.geckolib3.molang.util.MolangUtils.normalizeTime"),
            "the keyframe path must normalize through the upstream implementation");
        assertTrue(
            controller.contains("software.bernie.geckolib3.molang.util.MolangUtils.normalizeTime"),
            "the controller path must call the same implementation, not its own math");
        assertFalse(controller.contains("6000"), "the phase constant must not be copied into the controller path");
        assertFalse(controller.contains("24000"), "the day length must not be copied into the controller path");

        // time_stamp is not normalized on either path - it is the raw world time upstream calls `getDayTime()`.
        assertTrue(
            keyframe.contains("parser.setValue(\"query.time_stamp\", () -> mc.theWorld.getWorldTime());"),
            "the keyframe path's time_stamp is the raw world time");
        assertTrue(
            controller.contains("return mc.theWorld == null ? 0.0d : mc.theWorld.getWorldTime();"),
            "the controller path's time_stamp is the raw world time");
    }
}
