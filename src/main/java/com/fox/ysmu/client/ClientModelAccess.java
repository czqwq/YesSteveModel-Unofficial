package com.fox.ysmu.client;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.util.ModelIdUtil;

/**
 * The client's copy of the two authorization facts the server publishes: which models are <em>restricted</em>
 * ({@code CatalogAccess.AUTHORIZED}) and which of those this player has been <em>granted</em>.
 * <p>
 * Upstream carries the same two facts on its session channel: the restricted half is the per-entry {@code access}
 * field of a published catalog entry
 * ({@code network/forge/SessionCollectionPublication.java:749-759}, read back into {@code CatalogAccess} at
 * {@code :768-784}), and the granted half is the grant set published as its own list
 * ({@code :71-77}, and re-published as "clear then add every grant" whenever the set changes at {@code :184-196},
 * driven by {@code network/forge/SessionProtocolHandler.java:368-388}). On the client, upstream answers the same
 * question this class does:
 *
 * <pre>
 * // client/gui/PlayerModelScreen.java:255-257
 * var needAuth = entry.authorizationRequired()
 *         &amp;&amp; sessionGrants.map(grants -&gt; !grants.contains(entry.modelHash()))
 *         .orElseGet(() -&gt; auth == null || !auth.containModel(entry.modelHash()));
 * </pre>
 *
 * with {@code authorizationRequired()} being {@code entry.access() == CatalogAccess.AUTHORIZED}
 * ({@code model/catalog/client/entry/ClientCatalogEntry.java:43-45}). {@link #needsAuth(ResourceLocation)} is that
 * predicate, and the AUTH category filter uses the same one ({@code client/gui/CatalogBrowserState.java:108}).
 * <p>
 * <b>No information must never lock.</b> Upstream's {@code ClientSessionRuntime.authoritativeGrants()} answers
 * {@code Optional.empty()} while there is no session ({@code :96-105}), and the caller above only locks when it has a
 * set to test against - an unknown state is not a denial. Here the same rule is {@link #isEmpty()}: until
 * {@code S2CModelGrants} has been received (and after {@link #clear()}), {@link #needsAuth} is {@code false} for
 * every model. The GUI therefore draws no lock on a vanilla server, on a server without this mod's channel, or in the
 * window before the login packet arrives; the server is still the authority that refuses the selection.
 * <p>
 * <b>Identity.</b> The wire strings are the server's internal model ids - the {@code ServerModelManager.CACHE_NAME_INFO}
 * keys, which is also the path half of the {@code ysmu:<internal id>} ids the client registers
 * ({@code client/ClientModelManager.java:1855-1860} keys {@code MODELS} by the synced model id). A caller may hand
 * this class any of the spellings the server itself accepts, so the lookup repeats the four steps of
 * {@code model/ServerModelManager.java:592-602} (bare path, full {@code ysmu:} id, then each normalized through
 * {@link ModelIdUtil#getInternalModelId(String)}), which is the same normalization
 * {@code ServerModelManager.accessOf}/{@code hasModel} use.
 * <p>
 * No {@code @SideOnly(Side.CLIENT)}: like {@code ClientRoamingStore} and {@code ModelPreviewAnimationState}, this
 * class is a plain data structure so it can be covered by a unit test without a game. The fields are {@code volatile}
 * because the two writers do not run on the same thread: {@link #update} is queued to the client thread by the packet
 * handler, while {@link #clear} runs wherever the connection ends - Netty for a multiplayer disconnect, the server
 * thread for an integrated-server logout ({@code ClientEventHandler#clearClientRuntimeState}'s javadoc records that
 * split).
 */
public final class ClientModelAccess {

    /** The restricted internal model ids, or {@code null} while no packet has been received. */
    private static volatile Set<String> restrictedIds;

    /** The player's granted internal model ids, or {@code null} while no packet has been received. */
    private static volatile Set<String> grantedIds;

    private ClientModelAccess() {}

    /**
     * Replaces both sets with a full state from the server. A full state is adopted wholesale rather than merged,
     * which is upstream's rule as well: its authority delta is "clear the grants, then add every current grant"
     * ({@code SessionCollectionPublication.java:184-196}), so a grant the server no longer holds is gone on the client
     * too instead of leaving a tile unlocked that the server would refuse.
     */
    public static void update(Collection<String> restrictedIds, Collection<String> grantedIds) {
        ClientModelAccess.restrictedIds = copy(restrictedIds);
        ClientModelAccess.grantedIds = copy(grantedIds);
    }

    /**
     * Drops both sets, returning the client to "no authorization information". Called from the port's single
     * disconnect cleanup ({@code ClientEventHandler#clearClientRuntimeState}), because the sets describe the server
     * that just went away and the next connection publishes its own.
     */
    public static void clear() {
        restrictedIds = null;
        grantedIds = null;
    }

    /**
     * Whether no authorization state has arrived yet. This is the "no session" state of upstream's
     * {@code ClientSessionRuntime.authoritativeGrants()} ({@code :96-105}); {@link #needsAuth} answers {@code false}
     * for every model while it is true, so an uninformed client locks nothing.
     */
    public static boolean isEmpty() {
        return restrictedIds == null || grantedIds == null;
    }

    /**
     * Whether this model may only be worn with a grant - upstream's {@code ClientCatalogEntry.authorizationRequired()}
     * ({@code :43-45}). {@code false} while {@link #isEmpty()}, and {@code false} for an unknown or {@code null} id.
     */
    public static boolean requiresAuth(@Nullable ResourceLocation modelId) {
        return contains(restrictedIds, modelId);
    }

    /** Whether this player has been granted this model. {@code false} while {@link #isEmpty()}. */
    public static boolean isGranted(@Nullable ResourceLocation modelId) {
        return contains(grantedIds, modelId);
    }

    /**
     * The lock predicate the selection screen uses: the model is restricted and not granted. False while
     * {@link #isEmpty()} - see the class javadoc on why an unknown state is not a denial.
     */
    public static boolean needsAuth(@Nullable ResourceLocation modelId) {
        if (isEmpty()) {
            return false;
        }
        return requiresAuth(modelId) && !isGranted(modelId);
    }

    /**
     * The four spelling steps of {@code ServerModelManager.accessOf} ({@code :592-602}), applied to a published
     * string set. The raw path is tested first, so an id that is <em>already</em> an internal id is matched even
     * though {@link ModelIdUtil#getInternalModelId(String)} would encode it a second time (a {@code _name_<hex>}
     * internal id is itself made of safe characters but carries the encoded prefix, which
     * {@code ModelIdUtil.java:68-71} deliberately re-encodes).
     */
    private static boolean contains(@Nullable Set<String> ids, @Nullable ResourceLocation modelId) {
        if (ids == null || modelId == null) {
            return false;
        }
        String path = modelId.getResourcePath();
        if (ids.contains(path) || ids.contains(modelId.toString())) {
            return true;
        }
        return ids.contains(ModelIdUtil.getInternalModelId(path))
            || ids.contains(ModelIdUtil.getInternalModelId(modelId.toString()));
    }

    /** Copies a published list into a set, dropping {@code null}/empty entries a malformed payload could carry. */
    private static Set<String> copy(@Nullable Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> copied = new LinkedHashSet<>(ids.size());
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                copied.add(id);
            }
        }
        return copied;
    }
}
