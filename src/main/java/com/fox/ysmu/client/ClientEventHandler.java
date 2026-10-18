package com.fox.ysmu.client;

import java.util.List;

import javax.annotation.Nullable;

import com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime;
import com.fox.ysmu.client.audio.YSMSoundManager;
import com.fox.ysmu.client.gui.ConfigScreen;
import com.fox.ysmu.client.gui.DisclaimerScreen;
import com.fox.ysmu.client.gui.ExtraPlayerConfigScreen;
import com.fox.ysmu.client.gui.OpenModelFolderScreen;
import com.fox.ysmu.client.gui.PlayerModelScreen;
import com.fox.ysmu.client.gui.PlayerTextureScreen;
import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.input.WheelAnimationLock;
import com.fox.ysmu.client.render.AppliedModelRegistry;
import com.fox.ysmu.client.render.ModelFailureNotifier;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import com.fox.ysmu.client.renderer.HudPreviewCache;
import com.fox.ysmu.client.roaming.ClientRoamingStore;
import com.fox.ysmu.client.roaming.ClientRoamingSync;
import com.fox.ysmu.client.roaming.ModelFormExecution;
import com.fox.ysmu.util.RenderUtil;
import com.fox.ysmu.util.TickScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.fox.ysmu.Config;
import com.fox.ysmu.Tags;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.MovementSpeedMatcher;
import com.fox.ysmu.client.animation.RemotePlayerAnimationQueries;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.data.EntityClips;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.event.api.UpdateRemoteStructEvent;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.HandshakeMessage;
import com.fox.ysmu.network.message.RequestLoadModel;
import com.fox.ysmu.network.message.SetPlayAnimation;
import software.bernie.geckolib3.core.molang.RemoteAnimationVariables;
import software.bernie.geckolib3.geo.RenderContext;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.SessionScope;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;

@EventBusSubscriber(side = Side.CLIENT)
public class ClientEventHandler {

    private static boolean EXTRA_PLAYER = false;
    private static boolean pendingModelLoad;

    /** Tick counter for the periodic prune of {@link AppliedModelRegistry}; see {@code onClientTick}. */
    private static int appliedModelPruneTicker;

    /**
     * How long an applied-model record survives without its entity being found in the client world. The same ten
     * minutes {@code NPCData}'s prune uses, and for the same reason: a chunk can be unloaded for a while and the
     * entity still exists.
     */
    private static final long APPLIED_MODEL_RECORD_GRACE_MILLIS = 10L * 60L * 1000L;

    /**
     * The HUD paperdoll's FBO cache (row 44). Reference {@code client/ClientEventHandler.java:55-61}.
     * <p>
     * One instance for the whole client: the preview is per-frame screen state, not per-entity, and the cache
     * tracks the configured position/scale and the player's held item/armour for its own invalidation.
     */
    private static final HudPreviewCache hudPreviewCache = new HudPreviewCache();

    /**
     * Invalidate the HUD paperdoll FBO so the next frame re-bakes it. Reference
     * {@code client/ClientEventHandler.java:58-61}; the caller there is the follow-mode key
     * ({@code client/gui/ExtraPlayerConfigScreen.java:197}) and this port adds it on that same key.
     */
    public static void invalidateHudPreviewCache() {
        hudPreviewCache.invalidate();
    }

    /**
     * Whether the join-world welcome message has already been shown in this client session. Ported from the reference
     * branch's {@code client/ClientEventHandler.java:53} ({@code private static boolean welcomeShown = false;}), whose
     * comment reads "Whether the welcome message has been shown this session".
     * <p>
     * <b>One deliberate difference from the reference:</b> the reference writes this field {@code true} at
     * {@code :194} and <b>never writes it false anywhere</b> (grep of its whole tree finds only the declaration, the
     * {@code !welcomeShown} test and that one assignment), so there it means "once per client launch" - switching
     * servers in the same launch never shows the message again, which also makes {@code /ysm welcome on} unobservable
     * after the first join. This port resets it from {@link #clearClientRuntimeState()}, the port's single
     * disconnect/exit cleanup (called by both {@code PlayerLoggedOutEvent} and
     * {@code ClientDisconnectionFromServerEvent}), which is the behaviour the reference's own comment describes. See
     * {@code .agent/phase39-ysm-subcommands.md}.
     */
    private static boolean welcomeShown = false;

    /**
     * The read half of the once-per-session welcome rule (reference {@code ClientEventHandler.java:193}:
     * {@code Config.SHOW_WELCOME_MESSAGE && !welcomeShown}).
     * <p>
     * Package private rather than inlined into {@code onClientPlayerJoinWorld} so the rule - and the reset it depends
     * on - can be driven without a client: {@code ClientSessionStateResetTest} pins the whole lifecycle through this
     * method and {@link #setWelcomeShown(boolean)}. The behaviour is the reference's; only the seam is the port's.
     */
    static boolean shouldShowWelcome() {
        return Config.SHOW_WELCOME_MESSAGE && !welcomeShown;
    }

    /**
     * The single writer of {@link #welcomeShown}: {@code true} from the join block that just showed the message,
     * {@code false} from the disconnect cleanup. The reference writes the field directly in the join path and never
     * resets it; the seam exists so both writes have one owner and can be unit-tested (see
     * {@link #shouldShowWelcome()}).
     */
    static void setWelcomeShown(boolean shown) {
        welcomeShown = shown;
    }

    @SubscribeEvent
    public static void onTextureStitchEventPost(TextureStitchEvent.Post event) {
        // Record the client thread as early as the client offers a client-only entry point, so the model manager's
        // off-thread install guard is meaningful from the moment anything can ask it for animations.
        ClientModelManager.markClientThread();
        if (event.map.getTextureType() == 0) {
            pendingModelLoad = true;
        }
    }

