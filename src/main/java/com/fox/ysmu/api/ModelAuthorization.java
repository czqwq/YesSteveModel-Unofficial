package com.fox.ysmu.api;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedAuthModels;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.catalog.CatalogAccess;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

/**
 * The server-side authority for <em>which models a player may select</em> - the port of upstream's selection
 * predicate and of the two rules around it: {@code model/session/server/ServerModelSession.java:317-322}
 * ({@code canSelect}: public, or granted) and {@code :205-221} ({@code canRequest}: what may still be transferred
 * when {@code RestrictedAuth} is on). Upstream's {@code select} answers
 * {@code NOT_FOUND}/{@code UNAUTHORIZED}/{@code INVALID_REQUEST} and resets the selection to the intrinsic
 * default ({@code :147-180}); the port's single decision point is {@link com.fox.ysmu.network.message.SetModelAndTexture}'s
 * handler, which produces the same observable outcome (reject + reset + tell the player).
 * <p>
 * <b>Not to be confused with {@link ModelGuiApi}.</b> That class hands out a five-minute (viewer, target) grant for
 * opening a selection screen on one entity. This one decides whether a model may be <em>applied</em> at all.
 *
 * <h2>What "restricted" means, and why nothing is restricted by default</h2>
 * A model is restricted - {@link CatalogAccess#AUTHORIZED} - exactly when its effective (winning) source lives in the
 * {@code auth} scan root, {@code config/ysmu/auth} ({@link ServerModelManager#accessOf(ResourceLocation)},
 * upstream {@code model/catalog/snapshot/CatalogRecord.java:32-33}). Everything else is
 * {@link CatalogAccess#PUBLIC} and never consults the grant table. Upstream reaches the same default behaviour from
 * the other direction: its grant check is unconditional, but only its {@code auth} catalog root carries
 * {@code AccessPolicy.SESSION_AUTHORIZED} ({@code model/catalog/source/CatalogRootKind.java:5-7}), so on a fresh
 * install nothing is restricted and the feature is inert. The port has that same structure now, which is why it
 * needs <b>no</b> enable switch: an empty {@code config/ysmu/auth} means "no model is restricted", and an upgraded
 * server therefore behaves exactly as it did before this feature existed.
 * <p>
 * This replaced the port's own {@code Config.ENABLE_MODEL_AUTHORIZATION}: a global switch could only express
 * "every model needs a grant or none does", which is not upstream's mechanism and made the grant table useless in
 * its default position. Recorded in {@code .agent/phase53-model-authorization.md}.
 *
 * <h2>Where the grant table lives</h2>
 * Per player, in {@link ExtendedAuthModels} (the 1.7.10 EEP counterpart of upstream's {@code AuthModelsCapability}),
 * mutated by the {@code /ysm auth ...} family in {@code command/YsmCommand}. Read at the two places a model can be
 * applied on the server: the {@code SetModelAndTexture} packet handler and the revoke path of those commands.
 *
 * <h2>Deliberately not ported (recorded, not invented)</h2>
 * <ul>
 * <li><b>Catalog membership.</b> Upstream rejects a selection of an unknown model ({@code NOT_FOUND},
 * {@code ServerModelSession.java:158-162}). A server-side existence check is not safe in this port: a client may
 * legitimately hold models the server does not have - {@code /ysmclient load} registers locally scanned models "visible
 * only to you" ({@code command/CommandYsmClient}, {@code ClientModelManager}'s local loader) and selecting one sends its
 * id to the server, which stores it as that player's choice. Rejecting unknown ids would regress that documented
 * client-local path (the same failure shape {@code AGENTS.md} records for a texture id set that rejected a player's own
 * skin). An unknown id also has no source record, so {@link ServerModelManager#accessOf} answers PUBLIC for it and the
 * grant half cannot be used to reject it either.</li>
 * <li><b>Texture validation.</b> Upstream also answers {@code INVALID_REQUEST} when the requested texture is not one of
 * the model's textures ({@code :168-171}, {@code hasTexture} {@code :324-330}). The port has no server-side texture list
 * for a model ({@code ServerModelInfo} carries only a texture-name set used for cache plumbing), and the client's own
 * texture ladder already falls back. Not ported.</li>
 * <li><b>The presentation-only access level.</b> Upstream's {@code canRequest} lets an unauthorized player still fetch
 * a model's {@code PREVIEW} and {@code ICON} assets under {@code RestrictedAuth}, so its tile exists and is drawn
 * locked. This port's sync unit is the whole model (one encrypted cache file, or one OpenYSM 17 payload) and the
 * presentation metadata travels inside it, so there is no smaller unit to allow. With {@code RestrictedAuth} on, an
 * unauthorized model is therefore absent rather than locked, and the lock display (which the client computes from the
 * grant packet) applies to the default configuration where assets are shareable. Recorded for the owner; see
 * {@code .agent/phase53-model-authorization.md}.</li>
 * <li><b>Forced-selection memory of the mandatory flag.</b> Upstream's {@code ModelInfoCapability} also carries
 * {@code mandatory}, which makes its sync reach a client without the mod channel
 * ({@code event/CapabilityEvent.java:138,169,200}; {@code client/compat/top/TopPlugin.java:46}). This port's sync has no
 * such gate, so the flag would be dead state; only {@code ignoreGrants} is ported (see
 * {@link ExtendedModelInfo#setCommandSelection}).</li>
 * </ul>
 */
