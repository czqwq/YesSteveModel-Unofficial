package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.EntityQueryStubs.BoatStub;
import com.fox.ysmu.client.animation.EntityQueryStubs.PigStub;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * The two halves of {@link EntityQueryBindings} that are not about which entity answers what: the one binding that
 * takes the render partial tick ({@code ysm.input_vertical}), and the null-argument contract.
 *
 * <p>{@code input_vertical} is upstream's {@code entityVar} (derived from the entity's own movement against the yaw it
 * faces), so it has to work for any entity - not just the local player. The port feeds it the body yaw for a living
 * entity and 1.7.10's interpolated {@code rotationYaw} for anything else, which is the counterpart of upstream's
 * {@code entity.getViewYRot(partialTick)}; the partial-tick parameter is therefore real and is asserted here.
 */
class EntityQueryBindingsInputTest {

    private MolangParser parser;

    @BeforeEach
    void freshTable() {
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
     * The same entity gives 1, 0 and -1 as the partial tick walks a half turn, so the parameter is really
     * interpolated; the living branch uses the body yaw and the non-living one the entity's own yaw.
     */
    @Test
    void inputVerticalFollowsTheEntitysMovementYawAndPartialTicks() {
        // Non-living branch: the interpolated rotationYaw, 1.7.10's counterpart of upstream's getViewYRot.
        BoatStub boat = new BoatStub();
        boat.prevPosX = 0.0D;
        boat.posX = 0.0D;
        boat.prevPosZ = 0.0D;
        boat.posZ = 1.0D; // moving towards +Z, the direction yaw 0 faces
        boat.prevRotationYaw = 0.0F;
        boat.rotationYaw = 180.0F;

        EntityQueryBindings.apply(this.parser, boat, 0.0F);
        assertEquals(1.0D, value("ysm.input_vertical"), 1.0E-4D, "facing along the movement -> cos(0)");

        EntityQueryBindings.apply(this.parser, boat, 1.0F);
        assertEquals(-1.0D, value("ysm.input_vertical"), 1.0E-4D, "a half turn away -> cos(180)");

        EntityQueryBindings.apply(this.parser, boat, 0.5F);
        assertEquals(0.0D, value("ysm.input_vertical"), 1.0E-4D, "the parameter is really interpolated");

        // Living branch: the body yaw (renderYawOffset), same rule.
        PigStub pig = new PigStub();
        pig.prevPosX = 0.0D;
        pig.posX = 0.0D;
        pig.prevPosZ = 0.0D;
        pig.posZ = 1.0D;
        pig.prevRenderYawOffset = 0.0F;
        pig.renderYawOffset = 0.0F;
        EntityQueryBindings.apply(this.parser, pig, 0.0F);
        assertEquals(1.0D, value("ysm.input_vertical"), 1.0E-4D);

        // No displacement means no direction to report, whichever branch it would take.
        pig.posZ = 0.0D;
        EntityQueryBindings.apply(this.parser, pig, 0.0F);
        assertEquals(0.0D, value("ysm.input_vertical"), 1.0E-9D);
    }

    /** Both arguments are optional: a null parser or a null entity must write nothing and throw nothing. */
    @Test
    void nullArgumentsAreIgnoredAndWriteNothing() {
        EntityQueryBindings.apply(null, new BoatStub(), 0.0F);
        assertTrue(MolangParser.VARIABLES.isEmpty(), "a null parser must not be written to");

        EntityQueryBindings.apply(this.parser, null, 0.0F);
        assertTrue(MolangParser.VARIABLES.isEmpty(), "a null entity must not bind anything");

        EntityQueryBindings.apply(null, null, 0.0F);
        assertTrue(MolangParser.VARIABLES.isEmpty());
    }
}
