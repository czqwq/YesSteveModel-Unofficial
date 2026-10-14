package com.fox.ysmu.api;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.eep.ExtendedAuthModels;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

/**
 * The server-side authority for <em>which models a player may select</em> - the port of the access half of upstream's
 * model session: {@code model/session/server/ServerModelSession.java:317-322} ({@code canSelect}: a model is
 * selectable when it is public or granted) checked from {@code :147-180} ({@code select} answers
 * {@code NOT_FOUND}/{@code UNAUTHORIZED} and resets the selection to the intrinsic default).
 * <p>
 * <b>Not to be confused with {@link ModelGuiApi}.</b> That class hands out a five-minute (viewer, target) grant for
 * opening a selection screen on one entity. This one decides whether a model may be <em>applied</em> at all.
 *
 * <h2>Where the grant table lives</h2>
 * Per player, in {@link ExtendedAuthModels} (the 1.7.10 EEP counterpart of upstream's {@code AuthModelsCapability}),
 * mutated by the {@code /ysm auth ...} family in {@code command/YsmCommand}. Read at the two places a model can be
 * applied on the server: the {@code SetModelAndTexture} packet handler and the revoke path of those commands.
 *
 * <h2>"Default allow": why a switch, and why this switch</h2>
 * Upstream never asks this question: its grant check is <em>unconditional</em> but only the {@code auth} catalog root
 * carries {@code AccessPolicy.SESSION_AUTHORIZED} ({@code model/catalog/source/CatalogRootKind.java:5-7}), so on a
 * fresh install - an empty {@code auth} root - nothing is restricted and the feature is inert. That structure does not
 * exist here: every model is discovered from {@code config/ysmu/custom} and {@code config/ysmu/builtin}
 * ({@code model/ServerModelManager.java:50-63}) and this port has no catalog root kind at all
 * ({@code grep AccessPolicy} over {@code src/main/java/com/fox/ysmu} = 0). Something therefore has to name the
 * restricted set, and the task's risk control requires that an upgraded 1.7.10 server does not suddenly lock every
 * player out of every model.
 * <p>
 * The chosen answer is a single server switch, {@link Config#ENABLE_MODEL_AUTHORIZATION}, <b>off by default</b>: when
 * it is off this class answers "yes" for every model and every player, so the packet path and the commands behave
 * exactly as before. Upstream keeps its authorization posture in exactly that place - a default-off server config
 * ({@code config/ServerConfig.java:13,56-57} {@code RestrictedAuth = false}) - although its switch relaxes the
 * <em>asset download</em> gate and never the selection gate; the port has no per-player sync gate to relax
 * (see the finding below), so the switch is used where the port does have a decision to make.
 * <p>
 * The rejected alternative was "an empty grant table means allow": it needs no config, but it inverts the meaning of
 * the commands - {@code clear} would <em>release</em> a player instead of restricting them, and removing the last
 * grant would silently restore full access. That is the opposite of upstream's {@code clear}
 * ({@code command/sub/AuthCommand.java:83-102}: after clearing, a session-authorized selection is reset because it is
 * no longer selectable) and it is a security inversion, so exactly one mechanism is used: the switch.
 *
 * <h2>The built-in half: PUBLIC by upstream's own rule</h2>
 * Upstream's answer is not only "the auth root is restricted": every record outside that root is
 * {@code CatalogAccess.PUBLIC} ({@code model/catalog/snapshot/CatalogRecord.java:32-33}: {@code access = rootKind ==
 * AUTH ? AUTHORIZED : PUBLIC}), and {@code canSelect} accepts a public record without consulting the grant table
 * ({@code ServerModelSession.java:317-322}). The port mirrors that half too: a model the server is serving from the
 * built-in root and that the shipped {@code assets/ysmu/builtin-index.json} contract declares is selectable with the
 * switch on and an empty grant table ({@code ServerModelManager.isBuiltinModel}). The restricted set the switch names is
 * therefore everything else - {@code config/ysmu/custom} and directories inside the built-in root the contract does not
 * declare.
 *
 * <h2>Deliberately not ported (recorded, not invented)</h2>
 * <ul>
 * <li><b>Catalog membership.</b> Upstream rejects a selection of an unknown model ({@code NOT_FOUND},
 * {@code ServerModelSession.java:158-162}). A server-side existence check is not safe in this port: a client may
 * legitimately hold models the server does not have - {@code /ysmclient load} registers locally scanned models "visible
 * only to you" ({@code command/CommandYsmClient}, {@code ClientModelManager}'s local loader) and selecting one sends its
 * id to the server, which stores it as that player's choice. Rejecting unknown ids would regress that documented
 * client-local path (the same failure shape {@code AGENTS.md} records for a texture id set that rejected a player's own
 * skin). The grant half is what this port can enforce without that regression.</li>
 * <li><b>Texture validation.</b> Upstream also answers {@code INVALID_REQUEST} when the requested texture is not one of
 * the model's textures ({@code :168-171}, {@code hasTexture} {@code :324-330}). The port has no server-side texture list
 * for a model ({@code ServerModelInfo} carries only a texture-name set used for cache plumbing), and the client's own
 * texture ladder already falls back. Not ported.</li>
 * <li><b>Pushing grants to clients.</b> Upstream refreshes the player's grant set over its session protocol
 * ({@code SessionProtocolHandler.refreshGrants:339-388}) so its GUI can lock entries. This port sends nothing, because
 * its model <em>files</em> are not gated per player either: adding a cosmetic lock would need a new clientbound packet
 * that reaches old peers on login and would therefore force a {@code NETWORK_PROTOCOL} bump, while the server would
 * still hand every client every model. The server is the authority; a rejected selection simply does not take effect.
 * </li>
 * </ul>
 */
