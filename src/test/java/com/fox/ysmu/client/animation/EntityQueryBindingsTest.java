package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.EntityQueryStubs.BoatStub;
import com.fox.ysmu.client.animation.EntityQueryStubs.PigStub;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@link EntityQueryBindings}: the {@code query.*} / {@code ysm.*} values that belong to the entity being drawn.
 *
 * <p>This class exists because a sub-entity frame (an arrow, a vehicle) never went through the player path, so its
 * animation read whatever the local player's frame had left in the shared {@code MolangParser.VARIABLES} table - a
 * boat asking {@code query.is_in_water} answered with the <em>player's</em> state. The assertions here are the three
 * faces of the fix:
 * <ol>
 * <li>the published list is exactly the set of names {@code apply} writes (the boundary is checkable, not implied);</li>
 * <li>applying for one entity replaces every value the previous entity owned (nothing survives a frame change);</li>
 * <li>each name answers what upstream's typed registration answers for that kind of entity - a boat gets
 * {@code 0}/false for the living- and player-only names, a living non-player gets 20 for {@code ysm.food_level}.</li>
 * </ol>
 *
 * <p>{@code ysm.input_vertical} (the one binding that takes the partial tick) and the null-argument contract are in
 * {@link EntityQueryBindingsInputTest}; the entity doubles are in {@link EntityQueryStubs}.
 *
 * <p><b>What cannot be tested here.</b> The player-only expressions themselves (the six {@code ysm.has_*}, a player's
 * {@code ysm.food_level}, {@code query.player_level}, {@code ysm.armor_value} for a player, {@code ysm.input_vertical}
 * on the player's body-yaw branch) need an {@code EntityPlayer}, and 1.7.10's
 * {@code EntityPlayer(World, GameProfile)} dereferences {@code World.isRemote} and {@code World.getSpawnPoint()} on its
 * first lines ({@code build/rfg/minecraft-src/java/net/minecraft/entity/player/EntityPlayer.java:163-176}), so it
 * cannot be constructed in a unit JVM - the same limit {@code YsmCommandTest} records. The regression test below
 * therefore reproduces the pre-fix state by <em>seeding</em> the table with the values a player's frame would have
 * bound, which is exactly what the fix has to overwrite.
 */
class EntityQueryBindingsTest {

    private MolangParser parser;

    @BeforeEach
    void freshTable() {
        // Constructing the parser registers constants of its own, so the table is cleared after it exists.
        this.parser = new MolangParser();
        MolangParser.VARIABLES.clear();
    }

    @AfterEach
    void clearTable() {
        MolangParser.VARIABLES.clear();
    }

    private double value(String name) {
        LazyVariable variable = MolangParser.VARIABLES.get(name);
        assertNotNull(variable, name + " must have been bound by apply()");
        return variable.get();
    }

    /**
     * The credential: {@link EntityQueryBindings#PUBLISHED_NAMES} must be exactly the names {@code apply} binds.
     * <p>
     * A name bound but not listed makes the documented boundary a lie; a name listed but not bound makes a caller that
     * trusts the list read a stale value. Both directions are checked against the table the call actually writes.
     */
    @Test
    void publishedNamesAreExactlyTheNamesApplyBinds() {
        EntityQueryBindings.apply(this.parser, new BoatStub(), 0.0F);

        Set<String> bound = new HashSet<>(MolangParser.VARIABLES.keySet());
        Set<String> declared = new HashSet<>(Arrays.asList(EntityQueryBindings.PUBLISHED_NAMES));

        assertEquals(59, EntityQueryBindings.PUBLISHED_NAMES.length, "the count is part of the credential");
        assertEquals(EntityQueryBindings.PUBLISHED_NAMES.length, declared.size(), "the list must not repeat a name");
        assertEquals(declared, bound, "PUBLISHED_NAMES must be exactly what apply() writes");
    }

    /**
     * The regression: the frame's own entity must overwrite every value the previous frame left behind.
     * <p>
     * The values seeded here are a local player's frame - in water, carrying a rider, 20 health, a helmet, full food -
     * which is what a sub-entity used to read. After {@code apply} for the boat all five are the boat's own answers.
     */
    @Test
    void aSubEntitysFrameCannotKeepThePreviousFramesValues() {
        this.parser.setValue("query.is_in_water", () -> 1.0D);
        this.parser.setValue("query.has_rider", () -> 1.0D);
        this.parser.setValue("query.health", () -> 20.0D);
        this.parser.setValue("ysm.has_helmet", () -> 1.0D);
        this.parser.setValue("ysm.food_level", () -> 20.0D);

        EntityQueryBindings.apply(this.parser, new BoatStub(), 0.0F);

        assertEquals(0.0D, value("query.is_in_water"), 0.0D, "the boat is not in water");
        assertEquals(0.0D, value("query.has_rider"), 0.0D, "the boat carries nobody");
        assertEquals(0.0D, value("query.health"), 0.0D, "a boat is not a living entity");
        assertEquals(0.0D, value("ysm.has_helmet"), 0.0D, "a boat is not a player");
        assertEquals(0.0D, value("ysm.food_level"), 0.0D, "a boat is neither player nor living entity");
    }

    /** Two real entities in a row: the second one's values replace the first one's, name for name. */
    @Test
    void applyingAnotherEntityReplacesEveryValueThatEntityOwns() {
        PigStub pig = new PigStub();
        pig.setInWater(true);
        pig.setHealth(7.0F);
        pig.riddenByEntity = new BoatStub();
        pig.setSprinting(true);

        EntityQueryBindings.apply(this.parser, pig, 0.0F);
        assertEquals(1.0D, value("query.is_in_water"), 0.0D);
        assertEquals(1.0D, value("query.has_rider"), 0.0D);
        assertEquals(7.0D, value("query.health"), 0.0D);
        assertEquals(1.0D, value("query.is_sprinting"), 0.0D);
        assertEquals(3.0D, value("ysm.armor_value"), 0.0D);
        assertEquals(20.0D, value("ysm.food_level"), 0.0D);

        EntityQueryBindings.apply(this.parser, new BoatStub(), 0.0F);

        assertEquals(0.0D, value("query.is_in_water"), 0.0D, "the pig's water state must not survive");
        assertEquals(0.0D, value("query.has_rider"), 0.0D);
        assertEquals(0.0D, value("query.health"), 0.0D);
        assertEquals(0.0D, value("query.is_sprinting"), 0.0D);
        assertEquals(0.0D, value("ysm.armor_value"), 0.0D);
        assertEquals(0.0D, value("ysm.food_level"), 0.0D);
    }

    /** A living non-player answers its own living values, and 20 for {@code ysm.food_level} (upstream's own answer). */
    @Test
    void aLivingNonPlayerAnswersTwentyForFoodLevelAndItsOwnLivingValues() {
        PigStub pig = new PigStub();
        pig.setHealth(7.0F);
        pig.setSprinting(true);
        pig.setSneaking(true);
        pig.onGround = true;
        pig.hurtTime = 4;
        pig.isSwingInProgress = true;
        pig.swingProgressInt = 2;

        EntityQueryBindings.apply(this.parser, pig, 0.0F);

        assertEquals(20.0D, value("ysm.food_level"), 0.0D, "upstream answers 20 for a non-player living entity");
        assertEquals(7.0D, value("query.health"), 0.0D);
        assertEquals(10.0D, value("query.max_health"), 0.0D, "the pig's own attribute");
        assertEquals(3.0D, value("ysm.armor_value"), 0.0D);
        assertEquals(1.0D, value("query.is_sprinting"), 0.0D);
        assertEquals(1.0D, value("query.is_sneaking"), 0.0D);
        assertEquals(1.0D, value("ysm.is_sneak"), 0.0D);
        assertEquals(1.0D, value("ysm.swinging"), 0.0D);
        assertEquals(2.0D, value("ysm.swing_time"), 0.0D);
        assertEquals(4.0D, value("query.hurt_time"), 0.0D);
        assertEquals(1.0D, value("ysm.on_ladder"), 0.0D, "a living entity answers isOnLadder()");

        // The player-only names stay "not applicable" for a living non-player.
        assertEquals(0.0D, value("ysm.has_helmet"), 0.0D);
        assertEquals(0.0D, value("query.player_level"), 0.0D);
        assertEquals(0.0D, value("query.is_eating"), 0.0D);
        assertEquals(0.0D, value("query.has_cape"), 0.0D);
    }

    /** Every living- and player-guarded name answers 0 for a boat, rather than the local player's value. */
    @Test
    void aBoatAnswersNotApplicableForEveryLivingAndPlayerGuardedName() {
        EntityQueryBindings.apply(this.parser, new BoatStub(), 0.0F);

        String[] notApplicable = {
            "query.health", "query.max_health", "query.hurt_time", "query.is_sprinting", "query.is_sneaking",
            "query.is_sleeping", "ysm.is_sneak", "ysm.is_sleep", "ysm.armor_value", "ysm.hurt_time", "ysm.swinging",
            "ysm.swing_time", "ysm.mainhand_charged_crossbow", "ysm.food_level",
            "query.is_eating", "query.is_using_item", "query.is_blocking", "query.item_in_use_duration",
            "query.item_max_use_duration", "query.item_remaining_use_duration", "query.equipment_count",
            "query.has_cape", "query.player_level", "ysm.has_helmet", "ysm.has_chest_plate", "ysm.has_leggings",
            "ysm.has_boots", "ysm.has_mainhand", "ysm.has_offhand", "ysm.swinging_arm",
            "ysm.offhand_charged_crossbow", "ysm.is_fishing", "ysm.has_elytra", "ysm.elytra_rot_x",
            "ysm.elytra_rot_y", "ysm.elytra_rot_z", "ysm.on_ladder" };
        for (String name : notApplicable) {
            assertEquals(0.0D, value(name), 0.0D, name + " must answer 0 for a boat");
        }
    }

    /** The entity-level names are read off the entity, including the per-tick deltas the port uses for speed. */
    @Test
    void entityLevelNamesFollowTheEntityBeingDrawn() {
        BoatStub boat = new BoatStub();
        boat.setInWater(true);
        boat.onGround = true;
        boat.riddenByEntity = new BoatStub();
        boat.prevPosX = 0.0D;
        boat.posX = 0.5D;
        boat.prevPosY = 0.0D;
        boat.posY = 0.25D;
        boat.prevPosZ = 0.0D;
        boat.posZ = 0.0D;
        boat.prevRotationYaw = 10.0F;
        boat.rotationYaw = 40.0F;
        boat.rotationPitch = 15.0F;

        EntityQueryBindings.apply(this.parser, boat, 0.0F);

        assertEquals(1.0D, value("query.is_in_water"), 0.0D);
        assertEquals(1.0D, value("query.is_in_water_or_rain"), 0.0D);
        assertEquals(1.0D, value("query.has_rider"), 0.0D);
        assertEquals(1.0D, value("query.is_on_ground"), 0.0D);
        assertEquals(10.0D, value("query.ground_speed"), 1.0E-9D, "0.5 blocks per tick x 20");
        assertEquals(5.0D, value("query.vertical_speed"), 1.0E-9D, "0.25 blocks per tick x 20");
        assertEquals(30.0D, value("query.yaw_speed"), 1.0E-4D, "the per-tick yaw delta");
        assertEquals(40.0D, value("query.body_y_rotation"), 1.0E-4D);
        assertEquals(15.0D, value("query.body_x_rotation"), 1.0E-4D);
        assertEquals(
            Math.sqrt(0.5D * 0.5D + 0.25D * 0.25D),
            value("ysm.delta_movement_length"),
            1.0E-9D,
            "the three-axis per-tick distance");
    }
}
