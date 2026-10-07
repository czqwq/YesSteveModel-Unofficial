package com.fox.ysmu.client.animation;

import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityPig;

/**
 * The two entity doubles the {@link EntityQueryBindings} tests drive, shared so both test classes use the same shape.
 *
 * <p>{@code new EntityBoat(null)} / {@code new EntityPig(null)} are safe in a unit JVM: 1.7.10's {@code Entity(World)}
 * only assigns fields and {@code EntityLivingBase(World)} only applies attributes and positions. The methods these
 * stubs override are the ones {@code EntityQueryBindings.apply} reaches that would dereference the null world
 * ({@code Entity.isWet} calls {@code worldObj.canLightningStrikeAt}, {@code EntityLivingBase.isOnLadder} reads
 * {@code worldObj.getBlock}), plus the two living values the assertions want to be distinguishable from a default.
 *
 * <p>{@code Entity.inWater} is {@code protected}, so each stub exposes the setter its own test needs.
 */
final class EntityQueryStubs {

    private EntityQueryStubs() {}

    /** A non-living entity: answers the entity-level names and "not applicable" for the living/player ones. */
    static final class BoatStub extends EntityBoat {

        BoatStub() {
            super(null);
        }

        void setInWater(boolean inWater) {
            this.inWater = inWater;
        }

        @Override
        public boolean isWet() {
            return this.inWater;
        }
    }

    /** A living non-player: answers the living-only names and 20 for {@code ysm.food_level}. */
    static final class PigStub extends EntityPig {

        PigStub() {
            super(null);
        }

        void setInWater(boolean inWater) {
            this.inWater = inWater;
        }

        @Override
        public boolean isWet() {
            return this.inWater;
        }

        @Override
        public boolean isOnLadder() {
            return true;
        }

        @Override
        public int getTotalArmorValue() {
            return 3;
        }
    }
}
