package com.fox.ysmu.network.message;

import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.data.VehicleModelStates;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: "this vehicle's owner had model X", the port's counterpart of upstream's
 * {@code VehicleModelState} message (id 27, {@code network/protocol/ProtocolMessages.java:71-72,183-186}, built by
 * {@code network/forge/MinecraftStateHandler.java:37-45} and applied by {@code applyVehicle:107-114}).
 *
 * <p>Why a channel of its own instead of the NPC one ({@code UpdateNpcDataMessage}, ids 95/96): {@code NPCData} stores
 * a {@code (modelId, textureId)} pair and rejects a null texture id ({@code data/NPCData.java:70-77}), because "render
 * this entity as an NPC" means the player renderer's model <em>and</em> one of that model's textures. A vehicle has no
 * per-entity texture choice at all - the texture comes from the pack's own {@code files.vehicles} entry, which the
 * client picks by the sub-model id - so reusing it would mean inventing a texture id to satisfy the shape. Upstream
 * keeps the two apart for the same reason: the vehicle state is its own message and its own capability
 * ({@code capability/VehicleModelInfoCapability.java:7-16}), carrying the owner's model hash and the owner's
 * server-bound molang variables.
 *
 * <p>What is deliberately <b>not</b> carried: the owner's molang variables. Upstream puts them in the same capability
 * ({@code VehicleModelInfoCapability:10,12-16}) so a vehicle animation can read the owner's {@code v.roaming.*}. This
 * port's roaming channel is keyed by {@code (playerUUID, modelKey)} and already reaches every tracking client
 * ({@code S2CRoamingState.broadcast}, clientbound id 20), and this phase has no vehicle animation runtime that would
 * read them; copying the same values into a second message would be two payloads for one fact. Recorded as a
 * limitation rather than invented here - see the task report.
 */
public class S2CVehicleModelState implements IMessage {

    /**
     * The radius the state is broadcast with, matching the one the port's other entity-keyed override uses
     * ({@code NetworkHandler.broadcastNpcData}, {@code NetworkHandler.java:436-449}). A client that receives a state
     * for an entity it does not track simply stores it and never reads it.
     */
    private static final double TRACKING_RANGE = 128.0D;

    private int entityId;
    private String modelId;
    /** The owner at the moment of mounting, or null when the writer had none. */
    private UUID ownerId;

    public S2CVehicleModelState() {}