public final class ModelAuthorization {

    private ModelAuthorization() {}

    /** Whether {@code /ysm auth}'s restrictions are in force. Off by default; see the class javadoc. */
    public static boolean isEnabled() {
        return Config.ENABLE_MODEL_AUTHORIZATION;
    }

    /**
     * The whole access decision, as a pure function so a unit JVM can pin it (no player, no server).
     *
     * @param enabled whether authorization is in force ({@link #isEnabled()})
     * @param grants the player's granted model ids, or {@code null} when the table is <em>absent</em>. Absent is not
     *        the same as empty: an absent table means this build did not attach it (it is registered from
     *        {@code EntityEvent.EntityConstructing}, see {@link ExtendedAuthModels#register}), and answering "deny"
     *        there would be the mass lock-out the switch exists to prevent. A correct build always has a table.
     * @param modelId the model about to be applied; {@code null} means "no model", which is not a grant decision
     * @param intrinsicDefault the id of the server's default model ({@link ExtendedModelInfo#defaultModelId()}), which
     *        is always selectable: upstream accepts its {@code Selection.IntrinsicDefault} unconditionally
     *        ({@code ServerModelSession.java:153-156}) and resolves it without a grant check
     *        ({@code capability/ModelSelectionService.java:15-31}). It is the fallback this class resets players to, so
     *        it must not itself be gated.
     * @param builtin whether {@code modelId} is served from the built-in root and declared by the shipped contract
     *        ({@code ServerModelManager.isBuiltinModel}). Upstream marks every non-{@code auth} record
     *        {@code CatalogAccess.PUBLIC} and answers {@code canSelect} from {@code PUBLIC || granted}
     *        ({@code CatalogRecord.java:32-33}, {@code ServerModelSession.java:317-322}), so a built-in model is
     *        selectable with an empty grant table. See the class javadoc.
     * @return whether the model may be applied
     */
    public static boolean canSelect(boolean enabled, @Nullable Set<ResourceLocation> grants,
        @Nullable ResourceLocation modelId, @Nullable ResourceLocation intrinsicDefault, boolean builtin) {
        if (!enabled) {
            // Default allow: with the switch off nothing changes for an existing server.
            return true;
        }
        if (modelId == null) {
            return true;
        }
        if (modelId.equals(intrinsicDefault)) {
            return true;
        }
        if (builtin) {
            // Upstream's built-in root: CatalogAccess.PUBLIC, so no grant is ever required for it.
            return true;
        }
        if (grants == null) {
            return true;
        }
        return grants.contains(modelId);
    }

    /**
     * The server-registry entry point: the same decision with {@code builtin} resolved from the live registration table
     * ({@link ServerModelManager#isBuiltinModel(ResourceLocation)}), so callers on the server - the
     * {@code SetModelAndTexture} handler and the {@code /ysm auth} revoke path - do not have to know how "built-in" is
     * decided. The pure overload above is what the unit tests pin.
     */
    public static boolean canSelect(boolean enabled, @Nullable Set<ResourceLocation> grants,
        @Nullable ResourceLocation modelId, @Nullable ResourceLocation intrinsicDefault) {
        return canSelect(enabled, grants, modelId, intrinsicDefault, ServerModelManager.isBuiltinModel(modelId));
    }

    /** The grant table of one player, or {@code null} when this build did not attach it. */
    @Nullable
    public static Set<ResourceLocation> grantsOf(EntityPlayer player) {
        ExtendedAuthModels auth = ExtendedAuthModels.get(player);
        return auth == null ? null : auth.getAuthModels();
    }

    /** Whether {@code modelId} may be applied to {@code player} right now; what the selection point calls. */
    public static boolean maySelect(EntityPlayer player, @Nullable ResourceLocation modelId) {
        return canSelect(isEnabled(), grantsOf(player), modelId, ExtendedModelInfo.defaultModelId());
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
