package com.fox.ysmu.client.animation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.util.ModelIdUtil;

/**
 * The domain one of a model's animation sets belongs to.
 *
 * <p>
 * Upstream makes an animation set a first-class identity rather than one entry in a per-model namespace: a
 * {@code player} render target's sets are keyed {@code player/main} - except {@code fp_arm}, which is keyed
 * {@code player/first-person} ({@code model/catalog/content/DefaultAnimationKey.java:36-38}), and the loader sorts the
 * files into exactly those two groups before baking one animation store per group
 * ({@code model/resource/client/render/ModelRenderTargetLoader.java:243-253}), with one condition manager and one
 * controller set per store ({@code ModelRenderTargetAssembler.java:53-60}).
 *
 * <p>
 * The port flattens every set of a model into one {@link software.bernie.geckolib3.file.AnimationFile} under one id,
 * with the later file overwriting the earlier one's clips. That is a defect whenever two sets define the same clip
 * name with different content, and the built-in default model already ships such a pair: {@code main.animation.json}
 * has a {@code parallel0} driving the body ({@code RightEyesBase}, {@code LeftEyesBase}, {@code Breast}) and
 * {@code parallel1} driving {@code Ribbon}, while {@code fp.arm.animation.json} declares {@code parallel0..7} as empty
 * stubs; flattening them in that order (<b>fp_arm</b> is last in {@code builtin/default/ysm.json}'s
 * {@code files.player.animation}) replaces the body's clips with the stubs for every model that does not define its
 * own. This type is that missing notion, kept to upstream's two domains and its one rule for choosing between them.
 */
public final class AnimationDomain {

    /** Every set of the model proper - upstream's {@code player/main}. */
    public static final String MAIN = "player/main";

    /** The {@code fp_arm} set, which drives the first-person arm - upstream's {@code player/first-person}. */
    public static final String FIRST_PERSON = "player/first-person";

    /** Both domains, in the order they are installed. */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(MAIN, FIRST_PERSON));

    /**
     * The animation-set key that names the first-person domain. It is a key of the pack's own
     * {@code files.player.animation} map, and it is the same literal upstream compares against
     * ({@code ModelRenderTargetLoader.java:246}).
     */
    private static final String FIRST_PERSON_SET = "fp_arm";

    private AnimationDomain() {}

    /**
     * The domain an animation-set key belongs to.
     *
     * @param animationSet the pack's own key, for example {@code main}, {@code arm} or {@code fp_arm}
     */
    public static String ofAnimationSet(String animationSet) {
        return FIRST_PERSON_SET.equals(animationSet) ? FIRST_PERSON : MAIN;
    }

    /** Whether {@code domain} is the first-person arm's domain. */
    public static boolean isFirstPerson(String domain) {
        return FIRST_PERSON.equals(domain);
    }

    /**
     * Whether an animation-file id is some model's first-person ({@code arm}) file id.
     *
     * <p>
     * The port reuses the model's {@code arm} id for the first-person domain ({@link #animationFileId}), so the
     * sub-name of the id is what tells the two domains apart. A caller that has to make a per-domain decision about an
     * animatable - which condition manager its armor names live in
     * ({@code client/animation/condition/FPArmConditionManager.java}), or which controller map drives it
     * ({@link #controllerSetId}) - asks this rather than testing {@code instanceof CustomFirstPersonArmEntity}, because
     * the id is the identity the engine and the registries are keyed by.
     *
     * @param animationFileId the id an animatable answers with as its animation file ({@code CustomPlayerEntity#getAnimation})
     */
    public static boolean isFirstPersonId(ResourceLocation animationFileId) {
        return animationFileId != null && "arm".equals(ModelIdUtil.getSubNameFromId(animationFileId));
    }

    /**
     * The id a model's controller set is registered under, for whatever animation-file id an animatable reads.
     *
     * <p>
     * Upstream keeps <em>one</em> controller map per {@code player} render target and hands it to both its player
     * entity and its first-person arm entity: {@code PlayerControllerCollection} and {@code FPArmControllerCollection}
     * are built from the same {@code PlayerModelResources.animationControllers()} through the collections' own
     * {@code ResourceAdapter} ({@code client/controller/collections/FPArmControllerCollection.java:74-76}). The port
     * keys {@code OpenYsmAnimationControllerRegistry} by animation id instead, and the arm's animation id is the
     * model's {@code arm} id - so the arm has to be routed back to the model's {@code main} id to reach the same
     * controller map. Without this the arm's declared {@code fp.arm.*} controllers could never be found.
     *
     * @param animationFileId an animatable's animation-file id; the model proper's id is returned unchanged
     */
    public static ResourceLocation controllerSetId(ResourceLocation animationFileId) {
        if (!isFirstPersonId(animationFileId)) {
            return animationFileId;
        }
        return ModelIdUtil.getMainId(ModelIdUtil.getParentModelId(animationFileId));
    }

    /**
     * The id one domain's animation file is installed under.
     *
     * <p>
     * The main domain keeps the model's {@code main} id, which is what every reader already asks for
     * ({@link ModelIdUtil#getMainId}). The first-person domain uses the model's {@code arm} id - the id the
     * first-person arm's own geometry already lives under ({@code FirstPersonHandRenderer} looks the arm model up by
     * it) - so the two domains are two cache entries and cannot overwrite each other. Animations and geometry are
     * separate caches in the engine, so sharing the id is not a collision.
     *
     * @param modelId the model's own id, not one of its sub-model ids (its {@code main} or {@code arm} id)
     */
    public static ResourceLocation animationFileId(ResourceLocation modelId, String domain) {
        return isFirstPerson(domain) ? ModelIdUtil.getArmId(modelId) : ModelIdUtil.getMainId(modelId);
    }
}
