package software.bernie.geckolib3.geo.render.built;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;

public class GeoModel implements Serializable {

    private static final long serialVersionUID = 42L;
    public List<GeoBone> topLevelBones = new ArrayList<>();
    public List<GeoBone> leftHandBones = new ObjectArrayList<>();
    public List<GeoBone> rightHandBones = new ObjectArrayList<>();
    public List<GeoBone> elytraBones = new ObjectArrayList<>();
    @Nullable
    public GeoBone firstPersonHead = null;
    @Nullable
    public GeoBone firstPersonViewLocator = null;
    public ModelProperties properties;

    /**
     * Lazily built locator-group index, keyed by group base name (see {@link #locatorGroup(String)}).
     * <p>
     * {@code transient} on purpose: this graph is written to the client's baked-geometry cache with default Java
     * serialisation, and the accessor rebuilds the index from {@link #topLevelBones} on demand. A field that took
     * part in serialisation would either change the cache ABI or come back {@code null} from an entry written before
     * it existed; a transient one is simply absent from both old and new streams, so the bytes on disk are
     * unchanged and every deserialised model can answer the same query.
     */
    private transient volatile Map<String, List<GeoBone>> locatorGroups;

    public boolean hasTopLevelBone(String name) {
        return topLevelBones.stream()
            .anyMatch(bone -> bone.name.equals(name));
    }

    public Optional<GeoBone> getTopLevelBone(String name) {
        for (GeoBone bone : topLevelBones) {
            if (bone.name.equals(name)) {
                return Optional.of(bone);
            }
        }
        return Optional.empty();
    }

    public Optional<GeoBone> getBone(String name) {
        for (GeoBone bone : topLevelBones) {
            GeoBone optionalBone = getBoneRecursively(name, bone);
            if (optionalBone != null) {
                return Optional.of(optionalBone);
            }
        }
        return Optional.empty();
    }

    /**
     * Every bone in one locator group, in model traversal order - the port's version of the grouping upstream derives
     * from a bone's name.
     * <p>
     * Upstream registers named locator groups on its model state and resolves a bone to a group by <b>stripping the
     * bone name's trailing digits</b> and matching the remainder
     * ({@code tmp/YesSteveModel-dev-1.20/.../geckolib3/geo/render/built/GeoLocatorType.java:34-47}); its humanoid
     * groups are {@code Head}, {@code LeftHandLocator}, {@code RightHandLocator}, {@code BackpackLocator},
     * {@code ElytraLocator}, {@code PistolLocator}, {@code RifleLocator}, {@code LeftWaistLocator},
     * {@code RightWaistLocator}, {@code LeftShoulderLocator}, {@code RightShoulderLocator}, {@code BladeLocator} and
     * {@code SheathLocator} ({@code client/model/locator/PlayerLocator.java:13-25}). So {@code Head2} belongs to the
     * {@code Head} group and {@code LeftHandLocator2} to {@code LeftHandLocator} - a pack expresses "another one" by
     * numbering the bone, and upstream's consumers act on every member of the group
     * ({@code client/entity/CustomHumanoidEntity.java:68-89} rotates each head; the item-in-hand layer draws the held
     * stack at each hand locator).
     * <p>
     * The rule is the exact one upstream applies, including the edge cases: a bone whose name is all digits keeps its
     * name (no group matches it), a name with no trailing digits must equal {@code baseName} exactly, and an empty or
     * {@code null} name matches nothing.
     * <p>
     * <b>Usage for consumers</b> (e.g. the head group rotation in
     * {@code CustomPlayerModel#codeAnimation} or a layer that wants every hand locator):
     * <pre>{@code
     * for (GeoBone bone : model.locatorGroup("Head")) {
     *     // same GeoBone instances the AnimationProcessor registers - writing a rotation here reaches the draw
     * }
     * }</pre>
     * The returned list is unmodifiable and cached; it is empty when the model declares no matching bone, which is
     * what every existing call site already treats as "no locator".
     * <p>
     * The three legacy chains this model already carried ({@link #leftHandBones}, {@link #rightHandBones},
     * {@link #elytraBones}) are untouched and keep their current meaning: one upward parent chain for the first
     * exact-named locator. This accessor is the upstream-shaped alternative and can be wired consumer by consumer.
     *
     * @param baseName group name without a numeric suffix, e.g. {@code "Head"} or {@code "LeftHandLocator"}
     * @return the group's bones in traversal ({@code topLevelBones} then {@code childBones}) order; empty if none
     */
    public List<GeoBone> locatorGroup(String baseName) {
        if (baseName == null || baseName.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<GeoBone>> groups = this.locatorGroups;
        if (groups == null) {
            groups = new HashMap<>();
            this.locatorGroups = groups;
        }
        synchronized (groups) {
            List<GeoBone> group = groups.get(baseName);
            if (group == null) {
                ArrayList<GeoBone> found = new ArrayList<>(2);
                for (GeoBone topLevelBone : topLevelBones) {
                    collectLocatorGroup(baseName, topLevelBone, found);
                }
                group = found.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(found);
                groups.put(baseName, group);
            }
            return group;
        }
    }

    private static void collectLocatorGroup(String baseName, GeoBone bone, List<GeoBone> found) {
        if (bone == null) {
            return;
        }
        if (isLocatorGroupName(bone.name, baseName)) {
            found.add(bone);
        }
        for (GeoBone childBone : bone.childBones) {
            collectLocatorGroup(baseName, childBone, found);
        }
    }

    /** Upstream's {@code GeoLocatorType#getByBoneName} name rule, kept character-for-character. */
    private static boolean isLocatorGroupName(String boneName, String baseName) {
        if (boneName == null || boneName.isEmpty()) {
            return false;
        }
        int suffixStart = boneName.length();
        while (suffixStart > 0 && Character.isDigit(boneName.charAt(suffixStart - 1))) {
            suffixStart--;
        }
        if (suffixStart > 0 && suffixStart < boneName.length()) {
            boneName = boneName.substring(0, suffixStart);
        }
        return boneName.equals(baseName);
    }

    private GeoBone getBoneRecursively(String name, GeoBone bone) {
        if (bone.name.equals(name)) {
            return bone;
        }
        for (GeoBone childBone : bone.childBones) {
            if (childBone.name.equals(name)) {
                return childBone;
            }
            GeoBone optionalBone = getBoneRecursively(name, childBone);
            if (optionalBone != null) {
                return optionalBone;
            }
        }
        return null;
    }
}
