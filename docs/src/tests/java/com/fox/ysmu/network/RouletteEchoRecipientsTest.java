package com.fox.ysmu.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The roulette echo's recipient rules: "everyone tracking the sender, except the sender".
 *
 * <p>1.7.10's FML cannot express that exclusion - {@code FMLOutboundHandler.ALLAROUNDPOINT.selectNetworks}
 * ({@code FMLOutboundHandler.java:189-209}) walks the whole player list with no exclusion branch and
 * {@code NetworkRegistry.TargetPoint} has no field to exclude with ({@code NetworkRegistry.java:82-104}) - so
 * {@code NetworkHandler.sendToTrackingPlayersExcept} enumerates the recipients itself. These two rules are the ones it
 * copies from FML, tested here because {@code NetworkHandler} cannot be loaded in a unit JVM at all (its channel is
 * created in a static initialiser; see {@code YsmCommandTest.java:47}).</p>
 */
class RouletteEchoRecipientsTest {

    /** The point of the whole fix: the sender is not among the recipients. */
    @Test
    void theSenderIsExcludedByIdentity() {
        List<String> players = Arrays.asList("sender", "bystander-a", "bystander-b");

        List<String> recipients = TrackingRecipients.excludingSender("sender", players);

        assertEquals(Arrays.asList("bystander-a", "bystander-b"), recipients);
        assertFalse(recipients.contains("sender"), "the echo must never go back to the sender");
    }

    /** Order is the candidates' order, and the input list is not modified. */
    @Test
    void theOtherRecipientsKeepTheirOrderAndTheInputIsUntouched() {
        List<String> players = new ArrayList<>(Arrays.asList("a", "sender", "b", "c"));

        List<String> recipients = TrackingRecipients.excludingSender("sender", players);

        assertEquals(Arrays.asList("a", "b", "c"), recipients);
        assertEquals(4, players.size(), "the caller's list must not be filtered in place");
    }

    /** No candidates means no recipients; a sender that is not in the list changes nothing. */
    @Test
    void anEmptyOrSenderlessListIsHandled() {
        assertTrue(TrackingRecipients.excludingSender("sender", Collections.emptyList()).isEmpty());
        assertEquals(
            Arrays.asList("a", "b"),
            TrackingRecipients.excludingSender("sender", Arrays.asList("a", "b")),
            "excluding someone who is not there is not an error");
    }

    /**
     * FML's selection rule, verbatim ({@code FMLOutboundHandler.java:195-201}): same dimension and squared distance
     * strictly below the squared range.
     */
    @Test
    void theRangeRuleMatchesFmlsAllAroundPoint() {
        // Same spot: distance 0, always inside.
        assertTrue(TrackingRecipients.withinTargetPoint(0, 0, 64, 0, 0, 0, 64, 0, 128d));
        // A 3-4-5 offset: exactly 5 blocks away, so exactly on the boundary.
        assertFalse(
            TrackingRecipients.withinTargetPoint(0, 0, 64, 0, 0, 3, 64, 4, 5d),
            "FML compares d*d < range*range, so exactly range is NOT included");
        assertTrue(
            TrackingRecipients.withinTargetPoint(0, 0, 64, 0, 0, 3, 64, 4, 5.0001d),
            "just inside the range is included");
        assertFalse(TrackingRecipients.withinTargetPoint(0, 0, 64, 0, 0, 3, 64, 4, 4.9999d));
        // Dimension is checked before the distance, exactly like FML.
        assertFalse(
            TrackingRecipients.withinTargetPoint(0, 0, 64, 0, -1, 0, 64, 0, 128d),
            "a player in another dimension is never a recipient");
    }
}