public final class ModelAuthorization {

    private ModelAuthorization() {}

    /**
     * The whole selection decision, as a pure function so a unit JVM can pin it (no player, no server).
     *
     * <p>Upstream's rule is
     * {@code record != null && (record.entry().access() == CatalogAccess.PUBLIC || grants.contains(modelId))}
     * ({@code ServerModelSession.java:317-322}). The two extra {@code true}s below are upstream's too, just handled at
     * its call site instead of inside that method: {@code select} accepts {@code Selection.IntrinsicDefault}
     * unconditionally ({@code :153-156}, resolved without a grant check by
     * {@code capability/ModelSelectionService.java:15-31}), and a selection of "no model" is not a grant decision at
     * all. Folding them in here keeps this class the single choke point no caller can forget.</p>
     *
     * @param grants the player's granted model ids, or {@code null} when the table is <em>absent</em>. Absent is not
     *        the same as empty: an absent table means this build did not attach it (it is registered from
     *        {@code EntityEvent.EntityConstructing}, see {@link ExtendedAuthModels#register}), and answering "deny"
     *        there would lock every player out of every restricted model because of a wiring fault. A correct build
     *        always has a table.
     * @param modelId the model about to be applied; {@code null} means "no model", which is not a grant decision
     * @param access whether that model is public or needs a grant ({@link ServerModelManager#accessOf(ResourceLocation)})
     * @return whether the model may be applied
     */
    public static boolean canSelect(@Nullable Set<ResourceLocation> grants, @Nullable ResourceLocation modelId,
        CatalogAccess access) {
        if (modelId == null) {
            return true;
        }
        if (modelId.equals(ExtendedModelInfo.defaultModelId())) {
            // The intrinsic default is the fallback every refusal resets to, so gating it would deadlock the reset.
            return true;
        }
        if (access == CatalogAccess.PUBLIC) {
            return true;
        }
        if (grants == null) {
            return true;
        }
        return grants.contains(modelId);
    }

    /**
     * Whether the model's <em>assets</em> may be transferred to a player who is asking for them - upstream's
     * {@code ServerModelSession.canRequest(modelId, kind, restrictedAuth)} ({@code :205-221}), reduced to this port's
     * two asset kinds.
     *
     * <p>Upstream's shape is: everything is allowed while {@code RestrictedAuth} is off, and while it is on,
     * {@code PUBLIC || granted} is allowed, and otherwise only {@code PREVIEW}/{@code ICON} - plus
     * {@code CHUNK} for a model an operator force-set on this player ({@code ignoresGrantsFor}). The port has no
     * separate preview/icon transfer (its previews ride inside the model payload - see the class javadoc), so the
     * kinds collapse: the payload itself is the {@code CHUNK} case, and the forced-selection exception stays.</p>
     *
     * @param restrictedAuth the server's {@code RestrictedAuth} switch ({@link com.fox.ysmu.Config#RESTRICTED_AUTH})
     * @param grants the player's granted ids, or {@code null} for "table not attached" (see {@link #canSelect})
     * @param modelId the model whose bytes are about to be sent
     * @param ignoresGrants whether this player's current selection was force-set by an operator
     *        ({@link ExtendedModelInfo#ignoresGrantsFor(ResourceLocation)})
     * @return whether the bytes may be sent
     */
    public static boolean canRequest(boolean restrictedAuth, @Nullable Set<ResourceLocation> grants,
        @Nullable ResourceLocation modelId, boolean ignoresGrants) {
        if (!restrictedAuth) {
            // Upstream's default: assets are shareable, which is what lets every client render other players' models.
            return true;
        }
        if (canSelect(grants, modelId, ServerModelManager.accessOf(modelId))) {
            return true;
        }
        return ignoresGrants;
    }

