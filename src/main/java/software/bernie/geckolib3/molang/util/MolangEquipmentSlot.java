package software.bernie.geckolib3.molang.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The equipment slots a model can name in a MoLang query.
 * <p>
 * 1.7.10 has no equipment-slot enum, so this stands in for the modern {@code EquipmentSlot} and carries the armour
 * index and inventory slot used by {@link MolangEquipmentUtil}.
 */
public enum MolangEquipmentSlot {

    HEAD("head", 3),
    CHEST("chest", 2),
    LEGS("legs", 1),
    FEET("feet", 0),
    MAINHAND("mainhand", -1),
    OFFHAND("offhand", -2);

    private static final Map<String, MolangEquipmentSlot> BY_NAME = new HashMap<>();

    static {
        for (MolangEquipmentSlot slot : values()) {
            BY_NAME.put(slot.name, slot);
        }
    }

    private final String name;
    private final int armorIndex;

    MolangEquipmentSlot(String name, int armorIndex) {
        this.name = name;
        this.armorIndex = armorIndex;
    }

    public String slotName() {
        return name;
    }

    /** 0..3 for armour, negative for held items. */
    public int armorIndex() {
        return armorIndex;
    }

    public boolean isArmor() {
        return armorIndex >= 0;
    }

    @Nullable
    public static MolangEquipmentSlot byName(String value) {
        return value == null ? null : BY_NAME.get(value.toLowerCase(Locale.ENGLISH));
    }
}
