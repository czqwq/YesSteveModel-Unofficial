package com.fox.ysmu.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime;
import com.fox.ysmu.client.audio.YSMSoundManager;
import com.fox.ysmu.client.particle.CustomParticleManager;
import com.fox.ysmu.compat.LocalAssetProvider;
import com.fox.ysmu.data.VehicleModelStates;
import com.fox.ysmu.network.message.S2CVehicleModelState;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import com.fox.ysmu.api.ModelGuiApi;
import com.fox.ysmu.data.EntityClips;
import com.fox.ysmu.data.EntityModelData;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.data.PlayerMotionState;
import com.fox.ysmu.eep.ExtendedAuthModels;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.eep.ExtendedRoamingVariables;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncModelInfo;
import com.fox.ysmu.network.message.SyncNpcDataMessage;
import com.fox.ysmu.network.message.SyncPlayerMotionState;
import com.fox.ysmu.network.message.SyncStarModels;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class CommonEventHandler {

    /**
     * CU-07: 1.7.10 对玩家实体的追踪半径固定为 512 格（EntityTracker.java:73），
     * 模型变更广播必须用同一半径，否则 64~512 格之间的观察者收不到更新。
     */
    private static final double PLAYER_TRACKING_RANGE = 512.0D;

    private static final Map<UUID, Byte> LAST_MOTION_STATES = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerLoggedIn(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player != null) {
            requestModelSync(event.player);
            syncNpcModels(event.player);
            // SHOW_MODEL_LOAD_CHAT（行 46）的服务端半边：登录时报告服务端模型缓存的构建耗时。
            // 参考分支 event/CommonEventHandler.java:36-44 读 ServerModelManager.LAST_LOAD_TIME_MS，
            // 同一个接缝。与 /ysm reload 那条消息不同：这条只有耗时，没有跳过计数（参考的注释也是这么说的）。
            // 两处按本仓/1.7.10 改写：语言串只能用 %s（ChatComponentTranslation.java:121-124 对其它的格式串
            // 直接抛 ChatComponentTranslationFormatException），所以耗时先格式化再传；开关默认关闭，
            // 所以默认不会每个玩家登录都刷一条。
            long loadTimeMs = ServerModelManager.LAST_LOAD_TIME_MS;
            if (loadTimeMs > 0 && Config.SHOW_MODEL_LOAD_CHAT) {
                event.player.addChatMessage(new net.minecraft.util.ChatComponentTranslation(
                    "message.yes_steve_model.model.reload.ready",
                    String.format(java.util.Locale.ROOT, "%.2f", (double) loadTimeMs)));
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.entity != null && event.entity.worldObj != null && !event.entity.worldObj.isRemote) {
            NPCData.remove(event.entity);
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        // CU-10: 任何维度的世界卸载都清理这些按实体挂着的瞬时状态：NPC 模型覆盖、宿主推送的动画剪辑、
        // 拾取器授权。原来只处理 dimensionId == 0，于是"在下界/末地退出服务器或存档"不会清理，
        // 而 1.7.10 的 entityId 跨服务器可复现，旧条目可能套到新会话里 id 相同的实体上。
        if (event.world != null && event.world.provider != null) {
            NPCData.clear();
            // The vehicle overrides are keyed by entity id for the same reason and have the same hazard the comment
            // above describes, so they go with the NPC ones. Upstream never clears its vehicle capability, but there
            // the entity itself carries it; here a stale entry could be read by an unrelated entity that happens to
            // get the same id in the next session.
            VehicleModelStates.clear();
            EntityClips.clear();
            ModelGuiApi.clearAllSelectionGrants();
        }
        // 客户端世界卸载：上一份世界遗留的音效音源与"控制器→音效"归属表、弹射物控制器状态、自定义粒子
        // 都要清掉，否则它们会跟着进下一个世界（SOURCE CommonEventHandler.java:87-93）。
        if (event.world != null && event.world.isRemote) {
            YSMSoundManager.clear();
            ProjectileControllerRuntime.clear();
            CustomParticleManager.clear();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            // 高版本资源健康检查：配置了路径但加载失败时在 chat 提醒玩家（幂等，成本极低）
            LocalAssetProvider.warnIfMisconfigured();
            CustomParticleManager.tick();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            LAST_MOTION_STATES.remove(event.player.getUniqueID());
            OpenYsmModelSyncServer.clear(event.player.getUniqueID());
            // The /ysm sync rate limit is per session, not per UUID for the lifetime of the server: the reference
            // drops a player's cooldown from this same logout handler
            // (network/sync/OpenYsmModelSyncServer.java:112-117 is the method, called from its CommonEventHandler), so
            // a relog does not inherit the cooldown of the session that ended.
            OpenYsmModelSyncServer.clearResyncCooldown(event.player.getUniqueID());
            ModelGuiApi.clearSelectionGrants(event.player);
        }
    }

    @SubscribeEvent
    public static void onEntityConstructing(EntityEvent.EntityConstructing event) {
        if (event.entity instanceof EntityPlayer player) {
            registerPlayerProperties(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (event.wasDeath) { // TODO 跨维度？
            copyPlayerProperties(event.original, event.entityPlayer);
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.target instanceof EntityPlayer trackPlayer) {
            syncTrackedPlayerModelInfo(event.entityPlayer, trackPlayer);
            syncTrackedPlayerMotionState(event.entityPlayer, trackPlayer);
        } else if (event.entityPlayer instanceof EntityPlayerMP viewer && NPCData.contains(event.target)) {
            // A player who was out of range when the override was broadcast still needs it when their client
            // starts tracking the entity, otherwise it only applies on the next login.
            EntityModelData data = NPCData.getData(event.target);
            if (data != null) {
                NetworkHandler.sendNpcData(
                    viewer,
                    event.target.getEntityId(),
                    data.getModelId(),
                    data.getTextureId());
            }
        }
        // The same gap exists for vehicles, and upstream closes it on this very event
        // (event/CapabilityEvent.java:152-159): the state is broadcast when the owner mounts, which is before this
        // client could see the vehicle, so a late tracker is resent it here.
        if (event.entityPlayer instanceof EntityPlayerMP vehicleViewer) {
            S2CVehicleModelState.resendTo(vehicleViewer, event.target);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityPlayer player) {
            syncJoinedPlayerState(player);
        }
    }

    private static int npcPruneTicker;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || ++npcPruneTicker < 1200) {
            return;
        }
        npcPruneTicker = 0;
        long now = System.currentTimeMillis();
        // Entity ids are never reused, so a missing entry is only wasted memory; the grace keeps a
        // temporarily chunk-unloaded entity's override from being pruned.
        NPCData.retainAll(now, id -> {
            WorldServer[] worlds = server.worldServers;
            if (worlds != null) {
                for (WorldServer world : worlds) {
                    if (world != null && world.getEntityByID(id) != null) {
                        return true;
                    }
                }
            }
            return false;
        }, 10L * 60L * 1000L);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (shouldHandleServerEndPlayerTick(event)) {
            syncDirtyMotionState(event.player);
            broadcastDirtyModelInfo(event.player);
        }
    }

    private static void requestModelSync(EntityPlayer player) {
        ServerModelManager.sendRequestSyncModelMessage(player);
    }

    private static void syncNpcModels(EntityPlayer player) {
        if (player instanceof EntityPlayerMP && !NPCData.isEmpty()) {
            NetworkHandler.sendToClientPlayer(new SyncNpcDataMessage(NPCData.snapshot()), player);
        }
    }

    private static void registerPlayerProperties(EntityPlayer player) {
        if (ExtendedModelInfo.get(player) == null) {
            ExtendedModelInfo.register(player);
        }
        if (ExtendedStarModels.get(player) == null) {
            ExtendedStarModels.register(player);
        }
        if (ExtendedRoamingVariables.get(player) == null) {
            ExtendedRoamingVariables.register(player);
        }
        // Forge only calls loadNBTData on an IExtendedEntityProperties that was already registered at
        // EntityConstructing, so a grant table registered lazily is an in-memory table that never persists.
        // `api/ModelAuthorization` reads this one; see `.agent/` task-9 briefing.
        if (ExtendedAuthModels.get(player) == null) {
            ExtendedAuthModels.register(player);
        }
    }

    private static void syncTrackedPlayerMotionState(EntityPlayer trackingPlayer, EntityPlayer trackedPlayer) {
        if (!trackedPlayer.worldObj.isRemote) {
            NetworkHandler.sendToClientPlayer(
                createMotionStateMessage(trackedPlayer, PlayerMotionState.pack(trackedPlayer)),
                trackingPlayer);
        }
    }

    private static void copyPlayerProperties(EntityPlayer oldPlayer, EntityPlayer newPlayer) {
        ExtendedModelInfo oldModelProps = ExtendedModelInfo.get(oldPlayer);
        ExtendedModelInfo newModelProps = ExtendedModelInfo.get(newPlayer);
        if (oldModelProps != null && newModelProps != null) {
            newModelProps.copyFrom(oldModelProps);
        }

        ExtendedStarModels oldStarProps = ExtendedStarModels.get(oldPlayer);
        ExtendedStarModels newStarProps = ExtendedStarModels.get(newPlayer);
        if (oldStarProps != null && newStarProps != null) {
            newStarProps.copyFrom(oldStarProps);
        }

        ExtendedRoamingVariables oldRoamingProps = ExtendedRoamingVariables.get(oldPlayer);
        ExtendedRoamingVariables newRoamingProps = ExtendedRoamingVariables.get(newPlayer);
        if (oldRoamingProps != null && newRoamingProps != null) {
            newRoamingProps.copyFrom(oldRoamingProps);
        }

        // Dimension travel / respawn clones the player, so the grant table has to travel with it; otherwise a
        // granted player loses their grants on every portal and falls back to the default model.
        ExtendedAuthModels oldAuthProps = ExtendedAuthModels.get(oldPlayer);
        ExtendedAuthModels newAuthProps = ExtendedAuthModels.get(newPlayer);
        if (oldAuthProps != null && newAuthProps != null) {
            newAuthProps.copyFrom(oldAuthProps);
        }
    }

    private static void syncTrackedPlayerModelInfo(EntityPlayer trackingPlayer, EntityPlayer trackedPlayer) {
        ExtendedModelInfo modelInfo = ExtendedModelInfo.get(trackedPlayer);
        if (modelInfo != null) {
            SyncModelInfo syncMsg = new SyncModelInfo(trackedPlayer.getEntityId(), modelInfo);
            NetworkHandler.sendToClientPlayer(syncMsg, trackingPlayer);
        }
    }

    private static void syncJoinedPlayerState(EntityPlayer player) {
        // CU-12: 只在服务端发送同步包。客户端的 ExtendedModelInfo.dirty 只写不读
        // （唯一读取点 broadcastDirtyModelInfo 已被 event.side.isServer() 限住），因此客户端不再 markDirty。
        if (player instanceof EntityPlayerMP serverPlayer) {
            ExtendedModelInfo modelInfo = ExtendedModelInfo.get(serverPlayer);
            if (modelInfo != null) {
                NetworkHandler.sendToClientPlayer(new SyncModelInfo(serverPlayer.getEntityId(), modelInfo), serverPlayer);
            }
        }
        syncStarModels(player);
    }

    private static void syncStarModels(EntityPlayer player) {
        ExtendedStarModels starModels = ExtendedStarModels.get(player);
        if (starModels != null && player instanceof EntityPlayerMP serverPlayer) {
            NetworkHandler.sendToClientPlayer(new SyncStarModels(starModels.getStarModels()), serverPlayer);
        }
    }

    private static boolean shouldHandleServerEndPlayerTick(TickEvent.PlayerTickEvent event) {
        return event.player != null && event.side.isServer() && event.phase == TickEvent.Phase.END;
    }

    private static void broadcastDirtyModelInfo(EntityPlayer player) {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isDirty()) {
            SyncModelInfo syncMsg = new SyncModelInfo(player.getEntityId(), eep);
            NetworkHandler.CHANNEL.sendToAllAround(syncMsg, getPlayerTrackingPoint(player));
            eep.setDirty(false);
        }
    }

    private static NetworkRegistry.TargetPoint getPlayerTrackingPoint(EntityPlayer player) {
        return new NetworkRegistry.TargetPoint(
            player.dimension,
            player.posX,
            player.posY,
            player.posZ,
            // CU-07: 必须与 1.7.10 的玩家实体追踪半径一致（EntityTracker.java:73 用 512），
            // 原值 64 会让 64~512 格之间的观察者一直显示旧模型（StartTracking 只在进入追踪范围时补发一次）。
            PLAYER_TRACKING_RANGE
        );
    }

    private static void syncDirtyMotionState(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        byte newState = PlayerMotionState.pack(player);
        Byte oldState = LAST_MOTION_STATES.get(playerId);
        if (oldState == null || oldState.byteValue() != newState) {
            LAST_MOTION_STATES.put(playerId, newState);
            NetworkHandler.CHANNEL.sendToDimension(createMotionStateMessage(player, newState), player.dimension);
        }
    }

    private static SyncPlayerMotionState createMotionStateMessage(EntityPlayer player, byte motionState) {
        return new SyncPlayerMotionState(player.getUniqueID(), motionState);
    }
}
