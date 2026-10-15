package com.fox.ysmu.client.animation.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the slot-word to slot-index mapping the per-slot armour controllers depend on.
 * <p>
 * This is the join between three things that use different index spaces, which is why it is worth a test rather than
 * a comment: 1.7.10's {@code EntityPlayer#getEquipmentInSlot} numbers armour 1 boots, 2 leggings, 3 chestplate,
 * 4 helmet (the same convention {@code RenderPlayer#renderEquippedItems} relies on when it asks
 * {@code armorItemInSlot(3)} for the helmet); {@link ConditionArmor#getSlotNameFromIndex} turns those numbers into the
 * words a pack writes; and {@code AnimationManager#predicateArmor} hands the number straight back to
 * {@code getEquipmentInSlot}. Shift or flip the mapping and a pack's {@code player.armor_head} would be bound to the
 * boots controller and quietly play the wrong clip - no exception, no log.
 * <p>
 * Both conversions are pure static lookups, so no player or Minecraft class is needed.
 */
class ConditionArmorSlotIndexTest {

    @Test
    void theSlotWordsMatchTheIndicesEquipmentInSlotUses() {
        assertEquals("feet", ConditionArmor.getSlotNameFromIndex(1));
        assertEquals("legs", ConditionArmor.getSlotNameFromIndex(2));
        assertEquals("chest", ConditionArmor.getSlotNameFromIndex(3));
        assertEquals("head", ConditionArmor.getSlotNameFromIndex(4));
        assertEquals(1, ConditionArmor.getSlotIndexFromString("feet"));
        assertEquals(2, ConditionArmor.getSlotIndexFromString("legs"));
        assertEquals(3, ConditionArmor.getSlotIndexFromString("chest"));
        assertEquals(4, ConditionArmor.getSlotIndexFromString("head"));
    }

    @Test
    void theTwoConversionsAreInverses() {
        for (int index = 1; index <= 4; index++) {
            String slot = ConditionArmor.getSlotNameFromIndex(index);
            assertEquals(index, ConditionArmor.getSlotIndexFromString(slot), slot);
        }
    }

    @Test
    void aWordOrIndexOutsideTheFourArmourSlotsIsRejected() {
        // Not a slot word: the caller treats -1 as "invalid", so it must not silently land on a real slot.
        assertEquals(-1, ConditionArmor.getSlotIndexFromString("hat"));
        // 0 is the held item and 5 is past the armour array, so neither is a slot the controllers exist for.
        assertEquals("", ConditionArmor.getSlotNameFromIndex(0));
        assertEquals("", ConditionArmor.getSlotNameFromIndex(5));
    }
}
