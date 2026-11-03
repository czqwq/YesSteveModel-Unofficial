package com.fox.ysmu.network.message;

import com.fox.ysmu.client.animation.molang.RemoteMolangExecution;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: run a Molang expression against the named entities' model scopes. Sent by
 * {@code /ysm molang execute <targets> <expression>}.
 *
 * <p>Ported from upstream's {@code ExecuteMolangEvent} and its two halves:
 * {@code command/sub/MolangCommand.java:38-50} builds the message from the command's target list and
 * {@code NetworkHandler.broadcastToAllPlayers}s it - <b>every</b> client, not only the targets' own clients, because
 * each viewer's client then executes the expression against the entity ids it can see; the consumer is
 * {@code network/forge/ControlHandler.java:112-122} (bounds: at most {@code MAX_MOLANG_TARGETS} targets and an
 * expression no longer than {@code MAX_EXPRESSION_LENGTH}) and finally
 * {@code ControlHandler.executeMolang:253-272}, which resolves each id in the client's current level, parses the
 * expression and hands it to that player's animatable with {@code allowEmitting = true}, ignoring ids that are not
 * players.</p>
 *
 * <h3>Bounds</h3>
 * {@link #MAX_TARGETS} and {@link C2SSubmitRouletteExpression#MAX_EXPRESSION_LENGTH} are upstream's two limits
 * ({@code ControlHandler.java:51,53}); the expression bytes travel through the same length-prefixed, validated
 * codec the roulette channel uses ({@code C2SSubmitRouletteExpression.readExpression}), so a hostile payload cannot
 * ask for a large allocation.
 *
 * <h3>Divergence recorded</h3>
 * Upstream's consumer runs the expression through {@code PlayerAnimatableCapability#executeMolangExp}, which queues
 * it into that entity's {@code AnimationProcessor} and runs it in the entity's live Molang scope; this port's
 * equivalent is {@code client/roaming/ModelFormExecution} (the same call the roulette echo uses, itself ported from
 * the same {@code ControlHandler}). {@code PacketEvalMolang} is <em>not</em> reused for it: that one prints the
 * result into the local player's chat and evaluates outside any entity scope, which is what
 * {@code /ysm debug eval} wants and what this command must not do. Both paths share the one
 * {@code GeckoLibCache.parser}, and nothing here is gated on {@code Config.DEBUG_MOLANG_OVERLAY} - upstream's command
 * is ordinary administration, not debug tooling.
 */
public class S2CExecuteMolang implements IMessage {

    /** Upstream's {@code ControlHandler.MAX_MOLANG_TARGETS} ({@code network/forge/ControlHandler.java:51}). */
    public static final int MAX_TARGETS = 1024;

    private String expression;
    private int[] targets = new int[0];

    public S2CExecuteMolang() {}

    public S2CExecuteMolang(String expression, int[] targets) {
        this.expression = expression;
        this.targets = targets == null ? new int[0] : targets;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10 (NetworkHandler.java:98-107): a throwing decoder is a disconnect in FML, so every length is checked
        // against readableBytes() and a malformed payload decodes to "nothing to do" instead.
        try {
            if (buf.readableBytes() < 4) {
                return;
            }
            int count = buf.readInt();
            if (count < 0 || count > MAX_TARGETS || count * 4 > buf.readableBytes()) {
                ysmu.LOG.warn("Ignoring malformed YSM molang packet: target count {}", count);
                return;
            }
            this.targets = new int[count];
            for (int i = 0; i < count; i++) {
                this.targets[i] = buf.readInt();
            }
            this.expression = C2SSubmitRouletteExpression.readExpression(buf);
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring malformed YSM molang packet: {}", e.toString());
            this.targets = new int[0];
            this.expression = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int[] ids = this.targets == null ? new int[0] : this.targets;
        buf.writeInt(ids.length);
        for (int id : ids) {
            buf.writeInt(id);
        }
        C2SSubmitRouletteExpression.writeExpression(buf, this.expression);
    }

    /** The Molang source to run, or {@code null} when the payload was unusable. */
    public String expression() {
        return this.expression;
    }

    /** The entity ids the command named; empty when the payload was unusable. */
    public int[] targets() {
        return this.targets;
    }

    public static class Handler implements IMessageHandler<S2CExecuteMolang, IMessage> {

        @Override
        public IMessage onMessage(S2CExecuteMolang message, MessageContext ctx) {
            // The server instantiates every registered handler; this one is client-only work.
            if (ctx.side == Side.SERVER) {
                return null;
            }
            try {
                String expression = message.expression();
                int[] targets = message.targets();
                if (expression == null || targets == null || targets.length == 0) {
                    return null;
                }
                // Entity resolution and parsing are client work (Minecraft.theWorld, the client Molang parser), so
                // they live in client/animation/molang/RemoteMolangExecution; this handler only hands over the
                // payload on the client thread. The lambda body is the only reference to that client class, so
                // instantiating this handler on a dedicated server never loads it.
                DeferredWork.client(() -> RemoteMolangExecution.execute(expression, targets));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to handle YSM molang packet", e);
            }
            return null;
        }
    }
}
