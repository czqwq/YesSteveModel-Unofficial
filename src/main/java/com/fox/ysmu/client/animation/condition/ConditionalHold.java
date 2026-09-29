package com.fox.ysmu.client.animation.condition;

import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.fox.ysmu.compat.BackhandCompat;
import com.google.common.collect.Lists;

public class ConditionalHold {

    // S-03: the old "ore-dictionary still to be tested" marker is resolved - the ore-dictionary path below is
    // implemented (`doOreDictTest`, using OreDictionary instead of the 1.20.1 item tags).
    // 在 YesSteveModel 项目中，这些 Minecraft 标签文件（swords.json, axes.json, pickaxes.json, shovels.json, hoes.json,
    // tools.json）主要用于动画系统的条件判断。
    // 1.动画条件系统：
    // ·项目中的 ConditionalSwing、ConditionalUse 和 ConditionalHold 类用于根据玩家手持物品的类型来触发特定动画。
    // ·这些类通过 doTagTest 方法检查玩家手中的物品是否属于某个标签，例如检查是否是剑、斧头、镐等工具。
    // 2.工作原理：
    // ·当玩家挥动物品时，系统会检查该物品是否属于 swords 标签，如果是，则可能触发特定的剑类挥动动画。
    // ·当玩家使用物品时，系统会检查该物品是否属于 tools 标签，如果是，则可能触发工具使用动画。
    // ·这种机制允许为不同类型的物品定义不同的动画，而不需要为每种具体物品硬编码。
    // 3.实际应用示例：
    // ·当玩家手持任何类型的剑（通过 swords.json 标签定义）时，可能会触发特定的剑类攻击动画。
    // ·当玩家手持任何类型的镐（通过 pickaxes.json 标签定义）时，可能会触发特定的挖掘动画。
    // ·通过使用这些标签，项目可以更灵活地管理动画系统，而不需要为每种具体物品单独编写代码。当 Minecraft 添加新物品或模组添加新物品时，只要它们正确地添加到相应的标签中，动画系统就会自动支持它们。
    private static final String EMPTY = "";
    // A-01: 1.20.1's ConditionHold answers these two names for an empty hand (EMPTY_MAINHAND/EMPTY_OFFHAND there);
    // the 1.7.10 port returned "" instead, which is why `hold_mainhand:empty` / `hold_offhand:empty` - names the
    // shipped and third-party packs do use - were unreachable.
    private static final String EMPTY_MAINHAND = "hold_mainhand:empty";
    private static final String EMPTY_OFFHAND = "hold_offhand:empty";
    private final String emptyName;
    private final int preSize;
    private final String idPre;
    private final String oreDictPre; // 在1.7.10中，我们用它来表示矿物词典的前缀
    private final String extraPre;
    // 1.7.10: 不再使用 ResourceLocation，直接用 String 存储物品ID ("modid:name")
    private final List<String> idTest = Lists.newArrayList();
    // 1.7.10: 不再使用 TagKey，直接用 String 存储矿物词典的名称
    private final List<String> oreDictTest = Lists.newArrayList();
    private final List<EnumAction> extraTest = Lists.newArrayList();
    private final List<String> innerTest = Lists.newArrayList();

    public ConditionalHold(boolean isMainHand) {
        if (isMainHand) {
            idPre = "hold_mainhand$";
            oreDictPre = "hold_mainhand#";
            extraPre = "hold_mainhand:";
            emptyName = EMPTY_MAINHAND;
            preSize = 14;
        } else {
            idPre = "hold_offhand$";
            oreDictPre = "hold_offhand#";
            extraPre = "hold_offhand:";
            emptyName = EMPTY_OFFHAND;
            preSize = 13;
        }
    }

    public void addTest(String name) {
        if (name.length() <= preSize) {
            return;
        }
        String substring = name.substring(preSize);
        if (name.startsWith(idPre)) {
            // 1.7.10: 简单验证格式即可，不再有 isValidResourceLocation 方法
            // D-A3: normalised to the same lower-case form InnerClassify.registryId returns
            if (substring.contains(":")) {
                idTest.add(substring.toLowerCase(Locale.ROOT));
            }
        }
        if (name.startsWith(oreDictPre)) {
            // 1.7.10: 这里处理的是矿物词典名称
            oreDictTest.add(substring);
        }
        if (name.startsWith(extraPre)) {
            if (substring.equals(
                EnumAction.none.name()
                    .toLowerCase(Locale.US))) {
                return;
            }
            for (EnumAction action : EnumAction.values()) {
                if (action.name()
                    .toLowerCase(Locale.US)
                    .equals(substring)) {
                    extraTest.add(action);
                    break;
                }
            }
            innerTest.add(name);
        }
    }

    public String doTest(EntityPlayer player, boolean isMainHand) {
        if (BackhandCompat.getItemInHand(player, isMainHand) == null) {
            // A-01: an empty hand answers `hold_mainhand:empty` / `hold_offhand:empty`, matching 1.20.1.
            // AnimationManager#playIfPresent (D-A5) only plays it when the model actually defines it, so models
            // without the clip simply stop instead of driving AnimationController into its missing-animation branch.
            return emptyName;
        }
        String result = doIdTest(player, isMainHand);
        if (result.isEmpty()) {
            result = doOreDictTest(player, isMainHand);
            if (result.isEmpty()) {
                return doExtraTest(player, isMainHand);
            }
        }
        return result;
    }

    private String doIdTest(EntityPlayer player, boolean isMainHand) {
        if (idTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = BackhandCompat.getItemInHand(player, isMainHand);
        // D-A3: the single shared "item -> registry id" lookup (lower-case "modid:name"); addTest stores the
        // same normalised form, so the comparison no longer depends on the two sides agreeing on casing.
        String registryName = InnerClassify.registryId(itemInHand);
        if (!registryName.isEmpty() && idTest.contains(registryName)) {
            return idPre + registryName;
        }
        return EMPTY;
    }

    // 1.7.10: doTagTest 完全重写，使用 OreDictionary
    private String doOreDictTest(EntityPlayer player, boolean isMainHand) {
        if (oreDictTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = BackhandCompat.getItemInHand(player, isMainHand);
        // 获取物品堆栈对应的所有矿辞ID
        int[] oreIDs = OreDictionary.getOreIDs(itemInHand);
        if (oreIDs.length == 0) {
            return EMPTY;
        }

        // 遍历物品拥有的所有矿辞
        for (int oreID : oreIDs) {
            String oreName = OreDictionary.getOreName(oreID);
            // 检查这个矿辞名称是否在我们需要测试的列表里
            if (oreDictTest.contains(oreName)) {
                return oreDictPre + oreName; // 找到匹配，返回结果
            }
        }

        return EMPTY; // 未找到匹配
    }

    private String doExtraTest(EntityPlayer player, boolean isMainHand) {
        if (extraTest.isEmpty() && innerTest.isEmpty()) {
            return EMPTY;
        }
        String innerName = InnerClassify.doClassifyTest(extraPre, player, isMainHand);
        if (!innerName.isEmpty() && innerTest.contains(innerName)) {
            return innerName;
        }
        ItemStack itemInHand = BackhandCompat.getItemInHand(player, isMainHand);
        EnumAction action = itemInHand.getItemUseAction();
        if (extraTest.contains(action)) {
            return extraPre + action.name()
                .toLowerCase(Locale.US);
        }
        return EMPTY;
    }
}
