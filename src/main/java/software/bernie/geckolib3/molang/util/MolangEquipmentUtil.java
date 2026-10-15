package software.bernie.geckolib3.molang.util;

import javax.annotation.Nullable;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/**
 * Equipment lookups for the MoLang item queries.
 * <p>
 * The 1.20.1 original also consulted cosmetic-armour and elytra-slot mods through their compatibility layers. 1.7.10
 * has neither, so only the vanilla slots are read here; a host mod that wants the extra layers can replace this by
 * subclassing behaviour through its own query registration.
 */
public final class MolangEquipmentUtil {

    private MolangEquipmentUtil() {}

    /** The stack in {@code slot}, or {@code null} when the entity has nothing there. */
    @Nullable
    public static ItemStack getEquippedItem(EntityLivingBase entity, MolangEquipmentSlot slot) {
        if (entity == null || slot == null) {
            return null;
        }
        switch (slot) {
            case MAINHAND:
                return entity.getEquipmentInSlot(0);
            case OFFHAND:
                // 1.7.10 has no off hand; Backhand-style mods are out of scope for the shared engine.
                return null;
            default:
                return entity.getEquipmentInSlot(slot.armorIndex() + 1);
        }
    }
}
