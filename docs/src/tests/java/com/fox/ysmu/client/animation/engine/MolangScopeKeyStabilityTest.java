package com.fox.ysmu.client.animation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.IMolangPhysicsScope;
import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;
import software.bernie.geckolib3.core.processor.AnimationProcessor;

/**
 * A detached GUI preview must keep its MoLang scope across frames.
 * <p>
 * The scope holds the variables a pack's own scripts assign, and 可露凯-速度之星v1.1 depends on exactly that:
 * {@code parallel1} carries {@code "MRoot":{"scale":"v.roaming.player_size"}} and its {@code timeline} at tick 0
 * self-initialises the variable with {@code v.roaming.player_size=v.roaming.player_size?v.roaming.player_size:1}.
 * That statement runs once per animation restart, so the value has to survive in the scope - if the scope is thrown
 * away and rebuilt, every later frame reads the variable as 0, {@code MRoot} scales to nothing and the whole model
 * blinks out of the tile.
 * <p>
 * A tile gets a fresh id object every frame: {@code RenderUtil.renderModel} calls
 * {@code entity.setMainModel(ModelIdUtil.getMainId(modelId))} on every render, and {@code getMainId} returns a
 * {@code new ResourceLocation}. So the scope key must be built from the ids' <em>values</em>; keying any part of it on
 * {@code System.identityHashCode} makes the key - and therefore the scope - change from frame to frame. A world entity
 * is unaffected because it is keyed on the player's UUID, which is why the same model renders correctly once it is
 * selected as the player's own model.
 */
class MolangScopeKeyStabilityTest {

    /** The id objects a preview hands out, recreated exactly like {@code ModelIdUtil.getMainId} recreates them. */
    private static final class RecreatedIds implements IMolangPhysicsScope {

        @Override
        public EntityLivingBase getMolangEntity() {
            return null;
        }

        @Override
        public ResourceLocation getMolangModelId() {
            return new ResourceLocation("ysmu", "a_model/main");
        }

        @Override
        public ResourceLocation getMolangAnimationId() {
            // The tile answers with the same field getMainModel() returns, so both ids are recreated together.
            return new ResourceLocation("ysmu", "a_model/main");
        }
    }

    /** The scope's processor is never asked for anything here; it only has to be non-null for {@code begin}. */
    private static final class StubAnimatable implements software.bernie.geckolib3.core.IAnimatable {

        @Override
        public void registerControllers(software.bernie.geckolib3.core.manager.AnimationData data) {}

        @Override
        public software.bernie.geckolib3.core.manager.AnimationFactory getFactory() {
            return new software.bernie.geckolib3.core.manager.AnimationFactory(this);
        }
    }

    @AfterEach
    void releaseScope() {
        MolangPhysicsRuntime.end();
        MolangPhysicsRuntime.clear();
    }

    @Test
    void aDetachedPreviewKeepsTheVariablesItsOwnScriptAssigned() {
        IMolangPhysicsScope preview = new RecreatedIds();
        AnimationProcessor<StubAnimatable> processor = new AnimationProcessor<>(null);

        // Frame 1: parallel1's timeline runs once and assigns the roaming variable.
        MolangPhysicsRuntime.begin(preview, 0.0D, processor);
        MolangPhysicsRuntime.setVariable("v.roaming.player_size", 1.0D);
        MolangPhysicsRuntime.end();

        // Frame 2: the same preview, with the same model and animation, but freshly built id objects.
        MolangPhysicsRuntime.begin(preview, 1.0D, processor);
        assertEquals(
            1.0D,
            MolangPhysicsRuntime.getVariable("v.roaming.player_size", -999.0D),
            0.0D,
            "the scope must be keyed on the ids' values, or a preview loses every variable its scripts assigned and "
                + "MRoot.scale=v.roaming.player_size collapses the model to nothing");
    }
}