    public S2CVehicleModelState(int entityId, ResourceLocation modelId, UUID ownerId) {
        this.entityId = entityId;
        this.modelId = modelId == null ? "" : modelId.toString();
        this.ownerId = ownerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // N-10: validate, never throw, and assign only after a complete parse.
        try {
            int parsedEntity = buf.readInt();
            String parsedModel = buf.readBoolean() ? readUtf8(buf) : "";
            UUID parsedOwner = buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
            this.entityId = parsedEntity;
            this.modelId = parsedModel;
            this.ownerId = parsedOwner;
        } catch (Exception e) {
            ysmu.LOG.warn("Discarding malformed YSM vehicle model state packet", e);
            this.modelId = "";
            this.ownerId = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        boolean hasModel = StringUtils.isNotBlank(this.modelId);
        buf.writeBoolean(hasModel);
        if (hasModel) {
            byte[] bytes = this.modelId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
        }
        buf.writeBoolean(this.ownerId != null);
        if (this.ownerId != null) {
            buf.writeLong(this.ownerId.getMostSignificantBits());
            buf.writeLong(this.ownerId.getLeastSignificantBits());
        }
    }

    private static String readUtf8(ByteBuf buf) {
        int length = buf.readInt();
        if (length <= 0 || length > 512) {
            return "";
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public int getEntityId() {
        return entityId;
    }

    public String getModelId() {
        return modelId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    /**
     * The owner just mounted {@code vehicle}: record the owner's model <em>at this moment</em> and tell everyone who
     * can see the vehicle. Server thread only.
     *
     * <p>Upstream's shape: {@code mixin/ServerPlayerMixin.java:14-29} fires after {@code startRiding} places the
     * player, and {@code CapabilityEvent.onVehicleSetModel:244-258} copies the owner's model id into the vehicle's
     * capability and broadcasts it. A player whose model is not resolvable yet is discarded rather than stored, which
     * is upstream's "失败就丢弃" at its line 250.
     */
    public static void onOwnerMounted(Entity vehicle, EntityPlayerMP owner) {
        if (vehicle == null || owner == null || vehicle.worldObj == null || vehicle.worldObj.isRemote) {
            return;
        }
        // Upstream attaches the capability to everything that is neither a player nor a projectile
        // (CapabilityEvent.java:78-81). 1.7.10 has no common projectile base class, so only the player half is
        // filtered here; a projectile a player is riding keeps its own (datawatcher) channel and no vehicle renderer
        // reads this state for it.
        if (vehicle instanceof EntityPlayer) {
            return;
        }
        ExtendedModelInfo info = ExtendedModelInfo.get(owner);
        ResourceLocation model = info == null ? null : info.getModelId();
        if (model == null) {
            return;
        }
        VehicleModelStates.put(vehicle, model, owner.getUniqueID());
        NetworkHandler.sendToTrackingPlayers(
            new S2CVehicleModelState(vehicle.getEntityId(), model, owner.getUniqueID()),
            vehicle,
            TRACKING_RANGE);
    }

    /**
     * One client starts tracking {@code vehicle}: resend the stored state, because the broadcast that created it
     * happened before that client could see the entity. Upstream's counterpart is its {@code StartTracking} handler
     * ({@code event/CapabilityEvent.java:152-159}); 1.7.10 posts the same fact as {@code PlayerEvent.StartTracking}
     * ({@code EntityTrackerEntry.java:456} → {@code ForgeEventFactory.java:209-212}).
     */
    public static void resendTo(EntityPlayerMP player, Entity vehicle) {
        if (player == null || vehicle == null) {
            return;
        }
        VehicleModelStates.State state = VehicleModelStates.get(vehicle);
        if (state == null) {
            return;
        }
        NetworkHandler.sendToClientPlayer(
            new S2CVehicleModelState(vehicle.getEntityId(), state.getModelId(), state.getOwnerId()),
            player);
    }

    /**
     * The owner changed their model while riding: rewrite the vehicle's state so the vehicle follows the owner, which
     * is upstream's {@code CapabilityEvent.java:198-211} (it re-runs {@code onVehicleSetModel} for the vehicle the
     * player is riding whenever the player's model state is rebuilt). Only the owner may rewrite it - upstream checks
     * that the player is the vehicle's first passenger at that point.
     */
    public static void onOwnerModelChanged(EntityPlayerMP owner) {
        if (owner == null || owner.worldObj == null || owner.worldObj.isRemote) {
            return;
        }
        Entity vehicle = owner.ridingEntity;
        if (vehicle == null || vehicle.riddenByEntity != owner) {
            return;
        }
        VehicleModelStates.State state = VehicleModelStates.get(vehicle);
        // A vehicle whose owner is someone else is not this player's to rewrite (upstream's first-passenger test).
        if (state != null && state.getOwnerId() != null && !state.getOwnerId()
            .equals(owner.getUniqueID())) {
            return;
        }
        onOwnerMounted(vehicle, owner);
    }

    public static class Handler implements IMessageHandler<S2CVehicleModelState, IMessage> {

        @Override
        public IMessage onMessage(S2CVehicleModelState message, MessageContext ctx) {
            // N-10: never let a handler body escape into FML's catch(Throwable) -> rejectHandshake. The store is a
            // concurrent map, so the packet thread may write it while the render thread reads.
            try {
                if (ctx.side == Side.CLIENT && StringUtils.isNotBlank(message.modelId)) {
                    VehicleModelStates.put(
                        message.entityId,
                        new ResourceLocation(message.modelId),
                        message.ownerId);
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply synced YSM vehicle model state", e);
            }
            return null;
        }
    }
}
