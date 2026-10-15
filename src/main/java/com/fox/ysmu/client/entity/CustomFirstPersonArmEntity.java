package com.fox.ysmu.client.entity;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.AnimationDomain;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.condition.ConditionArmor;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.util.ControllerUtils;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * The first-person arm's animatable: the same model and texture as the player, read from the first-person animation
 * domain instead of the main one, with its own controller set and its own per-frame tick.
 *
 * <p>
 * Upstream separates the two the same way: a {@code player} render target keeps a second animation store for its
 * {@code fp_arm} set ({@code model/resource/client/render/ModelRenderTargetLoader.java:251-253}), and its
 * {@code CustomFirstPersonArmEntity} answers {@code getAnimation(name)} from that store rather than from the model's
 * own ({@code client/entity/CustomFirstPersonArmEntity.java:88-91}). The port has one animation file per domain keyed
 * by id ({@link AnimationDomain}), so the arm's counterpart is an animatable whose animation file id is the model's
 * {@code arm} id - the id its arm geometry already lives under.
 *
 * <p>
 * Only the animation file identity differs. The main model id is kept, so every other question this animatable
 * answers - the model's scale, its layer order, its roaming namespace - is the same one the player's own animatable
 * answers.
 */
public class CustomFirstPersonArmEntity extends CustomPlayerEntity {

    /**
     * The model's {@code arm} id, which is where the first-person domain's animation file is installed
     * ({@link AnimationDomain#animationFileId}).
     * <p>
     * {@link #getRequestedMainModel()} is a {@code main} sub-model id (every caller sets the main id), so the model's
     * own id is its parent; asking the domain helper with a {@code main} id would name {@code <model>/main/arm}.
     */
    @Override
    protected ResourceLocation getAnimationFileId() {
        ResourceLocation mainId = getRequestedMainModel();
        if (mainId == null) {
            return null;
        }
        return AnimationDomain.animationFileId(ModelIdUtil.getParentModelId(mainId), AnimationDomain.FIRST_PERSON);
    }

    /**
     * The arm's own controller set, upstream's {@code FPArmControllerCollection}
     * ({@code client/controller/collections/FPArmControllerCollection.java:22-32}): three families, and none of the
     * body's - an arm driven by the player's own {@code player.main} state machine would play the body's
     * idle/walk/run clips against the arm's bones.
     *
     * <p>
     * Every name here is upstream's spelling, because the two runtime lookups key off it: the pack-script binding
     * ({@code PackUserFunctions#scriptForController} turns {@code fp.arm.parallel_0} into
     * {@code fp.arm_ctrl_parallel_0}, upstream's {@code scriptName}) and the model's own declared controller
     * definition ({@code OpenYsmPlayerControllerRuntime#tryApply} resolves the arm's animation id back to the model's
     * controller map through {@link AnimationDomain#controllerSetId}).
     *
     * <p>
     * What each family is, in upstream's words: {@code misc} is the hybrid slot, i.e. a controller the model itself
     * declares as {@code fp.arm.misc} ({@code SingleControllerDiscovery.java:26-50} accepts it only for a declared
     * controller or a bound script), and here that is the port's OpenYSM slot predicate; {@code parallel_<i>} plays the
     * first-person set's {@code parallel<i>} or the model's declared state machine for that slot; {@code armor_<slot>}
     * drives the per-armor-slot clips from the first-person condition table. All three are created with a transition
     * length of 0 and the parallel family with additive rotation
     * ({@code FPArmControllerCollection.java:27-31}: {@code new HybridAnimationController(entity, name, 0, …)} and
     * {@code …, true)} for the parallel one).
     *
     * <p>
     * One deliberate divergence, and it is the port's existing shape rather than a new invention: upstream decides
     * which of these exist while it builds the model's resource set, so an animation with no bone channels
     * ({@link software.bernie.geckolib3.core.builder.Animation#isEmpty}, the built-in default's empty
     * {@code parallel0..7} stubs) gets no controller at all
     * ({@code ParallelControllerDiscovery.java:64-71}). The port registers one fixed set per animatable and routes at
     * runtime, which is what {@code CustomPlayerEntity#registerControllers} does for every other family too - and
     * playing an empty clip leaves every bone at its bind pose, so the visible result is the same.
     */
    @Override
    public void registerControllers(AnimationData data) {
        AnimationManager manager = AnimationManager.getInstance();
        data.addAnimationController(
            new AnimationController<CustomPlayerEntity>(
                this,
                ControllerUtils.FP_ARM_MISC_CONTROLLER,
                0,
                manager.scripted(manager::predicateOpenYsmSlot)));
        for (int i = 0; i < ControllerUtils.FP_ARM_PARALLEL_SLOTS; i++) {
            String controllerName = ControllerUtils.FP_ARM_PARALLEL_CONTROLLER_PREFIX + i;
            String animationName = "parallel" + i;
            data.addAnimationController(
                new AnimationController<CustomPlayerEntity>(
                    this,
                    controllerName,
                    0,
                    manager.scripted(e -> manager.predicateParallel(e, animationName)))
                    // 并行动画族的旋转是相加的，第一人称手臂这一族同样如此（上游
                    // FPArmControllerCollection.java:28-29 给 HybridAnimationController 传 blendRotation=true）。
                    .setAdditiveRotation(true));
        }
        // Upstream iterates the armor slots and keys them by the slot's own name
        // (ArmorControllerDiscovery.java:34-47), which is the same vocabulary ConditionArmor uses here.
        for (int slotIndex = 1; slotIndex <= 4; slotIndex++) {
            String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
            if (slotName.isEmpty()) {
                continue;
            }
            int finalSlotIndex = slotIndex;
            data.addAnimationController(
                new AnimationController<CustomPlayerEntity>(
                    this,
                    ControllerUtils.FP_ARM_ARMOR_CONTROLLER_PREFIX + slotName,
                    0,
                    manager.scripted(e -> manager.predicateArmor(e, finalSlotIndex))));
        }
        wireControllerListeners(data);
    }

