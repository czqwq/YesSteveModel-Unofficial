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
            "slashblade",
            "sword",
            "gohei",
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
        // A-10: upstream asks for these two before anything else (client/animation/condition/InnerClassify.java:25-33
        // is slashblade, sword, gohei, then the tools) because a SlashBlade blade is also a sword and would otherwise
        // be classified as one, so the pack's blade clips would never play. See matchesModItem for how they match here.
        if (matchesModItem(stack, "slashblade")) {
            return "slashblade";
        }
        if (item instanceof ItemSword || matchesName(stack, "sword")) {
            return "sword";
        }
        if (matchesModItem(stack, "gohei")) {
            return "gohei";
        }
        // The tools follow upstream's order (InnerClassify.java:34-45: axe, pickaxe, shovel, hoe) rather than the
        // port's earlier one, so a modded item that carries two of these names classifies the same way on both.
        if (item instanceof ItemAxe || matchesName(stack, "axe")) {
            return "axe";
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
        // 参考分支只判 `uid == null`，但 FML 的 findUniqueIdentifierFor 对"不在 GameData 里的物品"
        // 不是返回 null 而是抛 NPE（GameData.getUniqueName 返回 null → UniqueIdentifier 构造器里
        // string.split(null)）。于是任何没走 FML 注册的物品、或 GameData 尚未建好的初始化早期，
        // 这条分类路径都会直接炸掉整个条件求值 —— 参考分支自己的单测就会踩到这一点。
        // YSM-wiki 的语义是"认不出来就当空"，所以这里保留它的取值顺序，把这一步包起来，
        // 失败时退回和 QueryItemNameAnyFunction 同一个注册名来源（Item.itemRegistry）。
        try {
            GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(stack.getItem());
            if (uid != null) {
                return uid.toString()
                    .toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
            // 未注册物品的预期失败，不是错误；下面的注册表回退就是它的答案。
        }
        String registryName = Item.itemRegistry.getNameForObject(stack.getItem());
        return registryName == null ? "" : registryName.toLowerCase(Locale.ROOT);
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

    /**
     * A-10: {@code slashblade} and {@code gohei} name one mod's item rather than a vanilla class. Upstream tests the
     * class - {@code item instanceof ItemSlashBlade} ({@code client/compat/slashblade/SlashBladeAnimation.java:19})
     * and {@code item instanceof ItemHakureiGohei}
     * ({@code client/compat/touhoulittlemaid/client/TlmClientCompatInner.java:83}) - plus an item tag for the blade.
     * 1.7.10 has neither item tags nor a way for this port to compile against either mod, so the same intent is
     * expressed as "the item's class name, its id or its ore-dictionary name carries the mod's own name". That name is
     * upstream's own constant for the blade ({@code SLASH_BLADE_ID = "slashblade"},
     * {@code client/compat/slashblade/SlashBladeCompat.java:20}); the class half is what makes it work for the 1.7.10
     * build of the mod, whose registry domain need not spell it the same way.
     */
    private static boolean matchesModItem(ItemStack stack, String modName) {
        if (matchesName(stack, modName)) {
            return true;
        }
        Item item = stack.getItem();
        return item != null && item.getClass()
            .getSimpleName()
            .toLowerCase(Locale.ROOT)
            .contains(modName);
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
