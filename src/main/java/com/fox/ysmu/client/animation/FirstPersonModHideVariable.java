package com.fox.ysmu.client.animation;

import com.fox.ysmu.client.entity.CustomFirstPersonArmEntity;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;

/**
 * {@code ysm.first_person_mod_hide} - upstream's {@code playerVar("first_person_mod_hide", new
 * FirstPersonModHideVariable())} ({@code tmp/YesSteveModel-dev-1.20 .../client/animation/molang/YSMBinding.java:152}).
 *
 * <p><b>What upstream's value is.</b> The variable answers
 * {@code ctx.animationEvent().getRenderContext().firstPersonMod()}
 * ({@code client/animation/molang/variable/FirstPersonModHideVariable.java:10}), i.e. the render-context flag
 * {@code geckolib3/geo/RenderContext.java:9,59-66}. That flag is set while the mod draws its own first-person pass, and
 * its consumer inside upstream is the same pass: {@code PlayerAnimatableCapability#codeAnimation:77-89} hides the
 * model's {@code firstPersonAllHead} bone and moves the first-person head position while it is true. It is a boolean,
 * not a numeric gate - a pack reads it as a condition, and the shipped pack that does
 * ({@code tmp/可露凯-速度之星v1.1/animations/main.animation.json}, {@code "position":[0.0,
 * "ysm.first_person_mod_hide?2","ysm.first_person_mod_hide?2"]}) uses it exactly that way.
 *
 * <p><b>What the port's value is, and why.</b> 1.7.10 does not render the local player's body in first person at all
 * - vanilla skips it and this port only replaces the arm ({@code FirstPersonHandRenderer}) - so the port's
 * first-person pass <em>is</em> the first-person arm's own frame. That frame is drawn with its own animatable,
 * {@link CustomFirstPersonArmEntity} ({@code FirstPersonHandRenderer#poseFirstPersonArm:282-292} ticks it and
 * {@code CustomFirstPersonArmEntity#tickFirstPersonArm:146-168} poses it), whose animation set is the model's
 * {@code fp_arm} domain. So the flag is 1 exactly while that animatable's frame is being evaluated, and 0 for every
 * other frame - the world body, another player, a GUI preview.
 *
 * <p>The name used to be registered as the constant {@code MolangUtils.FALSE} (commented out at the bottom of
 * {@code AnimationRegister#registerYsmVariables}), which is the "silently always false" half of the same defect: a
 * pack's first-person branch could never be taken. Registering it and wiring this value is what makes the condition
 * true where upstream makes it true.
 *
 * <p><b>What it cannot be.</b> A <em>body</em>-domain clip is never evaluated during the port's first-person pass
 * (the body is not drawn there), so a pack that uses this flag on the body model still reads 0 - which is the
 * platform's answer, not a missing value: there is no frame in which that clip and the first-person pass coincide.
 */
public final class FirstPersonModHideVariable {

    private FirstPersonModHideVariable() {}

    /** 1 while the frame belongs to the first-person arm's own animatable, 0 otherwise. */
    public static double value(AnimationEvent<?> animationEvent) {
        return animationEvent != null && animationEvent.getAnimatable() instanceof CustomFirstPersonArmEntity ? 1.0D
            : 0.0D;
    }
}
