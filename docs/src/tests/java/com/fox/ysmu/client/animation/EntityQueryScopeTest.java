package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.EntityQueryStubs.BoatStub;
import com.fox.ysmu.client.animation.EntityQueryStubs.PigStub;
import com.fox.ysmu.client.animation.molang.EntityQueryScope;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@link EntityQueryScope}: the <b>lifetime</b> of the entity-scoped {@code query.*} / {@code ysm.*} values.
 *
 * <p>{@link EntityQueryBindingsTest} pins what a publication <em>writes</em>. This class pins what happens to it
 * afterwards, which is the defect batch 3 (§2.6.3) is about: the 61 names live in one process-wide table
 * ({@code MolangParser.VARIABLES}) and every reader that is not a draw - {@code /ysm query}, the debug overlay, a
 * pack's {@code @sync} script - used to read the last drawn entity's values, i.e. a vehicle's or an arrow's state
 * when one was drawn after the player. The two credentials here are:</p>
 * <ol>
 * <li>inside a frame the entity being drawn still answers exactly as before (the in-frame timing is untouched:
 * {@code CustomPlayerModel.java:208} publishes before {@code :210} evaluates);</li>
 * <li>after the frame the table no longer answers for that entity - it holds the values the frame opened on, and the
 * read-shaped publication puts back what it found, so nothing is left behind either way.</li>
 * </ol>
 *
 * <p><b>What cannot be tested here.</b> The reads that need an entity <em>and</em> a client - the debug overlay's
 * local player, a real {@code @sync} packet - need an {@code EntityPlayer}, and 1.7.10's
 * {@code EntityPlayer(World, GameProfile)} dereferences the world on its first lines (see
 * {@code EntityQueryBindingsTest}'s note and {@code YsmCommandTest}'s). Those two call sites are guarded by
 * {@code MolangDebugSnapshotEntityScopeWiringTest} instead, and the "no player" branch they fall back to in a unit JVM
 * is a no-op publication, which is what {@link #aPublicationWithNothingToPublishIsANoOp()} pins.</p>
 */
class EntityQueryScopeTest {

    private MolangParser parser;

    @BeforeEach
    void freshTable() {
        // Disarm whatever a failing test left open first: an open frame would make this test's baseline the previous
        // test's values, and the failure would look like a scope bug rather than a leaked test.
        EntityQueryScope.endFrame();
        MolangParser.VARIABLES.clear();
        this.parser = new MolangParser();
        // Constructing the parser registers constants of its own, so the table is cleared after it exists
        // (same shape as EntityQueryBindingsTest).
        MolangParser.VARIABLES.clear();
        // A physics frame another test left open would make the v.* read below answer that scope instead of the
        // table, so the "a pack variable is not touched" assertion is pinned to "no frame scope is open".
        software.bernie.geckolib3.core.molang.MolangPhysicsRuntime.end();
    }

    @AfterEach
    void clearTable() {
        EntityQueryScope.endFrame();
        MolangParser.VARIABLES.clear();
    }

    private static double read(String name) {
        LazyVariable variable = MolangParser.VARIABLES.get(name);
        assertNotNull(variable, name + " must exist in the table");
        return variable.get();
    }

    private static PigStub pig(float health) {
        PigStub pig = new PigStub();
        pig.setHealth(health);
        return pig;
    }

    /**
     * The in-frame half, unchanged: while a frame is open the drawn entity's own values are what a read sees. This is
     * {@code apply}'s own contract, asserted here because a frame-end restore must not weaken it.
     */
    @Test
    void insideAFrameTheDrawnEntitysOwnValuesAreWhatIsRead() {
        EntityQueryScope.beginFrame();
        assertTrue(EntityQueryScope.isFrameOpen());

        PigStub pig = pig(7.0F);
        pig.setInWater(true);
        pig.setSprinting(true);
        EntityQueryBindings.apply(this.parser, pig, 0.0F);

        assertEquals(7.0D, read("query.health"), 0.0D);
        assertEquals(1.0D, read("query.is_in_water"), 0.0D);
        assertEquals(1.0D, read("query.is_sprinting"), 0.0D);
        assertEquals(20.0D, read("ysm.food_level"), 0.0D, "upstream's answer for a living non-player");
    }

    /**
     * The defect: the frame's last entity must not answer after the frame ended.
     *
     * <p>This is the "frame外读取" credential. The pig's values are seeded by a real {@code apply} through the scope -
     * exactly what the vehicle and projectile renderers do - and once the frame is closed a read answers the value the
     * table held when the frame opened (nothing was registered here, so 0), not the pig's 7.</p>
     */
    @Test
    void afterTheFrameTheLastDrawnEntitysValuesAreGone() {
        EntityQueryScope.beginFrame();
        PigStub pig = pig(7.0F);
        pig.setInWater(true);
        pig.setSprinting(true);
        pig.setSneaking(true);
        pig.onGround = true;
        EntityQueryBindings.apply(this.parser, pig, 0.0F);
        assertEquals(7.0D, read("query.health"), 0.0D, "inside the frame it is the pig's value");

        EntityQueryScope.endFrame();

        assertFalse(EntityQueryScope.isFrameOpen());
        assertEquals(0.0D, read("query.health"), 0.0D, "帧外读取不得再是上一个实体的值");
        assertEquals(0.0D, read("query.is_in_water"), 0.0D);
        assertEquals(0.0D, read("query.is_sprinting"), 0.0D);
        assertEquals(0.0D, read("query.is_sneaking"), 0.0D);
        assertEquals(0.0D, read("query.max_health"), 0.0D);
    }

    /**
     * Two frames in a row: the second entity's frame cannot leave either entity's values behind, and the first
     * frame's values are not resurrected by the second frame's restore.
     */
    @Test
    void aSecondFrameDoesNotResurrectTheFirstFramesEntity() {
        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, pig(7.0F), 0.0F);
        EntityQueryScope.endFrame();
        assertEquals(0.0D, read("query.health"), 0.0D);

        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, pig(3.0F), 0.0F);
        assertEquals(3.0D, read("query.health"), 0.0D, "the second frame answers its own entity");
        EntityQueryScope.endFrame();

        assertEquals(0.0D, read("query.health"), 0.0D, "neither 7 nor 3 may survive the frame");
    }

    /**
     * The frame boundary restores the <b>registered default</b>, not zero.
     *
     * <p>This is why the frame scope snapshots instead of clearing. {@code AnimationRegister} registers
     * {@code ysm.food_level} with 20 ({@code AnimationRegister.java:399}), which is upstream's own answer for a
     * living non-player; a restore that wrote 0 would turn an unpublished read into a different lie than the one it
     * replaces.</p>
     */
    @Test
    void theFrameBoundaryRestoresTheRegisteredDefaultRatherThanZero() {
        this.parser.register(new LazyVariable("ysm.food_level", 20));
        EntityQueryScope.beginFrame();

        // A boat is neither player nor living entity, so apply() publishes 0 over the registered 20.
        EntityQueryBindings.apply(this.parser, new BoatStub(), 0.0F);
        assertEquals(0.0D, read("ysm.food_level"), 0.0D);

        EntityQueryScope.endFrame();

        assertEquals(20.0D, read("ysm.food_level"), 0.0D, "帧末还原的是注册默认值 20，不是 0");
    }

    /**
     * The coverage credential: all 61 published names are put back, name for name, and a pack's own {@code v.*}
     * variable is not touched at all - a roaming variable deliberately outlives a frame ({@code AGENTS.md}).
     */
    @Test
    void everyPublishedNameIsPutBackAndPackVariablesAreNotTouched() {
        int count = EntityQueryBindings.PUBLISHED_NAMES.length;
        double[] seeded = new double[count];
        for (int i = 0; i < count; i++) {
            seeded[i] = 100.0D + i;
            double value = seeded[i];
            this.parser.setValue(EntityQueryBindings.PUBLISHED_NAMES[i], () -> value);
        }
        this.parser.setValue("v.roaming.probe", () -> 5.0D);

        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, new BoatStub(), 0.0F);
        EntityQueryScope.endFrame();

        for (int i = 0; i < count; i++) {
            assertEquals(
                seeded[i],
                read(EntityQueryBindings.PUBLISHED_NAMES[i]),
                0.0D,
                EntityQueryBindings.PUBLISHED_NAMES[i] + " must be put back");
        }
        assertEquals(5.0D, read("v.roaming.probe"), 0.0D, "a pack variable outlives the frame and is not restored");
    }

    /**
     * The restore lands on the {@link LazyVariable} object a parsed expression cached, not on a replacement.
     * {@link EntityQueryBindingsTest#rebindingPreservesParsedVariableIdentityAndRestoresTheNextFramesSupplier} pins
     * the same identity for a re-publication; this is the frame boundary's half of it.
     */
    @Test
    void theRestoreLandsOnTheCachedVariableObject() {
        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, pig(7.0F), 0.0F);
        LazyVariable cached = MolangParser.VARIABLES.get("query.health");
        assertNotNull(cached);

        EntityQueryScope.endFrame();

        assertSame(cached, MolangParser.VARIABLES.get("query.health"), "identity must survive the restore");
        assertEquals(0.0D, cached.get(), 0.0D, "and the cached object must see the restored value");
    }

    /**
     * The read-shaped half: a publication answers for the entity it was asked about and leaves nothing behind.
     *
     * <p>This is what {@code /ysm query}, the debug overlay and {@code @sync} use. The baseline here is a table with no
     * publication at all, which is the state the frame boundary maintains between frames; after the read the same
     * state must be back - not the read's entity.</p>
     */
    @Test
    void anOffFrameReadPublishesItsOwnEntityAndLeavesNothingBehind() {
        PigStub pig = pig(7.0F);

        try (EntityQueryScope.Publication publication = EntityQueryScope.publishForRead(this.parser, pig)) {
            assertNotNull(publication);
            assertEquals(7.0D, read("query.health"), 0.0D, "读取期间回答的是被问的那个实体");
        }

        assertEquals(0.0D, read("query.health"), 0.0D, "读完不留下那个实体");

        BoatStub boat = new BoatStub();
        boat.setInWater(true);
        try (EntityQueryScope.Publication publication = EntityQueryScope.publishForRead(this.parser, boat)) {
            assertEquals(1.0D, read("query.is_in_water"), 0.0D);
            assertEquals(0.0D, read("query.health"), 0.0D,
                "the boat's own read: not a living entity, so not the pig's 7 either");
        }
        assertEquals(0.0D, read("query.is_in_water"), 0.0D);
    }

    /**
     * A read that happens <em>inside</em> an open frame - the debug overlay draws after every draw - puts the frame's
     * own values back. What is restored there is the value the table held, not that entity's suppliers, which is safe
     * because every draw publishes its own values before it evaluates anything.
     */
    @Test
    void anInFrameReadPutsTheFramesOwnValuesBack() {
        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, pig(7.0F), 0.0F);

        try (EntityQueryScope.Publication publication = EntityQueryScope.publishForRead(this.parser, pig(3.0F))) {
            assertEquals(3.0D, read("query.health"), 0.0D, "the read answers its own entity");
        }

        assertEquals(7.0D, read("query.health"), 0.0D, "the frame's entity is back afterwards");
        EntityQueryScope.endFrame();
        assertEquals(0.0D, read("query.health"), 0.0D, "and the frame boundary still ends the publication");
    }

    /**
     * A frame whose END never ran (the render path threw) must not make the half-finished frame the new baseline: the
     * second {@code beginFrame} keeps the first snapshot, so the values restored at the next END are still the ones the
     * table held <em>before</em> any of it.
     */
    @Test
    void aMissingFrameEndDoesNotPoisonTheSnapshot() {
        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, pig(7.0F), 0.0F);

        // No endFrame(): the previous frame never closed.
        EntityQueryScope.beginFrame();
        EntityQueryBindings.apply(this.parser, pig(3.0F), 0.0F);

        EntityQueryScope.endFrame();

        assertEquals(0.0D, read("query.health"), 0.0D, "the first snapshot is the one that must be put back");
        assertFalse(EntityQueryScope.isFrameOpen());
    }

    /** An END with no open frame changes nothing, so the pair cannot corrupt the table by running unbalanced. */
    @Test
    void anEndWithNoOpenFrameChangesNothing() {
        this.parser.setValue("query.health", () -> 9.0D);

        EntityQueryScope.endFrame();

        assertEquals(9.0D, read("query.health"), 0.0D);
        assertFalse(EntityQueryScope.isFrameOpen());
    }

    /**
     * The call sites that may have no entity (a unit JVM, a client that is not in a world) get a publication they can
     * still close, and it publishes nothing.
     */
    @Test
    void aPublicationWithNothingToPublishIsANoOp() {
        this.parser.setValue("query.health", () -> 9.0D);

        assertSame(EntityQueryScope.NOTHING, EntityQueryScope.publishForRead(null, pig(7.0F)));
        assertSame(EntityQueryScope.NOTHING, EntityQueryScope.publishForRead(this.parser, null));
        try (EntityQueryScope.Publication publication = EntityQueryScope.publishForRead(null, null)) {
            assertSame(EntityQueryScope.NOTHING, publication);
            assertEquals(9.0D, read("query.health"), 0.0D, "an empty publication leaves the table alone");
        }
        assertEquals(9.0D, read("query.health"), 0.0D);
    }
}