    /** The grant table of one player, or {@code null} when this build did not attach it. */
    @Nullable
    public static Set<ResourceLocation> grantsOf(EntityPlayer player) {
        ExtendedAuthModels auth = ExtendedAuthModels.get(player);
        return auth == null ? null : auth.getAuthModels();
    }

    /** Whether {@code modelId} needs a grant at all ({@link ServerModelManager#accessOf}); what the lock display asks. */
    public static boolean requiresAuth(@Nullable ResourceLocation modelId) {
        return ServerModelManager.accessOf(modelId) == CatalogAccess.AUTHORIZED;
    }

    /**
     * Whether {@code modelId} may be applied to {@code player} right now; what the selection point calls. The access
     * half comes from the live registration table, so a {@code /ysm reload} that moves a model between roots is
     * reflected on the next selection.
     */
    public static boolean maySelect(EntityPlayer player, @Nullable ResourceLocation modelId) {
        return canSelect(grantsOf(player), modelId, ServerModelManager.accessOf(modelId));
    }

    /**
     * Every model id this server can hand to a client, in the identity the client sends back on selection: the
     * {@code CACHE_NAME_INFO} keys are internal model ids ({@code model/format/FolderFormat.java:69-72},
     * {@code OpenYsmFormat.java:69-89}) and the client rebuilds exactly {@code ysmu:<internal id>} from its synced
     * {@code ModelData} ({@code client/ClientModelManager.java:1202-1204}). Used by {@code /ysm auth ... all}.
     */
    public static Set<ResourceLocation> allRegisteredModelIds() {
        return allRegisteredModelIds(ServerModelManager.CACHE_NAME_INFO.keySet());
    }

    /** The registered-id half of {@link #allRegisteredModelIds()}, split out so it is testable without a server. */
    static Set<ResourceLocation> allRegisteredModelIds(Set<String> registeredIds) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (String internalId : registeredIds) {
            ids.add(new ResourceLocation(ysmu.MODID, internalId));
        }
        return ids;
    }

    /**
     * Resolves a {@code /ysm auth ... add|remove <model>} argument to the model id the client would send, or
     * {@code null} when nothing registered matches.
     * <p>
     * Upstream resolves a catalog path and refuses an unknown one with "Unknown or ambiguous model path"
     * ({@code command/sub/AuthCommand.java:45-51}). The port's models have no catalog path, so the accepted spellings
     * are the ones a server operator can read off the config directory and the logs: the internal model id itself
     * ({@code wine_fox/01_taisho_maid}), a leading {@code ysmu:} prefix as the packet carries it, and the model's own
     * display name - which is what a {@code _name_<hex>} id decodes to for a directory whose name needs encoding
     * ({@code util/ModelIdUtil.java:64-105}). The port's ids are injective (a name is either kept as-is or becomes
     * {@code _name_<utf8 hex>}, and a name that already looks encoded is re-encoded), so upstream's "ambiguous" case
     * cannot arise here; the refusal message keeps upstream's wording because that is the text an operator looks up.
     */
    @Nullable
    public static ResourceLocation resolveModelArgument(@Nullable String argument) {
        return resolveModelArgument(argument, ServerModelManager.CACHE_NAME_INFO.keySet());
    }

    /** The registry half of {@link #resolveModelArgument(String)}, split out so it is testable without a server. */
    @Nullable
    static ResourceLocation resolveModelArgument(@Nullable String argument, Set<String> registeredIds) {
        if (argument == null) {
            return null;
        }
        String name = argument.trim();
        if (name.isEmpty()) {
            return null;
        }
        if (name.startsWith(ysmu.MODID + ":")) {
            name = name.substring(ysmu.MODID.length() + 1);
        }
        if (name.isEmpty()) {
            return null;
        }
        if (registeredIds.contains(name)) {
            return new ResourceLocation(ysmu.MODID, name);
        }
        String internalId = ModelIdUtil.getInternalModelId(name);
        if (registeredIds.contains(internalId)) {
            return new ResourceLocation(ysmu.MODID, internalId);
        }
        for (String candidate : registeredIds) {
            if (name.equals(ModelIdUtil.getModelDisplayName(candidate))) {
                return new ResourceLocation(ysmu.MODID, candidate);
            }
        }
        return null;
    }

}
