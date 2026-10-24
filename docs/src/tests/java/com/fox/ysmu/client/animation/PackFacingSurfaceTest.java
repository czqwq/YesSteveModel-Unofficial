package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.EntityQueryStubs.BoatStub;
import com.fox.ysmu.client.animation.EntityQueryStubs.PigStub;
import com.fox.ysmu.client.animation.molang.MolangFrameContext;
import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * A-3 / A-4 / A-5 / A-6: the pack-facing name surface this batch adds to the keyframe path.
 *
 * <p>What is asserted here is the part of the port that can be checked without a client: that every name from the
 * audit's list is <em>registered</em> (an unknown variable is not an error in this legacy parser - it is a silent 0, so
 * "missing" and "legitimately false" look identical to a pack), that the 40 placeholders carry upstream's own defaults
 * name by name, that the values whose source is the frame or its world are live suppliers rather than constants, and
 * that the A-4 function names make a whole expression parse instead of failing.
 *
 * <p>What cannot be checked here is stated where it belongs: a player is needed for the {@code playerVar} names
 * (1.7.10's {@code EntityPlayer} constructor needs a live world) and a client for anything reading {@code Minecraft}.
 * Those values are exercised by the same suppliers; the entity/world stand-ins used below are the ones
 * {@code EntityQueryBindingsTest} already uses.
 */
class PackFacingSurfaceTest {

    /** Every A-5 name, with the class that owns its value as the only grouping. */
    private static final String[] A5_WORLD_NAMES = { "ysm.weather", "ysm.dimension_name", "ysm.block_light",
        "ysm.sky_light", "ysm.is_open_air", "ysm.eye_in_water", "ysm.ladder_facing", "ysm.hit_target_id",
        "ysm.hit_target_type" };

    private static final String[] A5_ENTITY_NAMES = { "ysm.air_supply", "ysm.attack_time", "ysm.attack_damage",
        "ysm.movement_speed", "ysm.knockback_resistance", "ysm.xxa", "ysm.yya", "ysm.zza", "ysm.input_horizontal",
        "ysm.ground_speed2", "ysm.time_delta", "ysm.is_player", "ysm.entity_type" };

    private static final String[] A5_PASS_NAMES = { "ysm.texture_name", "ysm.rendering_in_inventory",
        "ysm.rendering_in_paperdoll" };

    /** A-3 plus the A-6 pair that is not a placeholder. */
    private static final String[] A3_AND_A6_QUERY_NAMES = { "ysm.first_person_mod_hide", "query.any_animation_finished",
        "query.all_animations_finished" };

    /**
     * A-6's 40 placeholders with upstream's exact default for each: {@code name, default}, from the
     * {@code addEmptyBinding} bodies named in {@code AnimationRegister#registerThirdPartyCompatPlaceholders}.
     * A string default is {@code StringUtils.EMPTY}, i.e. the pooled empty string (id 0).
     */
    private static final Object[][] PLACEHOLDERS = {
        // TACZCompat.java:42-50
        { "ctrl.tac_hold_gun", 0.0 }, { "ctrl.tac_gun_type", 0.0 }, { "ctrl.tac_gun_id", 0.0 },
        { "ctrl.tac_is_fire", 0.0 }, { "ctrl.tac_is_aim", 0.0 }, { "ctrl.tac_is_reload", 0.0 },
        { "ctrl.tac_is_melee", 0.0 }, { "ctrl.tac_is_draw", 0.0 }, { "ctrl.tac_fire_mode", 0.0 },
        // ImmersiveMelodiesCompat.java:33-37
        { "ctrl.im_pitch", 0.0 }, { "ctrl.im_volume", 0.0 }, { "ctrl.im_current", 0.0 }, { "ctrl.im_delta", 0.0 },
        { "ctrl.im_time", 0.0 },
        // CarryOnCompat.java:53-54
        { "ctrl.carryon_type", 0.0 }, { "ctrl.carryon_is_princess", 0.0 },
        // SwemCompat.java:41-42
        { "ctrl.swem_is_ride", 0.0 }, { "ctrl.swem_state", 0.0 },
        // CreateCompat.java:34-36, SophisticatedCompat.java:72, IronsSpellBooksCompat.java:44,
        // ParCoolCompat.java:89, SlashBladeCompat.java:80, BetterCombatCompat.java:34
        { "ctrl.create_hanging_skyhook", 0.0 }, { "ctrl.has_sophisticated_backpack", 0.0 },
        { "ctrl.iss_animation", 0.0 }, { "ctrl.parcool_state", 0.0 }, { "ctrl.slashblade_animation", 0.0 },
        { "ctrl.bcombat_attack_animation", 0.0 },
        // TlmClientCompat.java:118-133 - note the two defaults that are not 0
        { "tlm.is_begging", 0.0 }, { "tlm.is_sitting", 0.0 }, { "tlm.has_backpack", 0.0 },
        { "tlm.favorability_point", 0.0 }, { "tlm.favorability_level", 0.0 }, { "tlm.task_id", 0.0 },
        { "tlm.schedule", 0.0 }, { "tlm.activity", 0.0 }, { "tlm.gomoku_win_count", 0.0 }, { "tlm.gomoku_rank", 1.0 },
        { "tlm.game_statue", 0.0 }, { "tlm.backpack_type", 0.0 },
        // is_entity defaults to *true*: a name that is not a maid is an ordinary entity
        { "tlm.is_entity", 1.0 }, { "tlm.is_statue", 0.0 }, { "tlm.is_garage_kit", 0.0 }, { "tlm.show_item", 0.0 } };

    /**
     * Registers the batch onto a fresh parser. Deliberately not {@code registerVariables()}: that one is guarded to run
     * once per JVM, and a test that depends on winning that race (or on another test not clearing the table) would be
     * order-dependent. {@code register} and {@code setValue} are idempotent per name, so re-registering is safe.
     */
    private static void registerBatch() {
        AnimationRegister.registerMolangHooks();
        AnimationRegister.registerPackFacingVariables(new MolangParser());
    }

    private static double value(String name) {
        LazyVariable variable = MolangParser.VARIABLES.get(name);
        assertNotNull(variable, name + " must be registered");
        return variable.get();
    }

    @Test
    void everyNameOfTheAuditListIsRegistered() {
        registerBatch();
        List<String> expected = new ArrayList<>();
        Collections.addAll(expected, A5_WORLD_NAMES);
        Collections.addAll(expected, A5_ENTITY_NAMES);
        Collections.addAll(expected, A5_PASS_NAMES);
        Collections.addAll(expected, A3_AND_A6_QUERY_NAMES);
        for (String name : expected) {
            assertTrue(MolangParser.VARIABLES.containsKey(name), name + " must be registered, or it reads a silent 0");
        }
    }

    /**
     * The 40 placeholders, name by name and value by value. The credential is the count as well as the values: a
     * missing name is the thing upstream registers these for.
     */
    @Test
    void theFortyThirdPartyPlaceholdersCarryUpstreamsOwnDefaults() {
        registerBatch();
        assertEquals(40, PLACEHOLDERS.length, "24 ctrl.* and 16 tlm.* names");
        Set<String> seen = new HashSet<>();
        for (Object[] row : PLACEHOLDERS) {
            String name = (String) row[0];
            double expected = (Double) row[1];
            assertTrue(seen.add(name), name + " must appear once");
            assertEquals(expected, value(name), 0.0D, name + " must answer upstream's default for an absent mod");
        }
        // A registered *constant*: reading it twice cannot change, and no frame is involved.
        MolangFrameContext.end();
        for (Object[] row : PLACEHOLDERS) {
            assertEquals(
                ((Double) row[1]).doubleValue(),
                value((String) row[0]),
                0.0D,
                row[0] + " must stay a constant");
        }
    }

    /** {@code tlm.is_entity} and {@code tlm.gomoku_rank} are the two upstream defaults that are not false/0. */
    @Test
    void theTwoNonZeroPlaceholderDefaultsAreUpstreams() {
        registerBatch();
        assertEquals(1.0D, value("tlm.is_entity"), 0.0D, "TlmClientCompat.java:130 answers true");
        assertEquals(1.0D, value("tlm.gomoku_rank"), 0.0D, "TlmClientCompat.java:127 answers 1");
    }

    /**
     * A-5's entity-backed names are suppliers over the frame's entity, not constants: the same registration answers a
     * stub entity's own values.
     */
    @Test
    void theFrameEntityBackedNamesTrackTheFrameEntity() {
        registerBatch();
        BoatStub boat = new BoatStub();
        boat.setAir(123);
        MolangFrameContext.begin(boat, null);
        try {
            assertEquals(123.0D, value("ysm.air_supply"), 0.0D, "Entity#getAir() is upstream's getAirSupply()");
        } finally {
            MolangFrameContext.end();
        }

        // ysm.ground_speed2 is the same quantity as query.ground_speed: upstream divides its frame position delta by
        // the render tick delta, whose quotient is the per-tick delta 1.7.10 already stores.
        BoatStub moving = new BoatStub();
        moving.prevPosX = 1.0D;
        moving.posX = 1.5D;
        moving.prevPosZ = 2.0D;
        moving.posZ = 2.0D;
        MolangFrameContext.begin(moving, null);
        try {
            assertEquals(EntityQueryBindings.groundSpeed(moving), value("ysm.ground_speed2"), 0.0D);
            assertEquals(10.0D, value("ysm.ground_speed2"), 1.0E-6D, "0.5 blocks per tick is 10 blocks per second");
        } finally {
            MolangFrameContext.end();
        }

        // ysm.xxa is the living entity's own sideways input (EntityLivingBase#moveStrafing).
        PigStub pig = new PigStub();
        pig.moveStrafing = -0.75F;
        MolangFrameContext.begin(pig, null);
        try {
            assertEquals(-0.75D, value("ysm.xxa"), 1.0E-6D);
            // ysm.is_player distinguishes a player from every other living entity, and ysm.entity_type answers the
            // 1.7.10 registry string as a pooled id (that is what a pack's `==` comparison sees).
            assertEquals(0.0D, value("ysm.is_player"), 0.0D);
            assertEquals(
                MolangStringPool.intern(net.minecraft.entity.EntityList.getEntityString(pig)),
                value("ysm.entity_type"),
                0.0D);
        } finally {
            MolangFrameContext.end();
        }
    }

    /** The value outside a published frame is the registered default, never a previous entity's. */
    @Test
    void withoutAFrameTheEntityBackedNamesAnswerTheirDefault() {
        registerBatch();
        MolangFrameContext.end();
        assertEquals(0.0D, value("ysm.air_supply"), 0.0D);
        assertEquals(0.0D, value("ysm.ground_speed2"), 0.0D);
        assertEquals(0.0D, value("ysm.xxa"), 0.0D);
        assertEquals(0.0D, value("ysm.is_player"), 0.0D);
        assertEquals(MolangStringPool.EMPTY_ID, value("ysm.entity_type"), 0.0D);
    }

    /**
     * {@code ysm.time_delta} reads the value the engine publishes as {@code query.delta_time} - upstream binds that
     * one quantity twice ({@code YSMBinding.java:92}, {@code QueryBinding.java:63}), and the task leaves
     * {@code query.delta_time} itself alone. Before the fix the name was not registered at all.
     */
    @Test
    void timeDeltaReadsTheEnginesPublishedFrameDelta() {
        registerBatch();
        MolangParser parser = new MolangParser();
        // The shared table is process-wide, so the previous entry is put back: this test must not change what any
        // other test sees under the engine's own name.
        LazyVariable previous = MolangParser.VARIABLES.get("query.delta_time");
        try {
            parser.setValue("query.delta_time", () -> 0.25D);
            assertEquals(0.25D, value("ysm.time_delta"), 1.0E-9D);
        } finally {
            if (previous == null) {
                MolangParser.VARIABLES.remove("query.delta_time");
            } else {
                MolangParser.VARIABLES.put("query.delta_time", previous);
            }
        }
    }

    /**
     * A-3: {@code ysm.first_person_mod_hide} answers 1 exactly on the first-person arm's frame - the port's
     * first-person pass - and 0 for the body, a preview or nothing.
     */
    @Test
    void firstPersonModHideFollowsTheFirstPersonArmFrame() {
        assertEquals(
            1.0D,
            FirstPersonModHideVariable.value(eventFor(new CustomFirstPersonArmEntity())),
            0.0D,
            "the arm's own frame is the port's first-person pass");
        assertEquals(0.0D, FirstPersonModHideVariable.value(eventFor(new CustomPlayerEntity())), 0.0D);
        assertEquals(0.0D, FirstPersonModHideVariable.value(null), 0.0D);
    }

    /**
     * A-5: {@code ysm.texture_name} is the pack's texture key - the file's base name without its extension, which is
     * how upstream derives it ({@code RawModelAssembler.java:526-528}) - returned as a pooled string id so a pack's
     * {@code ysm.texture_name=='skin_pink'} comparison lands on the same id.
     */
    @Test
    void textureNameIsThePacksTextureKeyWithoutItsExtension() {
        CustomPlayerEntity animatable = new CustomPlayerEntity();
        animatable.setTexture(new ResourceLocation("ysmu", "wine_fox/14_momo/skin_pink.png"));
        assertEquals(
            MolangStringPool.intern("skin_pink"),
            FrameRenderQueries.textureName(eventFor(animatable)),
            0.0D);
        // A folder model whose texture kept its extension, and a name that has no extension at all.
        assertEquals("skin_pink", FrameRenderQueries.textureNameOf(new ResourceLocation("ysmu", "m/skin_pink.jpg")));
        assertEquals("skin", FrameRenderQueries.textureNameOf(new ResourceLocation("ysmu", "m/skin")));
        // Upstream answers null for an animatable that is not its CustomPlayerEntity; here that is the empty id.
        assertEquals(MolangStringPool.EMPTY_ID, FrameRenderQueries.textureName(null), 0.0D);
    }

    /**
     * A-5: the paper-doll / inventory split. Outside any preview bracket neither is set; inside it, a preview with no
     * screen open is the HUD paper doll (a unit JVM has no client, so {@code currentScreen()} is null - which is the
     * HUD doll's own case).
     */
    @Test
    void thePreviewBracketSplitsIntoThePaperDollWhenNoScreenIsOpen() {
        CustomPlayerEntity animatable = new CustomPlayerEntity();
        AnimationEvent<CustomPlayerEntity> event = eventFor(animatable);

        // The pass now travels on the event and is chosen by the entry point that opened the bracket
        // (`CustomPlayerRenderer.beginPreviewRender(RenderContext)` takes it explicitly instead of inferring it from
        // the open screen), so this asserts the pair the queries really read.
        assertEquals(0.0D, FrameRenderQueries.renderingInPaperdoll(event), 0.0D);
        assertEquals(0.0D, FrameRenderQueries.renderingInInventory(event), 0.0D);

        event.setRenderContext(software.bernie.geckolib3.geo.RenderContext.paperDollPass());
        assertEquals(1.0D, FrameRenderQueries.renderingInPaperdoll(event), 0.0D);
        assertEquals(0.0D, FrameRenderQueries.renderingInInventory(event), 0.0D, "the two passes are exclusive");

        event.setRenderContext(software.bernie.geckolib3.geo.RenderContext.inventoryPass());
        assertEquals(0.0D, FrameRenderQueries.renderingInPaperdoll(event), 0.0D, "the previous pass is gone");
        assertEquals(1.0D, FrameRenderQueries.renderingInInventory(event), 0.0D);

        event.setRenderContext(software.bernie.geckolib3.geo.RenderContext.NONE);
        assertEquals(0.0D, FrameRenderQueries.renderingInPaperdoll(event), 0.0D, "outside any bracket neither is set");
        assertEquals(0.0D, FrameRenderQueries.renderingInInventory(event), 0.0D);
    }

    /**
     * A-4: the function names that were missing. An unknown <em>function</em> is not a silent 0 - the whole keyframe
     * expression fails to parse and the channel is dropped - so what is asserted is that the names resolve and that an
     * expression using them parses and evaluates. The bare {@code bone_*} spellings are this port's existing extension
     * and must survive the addition.
     */
    @Test
    void theAddedFunctionNamesParseAndTheBareBoneNamesSurvive() throws Exception {
        registerBatch();
        MolangParser parser = new MolangParser();
        for (String name : new String[] { "ysm.bone_color", "ysm.bone_transparency", "ysm.bone_glow",
            "query.rotation_to_camera", "ysm.defer", "bone_color", "bone_transparency", "bone_glow" }) {
            assertNotNull(parser.functions.get(name), name + " must resolve to a function class");
        }

        // ysm.defer(...) is upstream's Defer, whose handler list nothing populates: the call parses and answers 0.
        assertEquals(0.0D, parser.parseExpression("ysm.defer('fn.motorSynth',1)").get(), 0.0D);
        // query.rotation_to_camera(axis) has upstream's one-argument guard and answers 0 without a client.
        assertEquals(0.0D, parser.parseExpression("query.rotation_to_camera(0)").get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("query.rotation_to_camera(1)").get(), 0.0D);
        assertEquals(0.0D, parser.parseExpression("query.rotation_to_camera(7)").get(), 0.0D);
        // The whole point: an expression that mixes one of them with the rest of a channel still parses.
        assertTrue(parser.parseExpression("ysm.first_person_mod_hide?2:0").get() >= 0.0D);
    }

    /**
     * A-6: {@code query.any_animation_finished} / {@code query.all_animations_finished} on the keyframe path. The
     * values come from the controller the engine is processing, which needs a live animation frame; what is checkable
     * here is that both names are registered and that the binding reads the engine's own completion flag rather than a
     * second invention.
     */
    @Test
    void theAnimationFinishedQueriesAreRegisteredAndBoundToTheEngineController() throws Exception {
        registerBatch();
        assertEquals(0.0D, value("query.any_animation_finished"), 0.0D);
        assertEquals(0.0D, value("query.all_animations_finished"), 0.0D);

        String keyframe = PlayerMotionPredicatesTest.stripComments(
            Files.readString(Path.of("src/main/java/com/fox/ysmu/client/animation/AnimationRegister.java")));
        assertTrue(keyframe.contains("parser.setValue(\"query.any_animation_finished\", () -> finishedAnimation(animationEvent));"));
        assertTrue(
            keyframe.contains("parser.setValue(\"query.all_animations_finished\", () -> finishedAnimation(animationEvent));"));
        assertTrue(
            keyframe.contains("controller.hasCurrentAnimationFinished()"),
            "the value must be the engine's own completion flag");
    }

    private static AnimationEvent<CustomPlayerEntity> eventFor(CustomPlayerEntity animatable) {
        return new AnimationEvent<>(animatable, 0.0F, 0.0F, 0.0F, false, Collections.emptyList());
    }
}
