package com.fox.ysmu.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The two rules a "send to everyone tracking this entity, but not to the entity's own player" broadcast needs.
 *
 * <p><b>Why these are here and not inline.</b> 1.7.10's FML has no way to exclude anyone from a nearby broadcast:
 * {@code SimpleNetworkWrapper.sendToAllAround} only sets {@code OutboundTarget.ALLAROUNDPOINT} plus a
 * {@code NetworkRegistry.TargetPoint} ({@code SimpleNetworkWrapper.java:206-211}), that target type carries exactly
 * five fields - dimension, x, y, z, range - and no exclusion field ({@code NetworkRegistry.java:82-104}), and FML's
 * own selection loops over <em>every</em> player in {@code playerEntityList} with no exclusion branch
 * ({@code FMLOutboundHandler.java:189-209}: dimension at {@code :195}, squared distance at {@code :197-201}). So a
 * sender-specific exclusion has to enumerate the candidates on this side, which means reproducing those two rules.
 * They live in their own class - free of Minecraft and FML references - so they can be checked in the unit JVM, where
 * {@code NetworkHandler} itself cannot even be loaded (its channel is created in a static initialiser; see
 * {@code YsmCommandTest.java:47}).
 *
 * <p><b>What they are for.</b> The roulette-expression echo: the sender's own client already executed the program
 * locally before sending it, so the server's echo exists only for the <em>other</em> clients that render the sender.
 * Sending it back to the sender would run the program a second time on that client, and a program with side effects
 * ({@code play_sound}, particles) would fire twice. See {@code C2SSubmitRouletteExpression.Handler}.
 */
public final class TrackingRecipients {

    private TrackingRecipients() {}

    /**
     * {@code candidates} without the sender.
     * <p>
     * Identity, not equality: the candidates are the server's own {@code EntityPlayerMP} instances, so the sender is
     * literally the same object in that list, and two distinct players are never "equal" in a way that would matter.
     * A {@code null} sender excludes the {@code null} entries rather than everything, which is why the caller checks
     * the sender first.
     *
     * @return a new list, in the candidates' order; never {@code null}
     */
    public static <T> List<T> excludingSender(T sender, Collection<T> candidates) {
        List<T> recipients = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            if (candidate != sender) {
                recipients.add(candidate);
            }
        }
        return recipients;
    }

    /**
     * FML's {@code ALLAROUNDPOINT} test, verbatim: the same dimension and a squared distance strictly below the
     * squared range ({@code FMLOutboundHandler.java:195-201}).
     * <p>
     * The strict comparison matters at the boundary: a player exactly {@code range} blocks away is <b>not</b> a
     * recipient, because FML compares {@code d4 * d4 + d5 * d5 + d6 * d6 < tp.range * tp.range}. The euclidean
     * distance is never taken, square roots never taken, exactly as there.
     */
    public static boolean withinTargetPoint(int dimension, double x, double y, double z, int playerDimension,
        double playerX, double playerY, double playerZ, double range) {
        if (playerDimension != dimension) {
            return false;
        }
        double dx = x - playerX;
        double dy = y - playerY;
        double dz = z - playerZ;
        return dx * dx + dy * dy + dz * dz < range * range;
    }
}
