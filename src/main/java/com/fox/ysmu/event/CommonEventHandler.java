package com.fox.ysmu.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
import com.fox.ysmu.eep.ExtendedModelInfo;
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

    private static final Map<UUID, Byte> LAST_MOTION_STATES = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerLoggedIn(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player != null) {
            requestModelSync(event.player);
            syncNpcModels(event.player);
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
        // The overworld unload marks leaving a save, so this is the point to drop transient per-entity state:
        // NPC model overrides, the animation clips a host mod pushed for them, and picker grants.
        if (event.world != null && event.world.provider != null && event.world.provider.dimensionId == 0) {
            NPCData.clear();
            EntityClips.clear();
            ModelGuiApi.clearAllSelectionGrants();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            LAST_MOTION_STATES.remove(event.player.getUniqueID());
            OpenYsmModelSyncServer.clear(event.player.getUniqueID());
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
    }

    private static void syncTrackedPlayerModelInfo(EntityPlayer trackingPlayer, EntityPlayer trackedPlayer) {
        ExtendedModelInfo modelInfo = ExtendedModelInfo.get(trackedPlayer);
        if (modelInfo != null) {
            SyncModelInfo syncMsg = new SyncModelInfo(trackedPlayer.getEntityId(), modelInfo);
            NetworkHandler.sendToClientPlayer(syncMsg, trackingPlayer);
        }
    }

    private static void syncJoinedPlayerState(EntityPlayer player) {
        ExtendedModelInfo modelInfo = ExtendedModelInfo.get(player);
        if (modelInfo != null) {
            if (player instanceof EntityPlayerMP serverPlayer) {
                NetworkHandler.sendToClientPlayer(new SyncModelInfo(serverPlayer.getEntityId(), modelInfo), serverPlayer);
            } else {
                modelInfo.markDirty();
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
            64.0D // 64个方块的范围，这是一个常用值
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
