package com.fox.ysmu.client.animation.condition;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraftforge.oredict.OreDictionary;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.compat.BackhandCompat;

import cpw.mods.fml.common.registry.GameRegistry;

public final class InnerClassify {

    /**
     * The `:kind` keywords the condition classifiers and the controller evaluator may match on. Kept next to
     * {@link #getItemType}'s returns so the two lists cannot drift (A-07).
     *
     * <p>
     * "fishingrod" is deliberately absent: it is a spelling alias of `fishing_rod` and is handled explicitly in
     * {@link #categoryMatches}, because leaving it in this set would disable the old "item id contains the
     * category" fallback for it.
     */
    private static final Set<String> KNOWN_TYPE_KEYWORDS = new HashSet<>(
        Arrays.asList(
            "sword",
            "pickaxe",
            "shovel",
            "hoe",
            "axe",
            "shield",
            "crossbow",
            "bow",
            "fishing_rod",
            "spear",
            "trident",
            "throwable_potion",
            "spyglass"));

    private InnerClassify() {}

    static String doClassifyTest(String prefix, EntityPlayer player, boolean isMainHand) {
        String itemType = getItemType(BackhandCompat.getItemInHand(player, isMainHand));
        return itemType.isEmpty() ? "" : prefix + itemType;
    }

    public static String getItemType(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        Item item = stack.getItem();
        if (item instanceof ItemSword || matchesName(stack, "sword")) {
            return "sword";
        }
        if (item instanceof ItemPickaxe || matchesName(stack, "pickaxe")) {
            return "pickaxe";
        }
        if (item instanceof ItemSpade || matchesName(stack, "shovel") || matchesName(stack, "spade")) {
            return "shovel";
        }
        if (item instanceof ItemHoe || matchesName(stack, "hoe")) {
            return "hoe";
        }
        if (item instanceof ItemAxe || matchesName(stack, "axe")) {
            return "axe";
        }
        if (matchesName(stack, "shield")) {
            return "shield";
        }
        if (matchesName(stack, "crossbow")) {
            return "crossbow";
        }
        // A-07: 1.7.10 has no ItemSpyglass / EnumAction(UseAnim).SPYGLASS, which is how the 1.20.1 packs answer
        // `use_*:spyglass`; here it degrades to "the item id / ore-dictionary name contains spyglass", which makes
        // the shipped `use_mainhand:spyglass` / `use_offhand:spyglass` names reachable for modded spyglasses.
        if (matchesName(stack, "spyglass")) {
            return "spyglass";
        }
        if (item instanceof ItemBow || matchesName(stack, "bow")) {
            return "bow";
        }
        if (item instanceof ItemFishingRod || matchesName(stack, "fishing_rod") || matchesName(stack, "fishingrod")) {
            return "fishing_rod";
        }
        if (matchesName(stack, "spear") || matchesName(stack, "trident")) {
            return "spear";
        }
        if ((item instanceof ItemPotion && ItemPotion.isSplash(stack.getItemDamage()))
            || matchesName(stack, "throwable_potion")) {
            return "throwable_potion";
        }
        return "";
    }

    /** Whether {@code keyword} is one of the `:kind` names {@link #getItemType} can return. */
    public static boolean isKnownTypeKeyword(String keyword) {
        return keyword != null && KNOWN_TYPE_KEYWORDS.contains(keyword.toLowerCase(Locale.ROOT));
    }

