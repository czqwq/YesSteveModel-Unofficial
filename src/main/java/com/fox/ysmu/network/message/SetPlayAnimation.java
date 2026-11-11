package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Asks the server to play one named extra animation of the sender's model, or to stop the current one.
 *
 * <h3>Why the payload is a name and not a slot index</h3>
 * A pack's {@code properties.extra_animation} is an ordered map whose <b>key</b> is the animation name and whose
 * <b>value</b> is the display label, and the key is not always {@code extra<i>}: LaPluma 2.5.3 declares
 * {@code HI}/{@code WHAT}/{@code Bird}/{@code Illustration0}/{@code Illustration1} plus three {@code #...} sub-page
 * markers, and its {@code extra.animation.json} really defines those named clips. Upstream sends the name itself -
 * its roulette's {@code clickDefault} hands {@code extraAnimations.get(selectId).key()} to
 * {@code ClientProtocolGateway.playSelfAnimation(selectKey)}
 * ({@code tmp/YesSteveModel-dev-1.20/.../client/gui/AnimationRouletteScreen.java:645-667}), and the reference branch
 * carries the same thing over the wire ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../network/message/
 * SetPlayAnimation.java:19-24}). This port used to send the slot index and let the server spell the name as
 * {@code "extra" + index}, which can only ever reach the legacy {@code extra0..extra7} vocabulary.
 * <p>
 * <b>Legacy behaviour is unchanged.</b> The eight key bindings and any legacy pack keep working byte for byte: a
 * legacy model declares its keys as {@code extra0..extra7}, so the sender spells the same name the old server-side
 * concatenation did ({@code "extra" + i}) and the EEP ends up holding exactly the same string as before.
 *
 * <h3>What replaces the old index validation</h3>
 * The handler used to accept only {@code STOP <= id && id < 8}. A name cannot be bounded by a numeric range, so
 * {@link #isPlausibleAnimationName(String)} takes its place: non-empty, at most
 * {@link #MAX_ANIMATION_NAME_LENGTH} characters, no control characters, and never a {@code '#'}-prefixed category
 * marker (those name a sub-page, not an animation). There is deliberately <b>no</b> server-side "does the model declare
 * this name" check: the client is the only side that can answer it, and it already refuses to send a name its model
 * does not declare ({@code AnimationRouletteScreen#playEntry} validates through
 * {@code EntityAnimationApi#modelHasClip} before sending). Upstream's {@code PlayAnimationCommand.java:26-45} performs
 * no name check either; a name nothing declares is simply skipped by the render path
 * ({@code AnimationManager#playIfPresent}). The check this class used to repeat was dead code on every real path
 * (both callers run on a server thread) and forced a client-class reference into a common packet class, which crashed
 * dedicated servers - see {@code .agent/phase76-animation-store-port.md} "实机反馈修复" and F-1 in
 * {@code .agent/review-uncommitted-2026-11-11.md}.
 */
public class SetPlayAnimation implements IMessage {

    /** The "stop" sentinel; the reference spells it the same way (its {@code SetPlayAnimation.java:24}). */
    private static final String STOP = ".stop";

    /**
     * Longest animation name this packet accepts. The old bound was the slot range {@code 0..7}; a name needs a
     * length bound of its own so a malformed or hostile payload cannot park an arbitrarily large string in the EEP
     * (and in every client's copy of it, since the EEP is broadcast to tracking players). Pack animation names are
     * short; 128 is far above anything a manifest uses and far below any packet-size limit.
     */
    static final int MAX_ANIMATION_NAME_LENGTH = 128;

    private String animationName = "";

    public SetPlayAnimation() {}

    public SetPlayAnimation(String animationName) {
        this.animationName = animationName == null ? "" : animationName;
    }

    public static SetPlayAnimation stop() {
        return new SetPlayAnimation(STOP);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: a malformed payload must not throw - FMLProxyPacket turns a throwing decoder into a disconnect. A
        // UTF-8 string is at least its varint length prefix, so a shorter buffer cannot hold one; a length prefix
        // that promises more bytes than the buffer holds is caught and dropped the same way.
        if (buf.readableBytes() < 2) {
            ysmu.LOG.warn("Ignoring malformed YSM play-animation packet: readableBytes={}", buf.readableBytes());
            this.animationName = "";
            return;
        }
        try {
            String name = ByteBufUtils.readUTF8String(buf);
            this.animationName = name == null ? "" : name;
        } catch (RuntimeException e) {
            ysmu.LOG.warn("Ignoring truncated YSM play-animation packet: {}", e.toString());
            this.animationName = "";
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.animationName == null ? "" : this.animationName);
    }

    /**
     * The shape test that replaces the old index bound. Deliberately about <em>shape</em>, not about whether the model
     * declares the name: the server cannot answer that (see the class javadoc), so the send side and the render path
     * are the two places that know the model, and a name nothing declares is skipped there.
     */
    static boolean isPlausibleAnimationName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_ANIMATION_NAME_LENGTH) {
            return false;
        }
        if (name.charAt(0) == '#') {
            // A sub-page marker ("#<classifyId>") or "#return": it names a category, never an animation, so it must
            // never enter the EEP.
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < ' ' || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    public static class Handler implements IMessageHandler<SetPlayAnimation, IMessage> {

        @Override
        public IMessage onMessage(SetPlayAnimation message, MessageContext ctx) {
            // N-10: guard the handler body; an exception here would be turned into a disconnect by FML.
            try {
                EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
                if (sender == null) {
                    return null;
                }
                String name = message.animationName;
                // The write is a mutation of the player's EEP, which the server tick reads and broadcasts and the
                // save path serialises; the handler runs on the Netty thread, so hand the write to the server
                // thread the way the neighbouring EEP writers do (SetModelAndTexture / C2SRoamingChanges).
                DeferredWork.server(() -> apply(sender, name));
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM play-animation packet", e);
            }
            return null;
        }

        /** Server thread: record the request in the sender's EEP; the render path skips a name the model lacks. */
        private static void apply(EntityPlayerMP sender, String name) {
            try {
                if (sender.isDead || sender.worldObj == null) {
                    return;
                }
                ExtendedModelInfo modelIdEEP = ExtendedModelInfo.get(sender);
                if (modelIdEEP == null) {
                    return;
                }
                if (STOP.equals(name)) {
                    modelIdEEP.stopAnimation();
                    return;
                }
                if (!isPlausibleAnimationName(name)) {
                    if (!name.isEmpty()) {
                        ysmu.LOG.warn("Ignoring YSM play-animation request with an unusable name (length={})", name.length());
                    }
                    return;
                }
                modelIdEEP.playAnimation(name);
            } catch (Exception e) {
                ysmu.LOG.warn("Ignoring malformed YSM play-animation packet", e);
            }
        }
    }
}