    /**
     * Resets the projectile timeline dispatch budget at the start of each render frame.
     * <p>
     * The budget is per frame rather than per projectile: a short-period timeline on one arrow would otherwise be
     * free to dispatch without bound, and a screen full of arrows would multiply that by however many are visible.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
       ProjectileTimelineRuntime.beginRenderFrame();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Reference ClientEventHandler.java:134-137: drive existing password/entity waits without a pool worker.
        TickScheduler.client().runDueTasks();
        // Report local roaming-variable changes (the model 模型设置 panel writes them). Done before the early return
        // below, which only guards the one-shot model load. Cheap when nothing changed.
        ClientRoamingSync.flushPending();
        // Keep playing model sounds centred on the listener; see YSMSoundManager#updateSourcePositions. No-op when
        // nothing is playing.
        YSMSoundManager.updateSourcePositions();
        // Record this as the client thread, so the model manager can refuse to install animations from anywhere else
        // (a host mod may query clips from the server thread).
        ClientModelManager.markClientThread();
        // Direct-buffer safety net. It rate-limits itself (one JMX read per 100 calls, i.e. ~5 s) and returns
        // immediately while `Config.ENABLE_DIRECT_BUFFER_WATCHDOG` is off, so this is one boolean read and one
        // increment per tick in the normal case.
        //
        // Called **every tick**, not from a 1200-tick block the way the reference does
        // (`tmp/YesSteveModel-Unofficial-perf-previewUI/.../ClientEventHandler.java:155-160` nests it inside
        // `if (++unloadTick >= 1200)`): the watchdog's own interval is 100 calls, so nesting it inside another
        // interval multiplies the two and the "check at ~5s intervals" its javadoc promises becomes ~100 minutes.
        // Wiring it here is what makes `ENABLE_DIRECT_BUFFER_WATCHDOG` and `DIRECT_BUFFER_WATCHDOG_THRESHOLD_MB`
        // real switches - before this line both were declared, configurable and read by nothing that ever ran.
        DirectBufferWatchdog.tick();
        // Install at most a few models that finished building on the loader thread. This is the publish half of
        // on-demand model loading: the parse happens off this thread and only the cache write and the texture upload
        // happen here, a few per tick, so a catalog of models fills in without ever blocking a frame.
        ClientModelManager.tick();
        // Report model failures that have been collecting for their window, as one line per batch; upstream drives the
        // same aggregation from a scheduler (ModelFailureNotificationAggregator:16-71) and this port from its tick.
        ModelFailureNotifier.tick();
        // Prune the per-entity applied-model records the same way NPCData's overrides are pruned on the server side:
        // entity ids are never reused, so a record for an entity the client no longer has only wastes memory, and the
        // grace keeps a temporarily unloaded entity from losing the model it is already showing. Every 1200 ticks,
        // like that prune, because this only bounds a table that a few dozen entities write.
        if (++appliedModelPruneTicker >= 1200) {
            appliedModelPruneTicker = 0;
            World world = Minecraft.getMinecraft().theWorld;
            AppliedModelRegistry.retainAll(
                System.currentTimeMillis(),
                id -> world != null && world.getEntityByID(id) != null,
                APPLIED_MODEL_RECORD_GRACE_MILLIS);
        }
        if (!pendingModelLoad) {
            return;
        }
        pendingModelLoad = false;

        // TextureStitchEvent.Post fires while TextureManager is still reloading
        // its texture map. Registering model textures there mutates the same
        // map and can make GTNH disable all user resource packs after a CME.
        ClientModelManager.loadDefaultModel();
        List<String> cachedModels = ClientModelManager.getCachedModelSnapshot();
        for (String md5 : cachedModels) {
            RequestLoadModel.loadModel(md5);
        }
    }

    @SubscribeEvent
    public static void onClientPlayerJoinWorld(EntityJoinWorldEvent event) {
        if (!event.world.isRemote || !(event.entity instanceof EntityClientPlayerMP)) {
            return;
        }
        RemotePlayerAnimationQueries.clear();
        // CU-10: 加入世界时同时清掉上一个服务器的远端动作状态（挂在客户端的 PlayerLoggedOutEvent
        // 在纯多人客户端不会触发，断开事件落地后这里仍需保留一次，覆盖"直接换服"的路径）。
        RemotePlayerMotionStates.clear();
        // Tell the server which wire format we speak; a mismatch disconnects instead of mis-decoding packets.
        NetworkHandler.CHANNEL.sendToServer(new HandshakeMessage(NetworkHandler.NETWORK_PROTOCOL));
        // 这里原来有一条 `if (!Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) ClientModelManager
        // .sendSyncModelMessage();`，已删除：服务端在玩家登录时**无条件**发 RequestSyncModel
        // （model/ServerModelManager.java 的 sendRequestSyncModelMessage），而客户端的 RequestSyncModel.Handler
        // 调用的正是同一个 ClientModelManager.sendSyncModelMessage()（network/message/RequestSyncModel.java:29-31），
        // 所以这里再主动触发一次只会让同一批模型在 legacy 通道上被同步/注册两遍。
        // 客户端仍保留一条兜底入口：PlayerModelScreen.initGui 在模型列表为空时补发一次（见那里的注释）。
        // The welcome/info message (reference client/ClientEventHandler.java:192-216), once per session: the guard is
        // shouldShowWelcome() and the flag is consumed before the client is touched, exactly as the reference orders it
        // (:193-195). The reference builds the clickable line from hard-coded Chinese/English chosen by
        // Minecraft.getLanguageManager(); this repository sends every user-facing line through a translation key, so
        // the language branch is the language file and the whole line is one ChatComponentTranslation carrying the
        // /ysm help click event - the same shape YsmCommand.processHelp gives its welcome_off line.
        if (shouldShowWelcome()) {
            setWelcomeShown(true);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(
                    new ChatComponentTranslation("commands.yes_steve_model.welcome", Tags.VERSION));
                if (Config.HIGH_VERSION_GAME_PATH == null || Config.HIGH_VERSION_GAME_PATH.isEmpty()) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation("commands.yes_steve_model.welcome.sound_hint"));
                }
                ChatComponentTranslation helpHint = new ChatComponentTranslation(
                    "commands.yes_steve_model.welcome.help_hint");
                helpHint.getChatStyle()
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ysm help"));
                mc.thePlayer.addChatMessage(helpHint);
            }
        }
    }

    /**
     * Upstream's {@code VanillaPlayerRenderEvent} ({@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/
     * client/event/VanillaPlayerRenderEvent.java:24-42}): the built-in {@code steve}/{@code alex} models do not draw
     * a texture of their own at all - they draw the player's actual Minecraft skin, which is not one of the pack's
     * textures and therefore travels to the renderer as this event's override
     * ({@code SpecialPlayerRenderEvent#setTextureLocationOverride}). The whole listener is that one assignment
     * upstream; the renderer has already put the entity, the model and the selection onto the animatable
     * ({@code CustomPlayerRenderer#applyEntityModel}) before it posts the event, and the first-person arm sets its own
     * animatable up the same way, so nothing here has to touch them.
     * <p>
     * Upstream's second branch - no entry in the skin map, so use that model's own default skin - has no counterpart
     * here: 1.7.10's {@code AbstractClientPlayer#getLocationSkin} already answers the loaded skin or the vanilla
     * default ({@code textures/entity/steve.png}, {@code AbstractClientPlayer.java:49-52}), and that default is the
     * only player default 1.7.10 ships (there is no {@code alex.png} - the slim variant reuses {@code steve.png}).
     */
    @SubscribeEvent
    public static void onRenderPlayer(SpecialPlayerRenderEvent event) {
        if (isVanillaPlayer(event.getModelId()) && event.getEntity() instanceof AbstractClientPlayer clientPlayer) {
            event.setTextureLocationOverride(clientPlayer.getLocationSkin());
        }
    }