    /**
     * Canonical registry id of the item in {@code stack}: lower-case {@code "modid:name"}, or {@code ""} when the
     * stack is empty or the item has no registry entry (D-A3).
     *
     * <p>
     * This is the single "item -> name" lookup shared by the player condition classifiers
     * ({@code ConditionalHold/ConditionalUse/ConditionalSwing#doIdTest}) and the non-player held-item path
     * ({@code AnimationManager#findEntityHoldAnimation}), which previously used
     * {@code GameRegistry.findUniqueIdentifierFor} and {@code Item.itemRegistry.getNameForObject} respectively.
     */
    public static String registryId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(stack.getItem());
        return uid == null ? "" : uid.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The {@code hold_mainhand$<registry id>} / {@code hold_offhand$<registry id>} condition name for what the
     * entity holds, or {@code ""} when nothing is held or the item has no registry id (D-A3).
     *
     * <p>
     * Only the id form is produced; the ore-dictionary ({@code #}) and `:kind` forms are still resolved by the
     * classifiers that have a player. The returned name is only usable when the model defines it - callers check
     * that before playing it.
     */
    public static String holdConditionName(ItemStack stack, boolean isMainHand) {
        String id = registryId(stack);
        if (id.isEmpty() || !id.contains(":")) {
            return "";
        }
        return (isMainHand ? "hold_mainhand$" : "hold_offhand$") + id;
    }

    /**
     * Hand-held item matching: the 1.7.10 counterpart of 1.20.1's {@code HandRenderFunction#eval}, shared by the
     * controller evaluator ({@code ctrl.hold}/{@code ctrl.use}/{@code ctrl.swing}) and the animation-file Molang
     * function {@code ctrl.hold} (A-06③ / A-07).
     *
     * <p>
     * Forms: {@code $modid:name} (exact item id), {@code #name} (1.7.10 has no item tags, so this maps to
     * {@link OreDictionary}, the same meaning {@code ConditionalHold#doOreDictTest} gives it),
     * {@code :kind} (a {@link #getItemType} result, an {@link EnumAction} name, the trident/spear and
     * fishingrod/fishing_rod aliases, or an id-contains fallback for unknown categories) and {@code empty}.
     *
     * <p>
     * A blank matcher keeps this repository's historical controller semantics, "any item in that hand counts"
     * (1.20.1's HandRenderFunction answers 0 for a blank matcher); both callers share this method, so the two paths
     * cannot disagree.
     */
    public static boolean itemMatches(ItemStack stack, String matcher) {
        if (StringUtils.isBlank(matcher)) {
            return stack != null;
        }
        if ("empty".equals(matcher)) {
            return stack == null;
        }
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        if (matcher.startsWith("$")) {
            return registryId(stack).equals(matcher.substring(1).toLowerCase(Locale.ROOT));
        }
        if (matcher.startsWith("#")) {
            return matchesOreName(stack, matcher.substring(1));
        }
        String category = matcher.startsWith(":") ? matcher.substring(1) : matcher;
        return categoryMatches(stack, category.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the player holds the requested thing in the requested hand, with the optional "is using it" /
     * "is swinging it" gates the controller functions need.
     *
     * @param hand         "mainhand" / "offhand" (anything else is treated as the main hand, which is this
     *                     repository's existing controller behaviour)
     * @param matcher      one of the {@link #itemMatches} forms, or blank
     * @param requireUse   {@code ctrl.use}: the player must be using that hand
     * @param requireSwing {@code ctrl.swing}: the player must be swinging that hand
     */
    public static boolean matchesHandCondition(EntityPlayer player, String hand, String matcher, boolean requireUse,
        boolean requireSwing) {
        if (player == null) {
            return false;
        }
        boolean mainHand = !"offhand".equalsIgnoreCase(StringUtils.defaultString(hand));
        if (!mainHand && !BackhandCompat.isBackhandLoaded()) {
            // 1.7.10 has no vanilla offhand slot (Backhand provides it), so off-hand conditions never match
            // without it; the player still has no item to look at.
            return false;
        }
        if (requireUse && (!player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != mainHand)) {
            return false;
        }
        if (requireSwing && (!player.isSwingInProgress || BackhandCompat.swingingArm(player) != mainHand)) {
            return false;
        }
        return itemMatches(BackhandCompat.getItemInHand(player, mainHand), matcher);
    }

    private static boolean categoryMatches(ItemStack stack, String category) {
        String itemType = getItemType(stack);
        if (category.equals(itemType)) {
            return true;
        }
        if ("trident".equals(category) && "spear".equals(itemType)) {
            return true;
        }
        if ("spear".equals(category) || "trident".equals(category)) {
            return matchesName(stack, "spear") || matchesName(stack, "trident");
        }
        if ("fishingrod".equals(category) && "fishing_rod".equals(itemType)) {
            // fishingrod is a spelling alias of fishing_rod; getItemType only returns the underscored form
            return true;
        }
        // 1.20.1's HandRenderFunction also matches the item's use animation name (:eat/:bow/...); 1.7.10's
        // counterpart is the EnumAction name.
        EnumAction action = stack.getItemUseAction();
        if (action != null && category.equals(action.name().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (isKnownTypeKeyword(category)) {
            return false;
        }
        return registryId(stack).contains(category);
    }

    private static boolean matchesOreName(ItemStack stack, String oreName) {
        if (oreName == null || oreName.isEmpty()) {
            return false;
        }
        int[] oreIds = OreDictionary.getOreIDs(stack);
        for (int oreId : oreIds) {
            String name = OreDictionary.getOreName(oreId);
            if (name != null && name.equals(oreName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesName(ItemStack stack, String needle) {
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        String itemId = registryId(stack);
        if (itemId.contains(normalizedNeedle)) {
            return true;
        }
        int[] oreIds = OreDictionary.getOreIDs(stack);
        for (int oreId : oreIds) {
            String oreName = OreDictionary.getOreName(oreId);
            if (oreName != null && oreName.toLowerCase(Locale.ROOT).contains(normalizedNeedle)) {
                return true;
            }
        }
        return false;
    }
}
