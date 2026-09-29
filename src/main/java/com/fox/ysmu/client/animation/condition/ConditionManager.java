package com.fox.ysmu.client.animation.condition;

import java.util.Map;

import net.minecraft.util.ResourceLocation;

import com.google.common.collect.Maps;

public class ConditionManager {

    public static Map<ResourceLocation, ConditionalSwing> SWING = Maps.newHashMap();
    // A-02: 1.20.1 keeps a second swing table for the off hand (swing_offhand$/#/: prefixes). Without it every
    // swing_offhand* name was silently unregistered and unusable.
    public static Map<ResourceLocation, ConditionalSwing> SWING_OFFHAND = Maps.newHashMap();
    public static Map<ResourceLocation, ConditionalUse> USE_MAINHAND = Maps.newHashMap();
    public static Map<ResourceLocation, ConditionalUse> USE_OFFHAND = Maps.newHashMap();
    public static Map<ResourceLocation, ConditionalHold> HOLD_MAINHAND = Maps.newHashMap();
    public static Map<ResourceLocation, ConditionalHold> HOLD_OFFHAND = Maps.newHashMap();
    public static Map<ResourceLocation, ConditionArmor> ARMOR = Maps.newHashMap();

    public static void addTest(ResourceLocation id, String name) {
        SWING.putIfAbsent(id, new ConditionalSwing(true));
        SWING_OFFHAND.putIfAbsent(id, new ConditionalSwing(false));
        USE_MAINHAND.putIfAbsent(id, new ConditionalUse(true)); // true表示主手
        USE_OFFHAND.putIfAbsent(id, new ConditionalUse(false)); // false表示副手
        HOLD_MAINHAND.putIfAbsent(id, new ConditionalHold(true));
        HOLD_OFFHAND.putIfAbsent(id, new ConditionalHold(false));
        ARMOR.putIfAbsent(id, new ConditionArmor());

        ConditionalSwing conditionalSwing = SWING.get(id);
        ConditionalSwing conditionalSwingOffhand = SWING_OFFHAND.get(id);
        ConditionalUse conditionalUseMainhand = USE_MAINHAND.get(id);
        ConditionalUse conditionalUseOffhand = USE_OFFHAND.get(id);
        ConditionalHold conditionalHoldMainhand = HOLD_MAINHAND.get(id);
        ConditionalHold conditionalHoldOffhand = HOLD_OFFHAND.get(id);
        ConditionArmor conditionArmor = ARMOR.get(id);

        conditionalSwing.addTest(name);
        conditionalSwingOffhand.addTest(name);
        conditionalUseMainhand.addTest(name);
        conditionalUseOffhand.addTest(name);
        conditionalHoldMainhand.addTest(name);
        conditionalHoldOffhand.addTest(name);
        conditionArmor.addTest(name);

        // A-07: a name that is a condition name but has no route through any of the classifiers above would be
        // dropped silently; report each (model, name) once, with the reason.
        ConditionNameDiagnostics.warnIfUnclassifiable(id, name);
    }

    public static void clear() {
        SWING.clear();
        SWING_OFFHAND.clear();
        USE_MAINHAND.clear();
        USE_OFFHAND.clear();
        HOLD_MAINHAND.clear();
        HOLD_OFFHAND.clear();
        ARMOR.clear();
        ConditionNameDiagnostics.clear();
    }

    public static ConditionalSwing getSwing(ResourceLocation id) {
        return SWING.get(id);
    }

    /**
     * The swing classifier for one hand (A-02). 1.7.10 has no vanilla off-hand slot - Backhand provides it - but
     * the two prefix sets are still kept apart, matching 1.20.1's getSwingMainhand/getSwingOffhand.
     */
    public static ConditionalSwing getSwing(ResourceLocation id, boolean isMainHand) {
        return isMainHand ? SWING.get(id) : SWING_OFFHAND.get(id);
    }

    public static ConditionalSwing getSwingOffhand(ResourceLocation id) {
        return SWING_OFFHAND.get(id);
    }

    public static ConditionalUse getUseMainhand(ResourceLocation id) {
        return USE_MAINHAND.get(id);
    }

    public static ConditionalUse getUseOffhand(ResourceLocation id) {
        return USE_OFFHAND.get(id);
    }

    public static ConditionalHold getHoldMainhand(ResourceLocation id) {
        return HOLD_MAINHAND.get(id);
    }

    public static ConditionalHold getHoldOffhand(ResourceLocation id) {
        return HOLD_OFFHAND.get(id);
    }

    public static ConditionArmor getArmor(ResourceLocation id) {
        return ARMOR.get(id);
    }
}
