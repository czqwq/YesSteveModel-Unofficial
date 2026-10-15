package com.fox.ysmu.client.animation.condition;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.item.EnumAction;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.ysmu;

/**
 * Observability for condition animation names that the legacy classifiers can never select (A-07).
 *
 * <p>
 * {@code ConditionalHold/ConditionalUse/ConditionalSwing/ConditionArmor#addTest} only feed a name into the sets
 * the runtime queries when its suffix is a {@link EnumAction} name, a known {@link InnerClassify} item kind, or an
 * id/ore-dictionary form. Everything else ends up in {@code innerTest} and can never be returned by
 * {@code doExtraTest}, i.e. it is dropped silently. The names this class exists to break are the ones with no
 * producer at all: the built-in packs' {@code use_mainhand:spyglass} / {@code use_offhand:spyglass} used to be two of
 * them, and the {@code <action>:<kind>_<variant>} forms a reference pack declares ({@code swing:sword_up},
 * {@code hold_mainhand:bow_Yilumi}, ...) still are.
 *
 * <p>
 * A hand predicate counts as a producer just as a classifier does, so the names one answers are not reported here:
 * {@code hold_*:empty}, {@code hold_mainhand:fishing} and {@code hold_*:charged_crossbow} - see the note on
 * {@link #HOLD_CHARGED_CROSSBOW_SUFFIX}.
 *
 * <p>
 * All this class can see is the <em>condition</em> and <em>hand-predicate</em> routes, not every route a clip can
 * have: a pack's own animation controller, a script, or an extra animation can play a clip whose name no classifier
 * will ever return. The warning therefore reports what has been established - no condition classifier selects this
 * name - rather than claiming the animation never plays at all. One family in the log is in exactly that position:
 * a reference pack's {@code swing:sword_up} / {@code _down} / {@code _jump} are played by its own
 * {@code player.parallel_5} controller states, not by a classifier.
 *
 * <p>
 * A name is reported against the model that <em>declares</em> it. Every model inherits the built-in default model's
 * clips for any name it does not define itself (the default-animation fallback), so a name the default declares and
 * no classifier can return - {@code lance} is one - would otherwise be reported once per model that merely inherits
 * it. {@link #warnIfUnclassifiable} is told which of the two happened and stays quiet for the inheriting model, while
 * the built-in default model's own registration still reports the name once.
 *
 * <p>
 * This class decides nothing but whether a warning is printed: {@link ConditionManager#addTest} calls it after
 * classifying a name, and it reports each (model, name) pair once, with the reason, so a pack author can see why no
 * classifier will ever return that name.
 */
final class ConditionNameDiagnostics {

    /**
     * The `hold_*:` suffixes that do have a route outside the kind/EnumAction sets, i.e. the names the hand
     * predicates produce themselves. Upstream answers all three the same way - {@code hold_*:empty} from
     * {@code ConditionalHold#doTest} ({@code ConditionalHold.java:72-75}), {@code hold_mainhand:fishing} from
     * {@code MainhandPredicate.java:47-51}, and {@code hold_*:charged_crossbow} from
     * {@code MainhandPredicate.java:44-46} / {@code OffhandPredicate.java:31-33}. The port keeps the same three
     * routes: {@code ConditionalHold#doTest} and {@code AnimationManager#predicateMainhandHold} /
     * {@code #predicateOffhandHold}, the last of which reaches the charged-clip branch through
     * {@code TinkersCrossbowCompat.isCrossbowLoaded} - a TiCon crossbow with its {@code InfiTool.Loaded} NBT flag
     * set, which is 1.7.10's crossbow.
     */
    private static final String HOLD_EMPTY_SUFFIX = "empty";
    private static final String HOLD_FISHING_SUFFIX = "fishing";
    private static final String HOLD_CHARGED_CROSSBOW_SUFFIX = "charged_crossbow";

    private static final String[] HOLD_PREFIXES = { "hold_mainhand:", "hold_offhand:" };
    private static final String[] ACTION_PREFIXES = { "use_mainhand:", "use_offhand:", "swing:", "swing_offhand:" };
    private static final String[] ID_PREFIXES = { "hold_mainhand$", "hold_offhand$", "use_mainhand$", "use_offhand$",
        "swing$", "swing_offhand$" };
    private static final String[] OREDICT_PREFIXES = { "hold_mainhand#", "hold_offhand#", "use_mainhand#",
        "use_offhand#", "swing#", "swing_offhand#" };
    private static final String[] ARMOR_SLOTS = { "head", "chest", "legs", "feet" };
    /** B-04: the riding classifiers, id form only - see the `#` group below. */
    private static final String[] RIDING_ID_PREFIXES = { "vehicle$", "passenger$" };
    private static final String[] RIDING_TAG_PREFIXES = { "vehicle#", "passenger#" };

