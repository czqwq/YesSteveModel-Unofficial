package com.fox.ysmu.client.animation.condition;

import javax.annotation.Nullable;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

/**
 * The {@code passenger$<entity id>} names, matched against the player's own first passenger.
 * <p>
 * Port of upstream's {@code ConditionalPassenger} ({@code client/animation/condition/ConditionalPassenger.java:20-79},
 * whose {@code doTest} reads {@code livingEntity.getFirstPassenger()}); the animation it selects is what upstream's
 * {@code player.passenger} controller plays
 * ({@code client/animation/predicate/PassengerPredicate.java:23-34}).
 */
public class ConditionalPassenger extends ConditionalEntityIdMatch {

    @Override
    protected String idPrefix() {
        return "passenger$";
    }

    @Override
    @Nullable
    protected Entity matchedEntity(EntityLivingBase entity) {
        return entity.riddenByEntity;
    }
}
