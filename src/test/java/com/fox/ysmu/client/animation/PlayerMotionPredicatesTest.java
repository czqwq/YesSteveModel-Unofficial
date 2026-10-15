package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * A-2: {@code query.is_jumping} and the flying predicate behind {@code ctrl.fly} / the {@code fly} state must give
 * <em>one</em> answer, whichever path asks.
 *
 * <p>The two paths had separate copies. The keyframe path had already dropped the local player's
 * {@code motionYState(player, 0.0) != 0} term with a written record of the defect it caused
 * ({@code AnimationRegister#isPlayerJumping}: the term is false for the single tick {@code motionY} passes through
 * zero at the apex of a jump, which dropped the state machine out of {@code jump} and snapped the arms back to
 * neutral and out again), while the controller path still had it - so for that tick a controller condition read
 * {@code query.is_jumping} = 0 and {@code ctrl.jump} = 0 while the animation file read 1. The flying predicates had
 * drifted the other way: the controller counted an Et Futurum Requiem elytra glide as flight and the keyframe path did
 * not.
 *
 * <p>The decision table is tested through the four-fact overload, because 1.7.10's {@code EntityPlayer} constructor
 * needs a live world ({@code build/rfg/minecraft-src/java/net/minecraft/entity/player/EntityPlayer.java:163-176}) and
 * cannot exist in a unit JVM. The property that the two paths share it is a source-order guard: the evaluator's three
 * methods must delegate, and the arithmetic must exist once.
 */
public class PlayerMotionPredicatesTest {

    /**
     * The apex case: airborne with zero vertical motion ({@code flying}, {@code riding}, {@code onGround} and
     * {@code inWater} all false). The removed {@code motionYState(0.0) != 0} term is the only thing that ever answered
     * false here, and it is what the keyframe path's history records as the arm-snap defect.
     */
    @Test
    void theApexOfAJumpStaysInTheJumpPhase() {
        assertTrue(
            AnimationRegister.isPlayerJumping(false, false, false, false),
            "an airborne player whose motionY is exactly 0 is still in the jump phase");
    }

    /**
     * The whole table: upstream's rule is four exclusions and nothing else
     * ({@code geckolib3/core/molang/builtin/QueryBinding.java:102}), so exactly one of the sixteen combinations is
     * true - the airborne one.
     */
    @Test
    void theRuleIsExactlyUpstreamsFourExclusions() {
        int trueCases = 0;
        for (int mask = 0; mask < 16; mask++) {
            boolean flying = (mask & 1) != 0;
            boolean riding = (mask & 2) != 0;
            boolean onGround = (mask & 4) != 0;
            boolean inWater = (mask & 8) != 0;
            boolean expected = !flying && !riding && !onGround && !inWater;
            assertEquals(
                expected,
                AnimationRegister.isPlayerJumping(flying, riding, onGround, inWater),
                "flying=" + flying + " riding=" + riding + " onGround=" + onGround + " inWater=" + inWater);
            if (expected) {
                trueCases++;
            }
        }
        assertEquals(1, trueCases, "only the fully airborne combination is the jump phase");
    }

    /** Detached evaluation (the evaluator also builds a context with no actor) answers false, not an exception. */
    @Test
    void detachedEvaluationAnswersFalseInsteadOfThrowing() {
        assertFalse(AnimationRegister.isPlayerJumping(null));
        assertFalse(AnimationRegister.isPlayerFlying(null));
        assertFalse(AnimationRegister.isPlayerOnGround(null));
    }

    /**
     * The sharing itself, which is the only part of the property a unit JVM can check: the evaluator's three methods
     * must be delegations, and the predicate's arithmetic and the local/remote split must each exist once. Comments are
     * stripped first, because the javadoc deliberately quotes the term that was removed.
     */
    @Test
    void theControllerPathCallsTheKeyframePathsPredicatesRatherThanKeepingCopies() throws Exception {
        String evaluator = stripComments(
            Files.readString(
                Path.of("src/main/java/com/fox/ysmu/client/animation/controller/OpenYsmControllerExpressionEvaluator.java")));
        String keyframe = stripComments(
            Files.readString(Path.of("src/main/java/com/fox/ysmu/client/animation/AnimationRegister.java")));

        assertTrue(
            evaluator.contains("return AnimationRegister.isPlayerJumping(player);"),
            "the controller path's isJumping must delegate to the keyframe path's predicate");
        assertTrue(evaluator.contains("return AnimationRegister.isPlayerFlying(player);"));
        assertTrue(evaluator.contains("return AnimationRegister.isPlayerOnGround(player);"));

        assertFalse(evaluator.contains("motionYState(0.0"), "the removed motionY term must not come back");
        assertFalse(evaluator.contains("capabilities.isFlying"), "the fly answer must come from the shared predicate");
        assertFalse(
            evaluator.contains("import com.fox.ysmu.client.animation.RemotePlayerMotionStates;"),
            "the local/remote split must not be duplicated in the controller path");

        assertTrue(
            keyframe.contains("return !flying && !riding && !onGround && !inWater;"),
            "the rule lives in the four-fact overload");
        assertFalse(
            keyframe.contains("motionYState(player, 0.0)"),
            "the keyframe path removed that term; it must stay removed");
    }

    /**
     * Removes {@code //} line comments and {@code /* … *}{@code /} block comments, so code-only assertions are safe.
     * Public so the other path-agreement tests can share it.
     */
    public static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