    /**
     * Non-player render entry: a registered living entity is rendered through YSM's replacement renderer.
     * <p>
     * Renderers that override {@code RendererLivingEntity#doRender} without calling {@code super} (several
     * GeckoLib-based mods do) never reach this event; those mods call
     * {@code com.fox.ysmu.client.renderer.EntityModelRenderApi#render} directly instead.
     */
    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre event) {
        if (event.entity instanceof EntityPlayer || !NPCData.contains(event.entity)) {
            return;
        }
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
        if (renderer == null || !renderer.hasModelFor(event.entity) || !renderer.canDrawFor(event.entity)) {
            // No YSM model this client can draw: leave the frame to the entity's own renderer rather than hiding it.
            // The second query is what covers a client whose built-in fallback is unusable (see onRender).
            return;
        }
        event.setCanceled(true);
        // N-5（记录级）：1.7.10 的 RenderLivingEvent.Pre 只有 x/y/z 与 yaw，**没有** partial tick 字段，
        // 因此这里从 Minecraft.timer.renderPartialTicks 取帧间插值（与 RenderPlayerEvent.Pre 的
        // event.partialRenderTick 不同）；timer 由 Mixin/AT 暴露，取不到时保持 0 的语义也与原实现一致。
        renderer.doRender(
            event.entity,
            event.x,
            event.y - event.entity.yOffset,
            event.z,
            event.entity.rotationYaw,
            Minecraft.getMinecraft().timer.renderPartialTicks);
    }

    /** Feeds remote animation variables into the entity's Molang scope. */
    @SubscribeEvent
    public static void onUpdateRemoteStruct(UpdateRemoteStructEvent event) {
        RemoteAnimationVariables.put(event.getEntity(), event.getRoamingVars());
    }

    @SubscribeEvent
    public static void onRender(RenderPlayerEvent.Pre event) {
        EntityPlayer player = event.entityPlayer;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP playerSelf = mc.thePlayer;
        if (player.equals(playerSelf) && Config.DISABLE_SELF_MODEL) {
            return;
        }
        if (!player.equals(playerSelf) && Config.DISABLE_OTHER_MODEL) {
            return;
        }
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
        // A client whose built-in fallback is unusable has nothing to draw for a model that is not published yet;
        // cancelling the vanilla event would then hide the player. Leave the frame to the vanilla renderer instead -
        // the same answer this port gives for DISABLE_SELF_MODEL and for a non-player entity with no model. Upstream
        // treats its own default-initialization failure as exceptional (model-fallback.md:49-53); a port that cancels
        // its host's renderer has to answer that exception with the host, not with an empty frame.
        if (renderer == null || !renderer.canDrawFor(player)) {
            ModelFailureNotifier.reportFallbackUnavailable();
            return;
        }
        event.setCanceled(true);
        if ((mc.currentScreen != null || EXTRA_PLAYER) && player.equals(playerSelf)) {
            renderSelfGuiPlayer(renderer, player, event.partialRenderTick);
        } else {
            float partialTicks = event.partialRenderTick;
            double ix = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
            double iy = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
            double iz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
            renderer.doRender(
                player,
                ix - RenderManager.renderPosX,
                iy - RenderManager.renderPosY - player.yOffset,
                iz - RenderManager.renderPosZ,
                player.rotationYaw,
                partialTicks);
        }
    }

    /**
     * Renders the local player as a preview (the HUD paper doll, or the world render while a screen is open).
     * <p>
     * {@code partialTicks} is forwarded rather than replaced with {@code 1.0F}: it is what makes this pass agree with
     * the world's own pass about the frame, which the engine's per-frame de-duplication keys on. See
     * {@code RenderUtil#renderPlayerEntity}.
     */
    private static void renderSelfGuiPlayer(CustomPlayerRenderer renderer, EntityPlayer player, float partialTicks) {
        PlayerPreviousRotationSnapshot snapshot = PlayerPreviousRotationSnapshot.capture(player);
        // This is a preview bracket, not the world pass: it is what makes CustomPlayerRenderer restore the pose the
        // draw leaves on the shared GeoModel (previewRenderDepth -> ModelPoseSnapshot) and what marks the frame as a
        // preview for the emission gate.
        //
        // The pass is the page's own: with a screen open this one entry point is drawing the world-while-a-screen-is-up
        // pass, and without one it is the HUD paper doll. Upstream splits the two across two setters
        // (util/RenderUtil.java:445 for the doll, mixin/client/InventoryScreenMixin.java:16 for the inventory doll), and
        // its world pass sets neither; this port has one entry point for the first two, so the screen decides, which is
        // the answer FrameRenderQueries inferred before the context existed. The two known residuals of that heuristic
        // are unchanged by this method and recorded here: the doll behind a non-covering screen still reads as the
        // inventory pass, and a screen open during the world pass makes that pass the inventory pass.
        CustomPlayerRenderer.beginPreviewRender(
            Minecraft.getMinecraft().currentScreen != null ? RenderContext.inventoryPass()
                : RenderContext.paperDollPass());
        try {
            syncPreviousRotationsToPreview(player);
            // REVERTED (42c): this used to branch on `RenderUtil.isPlayerPreviewPass()` and draw the HUD doll through
            // its own preview animatable. That changed the doll's placement (it is drawn on the matrix
            // `RenderUtil#renderPlayerEntity` already set up, and the tile-style framing does not compose with it) and
            // dropped its layers, while the flicker it was meant to remove remained. The doll goes back through the
            // world renderer, exactly as before, so `HUD_FOLLOW_*`, the `0 - player.yOffset` placement and the layer
            // pass (held item, head slot, elytra) are all unchanged. See
            // `.agent/phase42-immutable-render-context.md` for the reverted approach and what remains open.
            RenderUtil.withGuiEntityLighting(() -> renderer.doRender(
                player,
                0,
                0 - player.yOffset,
                0,
                player.rotationYaw,
                partialTicks));
        } finally {
            CustomPlayerRenderer.endPreviewRender();
            snapshot.restore(player);
        }
    }

    /**
     * Makes the preview draw use the entity's <i>current</i> rotation fields instead of interpolating them, so this
     * pass and the world's pass agree about the frame (the engine's per-frame de-duplication keys on that).
     * <p>
     * <b>{@code RenderUtil}'s HUD follow modes depend on this.</b> Their whole point is that the outer rotation the
     * doll is drawn under cancels the renderer's inner {@code 180 - renderYawOffset}
     * ({@code GeoReplacedEntityRenderer#applyRotations}), and that cancellation only holds exactly while the
     * renderer's interpolated body yaw IS the current {@code renderYawOffset} - which is what this sync makes true.
     * The mode math reads the current fields for the same reason; see {@code RenderUtil#renderPlayerEntity}. Removing
     * this sync without changing that math would put the 20 Hz sawtooth back into modes 1 and 3.
     */
    private static void syncPreviousRotationsToPreview(EntityPlayer player) {
        player.prevRenderYawOffset = player.renderYawOffset;
        player.prevRotationYaw = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
        player.prevRotationYawHead = player.rotationYawHead;
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        ItemRenderer itemRenderer = mc.entityRenderer.itemRenderer;
        if (AngelicaCompat.usesShaderHandRenderer()) {
            return;
        }
        FirstPersonHandRenderer.tryRender(event, mc, player, itemRenderer);
    }

    @SubscribeEvent
    public static void onRenderScreen(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        if (Config.DISABLE_PLAYER_RENDER) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;
        if (mc.currentScreen instanceof ExtraPlayerConfigScreen) return;
        // Covered by a full-screen YSMU GUI: mark the cache dirty instead of baking a paperdoll nobody can see.
        // See hudIsCoveredByScreen for the reference's measurement and the reasoning.
        if (hudIsCoveredByScreen(mc.currentScreen)) {
            hudPreviewCache.invalidate();
            return;
        }
        double posX = Config.PLAYER_POS_X;
        double posY = Config.PLAYER_POS_Y;
        float scale = (float) Config.PLAYER_SCALE;
        float yawOffset = (float) Config.PLAYER_YAW_OFFSET;
        // CU-11: EXTRA_PLAYER 必须在 finally 里复位，否则渲染异常会让它永久为 true，
        // 之后世界内的自身渲染也会被当成 GUI 预览（渲染到 0,0,0 并走 GUI 光照）。
        EXTRA_PLAYER = true;
        try {
            // Row 44: the paperdoll goes through the FBO cache rather than the full pipeline every frame
            // (reference client/ClientEventHandler.java:485). With Config.GUI_HUD_PREVIEW_CACHE off the same call
            // falls back to the previous per-frame renderPlayerEntity, so this is the only HUD call site either way.
            hudPreviewCache.render(player, posX, posY, scale, yawOffset, event.partialTicks);
        } finally {
            EXTRA_PLAYER = false;
        }
    }

    /**
     * True for the YSMU screens whose background completely covers the HUD, so the HUD paperdoll rendered
     * underneath them can never be seen. Reference {@code client/ClientEventHandler.java:492-500}.
     * <p>
     * <b>Why this matters here.</b> 1.7.10 draws the HUD <i>before</i> the screen
     * ({@code EntityRenderer#updateCameraAndRender} calls {@code ingameGUI.renderGameOverlay} at
     * {@code build/rfg/minecraft-src/java/net/minecraft/client/renderer/EntityRenderer.java:1111-1114} and only
     * then {@code currentScreen.drawScreen} at {@code :1130-1138}), so with one of these screens open the bake is
     * invisible. The reference measured that path at 36 % of the whole client thread before this guard; without
     * it the FBO cache would still bake an invisible doll at up to {@link HudPreviewCache}'s 120 Hz ceiling.
     * The cache is invalidated rather than simply skipped, so the paperdoll is fresh on the first frame after
     * the screen closes — which is also what picks up a model/texture switch made inside that screen.
     * <p>
     * {@code AnimationRouletteScreen} is deliberately NOT listed (neither in the reference nor here): it draws no
     * background and is meant to show the paperdoll. {@link ExtraPlayerConfigScreen} never reaches this method
     * (the caller returns above): it is a live preview of this very element.
     */
    private static boolean hudIsCoveredByScreen(GuiScreen screen) {
        return screen instanceof PlayerModelScreen
            || screen instanceof PlayerTextureScreen
            || screen instanceof ConfigScreen
            || screen instanceof DisclaimerScreen
            || screen instanceof OpenModelFolderScreen;
    }

    /**
     * The Post/{@code ALL} overlay pass: the Molang debug overlay first, then the model-sync progress bar.
     * <p>
     * The debug overlay is on the reference branch's shape
     * ({@code tmp/YesSteveModel-Unofficial-perf-previewUI/.../client/ClientEventHandler.java:299-308}: a
     * {@code RenderGameOverlayEvent.Post} subscriber on {@code ElementType.ALL}, i.e. after every other overlay so the
     * table sits on top), and the sync bar is the second half of that same reference handler ({@code :310-354}). Both
     * live in one subscriber rather than two, because Forge gives two subscribers of one event no order at all: the
     * reference draws the debug overlay and returns, so the bar can never cover it. Renamed from
     * {@code onRenderDebugOverlay} to the reference's own name - which is also the name
     * {@code client/gui/debug/DebugOverlay.java:246} already cited for its caller.
     * <p>
     * This tree had no {@code Post} subscriber at all - its HUD selfie preview runs on {@code Pre}/{@code HOTBAR}
     * ({@link #onRenderScreen} above), which this method deliberately does not touch. The overlay is only active while
     * {@code Config.DEBUG_MOLANG_OVERLAY} is on, because {@code DebugOverlay.toggle()} refuses to open otherwise.
     */
    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (com.fox.ysmu.client.gui.debug.DebugOverlay.isActive()) {
            com.fox.ysmu.client.gui.debug.DebugOverlay.render(event.resolution);
            return;
        }
        renderSyncProgress(event);
    }

    /**
     * Row 45 {@code Config.SHOW_LOADING_PROGRESS}: the model-sync progress bar, ported from the reference
     * ({@code client/ClientEventHandler.java:310-354}).
     * <p>
     * <b>Where the numbers come from.</b> The reference reads four {@code public static volatile} fields it keeps on
     * {@code ClientModelManager} ({@code SYNC_IN_PROGRESS} / {@code SYNC_TOTAL} / {@code SYNC_LOADED} /
     * {@code SYNC_CURRENT_MODEL}, reference {@code client/ClientModelManager.java:85-93}). This port's counters are
     * already {@code OpenYsmModelSyncClient}'s own ({@code syncStep} / {@code syncTotalModels} /
     * {@code loadedModelsCount}), so the bar reads those through the read-only outlets added there
     * ({@code isSyncInProgress} / {@code syncTotal} / {@code syncLoaded}) rather than a second copy of the same state.
     * <p>
     * <b>Two recorded divergences, both forced by state this port does not keep:</b>
     * <ol>
     *   <li>The reference's first line is its label plus the <i>name</i> of the model being loaded. This port has no
     *       such field and adding tracking for it was ruled out, so the label is drawn on its own and the name is
     *       simply missing; the counter line below is the reference's own.</li>
     *   <li>Only the OpenYSM round is reported. That is where the counters live, and the legacy md5-list channel
     *       ({@code ClientModelManager.sendSyncModelMessage}) keeps no counters at all, so with
     *       {@code Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL} off the bar never appears. Deriving progress for the legacy
     *       channel would mean inventing a second mechanism; the reference's own bar is driven by the one channel it
     *       has.</li>
     * </ol>
     * <p>
     * The overlay does not throw for a missing client: the reference's own guards ({@code mc.theWorld == null ||
     * mc.fontRenderer == null}) are kept. It does not add the reference's {@code suppressHandlerError} wrapper,
     * because this tree has no such helper and its other render subscribers ({@link #onRenderScreen},
     * {@link #onRenderHand}) are unguarded too - introducing it here alone would be a new cross-cutting mechanism
     * rather than part of this port.
     */
    private static void renderSyncProgress(RenderGameOverlayEvent.Post event) {
        if (!Config.SHOW_LOADING_PROGRESS) return;
        if (!OpenYsmModelSyncClient.isSyncInProgress()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.fontRenderer == null) return;

        int total = OpenYsmModelSyncClient.syncTotal();
        int loaded = OpenYsmModelSyncClient.syncLoaded();

        int screenWidth = event.resolution.getScaledWidth();
        int screenHeight = event.resolution.getScaledHeight();

        // Reference :323-330 draws `label + currentModel` here; this port has no current-model state, so the label
        // stands alone (see the divergence list above).
        String label = StatCollector.translateToLocal("gui.yes_steve_model.sync_model");
        int labelX = (screenWidth - mc.fontRenderer.getStringWidth(label)) / 2;
        mc.fontRenderer.drawStringWithShadow(label, labelX, screenHeight - 60, 0xFFFFFF);

        // Progress bar (determinate when the index has declared a total, indeterminate before that)
        int barWidth = 150;
        int barHeight = 8;
        int barX = (screenWidth - barWidth) / 2;
        int barY = screenHeight - 40;

        Gui.drawRect(barX, barY, barX + barWidth, barY + barHeight, 0xAA222222);
        String text;
        if (total > 0) {
            int fillWidth = loaded >= total ? barWidth : (int) (barWidth * ((float) loaded / total));
            Gui.drawRect(barX, barY, barX + fillWidth, barY + barHeight, 0xFF44AA44);
            text = loaded + " / " + total;
        } else {
            // Indeterminate: pulsing highlight (first quarter)
            int pulse = (int) ((System.currentTimeMillis() % 2000L) / 2000.0f * barWidth);
            Gui.drawRect(
                barX + pulse,
                barY,
                Math.min(barX + pulse + barWidth / 4, barX + barWidth),
                barY + barHeight,
                0xFF44AA44);
            text = StatCollector.translateToLocal("gui.yes_steve_model.sync_waiting");
        }

        int textX = (screenWidth - mc.fontRenderer.getStringWidth(text)) / 2;
        int textY = barY - mc.fontRenderer.FONT_HEIGHT - 2;
        mc.fontRenderer.drawStringWithShadow(text, textX, textY, 0xFFFFFF);
    }

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        // CU-04: 先调用 isMoveKey() 消费移动键的 pressTime（KeyBinding.pressTime 是一次性信号，
        // 不消费会在界面关闭后的下一个按键事件上补触发），再判断界面：
        // 1.7.10 的按键状态与 KeyInputEvent 都不受 GUI 影响（Minecraft.java:1839/1964）。
        boolean moveKey = isMoveKey();
        EntityPlayer player = mc.thePlayer;
        if (!moveKey || mc.currentScreen != null || player == null) {
            return;
        }
        // D1: the roulette's movement lock. Upstream returns before stopping the animation when the lock is on
        // (PlayerMoveEvent.java:44-46), which is the whole point of the lock: play an extra animation while walking.
        // This must stay *after* isMoveKey(): that call consumes the movement key's one-shot pressTime (CU-04), and
        // the lock decides only whether we stop, never whether the signal is consumed.
        if (WheelAnimationLock.isLocked()) {
            return;
        }
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isPlayAnimation()) {
            NetworkHandler.CHANNEL.sendToServer(SetPlayAnimation.stop());
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        // 注意：纯多人客户端不会收到这个 FML 事件（1.7.10 只有 ServerConfigurationManager 会 post），
        // 因此真正的兜底是下面的 onClientDisconnect。
        //
        // 这里是「玩家登出」边界，不是「本地会话结束」边界 —— 两者原先被混在一起：集成服务器上**任何**客人
        // 登出都会在服主客户端触发本方法（服务端 ServerConfigurationManager.playerLoggedOut →
        // firePlayerLoggedOut，同一个 FML 总线），于是服主自己的动画/漫游/连接状态被一并清掉。参考分支在它的
        // PlayerLoggedOutEvent 里按 UUID 判是不是本地玩家（reference ClientEventHandler.java:513-543，判定
        // 在 :545-551），本仓用同一判定（{@link SessionScope}，由本方法接上）。
        //
        // 事件里的 player 是服务端的 EntityPlayerMP，与客户端渲染用的 mc.thePlayer **不是同一个对象**，所以
        // 只能按 UUID 比。SessionScope.isLocalPlayer 只可能在 thePlayer 未知时返回 false —— 也就是只可能
        // **少清**，而少清的那一侧由 onClientDisconnect 的无条件全量清兜住：本地玩家登出必然伴随连接关闭
        // （退出集成服世界、专用服断开、被踢都会派发 ClientDisconnectionFromServerEvent）。
        if (event.player != null) {
            clearDepartedPlayerState(event.player);
        }
        if (isLocalPlayerLogout(event)) {
            clearClientRuntimeState();
        }
    }

    /**
     * 登出事件里的玩家是不是本地玩家（按 UUID 比；服务端对象与客户端对象不是同一个实例）。
     * 参考 {@code client/ClientEventHandler.java:545-551}；判定本身在 {@link SessionScope}。
     */
    private static boolean isLocalPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer loggedOut = event.player;
        EntityPlayer local = Minecraft.getMinecraft().thePlayer;
        return SessionScope.isLocalPlayer(
            loggedOut == null ? null : loggedOut.getUniqueID(),
            local == null ? null : local.getUniqueID());
    }

    /**
     * 远程玩家登出：只清**这一名玩家**的远端状态，不碰本地会话（见 {@link #onPlayerLeave}）。
     * <p>
     * 逐项与依据：
     * <ul>
     * <li>音效归属：不留该玩家的"控制器→音效"映射与音源（SOURCE ClientEventHandler.java:537）。</li>
     * <li>{@link RemoteAnimationVariables#clear(net.minecraft.entity.Entity)}：引擎侧那张表按 UUID 存
     * （{@code BY_ENTITY}），所以服务端的 EntityPlayerMP 也能命中 —— 与本地玩家对象不同实例不影响。</li>
     * <li>{@link MovementSpeedMatcher#clearSmoothing(EntityPlayer)}：按 UUID 的防滑步平滑倍速，参考在同一
     * 位置调用（reference {@code :535}）。</li>
     * <li>{@link RemotePlayerAnimationQueries} / {@link RemotePlayerMotionStates}：本仓只有全量 {@code clear()}，
     * 没有 per-UUID 出口，所以按参考的无条件清理处理（reference {@code :524-525}）。这两张表都是**远端玩家
     * 专用**的：本地玩家在 {@code RemotePlayerAnimationQueries.get} 的 {@code isRemotePlayer} 处直接短路、
     * 根本不写表，所以全量清不会碰到本地状态。</li>
     * </ul>
     * <p>
     * <b>没有在这里做的两项，都是本仓缺 per-player 出口，不是漏接：</b>
     * {@link AnimationManager#clearPlayerState()} 会同时清掉本地玩家的 swing/use 进度表（参考用的是
     * {@code AnimationManager.getInstance().resetPlayerState(pid)}，本仓没有那个方法），所以它只留给本地会话
     * 结束的那条路径；NPC 覆盖表与宿主推送的剪辑表是**连接级**状态，客人登出时连接还在，同样只留给全量清
     * （参考在 {@code :526} 无条件清 NPCData，本仓按连接语义收紧）。
     */
    private static void clearDepartedPlayerState(EntityPlayer loggedOut) {
        YSMSoundManager.clearOwner(loggedOut);
        RemoteAnimationVariables.clear(loggedOut);
        MovementSpeedMatcher.clearSmoothing(loggedOut);
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
    }

    /**
     * CU-10（合并 A-10 / NF-02 / D-A1）：客户端断开连接时清理运行期状态。
     * <p>
     * {@code PlayerLoggedOutEvent} 只在服务端（含集成服共享的 FML bus）触发，纯多人客户端永远收不到，
     * 所以这里挂到确实会在客户端触发的 {@link FMLNetworkEvent.ClientDisconnectionFromServerEvent} 上
     * （由 {@code NetworkDispatcher} 在 FML bus 上 post）。该事件只在连接关闭时触发，
     * 不会在连接建立期间被调用。
     * <p>
     * <b>这里保持无条件全量清，与 {@link #onPlayerLeave} 的按 UUID 判定不同</b>：连接已经断了，这一整套
     * 运行期状态（连接密钥、模型表、漫游值、姿态签名……）都属于刚刚消失的那个服务器，没有任何一条需要
     * 留给下一个连接；而 {@code PlayerLoggedOutEvent} 描述的是"某一名玩家离开"，集成服上客人离开时连接
     * 仍然有效。两条路径的差别就是 R3 那个缺陷的修复本身，不要为了统一而把它们合并回一个无条件入口。
     */
    @SubscribeEvent
    public static void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        // phase55: a /ysm upload in flight belongs to the connection that just ended; the background task notices
        // the missing net handler on its next chunk, and this clears the "uploading" lock right away (the R-02
        // requirement recorded on client/upload/UploadManager).
        com.fox.ysmu.client.upload.UploadManager.reset();
        clearClientRuntimeState();
    }

    /**
     * 客户端断开/退出的统一清理入口，由两条路径调用，且**只有第二条是无条件的**：
     * <ul>
     * <li>{@link #onPlayerLeave} —— 仅当登出的是**本地玩家**（{@link #isLocalPlayerLogout}）；客人登出走
     * {@link #clearDepartedPlayerState}，不走这里。</li>
     * <li>{@link #onClientDisconnect}（{@link FMLNetworkEvent.ClientDisconnectionFromServerEvent}）——
     * <b>无条件全量清</b>：连接已经断了，这一整套运行期状态都属于刚刚消失的那个服务器。</li>
     * </ul>
     * join 路径不调用。
     * <p>
     * 清 8 项：模型连接态、远端动画查询、远端动作状态、引擎侧远端 MoLang 变量、NPC 覆盖表、
     * 宿主推送的动画剪辑表、服务端下发的授权事实（{@link ClientModelAccess#clear()}），
     * 以及 {@link AnimationManager#clearPlayerState()} 里的两张 per-player
     * 进度表（{@code swingProgressByPlayer}/{@code useDurationByPlayer}，ExecPlan 的 A-11，由 t33 交付）、
     * 防滑步的每玩家平滑倍速（{@link MovementSpeedMatcher#clearAll()}）、
     * 客户端音效与音源，以及缩略图的姿态签名缓存（{@link RenderUtil#clearPreviewPoseCache()}：它按模型 id
     * 存，模型重载/断线后必须整表作废，见 {@link com.fox.ysmu.util.PreviewPoseCache}；因为它是渲染线程专用的
     * 普通 HashMap，这一项是排队回客户端线程清的，见方法体内注释），
     * 最后是进服欢迎消息的一次性标志 {@link #welcomeShown}（见该字段的 javadoc）。
     * 该方法本身幂等（MANAGER 未创建时判空跳过、其余是 ConcurrentHashMap.clear()），
     * 与本方法上面的两个 Remote* 清理重复但互为 no-op。
     */
    private static void clearClientRuntimeState() {
        ClientModelManager.clearConnectionState();
        RequestLoadModel.clearPendingLoads();
        com.fox.ysmu.util.TickScheduler.client().clear();
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
        RemoteAnimationVariables.clear();
        // The roaming values belong to the server that just went away, and so do the pending reports for it.
        ClientRoamingStore.clear();
        // The authorization facts belong to it as well: the next connection publishes its own restricted set and
        // grants, and until that packet arrives the lock display must stay off (ClientModelAccess.isEmpty()), which
        // clearing restores. Leaving them would lock tiles according to the previous server's rules.
        ClientModelAccess.clear();
        NPCData.clear();
        EntityClips.clear();
        // What each entity was shown with dies with the entities of the world that just went away. Cleared here as
        // well as in ClientModelManager#clearRuntimeModelCaches (the sync path), because this is the point that runs
        // when the connection ends without a following sync; the map is concurrent, so clearing it from this thread is
        // the same kind of write the render thread does.
        AppliedModelRegistry.clear();
        // The failure notices belong to the session that ended; a new connection reports its own failures again.
        ModelFailureNotifier.clear();
        // 缩略图的姿态签名按模型 id 存（PreviewPoseCache 是渲染线程专用的普通 HashMap，见它自己的 javadoc），
        // 而本方法会在 Netty 线程（ClientDisconnectionFromServerEvent，NetworkDispatcher.disconnect/close 里
        // post）或服务端线程（集成服的 PlayerLoggedOutEvent）上被调用 —— ClientModelManager#clearConnectionState
        // 的注释把这条前提写得很清楚，并因此**故意不碰**渲染线程读的普通 HashMap。所以这一项不在本线程直接清，
        // 而是按本仓既有的"要碰渲染线程状态就回客户端线程"约定排队（AGENTS.md 的 Network and Threading
        // Rules）。清的是"换服务器后同一个模型 id 可能指向另一份几何"这类残留，晚一帧无影响。
        Minecraft.getMinecraft().func_152344_a(() -> {
            RenderUtil.clearPreviewPoseCache();
            ModelFormExecution.clear();
        });
        // CU-10 闭环：清掉 AnimationManager 里两个不会自然释放的 per-player 进度表。
        AnimationManager.clearPlayerState();
        // 防滑步的平滑倍速同样按玩家 UUID 保存，是同一类不会自然释放的 per-player 状态：不清的话
        // 同一个 UUID 再次进来会带着上一段的倍速。参考分支在它的 PlayerLoggedOutEvent 处理里按 UUID
        // clearSmoothing（reference ClientEventHandler.java:535），本仓把那一项放在
        // clearDepartedPlayerState（任何玩家登出都清自己那一份），这里只兜住"本地会话结束"的全量清。
        MovementSpeedMatcher.clearAll();
        // Model sounds as well: the sources are still playing in the SoundSystem and their bytes belong to the
        // server that just went away, so leaving them would keep the previous server's audio going and keep a
        // stale owner table alive across the next join.
        YSMSoundManager.clear();
        // The once-per-session welcome flag belongs to the session that just ended, so the next join shows the
        // message again - the state the reference's own field comment ("this session", client/ClientEventHandler.java:53)
        // describes but never implements, because it never writes the field false. See the field's javadoc.
        setWelcomeShown(false);
        // D1: the roulette's movement lock is a per-session client preference, so a disconnect/new world drops it
        // instead of leaving the next session locked. Upstream keeps it in a static with no reset at all.
        WheelAnimationLock.reset();
    }

    /** Upstream's two built-in model ids ({@code VanillaPlayerRenderEvent.java:21-22}). */
    private static final String VANILLA_STEVE = "misc/2_steve";
    private static final String VANILLA_ALEX = "misc/1_alex";

    /**
     * Whether {@code modelId} is one of the built-in models that draw the player's own Minecraft skin.
     * <p>
     * Upstream compares the model id to the two names verbatim ({@code VanillaPlayerRenderEvent.java:44-46}), and the
     * two models ship in this port's built-in pack under the same relative paths - but a model id must be a legal
     * {@link ResourceLocation} path, so {@code ModelIdUtil} carries the folder part hex-encoded
     * ({@code ysmu:_name_6d6973632f325f7374657665}, which is what the client registers and the server syncs), and the
     * comparison has to decode it first ({@link ModelIdUtil#getModelDisplayName}). The two bare names are the same
     * models as published before the built-in pack moved under {@code misc/}; matching them by full path (never by
     * last segment, which would also catch a pack's own {@code wine_fox/alex}) keeps those installs working.
     * <p>
     * Package private rather than inlined so the rule can be pinned without a client, like
     * {@link #shouldShowWelcome()}.
     */
    static boolean isVanillaPlayer(@Nullable ResourceLocation modelId) {
        if (modelId == null) {
            return false;
        }
        String path = ModelIdUtil.getModelDisplayName(modelId);
        return VANILLA_STEVE.equals(path) || VANILLA_ALEX.equals(path) || "steve".equals(path) || "alex".equals(path);
    }

    private static final class PlayerPreviousRotationSnapshot {
        private final float prevRenderYawOffset;
        private final float prevRotationYaw;
        private final float prevRotationPitch;
        private final float prevRotationYawHead;

        private PlayerPreviousRotationSnapshot(EntityPlayer player) {
            this.prevRenderYawOffset = player.prevRenderYawOffset;
            this.prevRotationYaw = player.prevRotationYaw;
            this.prevRotationPitch = player.prevRotationPitch;
            this.prevRotationYawHead = player.prevRotationYawHead;
        }

        private static PlayerPreviousRotationSnapshot capture(EntityPlayer player) {
            return new PlayerPreviousRotationSnapshot(player);
        }

        private void restore(EntityPlayer player) {
            player.prevRenderYawOffset = this.prevRenderYawOffset;
            player.prevRotationYaw = this.prevRotationYaw;
            player.prevRotationPitch = this.prevRotationPitch;
            player.prevRotationYawHead = this.prevRotationYawHead;
        }
    }

    private static boolean isMoveKey() {
        KeyBinding[] keyBindings = Minecraft.getMinecraft().gameSettings.keyBindings;
        for (KeyBinding keyBinding : keyBindings) {
            if ((keyBinding == Minecraft.getMinecraft().gameSettings.keyBindForward
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindBack
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindLeft
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindRight
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindJump
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindSneak) && keyBinding.isPressed()) {
                return true;
            }
        }
        return false;
    }
}
