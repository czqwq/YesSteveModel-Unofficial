package com.fox.ysmu.client.animation.condition;

import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.fox.ysmu.compat.BackhandCompat;
import com.google.common.collect.Lists;

public class ConditionalSwing {

    // A-02: 1.20.1 keeps two prefix sets - swing$/swing#/swing: on the main hand and swing_offhand$/swing_offhand#/
    // swing_offhand: on the off hand - and ConditionManager keeps two tables. The 1.7.10 port had only the
    // main-hand set, so every swing_offhand* name was neither registered nor matchable.
    private static final String MAIN_ID_PRE = "swing$";
    private static final String MAIN_OD_PRE = "swing#";
    private static final String MAIN_EXTRA_PRE = "swing:";
    private static final String OFF_ID_PRE = "swing_offhand$";
    private static final String OFF_OD_PRE = "swing_offhand#";
    private static final String OFF_EXTRA_PRE = "swing_offhand:";
    private static final String EMPTY = "";
    private final String idPre;
    private final String odPre;
    private final String extraPre;
    private final int preSize;
    // 1.7.10: 使用String存储物品ID ("modid:name")
    private final List<String> idTest = Lists.newArrayList();
    // 1.7.10: 使用String存储矿物词典名称
    private final List<String> oreDictTest = Lists.newArrayList();
    private final List<EnumAction> extraTest = Lists.newArrayList();
    private final List<String> innerTest = Lists.newArrayList();

    /** Main-hand classifier; kept for callers that only ever ask about the main hand. */
    public ConditionalSwing() {
        this(true);
    }

    public ConditionalSwing(boolean isMainHand) {
        if (isMainHand) {
            idPre = MAIN_ID_PRE;
            odPre = MAIN_OD_PRE;
            extraPre = MAIN_EXTRA_PRE;
            preSize = MAIN_ID_PRE.length();
        } else {
            idPre = OFF_ID_PRE;
            odPre = OFF_OD_PRE;
            extraPre = OFF_EXTRA_PRE;
            preSize = OFF_ID_PRE.length();
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
        if (name.startsWith(odPre)) {
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
            return EMPTY;
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
        // D-A3: shared lower-case "modid:name" lookup (the same helper the hold/use classifiers and the
        // non-player held-item path use)
        String registryName = InnerClassify.registryId(itemInHand);
        if (!registryName.isEmpty() && idTest.contains(registryName)) {
            return idPre + registryName;
        }
        return EMPTY;
    }

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
                return odPre + oreName; // 找到匹配，返回结果
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