    /** One warning per (model, name); cleared together with {@link ConditionManager#clear()}. */
    private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * A-11: condition names whose suffix no classifier can ever produce, with the reason. The first two belong to
     * items 1.7.10 does not have - a goat horn arrived in 1.17 and the brush in 1.20 - so there is nothing to classify.
     * {@code trident} is different: 1.7.10 has no vanilla trident either, and this port answers {@code spear} for a
     * trident-named item on purpose ({@code InnerClassify#getItemType} matches both keywords into one kind), so the
     * name is dead but the item is not - a pack has to write {@code :spear}.
     * <p>
     * {@code lance} is the third shape and the reason it is listed: the suffix is not a kind in <em>either</em>
     * implementation, so this is not a mapping the port is missing - see its entry below.
     * <p>
     * A name is <em>not</em> listed here when it has a route outside the vocabulary: {@code hold_*:empty},
     * {@code hold_mainhand:fishing} and {@code hold_*:charged_crossbow} are hand-predicate names, and a predicate is
     * a producer exactly as a classifier is - see {@link #HOLD_CHARGED_CROSSBOW_SUFFIX}.
     * <p>
     * These are listed by name because the vocabulary check cannot see the difference: {@code trident} is a real kind
     * in {@code KNOWN_TYPE_KEYWORDS}, so {@link #isClassifiable} answers {@code true} and the pack would get no
     * warning at all while its animation never plays.
     */
    private static final Map<String, String> UNPRODUCIBLE_KINDS = unproducibleKinds();

    private static Map<String, String> unproducibleKinds() {
        Map<String, String> reasons = new ConcurrentHashMap<>();
        reasons.put(
            "toot_horn",
            "`use_*:toot_horn` needs a goat horn, which 1.7.10 does not have (it arrived in 1.17), so no item can"
                + " ever answer this name");
        reasons.put(
            "brush",
            "`use_*:brush` needs a brush, which 1.7.10 does not have (it arrived in 1.20), so no item can ever answer"
                + " this name");
        reasons.put(
            "trident",
            "`trident` is answered as `spear` here on purpose - 1.7.10 has no vanilla trident, so InnerClassify"
                + " treats both keywords as one kind - write `:spear` instead");
        // `lance` is not a vocabulary gap on this port's side. Upstream's `getClassify`
        // (client/animation/condition/InnerClassify.java:22-66) returns one of a fixed set of kind words -
        // slashblade, sword, gohei, axe, pickaxe, shovel, hoe, shield, crossbow, bow, fishing_rod, spear,
        // throwable_potion - and never `lance`, and the 1.20.1 UseAnim enum has no such value either, so upstream
        // cannot select these clips for any item. The clips are nevertheless shipped by the built-in default model
        // (tmp/YesSteveModel-dev-1.20 .../builtin/default/animations/arm.animation.json:5960, :6073, :6174, :6708,
        // :7161, copied into this port's assets/ysmu/builtin/default/animations/arm.animation.json at the same
        // lines), and every model inherits them through the default-animation fallback. Three of the five are
        // byte-identical to their `spear` counterparts in that same file (hold_mainhand, hold_offhand and swing;
        // measured by comparing the parsed clips), so `lance` is that asset's second name for the polearm pose -
        // most likely for a modded lance item - and not a pose the port lacks. Adding a `lance` kind here would
        // invent a mapping upstream does not have, so the honest answer is that this is unreachable content in the
        // shipped asset, not a missing port mapping.
        reasons.put(
            "lance",
            "`lance` is not a kind either implementation can produce: upstream's getClassify returns only slashblade,"
                + " sword, gohei, axe, pickaxe, shovel, hoe, shield, crossbow, bow, fishing_rod, spear and"
                + " throwable_potion (InnerClassify.java:22-66), and this port adds no `lance`. The clips come from the"
                + " built-in default model's arm animations - hold_mainhand:lance, hold_offhand:lance and swing:lance"
                + " are byte-identical to their `spear` counterparts there - and every model inherits them through the"
                + " default-animation fallback, so this is unreachable content in the shipped asset rather than a"
                + " missing mapping");
        return Collections.unmodifiableMap(reasons);
    }

    /** The reason {@code name}'s kind can never be produced, or an empty string when it has no such note. */
    static String unproducibleKindReason(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        for (String prefix : ACTION_PREFIXES) {
            if (name.startsWith(prefix)) {
                String reason = UNPRODUCIBLE_KINDS.get(
                    name.substring(prefix.length())
                        .toLowerCase(Locale.US));
                return reason == null ? "" : reason;
            }
        }
        for (String prefix : HOLD_PREFIXES) {
            if (name.startsWith(prefix)) {
                String reason = UNPRODUCIBLE_KINDS.get(
                    name.substring(prefix.length())
                        .toLowerCase(Locale.US));
                return reason == null ? "" : reason;
            }
        }
        return "";
    }

    private ConditionNameDiagnostics() {}

    /**
     * Warns once when {@code name} is a condition name that no legacy classifier can ever return.
     *
     * @param id              the model's animation id
     * @param name            the animation name from the model's animation file
     * @param declaredByModel {@code false} when the model did not declare this clip itself but inherited it from the
     *                        built-in default model through the default-animation fallback, in which case the
     *                        default model's own registration reports it and the inheriting model stays quiet
     */
    static void warnIfUnclassifiable(ResourceLocation id, String name, boolean declaredByModel) {
        if (!needsWarning(name, declaredByModel)) {
            return;
        }
        String unproducible = unproducibleKindReason(name);
        if (!WARNED.add(id + "|" + name)) {
            return;
        }
        if (!unproducible.isEmpty()) {
            ysmu.LOG.warn(
                "Condition animation '{}' of model {} can never be selected on 1.7.10: {}",
                name,
                id,
                unproducible);
            return;
        }
        // "No condition classifier selects it" is what was verified; the earlier wording also claimed the animation
        // was ignored, which is false for the names a pack's own controller states play - three of the variants a
        // reference pack declares (`swing:sword_up` / `_down` / `_jump`) are in exactly that position, so the last
        // clause names that route instead of leaving a reader to conclude the clip is dead.
        ysmu.LOG.warn(
            "Condition animation '{}' of model {} cannot be selected by the 1.7.10 condition classifiers: suffix '{}'"
                + " is neither an EnumAction name nor a known item kind (see InnerClassify); no classifier will ever"
                + " return this name, though a pack's own animation controller state can still play the clip",
            name,
            id,
            suffixOf(name));
    }

    /**
     * Whether a warning is due for this name, before the once-per-(model, name) memory.
     *
     * <p>
     * Pure, so the decision can be pinned by a test without a logger: {@code false} when the model merely inherited
     * the clip from the built-in default model (the default's own registration reports it once), and otherwise
     * {@code true} only when some classifier can never produce the name.
     *
     * @param declaredByModel whether the model's own animation files declare this name
     */
    static boolean needsWarning(String name, boolean declaredByModel) {
        if (!declaredByModel || name == null || name.isEmpty()) {
            return false;
        }
        // A-11: a name in UNPRODUCIBLE_KINDS is "classifiable" by the vocabulary check (it lists a real 1.20 kind),
        // yet no classifier here can ever return it - which is exactly the silent case this class exists to break, so
        // the reason lookup is asked first and independently of isClassifiable.
        return !unproducibleKindReason(name).isEmpty() || !isClassifiable(name);
    }

    static void clear() {
        WARNED.clear();
    }

    /**
     * @return true when the name is either not a condition name at all (e.g. {@code idle}/{@code walk}) or has a
     *         runtime route. Only a {@code false} result is warned about.
     */
    static boolean isClassifiable(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        for (String prefix : HOLD_PREFIXES) {
            if (name.startsWith(prefix)) {
                return isEmptyOrFishingOrActionOrKind(name.substring(prefix.length()));
            }
        }
        for (String prefix : ACTION_PREFIXES) {
            if (name.startsWith(prefix)) {
                return isActionOrKind(name.substring(prefix.length()));
            }
        }
        for (String prefix : ID_PREFIXES) {
            if (name.startsWith(prefix)) {
                // ConditionalHold/ConditionalUse/ConditionalSwing#addTest register a `$` suffix only when it contains ':'
                return name.substring(prefix.length())
                    .contains(":");
            }
        }
        for (String prefix : OREDICT_PREFIXES) {
            if (name.startsWith(prefix)) {
                // the '#' form is always registered (ore-dictionary names)
                return true;
            }
        }
        for (String prefix : RIDING_ID_PREFIXES) {
            if (name.startsWith(prefix)) {
                // ConditionalEntityIdMatch registers any non-empty suffix; the id is normalised on both sides
                return name.length() > prefix.length();
            }
        }
        for (String prefix : RIDING_TAG_PREFIXES) {
            if (name.startsWith(prefix)) {
                // 1.7.10 has no entity-type tags - they arrived with 1.13 and there is no tag manager to ask - so this
                // half has no route at all and is reported rather than dropped silently.
                return false;
            }
        }
        for (String slot : ARMOR_SLOTS) {
            if (name.startsWith(slot + "$")) {
                // ConditionArmor#addTest registers a `$` suffix only when it contains ':'
                return name.substring(slot.length() + 1)
                    .contains(":");
            }
            if (name.startsWith(slot + "#")) {
                return true;
            }
        }
        return true;
    }

    private static boolean isEmptyOrFishingOrActionOrKind(String suffix) {
        return HOLD_EMPTY_SUFFIX.equals(suffix) || HOLD_FISHING_SUFFIX.equals(suffix)
            || HOLD_CHARGED_CROSSBOW_SUFFIX.equals(suffix)
            || isActionOrKind(suffix);
    }

    private static boolean isActionOrKind(String suffix) {
        return isEnumActionName(suffix) || InnerClassify.isKnownTypeKeyword(suffix);
    }

    private static boolean isEnumActionName(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return false;
        }
        for (EnumAction action : EnumAction.values()) {
            // EnumAction.none is skipped by addTest, so a `:none` name is not selectable
            if (action != EnumAction.none && action.name()
                .toLowerCase(Locale.US)
                .equals(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String suffixOf(String name) {
        int index = Math.max(name.lastIndexOf(':'), Math.max(name.lastIndexOf('$'), name.lastIndexOf('#')));
        return index >= 0 && index + 1 < name.length() ? name.substring(index + 1) : name;
    }
}