    /**
     * Poses this arm for one frame - upstream's {@code CustomFirstPersonArmRenderer#render} does the same two things
     * before it draws ({@code client/renderer/CustomFirstPersonArmRenderer.java:27-28}: {@code checkModelUpdate()} and
     * then {@code armEntity.update(partialTick)}), and {@code update} is what ticks the arm's animation processor.
     *
     * <p>
     * The port splits that differently for one reason: the frame's animation event is built by the engine's renderer
     * ({@code GeoReplacedEntityRenderer#buildAnimationEvent}), not by the entity, so the caller builds it and hands it
     * in. Everything below is the port's counterpart of "tick the arm's own processor":
     * <ol>
     * <li>asking the provider for this arm's geometry is what registers the arm's {@code GeoBone}s with the processor
     * the provider owns ({@code AnimatedGeoModel#getModel:120-129} into {@code registerBone}) and makes the arm the
     * model that processor poses. The port never did this, which is exactly why the arm drew at its bind pose while
     * the rest of the model animated: its bones had never entered an {@code AnimationProcessor};</li>
     * <li>{@code setLivingAnimations} is the port's tick entry - it is where the controller set is evaluated and the
     * bone transforms are written.</li>
     * </ol>
     *
     * @param provider the renderer's model provider, i.e. the one the world path poses the body with
     * @param event    this frame's event, built for <em>this</em> animatable
     */
    public void tickFirstPersonArm(CustomPlayerModel provider, AnimationEvent<CustomPlayerEntity> event) {
        ResourceLocation animationFile = getAnimationFileId();
        if (provider == null || event == null || animationFile == null) {
            return;
        }
        // The id the arm's geometry lives under is the same id its first-person animation file is installed under
        // (AnimationDomain), which is also the id the caller resolved this frame's bone from.
        GeoModel armModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(animationFile);
        if (armModel == null) {
            return;
        }
        if (provider.getModel(animationFile) != armModel) {
            // The provider substituted the built-in default model, which is its own answer for geometry it cannot
            // draw; those are not this arm's bones, so there is nothing to pose this frame.
            return;
        }
        // hashCode() of this animatable, which is the port's unique id rule (`IGeoRenderer#getUniqueID`), and
        // deliberately not the rendered entity's: the body's tick already occupies that slot in the processor's
        // per-frame de-duplication table.
        provider.setLivingAnimations(this, hashCode(), event);
    }

    /**
     * The first-person arm is upstream's plain {@code CustomEntity}, not its {@code CustomHumanoidEntity}
     * ({@code client/entity/CustomFirstPersonArmEntity.java:16}), so its tick publishes the MoLang values without
     * firing the two humanoid script hooks; see {@link CustomPlayerEntity#firesHumanoidHooks()}.
     */
    @Override
    public boolean firesHumanoidHooks() {
        return false;
    }
}
