package com.fox.ysmu.client;

import java.util.List;
import com.fox.ysmu.client.gui.ExtraPlayerConfigScreen;
import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.RemotePlayerAnimationQueries;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
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

    @SubscribeEvent
    public static void onTextureStitchEventPost(TextureStitchEvent.Post event) {
        if (event.map.getTextureType() == 0) {
            pendingModelLoad = true;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !pendingModelLoad) {
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
        // N-02（收敛）：这里原来的 `if (!Config.ENABLE_OPEN_YSM_SYNC_PROTOCOL) ClientModelManager
        // .sendSyncModelMessage();` 已删除。服务端在玩家登录时**无条件**发 RequestSyncModel
        // （model/ServerModelManager.java:119-124），而客户端的 RequestSyncModel.Handler 调用的正是同一个
        // ClientModelManager.sendSyncModelMessage()（network/message/RequestSyncModel.java:29-31），
        // 所以这里再主动触发一次只会让同一批模型在 legacy 通道上被同步/注册两遍（17 打开时更是两条通道各一遍）。
        // legacy 入口并未减少：协议关闭时仍由服务端驱动的 RequestSyncModel 触发；
        // "17 失败才 fallback 到 legacy"的服务端 gate 在 ServerModelManager（model/**，本任务 out of scope）
        // —— 记为跨任务依赖（fix-network 的 N-02 服务端侧）。
    }

    @SubscribeEvent
    public static void onRenderPlayer(SpecialPlayerRenderEvent event) {
        CustomPlayerEntity animatable = event.getCustomPlayer();
        if (isVanillaPlayer(event.getModelId()) && event.getEntity() instanceof AbstractClientPlayer clientPlayer) {
            animatable.setEntity(clientPlayer);
            animatable.setMainModel(ModelIdUtil.getMainId(event.getModelId()));
            ResourceLocation location = clientPlayer.getLocationSkin();
            animatable.setTexture(location);
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
            renderSelfGuiPlayer(renderer, player);
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

    private static void renderSelfGuiPlayer(CustomPlayerRenderer renderer, EntityPlayer player) {
        PlayerPreviousRotationSnapshot snapshot = PlayerPreviousRotationSnapshot.capture(player);
        try {
            syncPreviousRotationsToPreview(player);
            RenderUtil.withGuiEntityLighting(() -> renderer.doRender(
                player,
                0,
                0 - player.yOffset,
                0,
                player.rotationYaw,
                1.0F));
        } finally {
            snapshot.restore(player);
        }
    }

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
            RenderUtil.renderPlayerEntity(player, posX, posY, scale, yawOffset, -500);
        } finally {
            EXTRA_PLAYER = false;
        }
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
     * 清 7 项：模型连接态、远端动画查询、远端动作状态、引擎侧远端 MoLang 变量、NPC 覆盖表、
     * 宿主推送的动画剪辑表，以及 {@link AnimationManager#clearPlayerState()} 里的两张 per-player
     * 进度表（{@code swingProgressByPlayer}/{@code useDurationByPlayer}，ExecPlan 的 A-11，由 t33 交付）。
     * 该方法本身幂等（MANAGER 未创建时判空跳过、其余是 ConcurrentHashMap.clear()），
     * 与本方法上面的两个 Remote* 清理重复但互为 no-op。
     */
    private static void clearClientRuntimeState() {
        ClientModelManager.clearConnectionState();
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
        RemoteAnimationVariables.clear();
        NPCData.clear();
        EntityClips.clear();
        // CU-10 闭环：清掉 AnimationManager 里两个不会自然释放的 per-player 进度表。
        AnimationManager.clearPlayerState();
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
