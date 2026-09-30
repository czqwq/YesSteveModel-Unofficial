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
 * {@code doExtraTest}, i.e. it is dropped silently. The repository's own built-in packs ship two such names today
 * ({@code use_mainhand:spyglass} / {@code use_offhand:spyglass} used to be two of them, and
 * {@code hold_mainhand:charged_crossbow} still is, because 1.7.10 has no crossbow).
 *
 * <p>
 * This class changes no behaviour: {@link ConditionManager#addTest} calls it after classifying a name, and it
 * reports each (model, name) pair once, with the reason, so a pack author can see why an animation never plays.
 */
final class ConditionNameDiagnostics {

    /** The two `hold_*:` suffixes that do have a route outside the kind/EnumAction sets. */
    private static final String HOLD_EMPTY_SUFFIX = "empty";
    private static final String HOLD_FISHING_SUFFIX = "fishing";

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
     * A-11: condition names whose kind no classifier here can ever produce, with the reason. The first two belong to
     * items 1.7.10 does not have - a goat horn arrived in 1.17 and the brush in 1.20 - so there is nothing to classify.
     * {@code trident} is different: 1.7.10 has no vanilla trident either, and this port answers {@code spear} for a
     * trident-named item on purpose ({@code InnerClassify#getItemType} matches both keywords into one kind), so the
     * name is dead but the item is not - a pack has to write {@code :spear}.
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
     * @param id   the model's animation id
     * @param name the animation name from the model's animation file
     */
    static void warnIfUnclassifiable(ResourceLocation id, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        String unproducible = unproducibleKindReason(name);
        // A-11: a name in UNPRODUCIBLE_KINDS is "classifiable" by the vocabulary check (it lists a real 1.20 kind),
        // yet no classifier here can ever return it - which is exactly the silent case this class exists to break.
        if (unproducible.isEmpty() && isClassifiable(name)) {
            return;
        }
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
        ysmu.LOG.warn(
            "Condition animation '{}' of model {} cannot be selected by the 1.7.10 condition classifiers: suffix '{}'"
                + " is neither an EnumAction name nor a known item kind (see InnerClassify); the animation is ignored",
            name,
            id,
            suffixOf(name));
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
        return HOLD_EMPTY_SUFFIX.equals(suffix) || HOLD_FISHING_SUFFIX.equals(suffix) || isActionOrKind(suffix);
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
