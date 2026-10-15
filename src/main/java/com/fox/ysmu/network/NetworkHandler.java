package com.fox.ysmu.network;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Tags;
import com.fox.ysmu.network.message.*;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class NetworkHandler {

    public static final String PROTOCOL_VERSION = Tags.VERSION;

    /**
     * Wire format version, exchanged by {@link com.fox.ysmu.network.message.HandshakeMessage}. Bump it
     * whenever an existing packet's layout changes; 1.7.10's SimpleNetworkWrapper does no negotiation of its
     * own, so a mismatched client would otherwise decode packets with the wrong layout.
     * <p>
     * History:
     * <ul>
     * <li>1: initial wire layout.</li>
     * <li>2: the OpenYSM 17-protocol envelope ({@code C2S/S2CModelSyncPayload17}) and its feedback
     * ({@code C2SCompleteFeedback17}) gained a leading {@code int sessionId} (N-11), and the trailing varint of
     * packet 03 now carries the legacy model count. An old peer would decode those packets at the wrong offsets,
     * so the handshake has to reject the pair. Version 2 also introduces the new serverbound id 98
     * ({@code RevokeModelGuiGrant}, NF-01), which only exists in this build.</li>
     * <li>3: the roaming-variable channel, serverbound id 99 ({@code C2SRoamingChanges}) and clientbound id 20
     * ({@code S2CRoamingState}). Sending either to an older peer would hit FML's unknown-discriminator path, which
     * disconnects rather than ignores, so the pair has to fail the handshake instead. See
     * {@code .agent/phase15-roaming-variables.md}.</li>
     * <li>4: the three {@code /ysm} subcommand packets of {@code .agent/phase39-ysm-subcommands.md}, clientbound ids
     * 32 ({@code SyncGamePath}), 33 ({@code SetWelcomeConfig}) and 34 ({@code ShowBufferInfo}). These are the first
     * additions that a command <em>sends</em> on demand, so the same unknown-discriminator rule applies as in
     * version 3: a peer that does not know the id is disconnected by FML, and the handshake has to reject the pair
     * first with a readable message. (The earlier debug ids 23/26/27/31 arrived with senders in
     * {@code .agent/phase35-debug-command-senders.md} without a bump; that omission is closed by this entry rather
     * than repeated.)</li>
     * <li>5: serverbound id 7 ({@code SetPlayAnimation}) carries the animation <b>name</b> instead of a slot index,
     * so the extra-animation wheel can reach the named keys a YSM 2.3.0+ pack declares (LaPluma 2.5.3: {@code HI},
     * {@code WHAT}, {@code Bird}, ...). The old payload was a fixed four-byte int and the new one is a
     * length-prefixed UTF-8 string, so an old peer would read the string's length varint as the index and the
     * following bytes as whatever came next: the handshake has to reject the pair rather than let it decode. The
     * legacy {@code extra0..extra7} vocabulary is unchanged - those senders now spell the same name the server used
     * to concatenate.</li>
     * <li>6: clientbound id 35 ({@code S2CVehicleModelState}), the vehicle half of upstream's
     * {@code VehicleModelState} (its id 27). The server sends it when a player mounts a vehicle, so it reaches a peer
     * that does not know the id on an ordinary action rather than on a command - FML disconnects that peer on an
     * unknown discriminator, so the handshake has to reject the pair first with a readable message, exactly as
     * versions 3 and 4 do. Nothing existing is renumbered or reused; 35 is the first free clientbound id above the
     * highest in use (34).</li>
     * <li>7: serverbound id 101/clientbound id 36 carry full roulette expressions plus the target model identity.
     * The server authorizes only the sender's current model; clients reject stale world/generation/model contexts.
     * This is distinct from numeric ysm.sync arguments. Unknown discriminators must fail the handshake before use.</li>
     * <li>8: clientbound id 37 ({@code S2CModelGrants}), the authorization facts of upstream's session publication
     * ({@code network/forge/SessionCollectionPublication.java:71-77} for the grants, {@code :749-759} for each entry's
     * access): the restricted model ids and the player's granted ids. It is sent on login and after every
     * {@code /ysm auth} mutation, so a peer that does not know the id meets it on an ordinary action rather than on a
     * command - FML disconnects that peer on the unknown discriminator, so the handshake has to reject the pair first,
     * exactly as versions 3, 4, 6 and 7 do. Nothing existing is renumbered or reused; 37 is the first free clientbound
     * id above the highest in use (36, {@code S2CExecuteRouletteExpression}).</li>
     * </ul>
     */
    public static final int NETWORK_PROTOCOL = 8;

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("ysmu_network");

    // Packet ids are part of the wire protocol. Add new ids; do not renumber existing ones.
    //
    // N-10 note (read before touching any packet):
    //  * A packet whose fromBytes()/onMessage() throws is NOT ignored. FMLProxyPacket.processPacket catches
    //    Throwable and calls NetworkDispatcher.rejectHandshake -> kickWithMessage, so a malformed payload or an
    //    unknown discriminator disconnects the player. Every decoder therefore validates lengths against
    //    readableBytes() and soft-fails (drop the packet + warn) instead of throwing, and every handler body is
    //    wrapped in try/catch. SimpleNetworkWrapper does no version negotiation (see NETWORK_PROTOCOL above).
    //  * DeferredWork only protects the body of a handler that explicitly hops to the game thread
    //    (HandshakeMessage / OpenModelGuiMessage / SetModelAndTexture / SetNpcModelAndTexture); it does not cover
    //    fromBytes() and it does not cover handlers that run inline, so it is not a substitute for the soft-fail
    //    and try/catch rules.
    private static final int SERVERBOUND_SYNC_MODEL_FILES = 0;
    private static final int SERVERBOUND_SET_MODEL_AND_TEXTURE = 5;
    private static final int SERVERBOUND_SET_PLAY_ANIMATION = 7;
    private static final int SERVERBOUND_SET_STAR_MODEL = 9;
    private static final int SERVERBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17 = 14;
    private static final int SERVERBOUND_OPENYSM_VERSION_CHECK_17 = 15;
    private static final int SERVERBOUND_OPENYSM_COMPLETE_FEEDBACK_17 = 16;
    private static final int SERVERBOUND_HANDSHAKE = 97;
    /**
     * NF-01: client to server, drop the model-selection grant the server handed out for one entity. The client
     * sends it from {@code PlayerModelScreen.onGuiClosed} ({@code client/gui/PlayerModelScreen.java:512-516}) when
     * {@code ModelSelectionTarget.getGrantId() != -1}; the server releases only the sender's own grant, so a forged
     * entity id cannot revoke somebody else's. The id is appended after 97; nothing existing is renumbered.
     */
    private static final int SERVERBOUND_REVOKE_MODEL_GUI_GRANT = 98;
    /**
     * The roaming-variable channel (see {@code .agent/phase15-roaming-variables.md}): client to server reports a
     * settings change, server to client answers with the authoritative map. Appended after 98 and 19; nothing
     * existing is renumbered.
     */
    // Version 7: full roulette programs use their own self-authorized channel, not ysm.sync.
    // Unknown FML discriminators disconnect old peers, so handshake rejects older protocol versions.
    private static final int SERVERBOUND_SUBMIT_ROULETTE_EXPRESSION = 101;
    private static final int CLIENTBOUND_EXECUTE_ROULETTE_EXPRESSION = 36;
    private static final int SERVERBOUND_ROAMING_CHANGES = 99;
    private static final int CLIENTBOUND_ROAMING_STATE = 20;

    private static final int CLIENTBOUND_SEND_MODEL_FILE = 1;
    private static final int CLIENTBOUND_REQUEST_SYNC_MODEL = 2;
    private static final int CLIENTBOUND_REQUEST_LOAD_MODEL = 3;
    private static final int CLIENTBOUND_SYNC_MODEL_INFO = 4;
    private static final int CLIENTBOUND_SYNC_STAR_MODELS = 8;
    /**
     * N-09 (deprecated): id 10 has no sender anywhere in this repository (the model-management GUI it belonged
     * to was never wired on 1.7.10). The id stays reserved and must NOT be renumbered or reused; the handler is a
     * documented no-op. Re-wiring it needs a sender plus a client screen, which is a new feature, not a fix.
     */
    private static final int CLIENTBOUND_REQUEST_SERVER_MODEL_INFO = 10;
    private static final int CLIENTBOUND_SYNC_PLAYER_MOTION_STATE = 11;
    /**
     * N-09 (deprecated): id 12 has no sender either; {@code UploadManager} is an unwired placeholder for
     * OpenYSM's upload feature. Keep the id reserved, do NOT renumber or reuse it, and do not grow the handler
     * into a real upload path here (see the ExecPlan's Decision Log).
     */
    private static final int CLIENTBOUND_COMPLETE_FEEDBACK = 12;
    private static final int CLIENTBOUND_SEND_MODEL_PASSWORD = 13;
    private static final int CLIENTBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17 = 17;
    private static final int CLIENTBOUND_OPENYSM_VERSION_CHECK_17 = 18;
    private static final int CLIENTBOUND_SEND_MODEL_FILE_CHUNK = 19;

    public static final int OPEN_NPC_MODEL_GUI = 93;
    public static final int SET_NPC_MODEL_ID = 94;
    public static final int SYNC_NPC_DATA = 95;
    public static final int UPDATE_NPC_DATA = 96;
    /**
     * A-16: a pack's {@code ysm.sync(...)} call and the server's echo of it
     * ({@code C2SMolangSync} / {@code S2CMolangSync}). Two new ids, as the repository rule requires - existing numbers
     * are part of the wire protocol and are never renumbered; 21 and 100 were free.
     */
    private static final int CLIENTBOUND_MOLANG_SYNC = 21;
    private static final int SERVERBOUND_EMIT_MOLANG_SYNC = 100;

    /**
     * A-17: the client half of {@code /ysm playsound}. Model sound bytes and the {@code SoundSystem} only exist on
     * the client, so the server forwards the request instead of acting on it. Appended after 21; nothing existing is
     * renumbered.
     */
    private static final int CLIENTBOUND_PLAY_SOUND = 30;

    /**
     * The Molang debug-overlay cluster (see {@code .agent/phase33-m4-debug-overlay-ported.md}). Four clientbound ids,
     * taken verbatim from the reference branch
     * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../network/NetworkHandler.java:44-53}), which the audit
     * {@code .agent/phase31-m4-debug-overlay.md} verified are free here: 23, 26, 27 and 31. Nothing existing is
     * renumbered or reused. The consumers are client-only state (MolangParser is a client singleton, the overlay
     * touches {@code net.minecraft.client.*} and LWJGL), so each is registered {@code Side.CLIENT} like its
     * neighbours.
     * <p>
     * All four are behind {@code Config.DEBUG_MOLANG_OVERLAY} (default off); registration itself is unconditional
     * because the ids are part of the wire protocol, but no handler does anything while the switch is off.
     * <p>
     * This paragraph used to end with "and this tree registers no sender for them at all. That last point is why
     * {@link #NETWORK_PROTOCOL} is not bumped". The senders arrived in {@code .agent/phase35-debug-command-senders.md}
     * without a bump, so an older peer could have been disconnected by {@code /ysm debug} on an id it does not know;
     * version 4 (see {@link #NETWORK_PROTOCOL}) now covers both that and the three packets below.
     */
    private static final int CLIENTBOUND_QUERY_MOLANG_VAR = 23;
    private static final int CLIENTBOUND_EVAL_MOLANG = 26;
    private static final int CLIENTBOUND_RESET_MOLANG = 27;
    private static final int CLIENTBOUND_SET_DEBUG_OVERLAY = 31;

    /**
     * The three {@code /ysm} subcommand packets of {@code .agent/phase39-ysm-subcommands.md}: {@code /ysm
     * setgamepath} ({@link com.fox.ysmu.network.message.SyncGamePath}), {@code /ysm welcome}
     * ({@link com.fox.ysmu.network.message.SetWelcomeConfig}) and {@code /ysm buffer}
     * ({@link com.fox.ysmu.network.message.ShowBufferInfo}).
     * <p>
     * The reference branch numbers these 20/21/22, and both of its first two numbers are already spent here on a
     * different pairing (20 is this tree's {@code S2CRoamingState}, 21 its {@code S2CMolangSync}), so the reference's
     * numbers cannot be preserved. The three ids are therefore appended after the highest clientbound id in use (31):
     * 32, 33 and 34, in the order setgamepath / welcome / buffer. The ids that are free below them - 22, 24, 25, 28
     * and 29 - are left alone on purpose: 22 is the reference's {@code ShowBufferInfo}, 24/25 its
     * {@code S2CSyncIndexChunk17} / {@code SpawnParticleCommand} and 29 its {@code S2CMolangSync}, all of which a
     * later batch may port under their own numbers. Nothing existing is renumbered or reused.
     * <p>
     * All three are server to client: the command forwards work that only the client can do (update its own config
     * and asset provider, toggle its own welcome flag, read its own JVM's buffer pools), exactly like
     * {@code S2CPlaySound} / {@code S2CSetDebugOverlay}. Each handler is registered {@link Side#CLIENT} and each
     * opens with the same receiving-side guard.
     */
    private static final int CLIENTBOUND_SYNC_GAME_PATH = 32;
    private static final int CLIENTBOUND_SET_WELCOME_CONFIG = 33;
    private static final int CLIENTBOUND_SHOW_BUFFER_INFO = 34;
    /**
     * The vehicle model state (task-17), appended above the highest clientbound id in use: the port's counterpart of
     * upstream's {@code VehicleModelState} (its id 27). See {@link #NETWORK_PROTOCOL} version 6 for why this is a new
     * id rather than a reuse of the NPC channel.
     */
    private static final int CLIENTBOUND_VEHICLE_MODEL_STATE = 35;
    /**
     * The authorization facts (task batch 4): which models are restricted and which of them the receiving player may
     * wear. Appended above the highest clientbound id in use (36, {@code S2CExecuteRouletteExpression}); see
     * {@link #NETWORK_PROTOCOL} version 8 for why an older peer has to fail the handshake rather than receive it.
     * <p>
     * The sender is {@link com.fox.ysmu.network.ModelGrantSync} (login and every {@code /ysm auth} mutation); the
     * client half is {@link com.fox.ysmu.client.ClientModelAccess}.
     */
    private static final int CLIENTBOUND_MODEL_GRANTS = 37;

    public static void init() {
        registerServerboundMessages();
        registerClientboundMessages();
        initBukkit();
    }

    private static void registerServerboundMessages() {
        CHANNEL.registerMessage(
            SyncModelFiles.Handler.class,
            SyncModelFiles.class,
            SERVERBOUND_SYNC_MODEL_FILES,
            Side.SERVER);
        CHANNEL.registerMessage(
            SetModelAndTexture.Handler.class,
            SetModelAndTexture.class,
            SERVERBOUND_SET_MODEL_AND_TEXTURE,
            Side.SERVER);
        CHANNEL.registerMessage(
            SetPlayAnimation.Handler.class,
            SetPlayAnimation.class,
            SERVERBOUND_SET_PLAY_ANIMATION,
            Side.SERVER);
        CHANNEL.registerMessage(
            SetStarModel.Handler.class,
            SetStarModel.class,
            SERVERBOUND_SET_STAR_MODEL,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SModelSyncPayload17.Handler.class,
            C2SModelSyncPayload17.class,
            SERVERBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SVersionCheck17.Handler.class,
            C2SVersionCheck17.class,
            SERVERBOUND_OPENYSM_VERSION_CHECK_17,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SCompleteFeedback17.Handler.class,
            C2SCompleteFeedback17.class,
            SERVERBOUND_OPENYSM_COMPLETE_FEEDBACK_17,
            Side.SERVER);
        CHANNEL.registerMessage(
            HandshakeMessage.Handler.class,
            HandshakeMessage.class,
            SERVERBOUND_HANDSHAKE,
            Side.SERVER);
        // NF-01: the server-side half of the revoke channel. See SERVERBOUND_REVOKE_MODEL_GUI_GRANT above.
        CHANNEL.registerMessage(
            RevokeModelGuiGrant.Handler.class,
            RevokeModelGuiGrant.class,
            SERVERBOUND_REVOKE_MODEL_GUI_GRANT,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SRoamingChanges.Handler.class,
            C2SRoamingChanges.class,
            SERVERBOUND_ROAMING_CHANGES,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SSubmitRouletteExpression.Handler.class,
            C2SSubmitRouletteExpression.class,
            SERVERBOUND_SUBMIT_ROULETTE_EXPRESSION,
            Side.SERVER);
    }

    private static void registerClientboundMessages() {
        CHANNEL.registerMessage(
            S2CExecuteRouletteExpression.Handler.class,
            S2CExecuteRouletteExpression.class,
            CLIENTBOUND_EXECUTE_ROULETTE_EXPRESSION,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SendModelFile.Handler.class,
            SendModelFile.class,
            CLIENTBOUND_SEND_MODEL_FILE,
            Side.CLIENT);
        CHANNEL.registerMessage(
            RequestSyncModel.Handler.class,
            RequestSyncModel.class,
            CLIENTBOUND_REQUEST_SYNC_MODEL,
            Side.CLIENT);
        CHANNEL.registerMessage(
            RequestLoadModel.Handler.class,
            RequestLoadModel.class,
            CLIENTBOUND_REQUEST_LOAD_MODEL,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SyncModelInfo.Handler.class,
            SyncModelInfo.class,
            CLIENTBOUND_SYNC_MODEL_INFO,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SyncStarModels.Handler.class,
            SyncStarModels.class,
            CLIENTBOUND_SYNC_STAR_MODELS,
            Side.CLIENT);
        // CU-19 (network half): RequestServerModelInfo (id 10) is unregistered here on purpose. It had no sender
        // anywhere in the repository (its model-management GUI was never wired on 1.7.10) and t29 removed the last
        // trace on the client (ModelInfoButton). The id constant above stays reserved with its deprecation note:
        // ids are part of the wire protocol and must never be renumbered or reused.
        CHANNEL.registerMessage(
            SyncPlayerMotionState.Handler.class,
            SyncPlayerMotionState.class,
            CLIENTBOUND_SYNC_PLAYER_MOTION_STATE,
            Side.CLIENT);
        // N-09 (deprecated, id 12 kept as-is): registered for wire compatibility but never sent - see the id
        // constant above. UploadManager stays an unwired placeholder.
        CHANNEL.registerMessage(
            CompleteFeedback.Handler.class,
            CompleteFeedback.class,
            CLIENTBOUND_COMPLETE_FEEDBACK,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SendModelPassword.Handler.class,
            SendModelPassword.class,
            CLIENTBOUND_SEND_MODEL_PASSWORD,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CModelSyncPayload17.Handler.class,
            S2CModelSyncPayload17.class,
            CLIENTBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CVersionCheck17.Handler.class,
            S2CVersionCheck17.class,
            CLIENTBOUND_OPENYSM_VERSION_CHECK_17,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SendModelFileChunk.Handler.class,
            SendModelFileChunk.class,
            CLIENTBOUND_SEND_MODEL_FILE_CHUNK,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CRoamingState.Handler.class,
            S2CRoamingState.class,
            CLIENTBOUND_ROAMING_STATE,
            Side.CLIENT);
        // A-17: the client half of /ysm playsound. See CLIENTBOUND_PLAY_SOUND above for why the server forwards.
        CHANNEL.registerMessage(
            S2CPlaySound.Handler.class,
            S2CPlaySound.class,
            CLIENTBOUND_PLAY_SOUND,
            Side.CLIENT);
        // The Molang debug-overlay cluster. See the id constants above for the reference branch they come from and
        // why the ids were free; each handler is a no-op while Config.DEBUG_MOLANG_OVERLAY is off.
        CHANNEL.registerMessage(
            PacketQueryMolangVar.Handler.class,
            PacketQueryMolangVar.class,
            CLIENTBOUND_QUERY_MOLANG_VAR,
            Side.CLIENT);
        CHANNEL.registerMessage(
            PacketEvalMolang.Handler.class,
            PacketEvalMolang.class,
            CLIENTBOUND_EVAL_MOLANG,
            Side.CLIENT);
        CHANNEL.registerMessage(
            PacketResetMolang.Handler.class,
            PacketResetMolang.class,
            CLIENTBOUND_RESET_MOLANG,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CSetDebugOverlay.Handler.class,
            S2CSetDebugOverlay.class,
            CLIENTBOUND_SET_DEBUG_OVERLAY,
            Side.CLIENT);
        // The /ysm subcommand packets. See CLIENTBOUND_SYNC_GAME_PATH above for the ids and why they are appended.
        CHANNEL.registerMessage(
            SyncGamePath.Handler.class,
            SyncGamePath.class,
            CLIENTBOUND_SYNC_GAME_PATH,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SetWelcomeConfig.Handler.class,
            SetWelcomeConfig.class,
            CLIENTBOUND_SET_WELCOME_CONFIG,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ShowBufferInfo.Handler.class,
            ShowBufferInfo.class,
            CLIENTBOUND_SHOW_BUFFER_INFO,
            Side.CLIENT);
        // The vehicle model state. See CLIENTBOUND_VEHICLE_MODEL_STATE above for the id and the protocol bump.
        CHANNEL.registerMessage(
            S2CVehicleModelState.Handler.class,
            S2CVehicleModelState.class,
            CLIENTBOUND_VEHICLE_MODEL_STATE,
            Side.CLIENT);
        // The authorization facts. See CLIENTBOUND_MODEL_GRANTS above for the id and the protocol bump.
        CHANNEL.registerMessage(
            S2CModelGrants.Handler.class,
            S2CModelGrants.class,
            CLIENTBOUND_MODEL_GRANTS,
            Side.CLIENT);
    }

    private static void initBukkit() {
        CHANNEL.registerMessage(
            OpenModelGuiMessage.Handler.class,
            OpenModelGuiMessage.class,
            OPEN_NPC_MODEL_GUI,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SetNpcModelAndTexture.Handler.class,
            SetNpcModelAndTexture.class,
            SET_NPC_MODEL_ID,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SMolangSync.Handler.class,
            C2SMolangSync.class,
            SERVERBOUND_EMIT_MOLANG_SYNC,
            Side.SERVER);
        CHANNEL.registerMessage(
            SyncNpcDataMessage.Handler.class,
            SyncNpcDataMessage.class,
            SYNC_NPC_DATA,
            Side.CLIENT);
        CHANNEL.registerMessage(
            UpdateNpcDataMessage.Handler.class,
            UpdateNpcDataMessage.class,
            UPDATE_NPC_DATA,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CMolangSync.Handler.class,
            S2CMolangSync.class,
            CLIENTBOUND_MOLANG_SYNC,
            Side.CLIENT);
    }

    public static void sendToClientPlayer(IMessage message, EntityPlayer player) {
        if (player instanceof EntityPlayerMP) {
            CHANNEL.sendTo(message, (EntityPlayerMP) player);
        }
    }

    /** Server-side send entry: tells nearby clients that an entity's model override changed. */
    public static void broadcastNpcData(Entity entity, int entityId, ResourceLocation modelId,
        ResourceLocation textureId) {
        broadcastNpcData(new UpdateNpcDataMessage(entityId, modelId, textureId), entity);
    }

    /**
     * Sends a message to every client that could see {@code entity}, the entity's own client included when it is a
     * player. Used by the roaming channel so a settings change reaches exactly the clients that render that player.
     */
    public static void sendToTrackingPlayers(IMessage message, Entity entity, double range) {
        if (message == null || entity == null || entity.worldObj == null) {
            return;
        }
        CHANNEL.sendToAllAround(
            message,
            new NetworkRegistry.TargetPoint(entity.dimension, entity.posX, entity.posY, entity.posZ, range));
    }

    /**
     * The same broadcast as {@link #sendToTrackingPlayers}, minus one player - the sender of a request whose own
     * client has already applied the same change locally.
     *
     * <p><b>Why this cannot be {@code sendToAllAround}.</b> 1.7.10's FML has no exclusion: the target type has five
     * fields and no "except" field ({@code NetworkRegistry.TargetPoint}, {@code NetworkRegistry.java:82-104}), and
     * {@code FMLOutboundHandler.ALLAROUNDPOINT.selectNetworks} walks the whole {@code playerEntityList} with no
     * exclusion branch ({@code FMLOutboundHandler.java:189-209}). The sender's own squared distance to its own
     * position is 0, which passes the range test at {@code :201}, so the sender is always a recipient of
     * {@code sendToAllAround}. The exclusion therefore has to happen here, one {@code CHANNEL.sendTo} per recipient,
     * with {@link TrackingRecipients} reproducing FML's two selection rules. The shared
     * {@link #sendToTrackingPlayers} is deliberately left untouched: the roaming, NPC and vehicle broadcasts rely on
     * including the entity's own player, which is what the reference does.
     *
     * <p><b>First use: the roulette-expression echo</b> ({@code C2SSubmitRouletteExpression.Handler}), where the
     * sender's client must not execute the program twice. The echo is a <em>remote-playback</em> statement - the
     * recipients' clients resolve {@code entityId} and run the program against that entity - so excluding the sender
     * does not leave the sender's own screen without the effect: it applied it locally at click time.
     *
     * @param excluded the player who must not receive the message; {@code null} means "nobody to exclude", which
     *                 would make this a plain {@code sendToAllAround} and is treated as a programming error by the
     *                 caller rather than silently re-broadcasting
     */
    public static void sendToTrackingPlayersExcept(IMessage message, Entity tracked, EntityPlayerMP excluded,
        double range) {
        if (message == null || tracked == null || tracked.worldObj == null || excluded == null) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return;
        }
        // Same point, same rule, same dimension test as sendToTrackingPlayers above and as FML's own selection.
        for (EntityPlayerMP player : TrackingRecipients
            .excludingSender(excluded, server.getConfigurationManager().playerEntityList)) {
            if (TrackingRecipients.withinTargetPoint(
                tracked.dimension,
                tracked.posX,
                tracked.posY,
                tracked.posZ,
                player.dimension,
                player.posX,
                player.posY,
                player.posZ,
                range)) {
                CHANNEL.sendTo(message, player);
            }
        }
    }

    /** Server-side send entry: tells nearby clients to drop an entity's model override. */
    public static void broadcastNpcDataRemoval(Entity entity, int entityId) {
        broadcastNpcData(UpdateNpcDataMessage.removal(entityId), entity);
    }

    /** Sends a single entity override to one player, for example after a login or dimension change. */
    public static void sendNpcData(EntityPlayerMP player, int entityId, ResourceLocation modelId,
        ResourceLocation textureId) {
        if (player != null) {
            CHANNEL.sendTo(new UpdateNpcDataMessage(entityId, modelId, textureId), player);
        }
    }

    /**
     * Asks a player's client to open the model selection GUI for an entity. Both ids are the entity's tracked id:
     * the client resolves {@code entityId} to preview it and the server resolves {@code npcId} when the selection
     * comes back.
     */
    public static void sendOpenModelGui(EntityPlayerMP player, Entity target) {
        if (player != null && target != null) {
            CHANNEL.sendTo(new OpenModelGuiMessage(target.getEntityId(), target.getEntityId()), player);
        }
    }

    private static void broadcastNpcData(IMessage message, Entity entity) {
        if (entity != null && entity.worldObj != null) {
            CHANNEL.sendToAllAround(
                message,
                new NetworkRegistry.TargetPoint(
                    entity.dimension,
                    entity.posX,
                    entity.posY,
                    entity.posZ,
                    128.0D));
            return;
        }
        CHANNEL.sendToAll(message);
    }

    /**
     * N-08: whether the player's outbound buffer accepts another write. The legacy model download path sends
     * one 1.9 MB packet or many 512 KB chunks per model and {@code SimpleNetworkWrapper.sendTo} uses
     * {@code writeAndFlush} without checking writability, so a slow client would otherwise let the server pile
     * every chunk into the channel's outbound buffer.
     * <p>
     * A memory connection (single player / integrated server) has no real bandwidth limit and is always treated
     * as writable so the sender never spins on a local world.
     */
    public static boolean isChannelWritable(EntityPlayer player) {
        NetworkManager manager = getNetManager(player);
        if (manager == null || !manager.isChannelOpen()) {
            return false;
        }
        if (manager.isLocalChannel()) {
            return true;
        }
        return manager.channel() != null && manager.channel().isWritable();
    }

    /** N-08: whether the connection is still open, used to tell "buffer full" from "player gone". */
    public static boolean isChannelOpen(EntityPlayer player) {
        NetworkManager manager = getNetManager(player);
        return manager != null && manager.isChannelOpen();
    }

    private static NetworkManager getNetManager(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) {
            return null;
        }
        NetHandlerPlayServer handler = ((EntityPlayerMP) player).playerNetServerHandler;
        return handler == null ? null : handler.netManager;
    }
}
