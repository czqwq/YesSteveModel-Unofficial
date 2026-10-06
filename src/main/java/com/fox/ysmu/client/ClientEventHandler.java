package com.fox.ysmu.client;

import java.util.List;
import com.fox.ysmu.client.gui.ExtraPlayerConfigScreen;
import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.fox.ysmu.Config;
import com.fox.ysmu.Tags;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.RemotePlayerAnimationQueries;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
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
import com.fox.ysmu.util.ModelIdUtil;
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
        com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime.beginRenderFrame();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Report local roaming-variable changes (the model 模型设置 panel writes them). Done before the early return
        // below, which only guards the one-shot model load. Cheap when nothing changed.
        com.fox.ysmu.client.roaming.ClientRoamingSync.flushPending();
        // Keep playing model sounds centred on the listener; see YSMSoundManager#updateSourcePositions. No-op when
        // nothing is playing.
        com.fox.ysmu.client.audio.YSMSoundManager.updateSourcePositions();
        // Record this as the client thread, so the model manager can refuse to install animations from anywhere else
        // (a host mod may query clips from the server thread).
        ClientModelManager.markClientThread();
        // Install at most a few models that finished building on the loader thread. This is the publish half of
        // on-demand model loading: the parse happens off this thread and only the cache write and the texture upload
        // happen here, a few per tick, so a catalog of models fills in without ever blocking a frame.
        ClientModelManager.tick();
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

    @SubscribeEvent
    public static void onRenderPlayer(SpecialPlayerRenderEvent event) {
        CustomPlayerEntity animatable = event.getCustomPlayer();
        if (isVanillaPlayer(event.getModelId()) && event.getEntity() instanceof AbstractClientPlayer clientPlayer) {
            animatable.setEntity(clientPlayer);
            animatable.setMainModel(ModelIdUtil.getMainId(event.getModelId()));
            // The built-in steve/alex models draw the player's own Minecraft skin, which is not one of the pack's
            // textures. Upstream hands such a texture to the renderer as an override rather than as the model's
            // texture (see VanillaPlayerRenderEvent), which is what keeps it distinguishable from a pack texture that
            // has simply not been uploaded.
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
        if (renderer == null || !renderer.hasModelFor(event.entity)) {
            // No YSM model for this client: leave the frame to the entity's own renderer rather than hiding it.
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
        event.setCanceled(true);
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
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
        // This is a preview render, not the world: it plays the pack's preview animation, whose pose must not leak
        // into the world's own animation state (upstream keeps a separate entity for exactly this reason).
        CustomPlayerRenderer.beginPreviewRender();
        try {
            syncPreviousRotationsToPreview(player);
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
        double posX = Config.PLAYER_POS_X;
        double posY = Config.PLAYER_POS_Y;
        float scale = (float) Config.PLAYER_SCALE;
        float yawOffset = (float) Config.PLAYER_YAW_OFFSET;
        // CU-11: EXTRA_PLAYER 必须在 finally 里复位，否则渲染异常会让它永久为 true，
        // 之后世界内的自身渲染也会被当成 GUI 预览（渲染到 0,0,0 并走 GUI 光照）。
        EXTRA_PLAYER = true;
        try {
            RenderUtil.renderPlayerEntity(player, posX, posY, scale, yawOffset, -500, event.partialTicks);
        } finally {
            EXTRA_PLAYER = false;
        }
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
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isPlayAnimation()) {
            NetworkHandler.CHANNEL.sendToServer(SetPlayAnimation.stop());
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        // 注意：纯多人客户端不会收到这个 FML 事件（1.7.10 只有 ServerConfigurationManager 会 post），
        // 因此真正的兜底是下面的 onClientDisconnect。
        // 音效归属状态按玩家清理：不留该玩家的"控制器→音效"映射与音源。集成服上任何客人登出都会走到
        // 这里，所以只清这一个 owner，不动其他人的音源（SOURCE ClientEventHandler.java:537）。
        if (event.player != null) {
            com.fox.ysmu.client.audio.YSMSoundManager.clearOwner(event.player);
        }
        clearClientRuntimeState();
    }

    /**
     * CU-10（合并 A-10 / NF-02 / D-A1）：客户端断开连接时清理运行期状态。
     * <p>
     * {@code PlayerLoggedOutEvent} 只在服务端（含集成服共享的 FML bus）触发，纯多人客户端永远收不到，
     * 所以这里挂到确实会在客户端触发的 {@link FMLNetworkEvent.ClientDisconnectionFromServerEvent} 上
     * （由 {@code NetworkDispatcher} 在 FML bus 上 post）。该事件只在连接关闭时触发，
     * 不会在连接建立期间被调用。
     */
    @SubscribeEvent
    public static void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        clearClientRuntimeState();
    }

    /**
     * 客户端断开/退出的统一清理入口（只由上面的两个断开事件调用：{@code PlayerLoggedOutEvent}
     * 与 {@link FMLNetworkEvent.ClientDisconnectionFromServerEvent}；join 路径不调用）。
     * <p>
     * 清 8 项：模型连接态、远端动画查询、远端动作状态、引擎侧远端 MoLang 变量、NPC 覆盖表、
     * 宿主推送的动画剪辑表，以及 {@link AnimationManager#clearPlayerState()} 里的两张 per-player
     * 进度表（{@code swingProgressByPlayer}/{@code useDurationByPlayer}，ExecPlan 的 A-11，由 t33 交付）、
     * 客户端音效与音源，最后是进服欢迎消息的一次性标志 {@link #welcomeShown}（见该字段的 javadoc）。
     * 该方法本身幂等（MANAGER 未创建时判空跳过、其余是 ConcurrentHashMap.clear()），
     * 与本方法上面的两个 Remote* 清理重复但互为 no-op。
     */
    private static void clearClientRuntimeState() {
        ClientModelManager.clearConnectionState();
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
        RemoteAnimationVariables.clear();
        // The roaming values belong to the server that just went away, and so do the pending reports for it.
        com.fox.ysmu.client.roaming.ClientRoamingStore.clear();
        NPCData.clear();
        EntityClips.clear();
        // CU-10 闭环：清掉 AnimationManager 里两个不会自然释放的 per-player 进度表。
        AnimationManager.clearPlayerState();
        // Model sounds as well: the sources are still playing in the SoundSystem and their bytes belong to the
        // server that just went away, so leaving them would keep the previous server's audio going and keep a
        // stale owner table alive across the next join.
        com.fox.ysmu.client.audio.YSMSoundManager.clear();
        // The once-per-session welcome flag belongs to the session that just ended, so the next join shows the
        // message again - the state the reference's own field comment ("this session", client/ClientEventHandler.java:53)
        // describes but never implements, because it never writes the field false. See the field's javadoc.
        setWelcomeShown(false);
    }

    private static boolean isVanillaPlayer(ResourceLocation modelId) {
            return modelId.getResourcePath().equals("steve") || modelId.getResourcePath().equals("alex");
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
