package software.bernie.geckolib3.molang.builtin.query;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.molang.context.IContext;
import software.bernie.geckolib3.molang.function.entity.LivingEntityFunction;
import software.bernie.geckolib3.molang.runtime.ExecutionContext;
import software.bernie.geckolib3.molang.util.MolangEquipmentSlot;
import software.bernie.geckolib3.molang.util.MolangEquipmentUtil;
import software.bernie.geckolib3.molang.util.MolangUtils;

public class ItemNameAny extends LivingEntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<EntityLivingBase>> context, ArgumentCollection arguments) {
        MolangEquipmentSlot slotType = MolangUtils.parseSlotType(context.entity(), arguments.getAsString(context, 0));
        if (slotType == null) {
            return null;
        }

        EntityLivingBase entity = context.entity()
            .entity();
        ItemStack itemStack = MolangEquipmentUtil.getEquippedItem(entity, slotType);
        if (itemStack == null) {
            return false;
        }

        ResourceLocation actualId = MolangUtils.itemId(itemStack);
        if (actualId == null) {
            return false;
        }

        for (int i = 1; i < arguments.size(); i++) {
            ResourceLocation id = arguments.getAsResourceLocation(context, i);
            if (id == null) {
                return null;
            }
            if (id.equals(actualId)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 2;
    }
}
