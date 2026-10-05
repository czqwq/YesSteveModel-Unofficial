package com.fox.ysmu.client.animation.condition;

import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.google.common.collect.Maps;

/**
 * The first-person arm's own condition table - upstream's {@code FPArmConditionManager}.
 *
 * <p>
 * Upstream keeps a second, separate condition manager beside the player's own and feeds each from its own animation
 * store ({@code model/resource/client/render/ModelRenderTargetAssembler.java:53-60}: {@code animations.keySet()
 * .forEach(conditionManager::addTest)} and {@code fpArmAnimations.keySet().forEach(fpArmConditionManager::addTest)}),
 * and the arm's armor predicate reads <em>that</em> one
 * ({@code client/animation/predicate/FPArmArmorPredicate.java:36}: {@code event.getAnimatableEntity()
 * .getFPArmConditionManager().getArmor()}). The split is why a name the {@code fp_arm} set declares
 * ({@code head$mod:item}, {@code head#oreDict}) is tested only for the arm, and a name the model proper declares only
 * for the body.
 *
 * <p>
 * Upstream hangs the manager off the render target, because that is where its two stores live. The port's condition
 * tables are static maps keyed by the animation-file id the entity reads ({@code ConditionManager}), so this is the
 * same shape for the other domain: the key is the model's {@code arm} id, which is
 * {@code AnimationDomain.animationFileId(modelId, FIRST_PERSON)} - the id
 * {@code CustomFirstPersonArmEntity#getAnimationFileId} answers with.
 *
 * <p>
 * Only the armor classifier exists on this side, which is what upstream's manager holds too: it declares
 * {@code armor} and nothing else ({@code FPArmConditionManager.java:3-11}), and the arm's only
 * condition-driven controller family is {@code fp.arm.armor_<slot>}
 * ({@code client/controller/collections/FPArmControllerCollection.java:30-31}).
 */
public final class FPArmConditionManager {

    private static final Map<ResourceLocation, ConditionArmor> ARMOR = Maps.newHashMap();

    private FPArmConditionManager() {}

    /**
     * Classifies one animation name of a model's first-person set, so the name can be tested later.
     * <p>
     * {@code ConditionArmor.addTest} ignores a name that is not a {@code <slot>$<id>} / {@code <slot>#<oreDict>}
     * condition, so this may be handed every clip name of the set, exactly as upstream hands it every key of
     * {@code fpArmAnimations}.
     *
     * @param armId the model's {@code arm} id (the first-person domain's animation-file id)
     */
    public static void addTest(ResourceLocation armId, String name) {
        if (armId == null || name == null) {
            return;
        }
        ARMOR.computeIfAbsent(armId, ignored -> new ConditionArmor())
            .addTest(name);
        // The A-07 observability follows the name into its own domain: a first-person clip whose name no classifier
        // could ever return is reported against the arm's id, exactly as the body's names are reported against the main
        // id. Before the two tables were split, every first-person name was diagnosed as if it belonged to the body.
        ConditionNameDiagnostics.warnIfUnclassifiable(armId, name);
    }

    /**
     * The first-person armor classifier for one model, or {@code null} before that model registered any name.
     * Use {@link ConditionManager#armorFor(ResourceLocation)} rather than choosing a table by hand.
     */
    public static ConditionArmor getArmor(ResourceLocation armId) {
        return armId == null ? null : ARMOR.get(armId);
    }

    /** Drops every model's table; called with the rest of the per-connection state. */
    public static void clear() {
        ARMOR.clear();
    }
}
