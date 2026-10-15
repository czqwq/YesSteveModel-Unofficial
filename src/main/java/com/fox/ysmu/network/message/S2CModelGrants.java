package com.fox.ysmu.network.message;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.fox.ysmu.client.ClientModelAccess;
import com.fox.ysmu.util.DeferredWork;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: the two authorization facts of one player - which models are restricted and which of them this
 * player may wear - as the server's authority holds them.
 * <p>
 * This is the port's single-packet reduction of upstream's session publication. Upstream sends the same information
 * in two parts: each published catalog entry carries its {@code access} (PUBLIC/AUTHORIZED,
 * {@code network/forge/SessionCollectionPublication.java:749-759}), and the player's grant set is published as its own
 * list of model ids ({@code :71-77}). The restricted half is therefore <em>derived</em> here rather than carried per
 * entry: the client needs one bit per model ("does this one need a grant?"), and the ids of the models that do are
 * exactly the {@code AUTHORIZED} subset of upstream's per-entry field. Both lists travel on every send - upstream's
 * full publication sends every grant ({@code :71-77}) and its authority delta is "clear, then add every grant"
 * ({@code :184-196}), so a full state is the shape the client adopts wholesale.
 * <p>
 * Who sends it: {@code network/ModelGrantSync}, on login and after every {@code /ysm auth} mutation, which is where
 * upstream calls {@code SessionProtocolHandler.refreshGrants} ({@code :339-341}, publishing from {@code :368-388}).
 * The client half is {@link ClientModelAccess}; {@code client/gui/PlayerModelScreen.java:255-257} is upstream's
 * consumer of the same two facts.
 * <p>
 * Both lists are the server's internal model ids ({@code ServerModelManager.CACHE_NAME_INFO} keys). The grants arrive
 * as {@code ResourceLocation}s, so {@code ModelGrantSync} sends their path half; that is the spelling the client's
 * registered ids use ({@code ysmu:<internal id>}, see {@link ClientModelAccess}'s javadoc).
 */
public class S2CModelGrants implements IMessage {

    /**
     * The restricted internal model ids, or {@code null} when this payload could not be read.
     * <p>
     * {@code null} is the "drop this packet" marker the N-10 rule asks for
     * ({@code network/NetworkHandler.java:71-80}: a decoder that throws is a kick, not an ignore), not an empty
     * set: an empty restricted set means "nothing is restricted" and would silently unlock a client that had
     * already been told otherwise.
     */
    private List<String> restrictedIds;

    /** The granted internal model ids of the receiving player, or {@code null} when this payload could not be read. */
    private List<String> grantedIds;

    public S2CModelGrants() {}

    public S2CModelGrants(@Nullable Collection<String> restrictedIds, @Nullable Collection<String> grantedIds) {
        this.restrictedIds = copyOf(restrictedIds);
        this.grantedIds = copyOf(grantedIds);
    }

    @Nullable
    public List<String> getRestrictedIds() {
        return restrictedIds;
    }

    @Nullable
    public List<String> getGrantedIds() {
        return grantedIds;
    }

    /**
     * Whether both lists were read to the end. A {@code false} means the payload is dropped
     * ({@link Handler} returns without touching the client's state), which leaves whatever the previous packet
     * established in place instead of replacing it with "nothing is restricted".
     */
    public boolean isValid() {
        return restrictedIds != null && grantedIds != null;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: validate against readableBytes() and soft-fail - never throw. The payload is adopted whole or dropped
        // whole: a half-read one would leave one list new and the other stale, which is exactly the mixed state
        // isValid() exists to prevent.
        List<String> parsedRestricted = null;
        List<String> parsedGranted = null;
        try {
            parsedRestricted = readIds(buf);
            parsedGranted = parsedRestricted == null ? null : readIds(buf);
            if (parsedGranted == null) {
                parsedRestricted = null;
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Ignoring malformed YSM model-grant packet", e);
            parsedRestricted = null;
            parsedGranted = null;
        }
        this.restrictedIds = parsedRestricted;
        this.grantedIds = parsedGranted;
    }

    /**
     * Reads one length-prefixed id list, or {@code null} when the buffer cannot hold it.
     * <p>
     * The count comes from the peer, so it is compared with {@code readableBytes()} before anything is allocated -
     * every entry needs at least one byte (the length varint; {@code ByteBufUtils.readUTF8String} is
     * {@code <varint length>[<UTF8 bytes>]}), so a count above the remaining byte count cannot be honest. The same
     * shape as {@code SyncStarModels#fromBytes} (N-06/N-10).
     */
    @Nullable
    private static List<String> readIds(ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            return null;
        }
        int count = buf.readInt();
        if (count < 0 || count > buf.readableBytes()) {
            ysmu.LOG.warn(
                "Ignoring malformed YSM model-grant list: size={}, readableBytes={}",
                count,
                buf.readableBytes());
            return null;
        }
        List<String> parsed = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.readableBytes() < 1) {
                return null;
            }
            parsed.add(ByteBufUtils.readUTF8String(buf));
        }
        return parsed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeIds(buf, restrictedIds);
        writeIds(buf, grantedIds);
    }

    private static void writeIds(ByteBuf buf, @Nullable List<String> ids) {
        List<String> safe = ids == null ? Collections.<String>emptyList() : ids;
        buf.writeInt(safe.size());
        for (String id : safe) {
            ByteBufUtils.writeUTF8String(buf, id);
        }
    }

    /** Defensive copy; {@code null} entries cannot occur in the server's own sets but would break {@link #toBytes}. */
    private static List<String> copyOf(@Nullable Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copied = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                copied.add(id);
            }
        }
        return copied;
    }

    public static class Handler implements IMessageHandler<S2CModelGrants, IMessage> {

        @Override
        public IMessage onMessage(S2CModelGrants message, MessageContext ctx) {
            // N-10: never let a handler body escape into FML's catch(Throwable) -> rejectHandshake.
            try {
                if (ctx.side != Side.CLIENT || message == null || !message.isValid()) {
                    return null;
                }
                final List<String> restricted = message.restrictedIds;
                final List<String> granted = message.grantedIds;
                // The holder is read by the GUI and the renderer on the client thread; the handler runs on the Netty
                // thread, so hop like the neighbouring clientbound packets do (S2CRoamingState.Handler:186-187).
                DeferredWork.client(() -> apply(restricted, granted));
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply the YSM model-grant packet", e);
            }
            return null;
        }

        @SideOnly(Side.CLIENT)
        private void apply(List<String> restricted, List<String> granted) {
            ClientModelAccess.update(restricted, granted);
        }
    }
}
