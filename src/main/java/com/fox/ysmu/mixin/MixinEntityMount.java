package com.fox.ysmu.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.network.message.S2CVehicleModelState;

/**
 * Records the owner's model on a vehicle when a player mounts it - the port's counterpart of upstream's
 * {@code mixin/ServerPlayerMixin.java:14-29}.
 *
 * <p>Upstream injects after {@code ServerPlayer#startRiding} places the rider, and only for the vehicle's first
 * passenger. 1.7.10 has no equivalent event: {@code EntityMountEvent} does not exist in this Forge
 * (a search of {@code build/rfg} for it finds nothing), and {@code startRiding} is not a 1.7.10 method at all. The
 * one shared entry point is {@link Entity#mountEntity(Entity)}, which <b>is</b> the rider calling it with the vehicle
 * as its argument, on both sides ({@code EntityPlayerMP#mountEntity} from the server, and
 * {@code NetHandlerPlayClient#handleEntityAttach:903-933} on the client), so the injection is guarded to the server
 * and to a player rider, exactly the pair upstream requires.
 *
 * <p>Why the extra {@code ridingEntity}/{@code riddenByEntity} test: {@code Entity#mountEntity} can return early
 * (its cycle check at {@code Entity.java:1916-1925}) or be called with {@code null} to dismount
 * ({@code Entity.java:1899-1908}), and the tail of the method runs either way. The test asks the same question
 * upstream asks - "is this player the vehicle's rider now" - and skips the write when the mount did not happen.
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class MixinEntityMount {

    @Inject(method = "mountEntity(Lnet/minecraft/entity/Entity;)V", at = @At("TAIL"))
    private void ysmu$onMount(Entity vehicle, CallbackInfo ci) {
        Entity rider = (Entity) (Object) this;
        if (rider.worldObj == null || rider.worldObj.isRemote) {
            return;
        }
        if (!(rider instanceof EntityPlayerMP)) {
            return;
        }
        if (vehicle == null || rider.ridingEntity != vehicle || vehicle.riddenByEntity != rider) {
            return;
        }
        S2CVehicleModelState.onOwnerMounted(vehicle, (EntityPlayerMP) rider);
    }
}
