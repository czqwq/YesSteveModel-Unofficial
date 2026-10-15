package com.fox.ysmu.client.animation.condition;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

/**
 * The {@code vehicle$<entity id>} names, matched against whatever the player is riding.
 * <p>
 * Port of upstream's {@code ConditionalVehicle} ({@code client/animation/condition/ConditionalVehicle.java:20-79});
 * the matching rule is in {@link ConditionalEntityIdMatch}, and the animation it selects -
 * {@code vehicle$minecraft:minecart} and friends - is what upstream's {@code player.vehicle} controller plays
 * ({@code client/animation/predicate/VehiclePredicate.java:64-70}).
 */
public class ConditionalVehicle extends ConditionalEntityIdMatch {

    @Override
    protected String idPrefix() {
        return "vehicle$";
    }

    @Override
    @Nullable
    protected Entity matchedEntity(EntityLivingBase entity) {
        return entity.ridingEntity;
    }
}
